package com.example.data.remote

import com.example.performance.AppDispatchers

import android.os.SystemClock
import android.util.LruCache
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogNames
import com.example.data.repository.RecordingIdentity
import com.example.data.repository.RecordingVersions
import com.example.data.repository.StreamResolver
import com.example.playback.LosslessDiagnostics.Reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException

/**
 * Service for querying the Internet Archive API specifically for verified Lossless audio.
 * Supported uncompressed and lossless compressed formats:
 * - FLAC (.flac)
 * - WAV (.wav)
 * - AIFF (.aiff / .aif)
 * - ALAC / Apple Lossless (.m4a with ALAC codec)
 * - APE / Monkey's Audio (.ape)
 * - WavPack (.wv)
 * - DSD / Direct Stream Digital (.dsf / .dff)
 *
 * Enforces file-specific lossless verification with cancellable, bounded requests.
 * Rejects items that only contain lossy formats (MP3/OGG/VBR/Opus/AAC).
 */
object InternetArchiveApiService {
    private val sourceBitrates = LruCache<String, Pair<String, Int>>(512)
    internal fun bitrateForStream(url: String, decodedCodec: String): Int = sourceBitrates.get(url)?.let {
        it.second.takeIf { rate -> rate > 0 && it.first == decodedCodec } ?: 0
    } ?: 0
    private const val TIMEOUT_MS = 10_000L
    private const val PAGE_TIMEOUT_MS = 24_000L
    internal const val BACKGROUND_TIMEOUT_MS = 45_000L
    private const val PAGE_TTL_MS = 10 * 60_000L
    private const val ITEM_TTL_MS = 30 * 60_000L
    private val metadataRequests = Semaphore(2)
    private val headerRequests = Semaphore(2)
    private val pageLocks = Array(16) { Mutex() }
    private val itemLocks = Array(16) { Mutex() }
    private val genericTitle = Regex("(?i)^(?:track|audio|cd|disc|unknown)[ ._-]*[0-9]*$")
    private val pageCache = object : LruCache<String, Pair<Long, LosslessSearchPage>>(640) {
        override fun sizeOf(key: String, value: Pair<Long, LosslessSearchPage>): Int =
            value.second.tracks.size.coerceAtLeast(1)
    }
    private val itemCache = object : LruCache<String, Pair<Long, List<MusicTrack>>>(1280) {
        override fun sizeOf(key: String, value: Pair<Long, List<MusicTrack>>): Int =
            value.second.size.coerceAtLeast(1)
    }

    /** [page] refers to Archive item pages; a collection may contain several recordings. */
    data class LosslessSearchPage(
        val tracks: List<MusicTrack> = emptyList(),
        val nextPage: Int? = null,
        val isSuccessful: Boolean = true,
        val failure: Reason? = null,
        val exhaustive: Boolean = true
    )

    private class RecordingSearch(val track: MusicTrack, val excludedUrls: Set<String>, val allowed: () -> Boolean) {
        val headers = AtomicInteger(8)
        val items = linkedSetOf<String>()
        @Volatile var truncated = false
        @Volatile var incomplete = false
        @Volatile var failure: Reason? = null
    }

