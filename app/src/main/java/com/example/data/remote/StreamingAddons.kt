/* HTTP Addon protocol client; GPL-3.0. */
package com.example.data.remote

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import com.example.data.local.OtherSettingsPreferences
import com.example.data.model.MusicTrack
import com.example.data.repository.RecordingIdentity
import com.example.performance.AppDispatchers
import com.example.performance.BackgroundWorkCoordinator
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackSourcePolicy
import com.example.playback.PlaybackSource
import com.example.playback.PlaybackSourceKind
import com.example.playback.PlaybackDiagnostics
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** User-installed HTTP provider. No requests, client construction or services while disabled.
 * Uses standard manifest/search/stream protocol, not an arbitrary audio proxy.
 * Requests are bounded/cancellable and every catalog substitution proves recording identity.
 */
object StreamingAddons {
    data class Manifest(val name: String, val baseUrl: String)
    private data class Transport(val headers: Map<String, String>, val mime: String?, val bitrate: Int,
        val expires: Long, val headerScope: String?, val sourceId: String, val baseUrl: String, val provider: String,
        val atmos: Boolean = false, val codec: String = "",
        val drm: androidx.media3.common.MediaItem.DrmConfiguration? = null)
    private val transports = ConcurrentHashMap<String, Transport>()
    private data class Cached(val track: MusicTrack?, val expires: Long)
    private val resolutions = ConcurrentHashMap<String, Cached>()
    private data class CatalogMatch(val tracks: List<MusicTrack>, val expires: Long)
    private val catalogMatches = LruCache<String, CatalogMatch>(128)
    private data class Configuration(val manifest: Manifest, val defaults: Map<String, String>,
        val qualities: List<String>, val stereoValue: String, val expires: Long)
    private val configurations = ConcurrentHashMap<String, Configuration>()
    private val manifestFallbacks = ConcurrentHashMap<String, Long>()
    private val manifestFlights = Array(8) { kotlinx.coroutines.sync.Mutex() }
    private val permits = Semaphore(2)
    private val productionClient by lazy { createClient() }
    @androidx.annotation.VisibleForTesting internal var testClient: OkHttpClient? = null

