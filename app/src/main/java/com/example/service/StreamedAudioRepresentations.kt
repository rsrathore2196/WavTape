package com.example.service

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.example.data.model.MusicTrack
import com.example.data.remote.StreamingAddons
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.repository.RecordingIdentity
import com.example.playback.PlaybackSourceKind
import com.example.playback.PlaybackSourcePolicy
import com.example.playback.SourceTransitionOrigin
import com.example.playback.startupSourcesFor
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Representation evidence lives beside Media3's spans. No transport URL or authentication is persisted. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object StreamedAudioRepresentations {
    private const val META = "xtreme_streamed_representation_v1"
    private const val SCHEME = "xtreme-cached"
    private const val LIMIT = 256
    private data class DecodedMetadata(val encoded: String, val descriptor: Descriptor?)
    private val decodedMetadata = LruCache<String, DecodedMetadata>(LIMIT)
    private val boundMetadata = LruCache<String, Descriptor>(LIMIT)
    private val recordingHash = Regex("[a-f0-9]{64}")
    private val transports = object : LinkedHashMap<String, Descriptor>(LIMIT, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Descriptor>?) = size > LIMIT
    }
    private val progressive = mapOf("m4a" to "audio/mp4", "mp4" to "audio/mp4", "aac" to "audio/aac",
        "mp3" to "audio/mpeg", "flac" to "audio/flac", "opus" to "audio/ogg",
        "wav" to "audio/wav", "webm" to "audio/webm", "alac" to "audio/mp4")
    private val safeQuery = setOf("quality", "bitrate", "format", "codec", "itag", "clen", "length", "id", "track", "file", "version")

    data class Descriptor(val key: String, val recording: String, val provider: String, val kind: PlaybackSourceKind,
        val bitrate: Int, val codec: String, val mime: String, val lossless: Boolean, val length: Long = 0L, val trackId: String = "") {
        fun json(): String = JSONObject().put("recording", recording).put("provider", provider).put("kind", kind.name)
            .put("bitrate", bitrate).put("codec", codec).put("mime", mime).put("lossless", lossless).put("length", length).put("track", trackId).toString()
        fun uri(): Uri = Uri.Builder().scheme(SCHEME).authority("audio").appendPath(key)
            .appendQueryParameter("provider", provider).appendQueryParameter("kind", kind.name)
            .appendQueryParameter("bitrate", bitrate.toString()).appendQueryParameter("lossless", lossless.toString())
            .appendQueryParameter("codec", codec).appendQueryParameter("mime", mime).appendQueryParameter("track", trackId).build()
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun recording(track: MusicTrack) = digest("${track.id}|${RecordingIdentity.key(track)}")

    private fun archiveRecording(track: MusicTrack) =
        digest("${track.id}|archive|${com.example.data.remote.ArchiveRecordingIdentity.key(track)}")

    fun keyFor(uri: Uri): String? = synchronized(transports) { transports[uri.toString()]?.key }

    /** Bind a verified representation in memory. The audio loader persists it when it opens the cache. */
    fun remember(track: MusicTrack, policy: PlaybackSourcePolicy) {
        val uri = Uri.parse(track.audioUrl)
        if (uri.scheme == SCHEME || uri.scheme !in setOf("http", "https", "xtreme-youtube", "xtreme-sabr")) return
        val providerId = SourceTransitionOrigin.sourceId(track.id, uri, policy) ?: return
        val provider = policy.playbackSources.firstOrNull { it.id == providerId } ?: return
        if (provider.kind == PlaybackSourceKind.YOUTUBE) {
            val video = YouTubeAudioStreamService.videoIdFromUri(uri)
                ?: track.id.removePrefix("yt_").takeIf(YouTubeAudioStreamService::isVideoId) ?: return
            val referencedItag = uri.getQueryParameter("itag")?.toIntOrNull()
            val resolved = YouTubeAudioStreamService.cachedForUrl(track.audioUrl)
                ?: YouTubeAudioStreamService.cachedStreams(video).firstOrNull {
                    !it.isHls && (referencedItag == null || it.itag == referencedItag) &&
                        it.bitrateKbps == track.bitrateKbps && it.isSabr == (uri.scheme == "xtreme-sabr")
                } ?: return
            if (resolved.isHls || resolved.contentLength?.let { it <= 0 } == true) return
            val descriptor = Descriptor(resolved.cacheKey, recording(track), provider.id, provider.kind,
                resolved.bitrateKbps, resolved.codec, if (resolved.codec == "Opus") "audio/webm" else "audio/mp4", false,
                resolved.contentLength ?: 0L, track.id)
            boundMetadata.put(descriptor.key, descriptor)
            return
        }
        if (uri.scheme !in setOf("http", "https") || track.bitrateKbps <= 0) return
        val extension = uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        val declaredMime = StreamingAddons.mime(track.audioUrl)?.substringBefore(';')?.lowercase(Locale.ROOT)
        val mime = declaredMime ?: progressive[extension] ?: return
        if (mime !in progressive.values || extension in setOf("m3u8", "mpd")) return
        // A relay may have no extension, and ALAC can be delivered in .m4a.
        // The exact registered add-on format is stronger than the path.
        val registeredCodec = if (provider.kind == PlaybackSourceKind.ADDON && track.isLossless)
            StreamingAddons.cacheableLosslessCodec(track.audioUrl) else null
        val codec = registeredCodec ?: when {
            track.isLossless -> if (extension == "flac") "FLAC" else extension.uppercase(Locale.ROOT).ifBlank { "Lossless" }
            extension in setOf("opus", "ogg", "webm") -> "Opus"
            extension == "mp3" -> "MP3"
            else -> "AAC"
        }
        if (provider.kind == PlaybackSourceKind.ADDON && StreamingAddons.bitrateForStream(track.audioUrl, codec) <= 0) return
        val knownLength = listOf("clen", "length").firstNotNullOfOrNull { uri.getQueryParameter(it)?.toLongOrNull()?.takeIf { n -> n > 0 } } ?: 0L
        val normalized = uri.buildUpon().clearQuery().fragment(null).encodedAuthority(uri.encodedAuthority?.substringAfterLast('@')).apply {
            uri.queryParameterNames.filter { it.lowercase(Locale.ROOT) in safeQuery }.sorted().forEach { name ->
                uri.getQueryParameters(name).forEach { appendQueryParameter(name, it) }
            }
        }.build().toString()
        val nativeArchive = if (provider.kind == PlaybackSourceKind.INTERNET_ARCHIVE)
            com.example.data.remote.ArchiveFileKey.nativeTransport(track.audioUrl) else null
        val identity = if (nativeArchive == null) recording(track) else archiveRecording(track)
        // Native Archive cache fills validate the file's bytes or strong ETag before replaying
        // a sequential response into a persisted partial representation.
        val expected = Descriptor("", identity, provider.id, provider.kind, track.bitrateKbps, codec, mime,
            track.isLossless, knownLength, track.id)
        // A provider may reuse the same address for a different quality/format.
        // Neither URI equality nor recording identity permits joining those bytes.
        val variant = if (nativeArchive != null) digest(nativeArchive.url.toString())
            else digest("$normalized|${provider.kind}|${track.bitrateKbps}|$codec|$mime|${track.isLossless}|$knownLength|" +
                if (knownLength > 0) "known" else UUID.randomUUID().toString())
        val cacheIdentity = if (nativeArchive == null) identity else digest(track.id)
        val newKey = "stream:v1:${digest(provider.id).take(16)}:$cacheIdentity:$variant"
        synchronized(transports) {
            val existing = transports[track.audioUrl]?.takeIf { it.copy(key = "") == expected }
            val descriptor = existing ?: expected.copy(key = newKey)
            boundMetadata.put(descriptor.key, descriptor)
            transports[track.audioUrl] = descriptor
        }
    }

    fun persistBinding(cache: Cache, key: String) {
        val descriptor = boundMetadata.get(key) ?: return
        write(cache, descriptor)
    }

    private fun write(cache: Cache, descriptor: Descriptor) {
        val encoded = descriptor.json()
        if (cache.getContentMetadata(descriptor.key).get(META, "") != encoded)
            cache.applyContentMetadataMutations(descriptor.key, ContentMetadataMutations().set(META, encoded))
    }

    private fun read(cache: Cache, key: String): Descriptor? {
        val encoded = runCatching { cache.getContentMetadata(key).get(META, "") }.getOrNull().orEmpty()
        if (encoded.isBlank() || encoded.length > 2_048) return null
        // Reuse only the parsed metadata. Media3's current index and file spans
        // still decide whether the representation is eligible for playback.
        decodedMetadata.get(key)?.takeIf { it.encoded == encoded }?.let { return it.descriptor }
        val descriptor = runCatching {
            val value = JSONObject(encoded)
            Descriptor(key, value.getString("recording"), value.getString("provider"),
                PlaybackSourceKind.valueOf(value.getString("kind")), value.getInt("bitrate"), value.getString("codec"),
                value.getString("mime"), value.getBoolean("lossless"), value.optLong("length"), value.optString("track"))
                .takeIf { recordingHash.matches(it.recording) && it.bitrate in 1..100_000 &&
                    it.provider.isNotBlank() && it.mime in progressive.values }
        }.getOrNull()
        decodedMetadata.put(key, DecodedMetadata(encoded, descriptor))
        return descriptor
    }

    private fun complete(cache: Cache, descriptor: Descriptor): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(descriptor.key))
        if (length <= 0 || descriptor.length > 0 && descriptor.length != length || !cache.isCached(descriptor.key, 0L, length)) return false
        // Media3's index can outlive a manually removed or truncated span file.
        return cache.getCachedSpans(descriptor.key).filter { it.position < length }.all { span ->
            span.file?.let { it.isFile && it.length() == span.length } == true
        }
    }

    fun permitsCached(policy: PlaybackSourcePolicy, uri: Uri): Boolean = runCatching {
        if (uri.scheme != SCHEME || uri.host != "audio" || uri.pathSegments.size != 1) return@runCatching false
        val source = policy.resolutionSourcesFor(uri.getQueryParameter("track").orEmpty()).firstOrNull { it.id == uri.getQueryParameter("provider") } ?: return@runCatching false
        if (source.kind.name != uri.getQueryParameter("kind")) return@runCatching false
        val bitrate = uri.getQueryParameter("bitrate")?.toIntOrNull() ?: return@runCatching false
        val lossless = uri.getQueryParameter("lossless") == "true"
        bitrate > 0 && bitrate.toLong() * 1_000L <= policy.ceiling.maxBitrateBps && (!lossless || policy.maximum) &&
            (uri.getQueryParameter("codec")?.contains("Atmos", true) != true || policy.allowsDolbyAtmos)
    }.getOrDefault(false)

    fun validateCached(context: Context, uri: Uri, policy: PlaybackSourcePolicy): Boolean {
        if (!permitsCached(policy, uri)) return false
        val cache = MusicCache.getSimpleCache(context)
        val descriptor = read(cache, uri.pathSegments.single()) ?: return false
        return descriptor.uri() == uri && complete(cache, descriptor)
    }

    fun lookup(context: Context, track: MusicTrack, policy: PlaybackSourcePolicy, preferredSourceId: String? = null,
        currentSourceOnly: Boolean = false, bestQualityFirst: Boolean = false): MusicTrack? {
        val uri = Uri.parse(track.audioUrl)
        val scheme = uri.scheme
        if (scheme != null && (MusicCache.isLocal(uri) || scheme == "xtreme-offline")) return null
        val cache = MusicCache.getSimpleCache(context)
        val identity = recording(track)
        val sourceId = if (currentSourceOnly) {
            if (uri.scheme == SCHEME) uri.getQueryParameter("provider")
            else SourceTransitionOrigin.sourceId(track.id, uri, policy)
        } else preferredSourceId
        val order = (if (bestQualityFirst) policy.startupSourcesFor(track.id) else policy.resolutionSourcesFor(track.id))
            .filter { sourceId == null || it.id == sourceId }
        val opusOnly = uri.getQueryParameter("codec") == "opus"
        val archiveIdentity by lazy { archiveRecording(track) }
        val candidates = cache.keys.asSequence().mapNotNull { read(cache, it) }.filter {
            val sameRecording = it.recording == identity ||
                it.kind == PlaybackSourceKind.INTERNET_ARCHIVE && it.recording == archiveIdentity
            sameRecording && (!opusOnly || it.codec == "Opus") &&
                order.any { source -> source.id == it.provider } && permitsCached(policy, it.uri()) && complete(cache, it)
        }.toList()
        val descriptor = order.firstNotNullOfOrNull { source -> candidates.filter { it.provider == source.id }.maxByOrNull { it.bitrate } }
            ?: legacyYouTube(context, track, policy, order.map { it.id }) ?: return null
        return track.copy(audioUrl = descriptor.uri().toString(), bitrateKbps = descriptor.bitrate, isLossless = descriptor.lossless,
            qualityBadge = YouTubeAudioStreamService.displayQuality(descriptor.codec, descriptor.bitrate),
            source = when (descriptor.kind) { PlaybackSourceKind.YOUTUBE -> "YouTube Music"; PlaybackSourceKind.JIOSAAVN -> "JIOSAAVN"
                PlaybackSourceKind.INTERNET_ARCHIVE -> "INTERNET_ARCHIVE"; PlaybackSourceKind.ADDON -> "Streaming Addon: cached" })
    }

    private fun legacyYouTube(context: Context, track: MusicTrack, policy: PlaybackSourcePolicy,
        allowedProviders: List<String>): Descriptor? {
        val provider = policy.playbackSources.firstOrNull { it.kind == PlaybackSourceKind.YOUTUBE && it.id in allowedProviders } ?: return null
        val video = track.id.removePrefix("yt_").takeIf { track.id.startsWith("yt_") && YouTubeAudioStreamService.isVideoId(it) } ?: return null
        val opusOnly = Uri.parse(track.audioUrl).getQueryParameter("codec") == "opus"
        val streams = listOfNotNull(MusicCache.offlineAudio(context, video, policy.ceiling.maxBitrateBps, false, opusOnly),
            MusicCache.offlineAudio(context, video, policy.ceiling.maxBitrateBps, true, opusOnly))
        val stream = streams.maxByOrNull { it.bitrateKbps } ?: return null
        return Descriptor(stream.cacheKey, recording(track), provider.id, provider.kind, stream.bitrateKbps, stream.codec,
            if (stream.codec == "Opus") "audio/webm" else "audio/mp4", false, stream.contentLength ?: 0L, track.id)
            .also { write(MusicCache.getSimpleCache(context), it) }
    }

    fun completeSongCount(cache: Cache): Int = cache.keys.mapNotNull { read(cache, it) }.filter { complete(cache, it) }
        .map { it.trackId.ifBlank { it.recording } }.distinct().size

    /** Avoid counting an adopted YouTube representation again through its older video-only journal. */
    fun adoptedYoutubeVideos(cache: Cache): Set<String> = cache.keys.mapNotNull { read(cache, it) }
        .filter { it.kind == PlaybackSourceKind.YOUTUBE && complete(cache, it) }
        .mapNotNull { descriptor -> descriptor.key.split(':').getOrNull(1)?.takeIf(YouTubeAudioStreamService::isVideoId) }
        .toSet()
}