    /** Collections are discovery hints only. Every returned file still proves its own identity. */
    internal suspend fun findLosslessRecording(track: MusicTrack, excludedUrls: Set<String> = emptySet(),
        allowed: () -> Boolean = { true }): LosslessSearchPage =
        withContext(AppDispatchers.Io) {
            val search = RecordingSearch(track, excludedUrls, allowed)
            val plans = withContext(AppDispatchers.CpuBounded) { recordingQueries(track) }
            var successful = true
            var failure: Reason? = null
            var sawCandidates = false
            var remainingMs = BACKGROUND_TIMEOUT_MS
            val pages = plans.associateWith { 1 as Int? }.toMutableMap()
            val coordinator = com.example.performance.BackgroundWorkCoordinator
            // Start the next query as soon as either of the two active ones
            // finishes. An unrelated slow item should only occupy its own slot.
            repeat(2) {
                val active = pages.mapNotNull { (expression, page) -> page?.let { expression to it } }
                if (active.isEmpty()) return@repeat
                coordinator.awaitOptionalBatch()
                currentCoroutineContext().ensureActive()
                if (!allowed()) return@withContext blockedPage()
                if (synchronized(search.items) { search.items.size >= 20 })
                    return@withContext failedPage(Reason.METADATA_INCOMPLETE)
                val started = SystemClock.elapsedRealtime()
                val completed = coordinator.finishBoundedBatch {
                    withTimeoutOrNull(remainingMs) {
                        collectRecordingSearches(active, acceptable = { it.third.isNotEmpty() },
                            preferred = { result -> result.third.any(::isFlac) }) { (expression, page) ->
                            val result = searchPage(expression, expression, page, 4, true, search)
                            val matches = withContext(AppDispatchers.CpuBounded) {
                                result.tracks.filter { it.audioUrl !in excludedUrls }
                                    .mapNotNull { ArchiveFileKey.fromUrl(it.audioUrl)?.let(ArchiveFileEvidence::fresh) }
                                    .mapNotNull { verified -> ArchiveRecordingIdentity.confidence(track, verified)
                                        ?.let { verified to it } }
                                    .sortedWith(compareByDescending<Pair<MusicTrack, Int>> { isFlac(it.first) }
                                        .thenByDescending { it.second }).map { it.first }
                            }
                            Triple(expression, result, matches)
                        }
                    }
                } ?: return@withContext failedPage(Reason.NETWORK_TIMEOUT)
                if (!allowed()) return@withContext blockedPage()
                remainingMs -= (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                completed.firstOrNull { it.third.any(::isFlac) }?.let { (_, result, matches) ->
                    return@withContext result.copy(tracks = matches, failure = null)
                }
                for ((expression, result, matches) in completed) {
                    sawCandidates = sawCandidates || result.tracks.isNotEmpty()
                    successful = successful && result.isSuccessful
                    failure = result.failure ?: failure
                    if (matches.isNotEmpty()) return@withContext result.copy(tracks = matches, failure = null)
                    pages[expression] = result.nextPage
                }
                if (remainingMs <= 0) return@withContext failedPage(Reason.NETWORK_TIMEOUT)
            }
            LosslessSearchPage(isSuccessful = successful, failure = failure ?: if (sawCandidates)
                Reason.RECORDING_MISMATCH else Reason.NO_RESULTS,
                exhaustive = pages.values.all { it == null } && !search.truncated)
        }

    internal suspend fun <Q, T> collectRecordingSearches(queries: List<Q>, acceptable: (T) -> Boolean,
        preferred: (T) -> Boolean, fetch: suspend (Q) -> T): List<T> = coroutineScope {
        val results = Channel<T>(2)
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        var next = 0
        var pending = 0
        fun startQuery() {
            if (next == queries.size) return
            val query = queries[next++]
            pending++
            jobs += launch { results.send(fetch(query)) }
        }
        val completed = mutableListOf<T>()
        try {
            repeat(2) { startQuery() }
            while (pending > 0) {
                val result = results.receive()
                pending--
                completed += result
                if (preferred(result)) break
                if (completed.none(acceptable)) startQuery()
            }
        } finally {
            jobs.forEach { it.cancel() }
            results.cancel()
        }
        completed
    }

    internal fun originalFileUrl(track: MusicTrack): String? =
        ArchiveFileKey.fromId(track.id)?.url?.toString()

    /** Revalidate one item/file key. A cached sibling is never proof of the requested file. */
    private val directPolicies = LruCache<ArchiveFileKey, String>(128)

    internal suspend fun verifyDirectRecording(track: MusicTrack, policyKey: String? = null, forceRefresh: Boolean = false,
        allowed: () -> Boolean): MusicTrack? =
        withContext(AppDispatchers.Io) {
            val key = ArchiveFileKey.fromId(track.id) ?: ArchiveFileKey.nativeTransport(track.audioUrl)
                ?: return@withContext null
            if (!allowed() || !key.playableContainer) return@withContext null
            fun matches(file: MusicTrack) = ArchiveFileEvidence.matchesSelection(track, file)
            if (!forceRefresh && policyKey != null && directPolicies.get(key) == policyKey) {
                ArchiveFileEvidence.fresh(key)?.takeIf { matches(it) && allowed() }?.let { return@withContext it }
            }
            ArchiveFileEvidence.forget(key)
            flacHeaders.remove(key)
            val requestId = ArchivePlaybackEvents.nextRequestId()
            ArchivePlaybackEvents.record("exact_file", "checking", key, requestId = requestId)
            val doc = JSONObject().put("identifier", key.item)
            val recording = RecordingSearch(track, emptySet(), allowed)
            val file = verifyAndParseLosslessItem(doc, recording, exactFile = key,
                allowLiteral = ArchiveFileEvidence.isLiteralSelection(track), requestId = requestId).orEmpty().singleOrNull()
                ?: return@withContext null
            if (!matches(file)) {
                ArchivePlaybackEvents.record("exact_file", "identity_conflict", key)
                com.example.playback.LosslessDiagnostics.record(track.id, Reason.RECORDING_MISMATCH, "archive-direct-file")
                return@withContext null
            }
            if (!allowed()) return@withContext null
            val verified = ArchiveFileEvidence.fresh(key)?.takeIf(::matches)
            if (verified == null) {
                ArchivePlaybackEvents.record("exact_file", "identity_conflict", key)
                return@withContext null
            }
            if (policyKey != null) directPolicies.put(key, policyKey)
            verified
        }

    internal fun searchExpression(expression: String, targeted: Boolean): String {
        val collections = "($expression) AND (mediatype:audio OR mediatype:etree OR collection:etree)"
        // Album-level format indexes may be incomplete. Targeted artist/album/ISRC
        // searches inspect bounded individual files instead of excluding the item.
        return if (targeted) collections else collections + " AND " +
            "(format:(\"FLAC\" OR \"24bit Flac\" OR \"Flac\" OR \"WAV\" OR \"WAVE\" OR " +
            "\"Waveform Audio\" OR \"AIFF\" OR \"ALAC\" OR \"Apple Lossless Audio\" OR \"Apple Lossless\" OR \"Monkey's Audio\" OR " +
            "\"APE\" OR \"WavPack\" OR \"DSD\"))"
    }

    private fun isFlac(track: MusicTrack): Boolean = ArchiveFileKey.fromUrl(track.audioUrl)?.extension == "flac"

    internal fun recordingQueries(track: MusicTrack): List<String> {
        fun quote(value: String) = "\"${normalized(value)}\""
        val title = StreamResolver.extractCleanTitle(track.title, track.artist)
        val primary = RecordingIdentity.artistNames(track).firstOrNull().orEmpty()
        val album = CatalogNames.clean(track.album).takeUnless {
            normalized(it) in setOf("", "unknown", "single", "online stream", "youtube music")
        }.orEmpty()
        return buildList {
            RecordingIdentity.isrc(track.isrc)?.let { add("\"$it\"") }
            // Album tracks can appear in descriptions or other indexed fields while
            // being absent from the item's title. The manifest still proves identity.
            if (title.isNotBlank() && primary.isNotBlank()) add("(${quote(title)} AND ${quote(primary)})")
            if (album.isNotBlank() && primary.isNotBlank()) add("(title:${quote(album)} AND creator:${quote(primary)})")
            if (title.isNotBlank() && primary.isNotBlank()) add("(title:${quote(title)} AND creator:${quote(primary)})")
            if (album.isNotBlank()) add("title:${quote(album)}")
            if (primary.isNotBlank()) add("creator:${quote(primary)}")
            if (title.isNotBlank()) add("title:${quote(title)}")
        }.distinct()
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                if (ArchiveFileTransport.isRateLimited()) throw ArchiveFileTransport.Failure("rate_limited", 429)
                chain.proceed(chain.request()).also {
                    if (it.code == 429 || it.code == 503) ArchiveFileTransport.noteBackoff(it.header("Retry-After"), it.code)
                }
            }
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 3; maxRequestsPerHost = 2 })
            .build()
    }

    /**
     * Checks if a given file name and format string represents a supported lossless audio format.
     */
    fun isLosslessAudioFile(fileName: String, format: String): Boolean {
        val lowerName = fileName.lowercase().trim()
        val lowerFormat = format.lowercase().trim()

        // Explicitly reject known lossy extensions
        if (lowerName.endsWith(".mp3") ||
            lowerName.endsWith(".ogg") ||
            lowerName.endsWith(".opus") ||
            lowerName.endsWith(".aac") ||
            lowerName.endsWith(".wma") ||
            lowerFormat.contains("mp3") ||
            lowerFormat.contains("vorbis") ||
            lowerFormat.contains("vbr") ||
            lowerFormat.contains("opus") ||
            lowerFormat.contains("ogg") ||
            lowerFormat.split(' ', '/', '-').contains("aac")
        ) {
            return false
        }

        // 1. FLAC (.flac)
        if (lowerName.endsWith(".flac") || lowerFormat == "flac" || lowerFormat.contains("flac")) {
            return true
        }

        // 2. WAV (.wav)
        if (lowerName.endsWith(".wav") || lowerFormat == "wav" || lowerFormat.contains("waveform audio")) {
            return true
        }

        // 3. AIFF (.aiff / .aif)
        if (lowerName.endsWith(".aiff") || lowerName.endsWith(".aif") || lowerFormat == "aiff" || lowerFormat == "aif") {
            return true
        }

        // 4. ALAC / Apple Lossless (.m4a with ALAC codec)
        if (lowerName.endsWith(".m4a") && (lowerFormat.contains("alac") || lowerFormat.contains("apple lossless") || lowerName.contains("alac"))) {
            return true
        }
        if (lowerFormat.contains("alac") || lowerFormat.contains("apple lossless")) {
            return true
        }

        // 5. APE / Monkey's Audio (.ape)
        if (lowerName.endsWith(".ape") || lowerFormat.contains("monkey's audio") || lowerFormat == "ape") {
            return true
        }

        // 6. WavPack (.wv)
        if (lowerName.endsWith(".wv") || lowerFormat.contains("wavpack") || lowerFormat == "wv") {
            return true
        }

        // 7. DSD / Direct Stream Digital (.dsf / .dff)
        if (lowerName.endsWith(".dsf") || lowerName.endsWith(".dff") || lowerFormat.contains("dsd") || lowerFormat == "dsf" || lowerFormat == "dff") {
            return true
        }

        return false
    }

    /**
     * Search a real Archive item page, retaining every matching individual audio file.
     * Do not infer hi-res from bitrate or losslessness alone. Successful pages and parsed
     * items are cached separately so paging does not decode/fetch the same collection again.
     */
    suspend fun searchLosslessPage(
        query: String,
        page: Int = 1,
        pageSize: Int = 10,
        playableOnly: Boolean = true,
        allowed: () -> Boolean = { true }
    ): LosslessSearchPage {
        if (!allowed()) return blockedPage()
        ArchiveFileKey.itemLink(query)?.let { item ->
            val exact = ArchiveFileKey.fromUrl(query.trim())
            if (page != 1) return LosslessSearchPage()
            val failure = java.util.concurrent.atomic.AtomicReference<Reason>()
            val started = SystemClock.elapsedRealtime()
            val tracks = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                verifyAndParseLosslessItem(JSONObject().put("identifier", item), null,
                    exactFile = exact, allowLiteral = true, allowed = allowed, onIssue = { failure.set(it) })
                    .orEmpty().filter { !playableOnly || supportedSearchAudio(it) }
            } ?: return failedPage(Reason.NETWORK_TIMEOUT)
            val issue = failure.get()
            ArchivePlaybackEvents.record("exact_discovery", if (tracks.isEmpty()) (issue?.name?.lowercase() ?: "index_empty")
                else "discovered", exact, SystemClock.elapsedRealtime() - started, tracks.size)
            return if (!allowed()) blockedPage() else LosslessSearchPage(tracks, isSuccessful = issue == null,
                failure = issue, exhaustive = issue == null)
        }
        val terms = normalized(query).split(' ').filter(String::isNotBlank).take(12)
        if (terms.isEmpty()) return LosslessSearchPage()
        return searchPage(query, terms.joinToString(" AND ") { "\"$it\"" }, page, pageSize, playableOnly, null, allowed)
    }

    private suspend fun searchPage(query: String, expression: String, page: Int, pageSize: Int,
        playableOnly: Boolean, recording: RecordingSearch?, allowed: () -> Boolean = { true }): LosslessSearchPage = withContext(AppDispatchers.CpuBounded) {
        val terms = normalized(query).split(' ').filter(String::isNotBlank).take(12)
        val requestedPage = page.coerceAtLeast(1)
        val rows = pageSize.coerceIn(1, 20)
        val key = "$expression|$requestedPage|$rows|$playableOnly|${recording?.track?.let(RecordingIdentity::key).orEmpty()}|${recording?.excludedUrls?.sorted()}"
        val admitted = recording?.allowed ?: allowed
        pageLocks[(key.hashCode() and Int.MAX_VALUE) % pageLocks.size].withLock {
            if (!withContext(AppDispatchers.Io) { admitted() }) return@withLock blockedPage()
            val started = SystemClock.elapsedRealtime()
            val requestId = ArchivePlaybackEvents.nextRequestId()
            val cached = pageCache.get(key)
            if (cached != null && SystemClock.elapsedRealtime() - cached.first <
                if (cached.second.tracks.isEmpty()) 60_000L else PAGE_TTL_MS) {
                return@withLock cached.second
            }
            val partial = java.util.concurrent.ConcurrentLinkedQueue<MusicTrack>()
            val result = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                try {
                    val index = discoverItemPage(expression, recording != null, requestedPage, rows) { formattedQuery ->
                        val searchUrl = okhttp3.HttpUrl.Builder().scheme("https").host("archive.org")
                            .addPathSegment("advancedsearch.php").addQueryParameter("q", formattedQuery)
                            .addQueryParameter("fl[]", "identifier").addQueryParameter("fl[]", "title")
                            .addQueryParameter("fl[]", "creator").addQueryParameter("fl[]", "album")
                            .addQueryParameter("fl[]", "year").addQueryParameter("rows", rows.toString())
                            .addQueryParameter("page", requestedPage.toString())
                            .addQueryParameter("output", "json").build()
                        val request = Request.Builder().url(searchUrl)
                            .header("User-Agent", "WavTape/2.5.0 (Android; Lossless Audio Search)")
                            .header("Accept", "application/json").build()
                        JSONObject(requestMetadata(request, admitted))
                    }
                    val issue = java.util.concurrent.atomic.AtomicReference<Reason>()
                    val parsed = collectRecordingFiles(recording?.track,
                        index.items, recording?.excludedUrls.orEmpty(),
                        eligible = { candidate -> isFlac(candidate) &&
                            ArchiveFileKey.fromUrl(candidate.audioUrl)?.let { ArchiveFileEvidence.fresh(it) } != null }) {
                        cachedLosslessItem(it, recording, terms, admitted, requestId) { reason ->
                            issue.getAndUpdate { previous -> if (previous == null || previous == Reason.UNAUTHORIZED) reason else previous }
                        }?.also(partial::addAll)
                    }
                    if (!withContext(AppDispatchers.Io) { admitted() }) return@withTimeoutOrNull blockedPage()
                    val tracks = parsed.filterNotNull().flatten().distinctBy { it.id }
                        .filter { (recording != null || relevant(terms, it)) && (!playableOnly || supportedSearchAudio(it)) }
                        .sortedWith(compareByDescending<MusicTrack> { relevance(terms, it) }
                            .thenByDescending { it.qualityBadge.startsWith("Hi-Res") }
                            .thenByDescending { it.bitrateKbps })
                    val complete = index.failure == null && parsed.none { it == null } && recording?.incomplete != true &&
                        (issue.get() == null || issue.get() == Reason.UNAUTHORIZED)
                    val value = LosslessSearchPage(tracks,
                        nextPage = index.pageAfter(requestedPage), isSuccessful = complete,
                        failure = index.failure ?: recording?.failure ?: issue.get() ?:
                            if (parsed.any { it == null } || recording?.incomplete == true) Reason.METADATA_INCOMPLETE else null,
                        exhaustive = !index.hasMore && complete && recording?.truncated != true)
                    ArchivePlaybackEvents.record("index", when {
                        !value.isSuccessful -> "partial_scan"
                        index.items.isEmpty() -> "index_empty"
                        tracks.isEmpty() -> value.failure?.name?.lowercase() ?: "no_eligible_files"
                        else -> "discovered"
                    }, elapsedMs = SystemClock.elapsedRealtime() - started, count = tracks.size, requestId = requestId)
                    // Partial failures must be retried instead of being cached as exhausted.
                    if (value.isSuccessful && recording?.truncated != true)
                        pageCache.put(key, SystemClock.elapsedRealtime() to value)
                    value
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ArchivePlaybackEvents.record("index", archiveOutcome(e), elapsedMs = SystemClock.elapsedRealtime() - started, requestId = requestId)
                    val reason = archiveFailureReason(e)
                    cached?.second?.copy(isSuccessful = false, failure = reason) ?:
                        LosslessSearchPage(isSuccessful = false, failure = reason, exhaustive = false)
                }
            }
            result ?: if (!withContext(AppDispatchers.Io) { admitted() }) blockedPage() else {
                val tracks = (partial + cached?.second?.tracks.orEmpty()).distinctBy { it.id }
                    .filter { (recording != null || relevant(terms, it)) && (!playableOnly || supportedSearchAudio(it)) }
                    .sortedByDescending { relevance(terms, it) }
                ArchivePlaybackEvents.record("index", "partial_scan", elapsedMs = SystemClock.elapsedRealtime() - started,
                    count = tracks.size, requestId = requestId)
                LosslessSearchPage(tracks, nextPage = requestedPage, isSuccessful = false,
                    failure = Reason.NETWORK_TIMEOUT, exhaustive = false)
            }
        }
    }

    internal data class ItemPage(val items: List<JSONObject>, val hasMore: Boolean, val failure: Reason?) {
        fun pageAfter(page: Int): Int? = when {
            failure != null -> page
            hasMore && page < Int.MAX_VALUE -> page + 1
            else -> null
        }
    }

    /** The format index is a hint. The second lane inspects items whose formats were never indexed. */
    internal suspend fun discoverItemPage(expression: String, targeted: Boolean, page: Int, rows: Int,
        fetch: suspend (String) -> JSONObject): ItemPage = coroutineScope {
        val queries = if (targeted) listOf(searchExpression(expression, true))
            else listOf(searchExpression(expression, false), searchExpression(expression, true))
        val pages = queries.map { query ->
            async {
                try {
                    val response = fetch(query).optJSONObject("response")
                        ?: throw IOException("Invalid Archive search response")
                    val docs = response.optJSONArray("docs") ?: throw IOException("Missing Archive search results")
                    val total = response.optLong("numFound", -1L)
                    val hasMore = if (total >= 0L) page.toLong() * rows < total else docs.length() >= rows
                    ItemPage((0 until minOf(rows, docs.length())).mapNotNull { docs.optJSONObject(it) },
                        hasMore, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ItemPage(emptyList(), false, archiveFailureReason(e))
                }
            }
        }.awaitAll()
        ItemPage(pages.flatMap { it.items }.filter { text(it, "identifier").isNotBlank() }
            .distinctBy { text(it, "identifier") }, pages.any { it.hasMore },
            pages.firstNotNullOfOrNull { it.failure })
    }

    /** Do not make a verified recording wait for unrelated slow collection requests. */
    internal suspend fun collectRecordingFiles(requested: MusicTrack?, documents: List<JSONObject>,
        excludedUrls: Set<String> = emptySet(), eligible: (MusicTrack) -> Boolean = { true },
        load: suspend (JSONObject) -> List<MusicTrack>?
    ): List<List<MusicTrack>?> = coroutineScope {
        if (requested == null) return@coroutineScope documents.map { async { load(it) } }.awaitAll()
        val results = Channel<List<MusicTrack>?>(documents.size.coerceAtLeast(1))
        val jobs = documents.map { document -> launch { results.send(load(document)) } }
        val completed = mutableListOf<List<MusicTrack>?>()
        try {
            for (ignored in documents.indices) {
                val tracks = results.receive()
                completed.add(tracks)
                if (tracks.orEmpty().any { it.audioUrl !in excludedUrls &&
                    ArchiveRecordingIdentity.confidence(requested, it) != null && eligible(it) }) break
            }
        } finally {
            jobs.forEach { it.cancel() }
            results.cancel()
        }
        completed
    }

    /** Playback resolver compatibility: keep its bounded first-page result contract. */
    suspend fun searchLosslessTracks(query: String, maxResults: Int = 10): List<MusicTrack> {
        if (maxResults <= 0 || query.isBlank()) return emptyList()
        return withTimeoutOrNull(TIMEOUT_MS) {
            searchLosslessPage(query, pageSize = maxResults.coerceAtMost(20), playableOnly = false)
                .tracks.take(maxResults)
        } ?: emptyList()
    }

    // The existing Media3 stack has no AIFF/APE/WavPack/DSD extractor or native extension.
    // Keep those legacy resolver formats untouched while exposing playable search containers.
    private fun supportedSearchAudio(track: MusicTrack): Boolean {
        return ArchiveFileKey.fromUrl(track.audioUrl)?.playableContainer == true
    }

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

    private fun relevant(terms: List<String>, track: MusicTrack): Boolean {
        val metadata = normalized("${track.title} ${track.artist} ${track.album}")
        return terms.all { it in metadata }
    }

    private fun relevance(terms: List<String>, track: MusicTrack): Int {
        val phrase = terms.joinToString(" ")
        val title = normalized(track.title)
        val artist = normalized(track.artist)
        return when {
            title == phrase -> 5
            artist == phrase -> 4
            phrase in title -> 3
            phrase in artist -> 2
            else -> 1
        }
    }

    private suspend fun cachedLosslessItem(doc: JSONObject, recording: RecordingSearch?, terms: List<String>, allowed: () -> Boolean,
        requestId: Long, onIssue: (Reason) -> Unit): List<MusicTrack>? {
        val identifier = text(doc, "identifier")
        if (identifier.isBlank()) return emptyList()
        if (recording != null) synchronized(recording.items) {
            if (identifier in recording.items) return emptyList()
            if (recording.items.size >= 20) { recording.truncated = true; return null }
            recording.items.add(identifier)
        }
        return itemLocks[(identifier.hashCode() and Int.MAX_VALUE) % itemLocks.size].withLock {
            val cached = itemCache.get(identifier)
            if (cached != null && SystemClock.elapsedRealtime() - cached.first < ITEM_TTL_MS &&
                (recording == null || cached.second.any { it.audioUrl !in recording.excludedUrls &&
                    ArchiveRecordingIdentity.confidence(recording.track, it) != null &&
                    ArchiveFileKey.fromUrl(it.audioUrl)?.let(ArchiveFileEvidence::fresh) != null })) {
                return@withLock cached.second
            }
            var complete = true
            verifyAndParseLosslessItem(doc, recording, allowed = allowed, requestId = requestId, browseTerms = terms, onIssue = { reason ->
                complete = false
                onIssue(reason)
            })?.also { tracks ->
                if (complete && recording == null && tracks.isNotEmpty())
                    itemCache.put(identifier, SystemClock.elapsedRealtime() to tracks)
            }
        }
    }

    /**
     * Backward-compatible alias for [searchLosslessTracks].
     */
    suspend fun searchFlacTracks(query: String, maxResults: Int = 10): List<MusicTrack> =
        searchLosslessTracks(query, maxResults)

    /**
     * Retrieves metadata for an item and verifies that a true streamable lossless file exists.
     * Rejects items that only have lossy files.
     */
    private fun text(objectValue: JSONObject?, key: String): String = when (val value = objectValue?.opt(key)) {
        is String -> value.takeUnless { it == "null" }.orEmpty().trim()
        is org.json.JSONArray -> if (value.length() == 1) value.optString(0).trim() else ""
        is Number -> value.toString()
        else -> ""
    }

    private fun values(objectValue: JSONObject?, key: String): List<String> =
        when (val value = objectValue?.opt(key)) {
            is org.json.JSONArray -> (0 until value.length()).map { value.optString(it).trim() }
            is String -> listOf(value.trim())
            is Boolean, is Number -> listOf(value.toString())
            else -> emptyList()
        }

    private fun restriction(objectValue: JSONObject?, key: String): Boolean =
        values(objectValue, key).any { it.equals("true", true) || it == "1" || it.equals("yes", true) }

    private fun restricted(objectValue: JSONObject?): Boolean =
        restriction(objectValue, "is_dark") || restriction(objectValue, "access-restricted-item") ||
            restriction(objectValue, "private")

    private fun durationMillis(value: String): Long {
        val seconds = if (value.contains(':')) value.split(':').fold(0.0) { total, part ->
            val amount = part.toDoubleOrNull() ?: return 0L
            total * 60 + amount
        } else value.toDoubleOrNull() ?: return 0L
        return if (seconds.isFinite() && seconds > 0) (seconds * 1000).toLong() else 0L
    }

    private fun failedPage(reason: Reason) = LosslessSearchPage(isSuccessful = false, failure = reason, exhaustive = false)
    private fun blockedPage() = failedPage(Reason.NETWORK_POLICY_BLOCKED)

    private fun metadataRequest(item: String, suffix: String? = null, firstFile: Int? = null): Request {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("archive.org").addPathSegment("metadata")
            .addPathSegment(item).apply {
                if (suffix != null) addPathSegment(suffix)
                if (firstFile != null) {
                    addQueryParameter("start", firstFile.toString())
                    addQueryParameter("count", "128")
                }
                addQueryParameter("extended_err", "1")
            }.build()
        return Request.Builder().url(url).header("User-Agent", "WavTape (Android; Archive Metadata)").build()
    }

    private suspend fun readItem(identifier: String, allowed: () -> Boolean, onIssue: (Reason) -> Unit): JSONObject =
        metadataRequests.withPermit {
            suspend fun read(request: Request): JSONObject {
                val encoded = requestMetadata(request, allowed)
                return withContext(AppDispatchers.CpuBounded) { JSONObject(encoded) }
            }
            try {
                read(metadataRequest(identifier))
            } catch (error: IOException) {
                if (error.message != "Catalog response exceeds its metadata budget") throw error
                // Check item access flags before walking the bounded partial file manifest.
                val metadata = read(metadataRequest(identifier, "metadata")).optJSONObject("result")
                    ?: throw CatalogHttpException(404, null)
                val dark = read(metadataRequest(identifier, "is_dark")).opt("result")
                val restricted = read(metadataRequest(identifier, "access-restricted-item")).opt("result")
                val privateItem = read(metadataRequest(identifier, "private")).opt("result")
                val root = JSONObject().put("metadata", metadata)
                    .put("is_dark", dark).put("access-restricted-item", restricted).put("private", privateItem)
                if (restricted(root) || restricted(metadata)) return@withPermit root
                val files = org.json.JSONArray()
                for (page in 0..3) {
                    val batch = read(metadataRequest(identifier, "files", page * 128)).optJSONArray("result")
                        ?: throw IOException("Archive file list unavailable")
                    for (index in 0 until batch.length()) files.put(batch.get(index))
                    if (batch.length() < 128) return@withPermit root.put("files", files)
                }
                onIssue(Reason.METADATA_INCOMPLETE)
                root.put("files", files).put("wavtape_partial", true)
            }
        }

    /** Bind each recording to its own file tags and stable item-plus-file identity. */
    private suspend fun verifyAndParseLosslessItem(doc: JSONObject, recording: RecordingSearch?,
        exactFile: ArchiveFileKey? = null, allowLiteral: Boolean = false,
        allowed: () -> Boolean = recording?.allowed ?: { true }, requestId: Long? = null,
        browseTerms: List<String> = emptyList(), onIssue: (Reason) -> Unit = {}): List<MusicTrack>? {
        val identifier = text(doc, "identifier")
        if (identifier.isBlank()) return emptyList()
        fun issue(reason: Reason) {
            onIssue(reason)
            recording?.failure = reason
            if (reason == Reason.METADATA_INCOMPLETE || reason == Reason.NETWORK_TIMEOUT)
                recording?.incomplete = true
        }
        try {
            val root = readItem(identifier, allowed, ::issue)
            val metadata = root.optJSONObject("metadata") ?: throw CatalogHttpException(404, null)
            if (restricted(root) || restricted(metadata)) {
                issue(Reason.UNAUTHORIZED)
                ArchivePlaybackEvents.record("access", "access_denied", exactFile, requestId = requestId)
                return emptyList()
            }
            val browseHeaders = AtomicInteger(4)
            val headers = recording?.headers ?: browseHeaders
            val inspected = mutableSetOf<ArchiveFileKey>()
            fun admitHeader(key: ArchiveFileKey): Boolean {
                if (inspected.contains(key)) return true
                if (headers.getAndDecrement() <= 0) {
                    recording?.truncated = true
                    issue(Reason.METADATA_INCOMPLETE)
                    return false
                }
                inspected.add(key)
                return true
            }
            suspend fun validateContainer(track: MusicTrack): Boolean {
                val key = ArchiveFileKey.fromUrl(track.audioUrl) ?: return false
                return (ArchiveFileEvidence.fresh(key) != null || admitHeader(key)) && verifyContainer(track, allowed, requestId)
            }
            val tracks = parseLosslessItem(doc, root, recording?.track,
                excludedUrls = recording?.excludedUrls.orEmpty(), onRejected = { reason, _ ->
                    issue(reason)
                    ArchivePlaybackEvents.record("file_identity", reason.name.lowercase(), exactFile, requestId = requestId)
                }, exactFile = exactFile, allowLiteral = allowLiteral, browseTerms = browseTerms,
                validateUnlabeledAlac = ::validateContainer,
                validateCandidate = { candidate ->
                    if (!validateContainer(candidate)) false else {
                        val key = ArchiveFileKey.fromUrl(candidate.audioUrl)
                        val verified = key?.let(ArchiveFileEvidence::fresh)
                        verified != null && when {
                            recording == null -> true
                            exactFile != null -> ArchiveFileEvidence.matchesSelection(recording.track, verified)
                            else -> ArchiveRecordingIdentity.confidence(recording.track, verified) != null
                        }
                    }
                }) { url ->
                val key = ArchiveFileKey.fromUrl(url)
                if (key == null || !admitHeader(key)) null else readFlacHeader(key, allowed, onFailure = { issue(archiveFailureReason(it)) })
            }
            if (exactFile != null && tracks.isEmpty() && recording?.failure == null)
                issue(Reason.SOURCE_VERIFICATION_FAILED)
            return tracks.mapNotNull { candidate -> ArchiveFileKey.fromUrl(candidate.audioUrl)?.let(ArchiveFileEvidence::fresh) }
        } catch (e: CancellationException) {
            ArchivePlaybackEvents.record("metadata", "cancelled", exactFile, requestId = requestId)
            throw e
        } catch (e: Exception) {
            issue(archiveFailureReason(e))
            ArchivePlaybackEvents.record("metadata", archiveOutcome(e), exactFile,
                status = (e as? CatalogHttpException)?.status, requestId = requestId)
            return null
        }
    }

    private suspend fun requestMetadata(request: Request, allowed: () -> Boolean): String = withContext(AppDispatchers.Io) {
        repeat(2) { attempt ->
            if (ArchiveFileTransport.isRateLimited()) throw ArchiveFileTransport.Failure("rate_limited", 429)
            if (!allowed()) throw ArchiveFileTransport.Failure("policy_blocked")
            try { return@withContext httpClient.catalogRequest(request, maxResponseBytes = 4L * 1024 * 1024) }
            catch (e: CancellationException) { throw e }
            catch (e: IOException) {
                if (e.message == "Catalog response exceeds its metadata budget") throw e
                val retryable = e !is CatalogHttpException || e.status == 408 || e.status == 429 || e.status >= 500
                if (!retryable || attempt == 1 || ArchiveFileTransport.isRateLimited()) throw e
                delay(500L)
            }
        }
        throw IOException("Incomplete Archive response")
    }

    private fun archiveOutcome(error: Exception): String = when (error) {
        is ArchiveFileTransport.Failure -> error.outcome
        is CatalogHttpException -> when (error.status) {
            401, 403, 451 -> "access_denied"
            404, 410 -> "http_unavailable"
            429, 503 -> "rate_limited"
            else -> "http_error"
        }
        is java.io.InterruptedIOException -> "network_timeout"
        else -> "metadata_incomplete"
    }

    internal fun archiveFailureReason(error: Exception): Reason = when (archiveOutcome(error)) {
        "network_timeout" -> Reason.NETWORK_TIMEOUT
        "access_denied" -> Reason.UNAUTHORIZED
        "http_unavailable", "redirect_rejected" -> Reason.SOURCE_VERIFICATION_FAILED
        "policy_blocked" -> Reason.NETWORK_POLICY_BLOCKED
        "range_unavailable" -> Reason.SEEK_UNAVAILABLE
        "range_changed" -> Reason.SOURCE_VERIFICATION_FAILED
        else -> Reason.METADATA_INCOMPLETE
    }

    private val flacHeaders = LruCache<ArchiveFileKey, Pair<Long, ArchiveAudioMetadata.Tags>>(128)

    private suspend fun readFlacHeader(url: String): ArchiveAudioMetadata.Tags? =
        ArchiveFileKey.fromUrl(url)?.let { readFlacHeader(it, allowed = { true }) }

    private suspend fun readFlacHeader(key: ArchiveFileKey, allowed: () -> Boolean,
        onFailure: (IOException) -> Unit = {}): ArchiveAudioMetadata.Tags? =
        withContext(AppDispatchers.Io) {
            headerRequests.withPermit {
                if (!allowed()) return@withPermit null
                val now = SystemClock.elapsedRealtime()
                flacHeaders.get(key)?.takeIf { now - it.first in 0 until 300_000L }?.let { return@withPermit it.second }
                try {
                    val bytes = ArchiveFileTransport.probe(key, allowed = allowed)
                    var tags = withContext(AppDispatchers.CpuBounded) { ArchiveAudioMetadata.flac(bytes) }
                        ?: return@withPermit null
                    var next = ArchiveAudioMetadata.followingFlacMetadataOffset(bytes)
                    repeat(2) {
                        if (tags.title.isNotBlank() && tags.artist.isNotBlank() || next == null) return@repeat
                        val offset = checkNotNull(next)
                        val more = try { ArchiveFileTransport.probe(key, offset, 64 * 1024, allowed) }
                            catch (error: IOException) {
                                ArchivePlaybackEvents.record("header_range", archiveOutcome(error), key)
                                next = null
                                return@repeat
                            }
                        tags = ArchiveAudioMetadata.extendFlacTags(more, tags)
                        next = ArchiveAudioMetadata.followingMetadataOffset(more, 0, offset)
                    }
                    flacHeaders.put(key, now to tags)
                    tags
                } catch (e: CancellationException) { throw e }
                catch (e: IOException) {
                    onFailure(e)
                    ArchivePlaybackEvents.record("flac_header", archiveOutcome(e), key)
                    null
                }
            }
        }

    private suspend fun verifyContainer(track: MusicTrack, allowed: () -> Boolean, requestId: Long? = null): Boolean = withContext(AppDispatchers.Io) {
        val key = ArchiveFileKey.fromUrl(track.audioUrl) ?: return@withContext false
        if (!allowed() || !key.playableContainer) return@withContext false
        if (ArchiveFileEvidence.fresh(key) != null) return@withContext true
        val started = SystemClock.elapsedRealtime()
        try {
            var headerFailure: IOException? = null
            val flac = if (key.extension == "flac") readFlacHeader(key, allowed) { headerFailure = it } else null
            val playable = when (key.extension) {
                "flac" -> flac != null
                "wav" -> headerRequests.withPermit { ArchiveAudioMetadata.pcmWave(ArchiveFileTransport.probe(key, allowed = allowed)) }
                "m4a" -> headerRequests.withPermit {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        val prefix = ArchiveFileTransport.probe(key, allowed = allowed)
                        ArchiveAudioMetadata.alac(prefix) { offset, length ->
                            ArchiveFileTransport.probe(key, offset, length, allowed)
                        }
                    } ?: throw java.net.SocketTimeoutException("Archive ALAC metadata timed out")
                }
                else -> false
            }
            if (!playable) {
                ArchivePlaybackEvents.record("container", headerFailure?.let(::archiveOutcome) ?: "container_invalid",
                    key, SystemClock.elapsedRealtime() - started, requestId = requestId)
                return@withContext false
            }
            if (flac != null && !ArchiveFileEvidence.isLiteralSelection(track) && conflictsWithEmbedded(track, flac)) {
                ArchivePlaybackEvents.record("container", "identity_conflict", key, SystemClock.elapsedRealtime() - started, requestId = requestId)
                return@withContext false
            }
            val mime = when (key.extension) {
                "flac" -> androidx.media3.common.MimeTypes.AUDIO_FLAC
                "m4a" -> androidx.media3.common.MimeTypes.AUDIO_ALAC
                else -> null
            }
            if (mime != null && !runCatching {
                    androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, false, false).isNotEmpty()
                }.getOrDefault(false)) {
                ArchivePlaybackEvents.record("decoder", "decoder_unsupported", key)
                return@withContext false
            }
            if (!allowed()) return@withContext false
            ArchiveFileEvidence.remember(key, track.copy(
                durationMs = flac?.durationMs?.takeIf { it > 0L } ?: track.durationMs,
                isrc = flac?.isrc.orEmpty().ifBlank { track.isrc }))
            ArchivePlaybackEvents.record("container", "verified", key, SystemClock.elapsedRealtime() - started, requestId = requestId)
            true
        } catch (e: IOException) {
            ArchivePlaybackEvents.record("container", archiveOutcome(e), key, requestId = requestId)
            false
        }
    }

    private fun conflictsWithEmbedded(track: MusicTrack, tags: ArchiveAudioMetadata.Tags): Boolean {
        val embeddedCode = RecordingIdentity.isrc(tags.isrc)
        val metadataCode = RecordingIdentity.isrc(track.isrc)
        if (ArchiveRecordingIdentity.isrcConflict(embeddedCode, metadataCode)) return true
        if (tags.artist.isNotBlank() && !unknownArtist(track.artist)) {
            val credits = RecordingIdentity.artistNames(track)
            val embedded = RecordingIdentity.artistNames(track.copy(artist = tags.artist, primaryArtists = emptyList()))
            if (credits.isNotEmpty() && embedded.isNotEmpty() && !ArchiveRecordingIdentity.artistsAgree(credits, embedded)) return true
        }
        if (tags.title.isBlank()) return false
        val versions = RecordingVersions.markers(tags.title)
        if (versions.isNotEmpty() && RecordingVersions.markers(track.title) != versions) return true
        val embeddedTitle = RecordingIdentity.title(track.copy(title = tags.title, originalProviderTitle = ""))
        val expected = if (versions.isEmpty() && track.title.endsWith(" (Live)")) track.copy(title = track.title.removeSuffix(" (Live)")) else track
        return !CatalogNames.spellingEquivalent(RecordingIdentity.title(expected), embeddedTitle)
    }

    private fun fileName(file: JSONObject?) = (file?.opt("name") as? String).orEmpty()
    private fun fileTitle(file: JSONObject?) = text(file, "title")
        .ifBlank { text(file, "track_title") }.ifBlank { text(file, "song_title") }
    private fun fileArtist(file: JSONObject?) = text(file, "artist").ifBlank { text(file, "creator") }
        .ifBlank { text(file, "album_artist") }.ifBlank { text(file, "albumartist") }
    private fun fileDuration(file: JSONObject?) = sequenceOf("length", "duration", "runtime")
        .map { durationMillis(text(file, it)) }.firstOrNull { it > 0L }
        ?: text(file, "duration_ms").toLongOrNull()?.takeIf { it > 0L } ?: 0L
    private fun unknownArtist(artist: String) = artist.isBlank() ||
        artist.equals("Unknown Artist", true) || artist.equals("Various Artists", true)
    private fun conflicts(a: String, b: String): Boolean =
        a.isNotBlank() && b.isNotBlank() &&
            (RecordingVersions.markers(a) != RecordingVersions.markers(b) || !CatalogNames.spellingEquivalent(a, b))

    /** Interpret per-file evidence; a pathname can label a file but cannot identify a recording. */
    internal suspend fun parseLosslessItem(doc: JSONObject, root: JSONObject, requested: MusicTrack? = null,
        excludedUrls: Set<String> = emptySet(),
        onRejected: (Reason, String) -> Unit = { _, _ -> },
        exactFile: ArchiveFileKey? = null, allowLiteral: Boolean = false, browseTerms: List<String> = emptyList(),
        validateCandidate: suspend (MusicTrack) -> Boolean = { true },
        validateUnlabeledAlac: suspend (MusicTrack) -> Boolean = { false },
        readHeader: suspend (String) -> ArchiveAudioMetadata.Tags? = ::readFlacHeader
    ): List<MusicTrack> = withContext(AppDispatchers.CpuBounded) {
        val identifier = text(doc, "identifier")
        val metadata = root.optJSONObject("metadata") ?: return@withContext emptyList()
        if (identifier.isBlank() || restricted(root) || restricted(metadata)) return@withContext emptyList()
        if (exactFile != null && exactFile.item != identifier) return@withContext emptyList()
        val files = root.optJSONArray("files") ?: throw IOException("Missing Archive file metadata")
        val entries = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
        val byName = entries.groupBy { fileName(it) }
        fun original(file: JSONObject): JSONObject? {
            if (text(file, "source") != "derivative") return null
            val name = file.opt("original") as? String ?: return null
            val ancestor = byName[name]?.singleOrNull() ?: return null
            return ancestor.takeIf { text(it, "source") == "original" && !restricted(it) &&
                ArchiveFileKey.create(identifier, name) != null }
        }
        fun unlabeledAlac(file: JSONObject): Boolean {
            val name = fileName(file)
            val format = text(file, "format").lowercase(Locale.ROOT)
            return name.endsWith(".m4a", ignoreCase = true) && !format.contains("aac") &&
                !isLosslessAudioFile(name, format) && (exactFile?.file == name || text(file, "source") == "original")
        }
        val lossless = entries.filter {
            (isLosslessAudioFile(fileName(it), text(it, "format")) || unlabeledAlac(it)) &&
                byName[fileName(it)]?.size == 1 &&
                text(it, "size").toLongOrNull()?.let { size -> size > 0 } != false
        }
        val audioNames = setOf("flac", "wav", "m4a", "aif", "aiff", "ape", "wv", "dsf", "dff", "mp3", "ogg", "opus", "wma", "aac", "mka", "mp4")
        val audioGroups = entries.filter { fileName(it).substringAfterLast('.').lowercase(Locale.ROOT) in audioNames }
            .map { file -> fileName(original(file) ?: file) }.distinct()
        val isConcert = values(metadata, "collection").any { it.equals("etree", true) }
        val singleRecording = audioGroups.size == 1 && !isConcert && !root.optBoolean("wavtape_partial")
        val selectedFile = exactFile
        val preferredFile = selectedFile ?: requested?.let { ArchiveFileKey.fromId(it.id) }
        val wantedTitle = requested?.let(RecordingIdentity::title).orEmpty()
        val ordered = when {
            requested != null -> {
                fun titleHint(file: JSONObject): Boolean {
                    val hint = normalized(fileTitle(file) + " " + fileTitle(original(file)) + " " + fileName(file))
                    return wantedTitle.isNotBlank() && wantedTitle in hint
                }
                lossless.sortedWith(compareByDescending<JSONObject> { preferredFile?.file == fileName(it) }
                    .thenByDescending(::titleHint)
                    .thenByDescending { fileName(it).endsWith(".flac", ignoreCase = true) })
            }
            browseTerms.isNotEmpty() -> lossless.sortedByDescending { file ->
                // Use filenames to spend the existing probe budget on likelier matches.
                // Identity still comes from the manifest or embedded tags.
                val hint = normalized("${fileTitle(file)} ${fileTitle(original(file))} ${fileArtist(file)} ${fileName(file)}")
                browseTerms.count { it in hint }
            }
            else -> lossless
        }
        val accepted = mutableListOf<MusicTrack>()
        for (file in ordered) {
            currentCoroutineContext().ensureActive()
            val key = ArchiveFileKey.create(identifier, fileName(file)) ?: continue
            if (selectedFile != null && selectedFile != key || !key.playableContainer) continue
            if (restricted(file)) {
                onRejected(Reason.UNAUTHORIZED, "File access is restricted")
                continue
            }
            val url = key.url.toString()
            if (url in excludedUrls) continue
            val ancestor = original(file)
            val reportedTitle = fileTitle(file).takeUnless { genericTitle.matches(it) }.orEmpty()
            var title = reportedTitle
            var artist = fileArtist(file)
            var album = text(file, "album")
            var duration = fileDuration(file)
            var code = text(file, "isrc")
            fun reject(reason: Reason, detail: String) = onRejected(reason, "item=$identifier, file=${key.file}: $detail")
            fun conflictingCode(other: String) = RecordingIdentity.isrc(other)?.let { known ->
                RecordingIdentity.isrc(code)?.let { it != known } ?: false
            } ?: false

            val origTitle = fileTitle(ancestor)
            val itemArtist = text(metadata, "artist").ifBlank { text(metadata, "creator") }.ifBlank { text(doc, "creator") }
            val needsIdentity = title.isBlank() || duration <= 0L || unknownArtist(artist.ifBlank { itemArtist }) ||
                requested != null && CatalogNames.normalize(artist.ifBlank { itemArtist }) != CatalogNames.normalize(requested.artist)
            val plausible = requested == null || reportedTitle.isBlank() ||
                CatalogNames.spellingEquivalent(wantedTitle, normalized(reportedTitle)) ||
                wantedTitle.isNotBlank() && wantedTitle in normalized("${key.file} $origTitle")
            // An exact item title and one actual recording can safely fill those fields without a network probe.
            val canUseItemTitle = singleRecording && !text(metadata, "title").isBlank()
            val header = if (key.extension == "flac" && needsIdentity && plausible &&
                !(requested == null && (origTitle.isNotBlank() || canUseItemTitle) && duration > 0 &&
                    !unknownArtist(fileArtist(ancestor).ifBlank { itemArtist }))) readHeader(url) else null
            if (header != null) {
                if (conflicts(title, header.title) || (!unknownArtist(artist) && conflicts(artist, header.artist)) ||
                    conflictingCode(header.isrc)) {
                    reject(Reason.RECORDING_MISMATCH, "embedded tags conflict with file metadata")
                    continue
                }
                title = header.title.ifBlank { title }
                artist = header.artist.ifBlank { artist }
                album = header.album.ifBlank { album }
                code = header.isrc.ifBlank { code }
                if (header.durationMs > 0L) duration = header.durationMs
            }
            val sourceCoherent = ancestor != null && !conflicts(title, origTitle) &&
                (unknownArtist(artist) || !conflicts(artist, fileArtist(ancestor))) && !conflictingCode(text(ancestor, "isrc")) &&
                !conflicts(text(file, "version"), text(ancestor, "version"))
            if (sourceCoherent) {
                title = title.ifBlank { origTitle }
                if (unknownArtist(artist)) artist = fileArtist(ancestor)
                album = album.ifBlank { text(ancestor, "album") }
                code = code.ifBlank { text(ancestor, "isrc") }
            }
            if (canUseItemTitle && title.isBlank() && (ancestor == null || sourceCoherent)) title = text(metadata, "title")
            if (artist.isBlank()) artist = itemArtist.ifBlank { "Unknown Artist" }
            album = album.ifBlank { text(metadata, "album") }.ifBlank { text(doc, "album") }
                .ifBlank { if (audioGroups.size > 1) text(metadata, "title") else "Unknown" }
            if (duration <= 0 && singleRecording) duration = fileDuration(metadata)
            val missingTitle = title.isBlank() || genericTitle.matches(title.trim())
            val missingArtist = unknownArtist(artist)
            val literal = allowLiteral && (missingTitle || missingArtist)
            if (!literal && (missingTitle || missingArtist)) {
                reject(Reason.METADATA_INCOMPLETE, "missing individual recording evidence")
                continue
            }
            val reportedBitrate = text(file, "bitrate").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.toInt()
            val bitrate = reportedBitrate ?: if (duration > 0L) {
                ((text(file, "size").toLongOrNull()?.coerceIn(0, Long.MAX_VALUE / 8) ?: 0L) * 8L / duration)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else 0
            val version = text(file, "version").ifBlank { if (sourceCoherent) text(ancestor, "version") else "" }
            if (version.isNotBlank() && !title.contains(version, true)) title += " ($version)"
            if (isConcert && !Regex("(?i)\\blive\\b").containsMatchIn(title)) title += " (Live)"
            val candidate = MusicTrack(id = key.id, title = if (literal) key.file else title, artist = artist,
                album = album, durationMs = duration, coverUrl = "https://archive.org/services/img/$identifier",
                audioUrl = url, bitrateKbps = bitrate, qualityBadge = if (header != null &&
                    header.bitDepth > 16 && header.sampleRate >= 44_100) "Hi-Res • Lossless" else losslessQuality(file),
                genre = text(file, "genre").ifBlank { "Unknown" },
                year = text(file, "year").ifBlank { text(metadata, "year") }.ifBlank { text(doc, "year") },
                isrc = code, source = "INTERNET_ARCHIVE", isLossless = true)
            if (requested != null) {
                val matched = if (selectedFile == null) ArchiveRecordingIdentity.confidence(requested, candidate) != null
                    else ArchiveFileEvidence.matchesSelection(requested, candidate)
                if (!matched) {
                    reject(Reason.RECORDING_MISMATCH, "title, artist or version did not match")
                    continue
                }
            }
            if (unlabeledAlac(file) && !validateUnlabeledAlac(candidate) || !validateCandidate(candidate)) {
                if (requested != null || selectedFile != null)
                    reject(Reason.SOURCE_VERIFICATION_FAILED, "lossless container could not be verified")
                continue
            }
            val codec = when (key.extension) { "flac" -> "FLAC"; "m4a" -> "ALAC"; "wav" -> "PCM"; else -> "" }
            if (codec.isNotBlank() && bitrate > 0) sourceBitrates.put(url, codec to bitrate)

            if (selectedFile != null || requested != null) return@withContext listOf(candidate)
            accepted.add(candidate)
        }
        accepted
    }

    private fun losslessQuality(file: JSONObject): String {
        val bits = listOf("bits", "bitdepth", "bit_depth", "bits_per_sample")
            .firstNotNullOfOrNull { text(file, it).toIntOrNull()?.takeIf { value -> value in 8..64 } }
            ?: Regex("(?i)(16|24|32)[ -]?bit").find(text(file, "format"))?.groupValues?.get(1)?.toIntOrNull()
        val rate = listOf("samplerate", "sample_rate", "sampling_rate")
            .firstNotNullOfOrNull { key ->
                val value = text(file, key).lowercase(Locale.ROOT)
                val number = Regex("[0-9]+(?:[.][0-9]+)?").find(value)?.value?.toDoubleOrNull()
                number?.let { if (value.contains("khz")) it * 1000 else it }
                    ?.takeIf { it.isFinite() && it in 8000.0..768000.0 }
            }
        return if (bits != null && rate != null && (bits > 16 || rate > 48000)) "Hi-Res • Lossless" else "Lossless"
    }
}