    internal fun createClient(builder: OkHttpClient.Builder = OkHttpClient.Builder()) = AddonHttps.client(
        builder.connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .dispatcher(Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 })
    )

    fun normalize(raw: String): String {
        val url = raw.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid HTTPS server URL")
        require(url.username.isEmpty() && url.password.isEmpty()) { "Use your add-on's token URL, without username/password" }
        require(AddonHttps.isAllowed(url)) { AddonHttps.REQUIRED }
        val trimmed = url.encodedPath.trimEnd('/')
        val path = if (trimmed.substringAfterLast('/').endsWith(".json", true)) trimmed.substringBeforeLast('/') else trimmed
        return url.newBuilder().encodedPath(path.ifBlank { "/" }).fragment(null).build().toString().trimEnd('/')
    }
    private fun endpoint(base: String, path: String, query: String? = null, quality: String? = null): HttpUrl {
        val builder = requireNotNull(base.toHttpUrlOrNull()).newBuilder()
        path.split('/').forEach(builder::addPathSegment)
        if (query != null) builder.setQueryParameter("q", query)
        if (quality != null) builder.setQueryParameter("quality", quality).setQueryParameter("atmos", "none")
        return builder.build()
    }
    private const val MAX_MANIFEST_BYTES = 256 * 1024L
    private const val MAX_SEARCH_BYTES = 1024 * 1024L
    private const val MAX_STREAM_BYTES = 4 * 1024 * 1024L

    private suspend fun get(url: HttpUrl, responseType: String, maxBytes: Long): String =
        BackgroundWorkCoordinator.traceWork("Add-on $responseType") { permits.withPermit { fetch(url, responseType, maxBytes) } }

    private suspend fun fetch(url: HttpUrl, responseType: String, maxBytes: Long): String {
        val call = (testClient ?: productionClient).newCall(Request.Builder().url(url).header("X-LW-Intent", "stream")
            .header("User-Agent", "WavTape/${com.example.BuildConfig.VERSION_NAME}").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(if (e is AddonHttps.Required) e else IOException("Add-on connection failed"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw IOException("Add-on returned HTTP ${it.code}")
                            val body = it.body ?: throw IOException("Empty add-on response")
                            val declaredLength = body.contentLength()
                            val contentType = body.contentType()?.toString() ?: "unknown"
                            if (declaredLength > maxBytes) {
                                throw IOException("Add-on $responseType response exceeds the ${maxBytes / 1024} KB limit (Content-Length: $declaredLength; Content-Type: $contentType)")
                            }
                            // Read at most limit + 1 bytes, including chunked/unknown-length responses.
                            val source = body.source()
                            source.request(maxBytes + 1L)
                            if (source.buffer.size > maxBytes) {
                                throw IOException("Add-on $responseType response exceeds the ${maxBytes / 1024} KB limit (received more than $maxBytes bytes; Content-Type: $contentType)")
                            }
                            val result = source.readUtf8()
                            if (continuation.isActive) continuation.resume(result)
                        }
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }
    suspend fun connect(raw: String): Manifest = try {
        configuration(normalize(raw)).manifest
    } catch (_: org.json.JSONException) {
        throw IOException("This server did not return a valid add-on manifest")
    }

    private suspend fun configuration(base: String): Configuration = withContext(AppDispatchers.Io) {
        configurations[base]?.takeIf { it.expires > System.currentTimeMillis() }?.let { return@withContext it }
        val lock = manifestFlights[(base.hashCode() and Int.MAX_VALUE) % manifestFlights.size]
        lock.lock()
        try {
            configurations[base]?.takeIf { it.expires > System.currentTimeMillis() }?.let { return@withContext it }
            val body = get(endpoint(base, "manifest.json"), "manifest", MAX_MANIFEST_BYTES)
            withContext(AppDispatchers.CpuBounded) {
                val root = JSONObject(body)
                require(root.optString("name").isNotBlank() || root.optString("id").isNotBlank()) {
                    "This server did not return a valid add-on manifest"
                }
                val resources = root.optJSONArray("resources")
                require(resources == null || resources.length() == 0 || (0 until resources.length()).any {
                    resources.optString(it).lowercase() in setOf("search", "stream")
                }) { "This manifest does not provide music search or streaming" }
                val defaults = linkedMapOf<String, String>()
                val qualities = mutableListOf<String>()
                var stereoValue = "none"
                val settings = root.optJSONArray("settings") ?: JSONArray()
                fun scalar(value: Any?): String? = value?.takeIf { it is String || it is Number || it is Boolean }
                    ?.toString()?.takeIf { it.length <= 1024 }
                for (index in 0 until minOf(settings.length(), 32)) {
                    val setting = settings.optJSONObject(index) ?: continue
                    val key = setting.optString("key").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: continue
                    scalar(setting.opt("default"))?.let { defaults[key] = it }
                    if (key == "quality" || key == "atmos") {
                        val options = setting.optJSONArray("options") ?: JSONArray()
                        val values = (0 until minOf(options.length(), 32)).mapNotNull { i ->
                            scalar(options.optJSONObject(i)?.opt("value") ?: options.opt(i))
                        }
                        if (key == "quality") qualities.addAll(values)
                        else stereoValue = (values + listOfNotNull(defaults[key])).firstOrNull {
                            it.lowercase(Locale.ROOT) in setOf("0", "false", "off", "no", "none", "never", "stereo")
                        } ?: "none"
                    }
                }
                Configuration(Manifest(root.optString("name").ifBlank { root.optString("id") }.trim().take(100), base),
                    defaults.toMap(), qualities.distinct(), stereoValue, System.currentTimeMillis() + 600_000L).also {
                    if (configurations.size >= 32) configurations.entries.minByOrNull { it.value.expires }?.let { configurations.remove(it.key) }
                    configurations[base] = it
                }
            }
        } finally { lock.unlock() }
    }
    private suspend fun requestConfiguration(base: String): Configuration? {
        val now = System.currentTimeMillis()
        configurations[base]?.takeIf { it.expires > now }?.let { return it }
        if ((manifestFallbacks[base] ?: 0L) > now) return null
        val result = try { withTimeoutOrNull(2_500L) { configuration(base) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (result == null) {
            // Servers with working search and stream endpoints may omit the manifest.
            // Avoid paying for the same failed optional request on every song.
            if (manifestFallbacks.size >= 32) manifestFallbacks.entries.minByOrNull { it.value }
                ?.let { manifestFallbacks.remove(it.key, it.value) }
            manifestFallbacks[base] = System.currentTimeMillis() + 20_000L
        } else manifestFallbacks.remove(base)
        return result
    }

    private fun configuredEndpoint(base: String, path: String, query: String? = null, tier: String,
        config: Configuration?, dolbyAtmos: Boolean = false): HttpUrl {
        val builder = endpoint(base, path, query).newBuilder()
        config?.defaults?.forEach { (key, value) -> builder.setQueryParameter(key, value) }
        val options = config?.qualities.orEmpty()
        val requested = options.firstOrNull { it.equals(tier, true) } ?: run {
            val hints = when (tier.lowercase(Locale.ROOT)) {
                "hi_res", "hires" -> listOf("hi_res", "hi-res", "hires", "max", "best", "lossless", "flac", "hifi")
                "lossless" -> listOf("lossless", "flac", "hifi", "hi_res", "hi-res", "hires", "max", "best")
                else -> listOf("high", "320", "normal", "standard")
            }
            hints.firstNotNullOfOrNull { hint -> options.firstOrNull { it.lowercase(Locale.ROOT).replace(' ', '_').contains(hint) } }
        } ?: tier
        builder.setQueryParameter("quality", requested)
        builder.setQueryParameter("atmos", atmosParameter(dolbyAtmos, config?.stereoValue ?: "none"))
        if (query != null) builder.setQueryParameter("q", query)
        return builder.build()
    }
    /** Auto requests an available immersive mix while retaining the server's stereo fallback. */
    internal fun atmosParameter(allowed: Boolean, stereoValue: String = "none"): String =
        if (allowed) "auto" else stereoValue
    private fun quality(ceiling: AudioQualityCeiling) = when (ceiling) {
        AudioQualityCeiling.MAX -> "LOSSLESS"
        AudioQualityCeiling.HIGH -> "high"
        AudioQualityCeiling.NORMAL, AudioQualityCeiling.DATA_SAVER -> "low"
    }
    private fun fingerprint(base: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(base.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    internal fun catalogTrackId(base: String, id: String) = "addon_${fingerprint(base)}:" +
        android.util.Base64.encodeToString(id.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    fun ownsTrack(source: PlaybackSource, id: String): Boolean =
        source.kind == PlaybackSourceKind.ADDON && id.startsWith("addon_${fingerprint(source.baseUrl)}:")
    fun fromSource(url: String, sourceId: String): Boolean = transports[url]?.sourceId == sourceId
    internal fun registeredSourceId(url: String): String? = transports[url]?.sourceId
    private fun providerId(base: String, id: String): String? {
        val prefix = "addon_${fingerprint(base)}:"
        if (!id.startsWith(prefix)) return null
        return runCatching { String(android.util.Base64.decode(id.removePrefix(prefix), android.util.Base64.URL_SAFE)) }.getOrNull()
    }
    private fun array(body: String): JSONArray {
        if (body.trimStart().startsWith('[')) return JSONArray(body)
        var root = JSONObject(body)
        repeat(3) {
            for (key in listOf("tracks", "data", "results", "items")) root.optJSONArray(key)?.let { return it }
            root = root.optJSONObject("data") ?: return JSONArray()
        }
        return JSONArray()
    }
    private suspend fun search(base: String, name: String, url: HttpUrl): List<MusicTrack> {
        val body = get(url, "search", MAX_SEARCH_BYTES)
        return withContext(AppDispatchers.CpuBounded) {
            val rows = array(body)
            (0 until minOf(rows.length(), 20)).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val id = row.optString("id"); val title = row.optString("title"); val artist = row.optString("artist")
                if (id.isBlank() || title.isBlank() || artist.isBlank()) return@mapNotNull null
                MusicTrack(catalogTrackId(base, id), title, artist, row.optString("album"),
                    (row.optDouble("duration", 0.0) * 1000).toLong().coerceAtLeast(0),
                    row.optString("artworkURL", row.optString("artworkUrl", "")), "",
                    source = "Streaming Addon: $name", isrc = row.optString("isrc"), qualityBadge = "Custom stream")
            }
        }
    }
    private suspend fun searchPreferred(base: String, name: String, query: String, ceiling: AudioQualityCeiling,
        config: Configuration?, prefetched: Pair<HttpUrl, List<MusicTrack>>? = null): List<MusicTrack> {
        val tiers = if (ceiling == AudioQualityCeiling.MAX) listOf(quality(ceiling), "hi_res", "lossless")
            else listOf(quality(ceiling))
        for (url in tiers.map { configuredEndpoint(base, "search", query.trim(), it, config) }.distinct()) {
            val matches = prefetched?.takeIf { it.first == url }?.second ?: search(base, name, url)
            if (matches.isNotEmpty()) return matches
        }
        return emptyList()
    }

    private suspend fun playbackConfiguration(base: String, name: String, firstQuery: String, ceiling: AudioQualityCeiling):
        Pair<Configuration?, Pair<HttpUrl, List<MusicTrack>>?> = coroutineScope {
        val manifest = async { requestConfiguration(base) }
        withTimeoutOrNull(200L) { manifest.join(); true }?.let { return@coroutineScope manifest.await() to null }

        // Use default parameters to hide the search round trip on a cold connection.
        // A manifest with different parameters still takes precedence over this request.
        val tier = quality(ceiling)
        val defaultUrl = configuredEndpoint(base, "search", firstQuery, tier, null)
        val pending = async {
            try { search(base, name, defaultUrl) }
            catch (_: IOException) { null }
            catch (_: org.json.JSONException) { null }
        }
        val config = manifest.await()
        if (configuredEndpoint(base, "search", firstQuery, tier, config) != defaultUrl) {
            pending.cancel()
            return@coroutineScope config to null
        }
        config to pending.await()?.let { defaultUrl to it }
    }
    private suspend fun activeAddons(context: Context, policy: PlaybackSourcePolicy): List<PlaybackSource> {
        val saved = OtherSettingsPreferences.loadPlaybackSources(context)
        return policy.playbackSources.filter { source ->
            source.kind == PlaybackSourceKind.ADDON && saved.any {
                it.id == source.id && it.enabled && it.baseUrl == source.baseUrl
            }
        }
    }
    private suspend fun stillEnabled(context: Context, source: PlaybackSource, policy: PlaybackSourcePolicy): Boolean {
        OtherSettingsPreferences.loadPlaybackSources(context)
        return OtherSettingsPreferences.playbackIntegrations(context).sourceList(policy.ceiling)
            .any { it.id == source.id && it.enabled && it.baseUrl == source.baseUrl && it.available(policy.ceiling) }
    }
    suspend fun search(context: Context, query: String, policy: PlaybackSourcePolicy): List<MusicTrack> = withContext(AppDispatchers.Io) {
        if (!policy.online || query.isBlank()) return@withContext emptyList()
        val sources = activeAddons(context, policy)
        val completed = ConcurrentHashMap<String, List<MusicTrack>>()
        // Retain successful servers if another server times out. Existing HTTP
        // permits bound all servers together, not a separate pool for each URL.
        withTimeoutOrNull(12_000L) { coroutineScope {
            sources.map { source -> async {
                try {
                    val found = com.example.recommendation.DiscoveryRequestCoordinator.shared(
                        "addon-search:${source.id}:${query.trim()}:${policy.key}", essential = true, network = false) {
                        searchPreferred(source.baseUrl, source.name, query, policy.ceiling, requestConfiguration(source.baseUrl))
                    }
                    if (stillEnabled(context, source, policy)) completed[source.id] = found
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            } }.awaitAll()
        } }
        sources.flatMap { completed[it.id].orEmpty() }
    }
    suspend fun resolve(context: Context, track: MusicTrack, policy: PlaybackSourcePolicy,
        rejected: Set<String> = emptySet(), source: PlaybackSource? = null, overlapStreamRequests: Boolean = false): MusicTrack? = withContext(AppDispatchers.Io) {
        if (!policy.online) return@withContext null
        val active = activeAddons(context, policy)
        val selectedSource = (if (source == null) active.firstOrNull() else source.takeIf { it in active })
            ?: return@withContext null
        val key = withContext(AppDispatchers.CpuBounded) {
            val recording = providerId(selectedSource.baseUrl, track.id)?.let { "catalog:$it" }
                ?: "match:${RecordingIdentity.key(track)}"
            "${selectedSource.id}:${fingerprint(selectedSource.baseUrl)}:$recording:${policy.ceiling.name}:atmos=${policy.allowsDolbyAtmos}"
        }
        val cached = resolutions[key]?.takeIf { it.expires > System.currentTimeMillis() }
        val explicitRetry = policy.enhancer && policy.requestTrackId == track.id && policy.requestRevision > 0
        fun reuse(value: MusicTrack?) = value?.let { track.copy(audioUrl = it.audioUrl, source = it.source,
            isLossless = it.isLossless, bitrateKbps = it.bitrateKbps, qualityBadge = it.qualityBadge) }
        if (cached != null && (cached.track == null && !explicitRetry ||
                cached.track != null && (!explicitRetry || cached.track.isLossless) &&
                cached.track.audioUrl !in rejected && isVerified(cached.track.audioUrl, policy))) {
            return@withContext reuse(cached.track).takeIf { stillEnabled(context, selectedSource, policy) }
        }
        val result = com.example.recommendation.DiscoveryRequestCoordinator.shared("addon:$key:${rejected.hashCode()}:$overlapStreamRequests",
            essential = true, network = false) {
            val value = resolveUncached(selectedSource, track, policy, rejected, key, overlapStreamRequests)
            if (resolutions.size >= 128) resolutions.entries.minByOrNull { it.value.expires }?.let { resolutions.remove(it.key) }
            resolutions[key] = Cached(value, System.currentTimeMillis() + if (value == null) 5_000L else 120_000L)
            listOfNotNull(value)
        }.firstOrNull()
        reuse(result).takeIf { stillEnabled(context, selectedSource, policy) }
    }
    private suspend fun resolveUncached(source: PlaybackSource, track: MusicTrack, policy: PlaybackSourcePolicy,
        rejected: Set<String>, key: String, overlapStreamRequests: Boolean): MusicTrack? = withContext(AppDispatchers.Io) {
        if (!source.available(policy.ceiling) || !policy.online ||
            Uri.parse(track.audioUrl).scheme in setOf("file", "content")) return@withContext null
        val base = source.baseUrl
        val selected = providerId(base, track.id)
        try {
            var completed = false
            val result = withTimeoutOrNull(15_000L) {
                val known = catalogMatches.get(key)?.takeIf { it.expires > System.currentTimeMillis() }?.tracks
                suspend fun queries() = withContext(AppDispatchers.CpuBounded) {
                    val title = com.example.data.repository.StreamResolver.extractCleanTitle(track.title, track.artist)
                    val artist = RecordingIdentity.artistNames(track).firstOrNull().orEmpty()
                    listOf("$title $artist".trim(), "${track.title} ${track.artist}".trim(), title).filter(String::isNotBlank).distinct()
                }
                val initialQueries = if (selected == null && known == null) queries() else emptyList()
                // One manifest serves every request, including servers where it is optional.
                val (config, prefetched) = initialQueries.firstOrNull()?.let {
                    playbackConfiguration(base, source.name, it, policy.ceiling)
                } ?: (requestConfiguration(base) to null)
                // Addons are a lossless-only source at MAX quality. Try the
                // explicit tiers, but never request generic HIGH or accept a
                // lossy response as a fallback.
                val tiers = listOf("hi_res", "LOSSLESS", "CD")
                val tried = mutableSetOf<HttpUrl>()
                fun streamUrl(id: String, tier: String, atmos: Boolean) =
                    configuredEndpoint(base, "stream", tier = tier, config = config, dolbyAtmos = atmos)
                        .newBuilder().addPathSegment(id).build()
                suspend fun streamBody(url: HttpUrl): String? =
                    try { get(url, "stream", MAX_STREAM_BYTES) } catch (_: IOException) { null }
                suspend fun verify(body: String?, candidate: MusicTrack, atmos: Boolean): MusicTrack? {
                    if (body == null) return null
                    return withContext(AppDispatchers.CpuBounded) {
                        try { parseStream(body, base, track, candidate, policy.copy(dolbyAtmos = atmos), source.id) }
                        catch (_: org.json.JSONException) { null }
                    }
                }
                fun accepted(result: MusicTrack?) = result?.takeIf {
                    it.audioUrl !in rejected && (it.isLossless || transports[it.audioUrl]?.atmos == true)
                }

                suspend fun stream(candidates: List<MusicTrack>): MusicTrack? {
                    if (overlapStreamRequests) return coroutineScope {
                        // Overlap a slow higher tier with the next request under the
                        // existing HTTP permits. Verify in order so a later response
                        // cannot overwrite registered transport metadata.
                        val atmos = policy.allowsDolbyAtmos
                        data class Option(val url: HttpUrl, val stereoUrl: HttpUrl, val candidate: MusicTrack)
                        val options = buildList {
                            for (tier in tiers) for (candidate in candidates) {
                                val id = providerId(base, candidate.id) ?: continue
                                val url = streamUrl(id, tier, atmos)
                                if (tried.add(url)) add(Option(url, streamUrl(id, tier, false), candidate))
                            }
                        }
                        suspend fun verifyOption(option: Option, body: String?): MusicTrack? =
                            accepted(verify(body, option.candidate, atmos) ?:
                                if (atmos && tried.add(option.stereoUrl))
                                    verify(streamBody(option.stereoUrl), option.candidate, false) else null)
                        for (pair in options.chunked(2)) {
                            val first = async { streamBody(pair[0].url) }
                            val tryNext = kotlinx.coroutines.CompletableDeferred<Unit>()
                            val second = pair.getOrNull(1)?.let { option -> async {
                                // A quick successful response should need just one HTTP call.
                                withTimeoutOrNull(200L) { tryNext.await() }
                                streamBody(option.url)
                            } }
                            val preferred = verifyOption(pair[0], first.await())
                            if (preferred != null) {
                                second?.cancel()
                                return@coroutineScope preferred
                            }
                            tryNext.complete(Unit)
                            if (second != null) verifyOption(pair[1], second.await())?.let {
                                return@coroutineScope it
                            }
                        }
                        null
                    }
                    for (tier in tiers) for (candidate in candidates) {
                        val id = providerId(base, candidate.id) ?: continue
                        suspend fun request(atmos: Boolean): MusicTrack? {
                            val url = streamUrl(id, tier, atmos)
                            if (!tried.add(url)) return null
                            return verify(streamBody(url), candidate, atmos)
                        }
                        val resolved = (request(policy.allowsDolbyAtmos) ?:
                            if (policy.allowsDolbyAtmos) request(false) else null) ?: continue
                        accepted(resolved)?.let { return it }
                    }
                    return null
                }
                var candidates = if (selected != null) listOf(track) else known.orEmpty()
                var resolved = stream(candidates)
                if (resolved == null && selected == null) {
                    for (query in initialQueries.ifEmpty { queries() }) {
                        val found = searchPreferred(base, source.name, query, policy.ceiling, config, prefetched)
                        candidates = withContext(AppDispatchers.CpuBounded) { found.mapNotNull { candidate ->
                            RecordingIdentity.providerConfidence(track, candidate)?.let { candidate to it }
                        }.sortedByDescending { it.second }.take(2).map { it.first } }
                        if (candidates.isNotEmpty()) break
                    }
                    resolved = stream(candidates)
                    if (resolved != null) catalogMatches.put(key, CatalogMatch(candidates, System.currentTimeMillis() + 600_000L))
                }
                completed = true
                if (resolved != null) return@withTimeoutOrNull resolved
                PlaybackDiagnostics.record(track.id, policy, PlaybackSourceKind.ADDON, PlaybackDiagnostics.Stage.RESOLUTION,
                    if (candidates.isEmpty()) PlaybackDiagnostics.Reason.NO_MATCH else PlaybackDiagnostics.Reason.NO_FORMAT, source.id)
                null
            }
            if (!completed) PlaybackDiagnostics.record(track.id, policy, PlaybackSourceKind.ADDON,
                PlaybackDiagnostics.Stage.RESOLUTION, PlaybackDiagnostics.Reason.TIMEOUT, source.id)
            result
        } catch (e: CancellationException) { throw e }
        catch (error: Exception) {
            PlaybackDiagnostics.record(track.id, policy, PlaybackSourceKind.ADDON,
                PlaybackDiagnostics.Stage.RESOLUTION, PlaybackDiagnostics.reason(error), source.id)
            null
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    internal fun parseStream(body: String, base: String, requested: MusicTrack, candidate: MusicTrack,
        policy: PlaybackSourcePolicy, sourceId: String = PlaybackSource.addonId(base)): MusicTrack? {
        if (!policy.maximum || !AddonHttps.isAllowed(base)) return null
        val root = JSONObject(body)
        val original = root.optJSONObject("stream") ?: root.optJSONObject("data") ?: root
        val playback = original.optJSONObject("playback")
        if (playback != null && playback.optInt("version", 0) != 1) return null
        if (playback?.optString("type") == "encrypted") return null
        val value = if (playback == null) original else JSONObject(original.toString()).apply {
            for ((source, target) in listOf("uri" to "url", "mimeType" to "mimeType", "codec" to "codec", "expiresAt" to "expiresAt", "headers" to "headers")) {
                if (playback.has(source) && !playback.isNull(source)) put(target, playback.get(source))
            }
        }
        val drmData = playback?.optJSONObject("drm")
        val drm = if (drmData == null) null else {
            if (!drmData.optString("scheme").equals("widevine", true)) return null
            val license = drmData.optString("licenseUrl").toHttpUrlOrNull() ?: return null
            if (!AddonHttps.isAllowed(license)) return null
            androidx.media3.common.MediaItem.DrmConfiguration.Builder(androidx.media3.common.C.WIDEVINE_UUID)
                .setLicenseUri(license.toString()).setForceDefaultLicenseUri(true).setMultiSession(false).build()
        }
        if (value.optString("cipher").lowercase(Locale.ROOT) !in setOf("", "none")) return null
        if ((playback?.optBoolean("requiresProviderPlayer", false) == true ||
                playback?.optBoolean("requiresLicense", false) == true || value.optBoolean("requiresProviderPlayer", false)) && drm == null) return null
        val preview = value.opt("sample")?.toString()?.lowercase(Locale.ROOT) in setOf("true", "1") ||
            value.optBoolean("isPreview", false) || value.optBoolean("preview", false)
        if (preview) return null
        val duration = value.optLong("duration", 0L)
        val fullDuration = value.optLong("trackDuration", value.optLong("fullDuration", 0L))
        if (duration > 0L && fullDuration > 0L && duration + 5L < fullDuration) return null
        val encrypted = value.opt("encrypted")
        if (encrypted != null && encrypted != JSONObject.NULL && encrypted.toString().lowercase(Locale.ROOT) !in setOf("false", "0", "none", "") && drm == null) return null
        val format = value.optString("format").lowercase(Locale.ROOT)
        val codec = value.optString("codec").ifBlank { value.optString("fileCodec") }.ifBlank {
            format.takeIf { it in setOf("flac", "alac", "pcm", "wav") || com.example.playback.DolbyAtmosSupport.isAtmosCodec(it) }.orEmpty()
        }.ifBlank {
            when (value.optString("mimeType").lowercase(Locale.ROOT)) {
                "audio/flac", "audio/x-flac" -> "flac"
                "audio/alac", "audio/x-alac" -> "alac"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/eac3-joc" -> "eac3-joc"
                "audio/eac3" -> "eac3"
                else -> ""
            }
        }.lowercase(Locale.ROOT)
        val atmos = com.example.playback.DolbyAtmosSupport.isAtmosCodec(codec) || value.optBoolean("isDolbyAtmos", false)
        if (atmos && !policy.allowsDolbyAtmos) return null
        if (atmos && codec in setOf("flac", "alac", "pcm", "wav")) return null
        val rawBitrate = value.optInt("bitrate", 0)
        val bitrate = if (rawBitrate > 10000) rawBitrate / 1000 else rawBitrate
        if (!policy.maximum && (bitrate <= 0 || bitrate.toLong() * 1000 > policy.ceiling.maxBitrateBps)) return null
        val manifest = value.optString("manifestXml")
        var inlineXml = manifest
        val raw = value.optString("dataUrl").ifBlank { value.optString("url") }
        val stream = when {
            manifest.isNotBlank() -> {
                if (!manifest.contains("<MPD") || manifest.contains("<!DOCTYPE", true) || manifest.contains("<!ENTITY", true)) return null
                "data:application/dash+xml;base64," + android.util.Base64.encodeToString(manifest.toByteArray(), android.util.Base64.NO_WRAP)
            }
            raw.startsWith("data:application/dash+xml;base64,") -> {
                val xml = runCatching { String(android.util.Base64.decode(raw.substringAfter(','), android.util.Base64.DEFAULT)) }.getOrNull()
                    ?: return null
                if (!xml.contains("<MPD") || xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) return null
                inlineXml = xml
                raw
            }
            else -> base.toHttpUrlOrNull()?.resolve(raw)?.takeIf { raw.isNotBlank() && AddonHttps.isAllowed(it) }
                ?.toString() ?: return null
        }
        // The scoped Media3 factory validates every manifest, segment, key, and
        // redirect at request time, including relative links in remote manifests.
        val transport = value.optString("manifest").ifBlank { value.optString("mediaType") }.lowercase(Locale.ROOT)
        val declaredMime = value.optString("mimeType").lowercase(Locale.ROOT)
        // Use the same URL inference as Media3. Register the transport before
        // playback so its segments also receive the scoped add-on headers.
        val inferredTransport = Util.inferContentType(Uri.parse(stream))
        val mime = when {
            manifest.isNotBlank() || stream.startsWith("data:application/dash+xml") || format in setOf("dash", "mpd") ||
                transport in setOf("dash", "mpd") || declaredMime == MimeTypes.APPLICATION_MPD -> MimeTypes.APPLICATION_MPD
            format in setOf("hls", "m3u8") || transport in setOf("hls", "m3u8") || declaredMime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl") -> MimeTypes.APPLICATION_M3U8
            // Providers can describe the codec and omit the container. The manifest URL still identifies it.
            inferredTransport == C.CONTENT_TYPE_DASH -> MimeTypes.APPLICATION_MPD
            inferredTransport == C.CONTENT_TYPE_HLS -> MimeTypes.APPLICATION_M3U8
            declaredMime in setOf("audio/flac", "audio/x-flac") || (codec == "flac" && format == "flac") -> MimeTypes.AUDIO_FLAC
            atmos -> MimeTypes.AUDIO_E_AC3_JOC
            else -> null
        }
        if (drm != null && mime != MimeTypes.APPLICATION_MPD) return null
        val headers = value.optJSONObject("headers")?.let { obj -> obj.keys().asSequence().take(32).mapNotNull { key ->
            val v = obj.optString(key)
            (key to v).takeIf { key.lowercase() !in setOf("host", "content-length", "connection", "transfer-encoding") &&
                !key.contains('\n') && !key.contains('\r') && !v.contains('\n') && !v.contains('\r') && v.length < 8192 }
        }.toMap() }.orEmpty()
        val now = System.currentTimeMillis()
        fun epoch(value: String?): Long? = value?.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else it }
            ?: value?.let { if (android.os.Build.VERSION.SDK_INT >= 26)
                runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() else null }
        val urlExpiry = stream.toHttpUrlOrNull()?.let { uri ->
            (uri.queryParameter("expire") ?: uri.queryParameter("expires") ?: uri.queryParameter("Expires"))?.toLongOrNull()
                ?.let { if (it < 100_000_000_000L) it * 1000 else it }
        }
        val declaredExpiry = listOfNotNull(epoch(value.optString("expiresAt").takeIf(String::isNotBlank)),
            epoch(original.optString("expiresAt").takeIf(String::isNotBlank))).minOrNull()
        val expires = listOfNotNull(urlExpiry, declaredExpiry).minOrNull() ?: (now + 180_000L)
        if (expires <= now + 5_000L) return null
        if (transports.size >= 128) transports.entries.minByOrNull { it.value.expires }?.let { transports.remove(it.key) }
        val scope = stream.toHttpUrlOrNull()?.toString() ?: Regex("<BaseURL[^>]*>\\s*(https?://[^<]+)\\s*</BaseURL>")
            .find(inlineXml)?.groupValues?.get(1)?.trim()?.replace("&amp;", "&")?.toHttpUrlOrNull()?.takeIf(AddonHttps::isAllowed)?.toString()
        val provider = policy.configuredSources.firstOrNull { it.id == sourceId && it.kind == PlaybackSourceKind.ADDON }?.name
            ?.let { "Streaming Addon: $it" } ?: candidate.source
        transports[stream] = Transport(headers, mime, bitrate, minOf(expires - 5_000L, now + 180_000L), scope, sourceId, base, provider, atmos, codec, drm)
        return requested.copy(audioUrl = stream, source = provider, bitrateKbps = bitrate,
            isLossless = !atmos && codec in setOf("flac", "alac", "pcm", "wav"), qualityBadge = "Custom stream")
    }
    fun isVerified(url: String, policy: PlaybackSourcePolicy): Boolean = transports[url]?.let {
        it.expires > System.currentTimeMillis() && policy.maximum && (!it.atmos || policy.allowsDolbyAtmos) &&
            policy.playbackSources.any { source -> source.kind == PlaybackSourceKind.ADDON && source.id == it.sourceId && source.baseUrl == it.baseUrl }
    } == true
    // An active manifest can outlive its new-selection TTL. Keep origin-scoped
    // loading permission separate from reusable URL freshness.
    fun hasRegisteredTransport(url: String): Boolean = transportFor(url) != null
    fun allowsRegisteredTransport(url: String, policy: PlaybackSourcePolicy): Boolean = transportFor(url)?.let { transport ->
        policy.maximum && (!transport.atmos || policy.allowsDolbyAtmos) && policy.playbackSources.any { it.kind == PlaybackSourceKind.ADDON &&
            it.id == transport.sourceId && it.baseUrl == transport.baseUrl }
    } == true
    private fun transportFor(url: String): Transport? {
        transports[url]?.let { return it }
        val target = url.toHttpUrlOrNull()?.takeIf(AddonHttps::isAllowed) ?: return null
        return transports.entries.firstOrNull { (_, transport) ->
            val origin = transport.headerScope?.toHttpUrlOrNull()
            transport.mime in setOf(androidx.media3.common.MimeTypes.APPLICATION_MPD,
                androidx.media3.common.MimeTypes.APPLICATION_M3U8) && origin != null &&
                origin.scheme == target.scheme && origin.host == target.host && origin.port == target.port &&
                target.encodedPath.startsWith(origin.encodedPath.substringBeforeLast('/') + "/")
        }?.value
    }
    fun headers(url: String): Map<String, String> {
        // Resolution TTLs forbid reuse for a NEW selection. An existing HLS/DASH
        // session still needs its origin-scoped headers after that TTL elapses.
        // The registry stays bounded; URL expiry is enforced separately above.
        // A manifest's segment headers stay within the same origin and directory;
        // never forward a custom server's Authorization/Cookie to another host.
        return transportFor(url)?.headers.orEmpty()
    }
    fun mime(url: String): String? = transports[url]?.mime
    internal fun hasLicensedDrm(url: String): Boolean = transports[url]?.drm != null
    /** Apply only the exact registered addon transport, including licensed Widevine.
     * Segment URLs must not inherit a separate root's DRM configuration. */
    fun prepareMediaItem(item: androidx.media3.common.MediaItem): androidx.media3.common.MediaItem {
        val url = item.localConfiguration?.uri?.toString() ?: return item
        val transport = transports[url] ?: return item
        return item.buildUpon().setMimeType(transport.mime).setDrmConfiguration(transport.drm).build()
    }
    fun providerName(url: String): String? = transports[url]?.provider
    /** Only exact, unencrypted progressive recordings can be reused as complete cached files. */
    internal fun cacheableLosslessCodec(url: String): String? = transports[url]?.let { transport ->
        if (transport.drm != null || transport.mime == MimeTypes.APPLICATION_MPD ||
            transport.mime == MimeTypes.APPLICATION_M3U8) return@let null
        when (transport.codec) {
            "flac" -> "FLAC"
            "alac" -> "ALAC"
            "pcm", "wav" -> "PCM"
            else -> null
        }
    }

    /** Exact resolved representation only; segment URLs/catalog estimates are not bitrate evidence. */
    internal fun bitrateForStream(url: String, decodedCodec: String): Int = transports[url]?.let { transport ->
        // An adaptive manifest can contain several rates; only Media3's selected
        // representation Format can establish which one is currently decoded.
        if (transport.mime in setOf(androidx.media3.common.MimeTypes.APPLICATION_MPD,
                androidx.media3.common.MimeTypes.APPLICATION_M3U8)) return@let 0
        val expected = transport.codec.lowercase(Locale.ROOT)
        val matches = when (decodedCodec.uppercase(Locale.ROOT)) {
            "PCM" -> expected in setOf("pcm", "wav", "aiff", "audio/raw", "audio/wav")
            "AAC" -> expected in setOf("aac", "audio/aac", "audio/mp4a-latm") || expected.startsWith("mp4a")
            "E-AC-3 JOC" -> com.example.playback.DolbyAtmosSupport.isAtmosCodec(expected)
            "E-AC-3" -> expected in setOf("eac3", "ec3", "e-ac-3", "audio/eac3")
            else -> expected == decodedCodec.lowercase(Locale.ROOT)
        }
        transport.bitrate.takeIf { matches && it > 0 } ?: 0
    } ?: 0
}
