package com.example.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.DashWrappingSegmentIndex
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.wav.WavExtractor
import com.example.service.MusicCache
import com.example.service.FastStartManifests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.abs

/** Validate and warm the shared Media3 cache, without another player, decoder or audio focus. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object LosslessStreamPrebuffer {
    private val worker = Mutex()
    private fun currentCheck(automatic: Boolean) {
        if (automatic && !com.example.performance.BackgroundWorkCoordinator.optionalPlaybackWorkMayProceed())
            throw PreparationDeferred()
    }
    private class PreparationDeferred : RuntimeException()
    private class PreparationFailure(val reason: LosslessDiagnostics.Reason, message: String) : IOException(message)
    data class AudioInfo(val codec: String, val bitrateKbps: Int, val sampleRate: Int, val bitDepth: Int) {
        val isHiRes: Boolean get() = sampleRate >= 44_100 && bitDepth >= 16 &&
            (bitDepth > 16 || sampleRate > 48_000)
        val badge: String get() = if (isHiRes) "Hi-Res Lossless" else "Lossless"
    }
    data class Prepared(val audio: AudioInfo, val fromMs: Long, val untilMs: Long) {
        fun covers(positionMs: Long): Boolean = positionMs >= fromMs && untilMs - positionMs >= 750L
    }

    /** Only properties reported by the actual container qualify for a hi-res badge. */
    fun audioInfo(format: Format, fallbackBitrateKbps: Int = 0, allowDrm: Boolean = false): AudioInfo? {
        val codec = when (format.sampleMimeType) {
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            MimeTypes.AUDIO_RAW -> "PCM"
            else -> return null
        }
        if (format.sampleRate <= 0 || format.channelCount <= 0 || format.drmInitData != null && !allowDrm) return null
        val bitrate = format.averageBitrate.takeIf { it > 0 }?.div(1000) ?: fallbackBitrateKbps
        return AudioInfo(codec, bitrate.coerceAtLeast(0), format.sampleRate, format.sourceBitDepth())
    }

    private fun preparationAudioInfo(format: Format, requireLossless: Boolean): AudioInfo? {
        if (requireLossless) return audioInfo(format)
        if (format.sampleMimeType?.startsWith("audio/") != true || format.sampleRate <= 0 ||
            format.channelCount <= 0 || format.drmInitData != null) return null
        val codec = com.example.data.remote.YouTubeAudioStreamService.displayCodec(format.sampleMimeType, format.codecs)
        return AudioInfo(codec, (format.averageBitrate / 1000).coerceAtLeast(0), format.sampleRate, 0)
    }

    /** Prepare a small prefix through the same cache; no extra player or audio focus. */
    suspend fun warmSource(context: Context, item: MediaItem): Boolean = withContext(com.example.performance.AppDispatchers.Io) {
        val uri = item.localConfiguration?.uri ?: return@withContext false
        if (uri.scheme == "xtreme-sabr") return@withContext true // Native session owns its buffer.
        val source = MusicCache.createCacheDataSourceFactory(context, 3_000, 3_000).createDataSource()
        try {
            source.open(androidx.media3.datasource.DataSpec.Builder().setUri(uri).build())
            val bytes = ByteArray(32 * 1024)
            var read = 0
            val deadline = android.os.SystemClock.elapsedRealtime() + 2_000L
            while (read < 128 * 1024 && android.os.SystemClock.elapsedRealtime() < deadline) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count = source.read(bytes, 0, bytes.size)
                if (count < 0) break
                read += count
            }
            read > 0
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        finally { source.close() }
    }

    // Match DefaultMediaSourceFactory's classification when an add-on supplies
    // a manifest URL without declaring its MIME type.
    private fun contentType(item: MediaItem): Int = item.localConfiguration?.let {
        Util.inferContentTypeForUriAndMimeType(it.uri, it.mimeType)
    } ?: C.CONTENT_TYPE_OTHER

    internal fun isAdaptiveSource(item: MediaItem): Boolean = when (contentType(item)) {
        C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_HLS -> true
        else -> false
    }

    // A DASH manifest can omit the sample rate and channel count. The active
    // decoder verifies those after Media3 reads the selected representation.
    private fun supportsAdaptiveAudio(format: Format, requireLossless: Boolean, allowDrm: Boolean): Boolean {
        val mime = format.sampleMimeType ?: return false
        if (!mime.startsWith("audio/") || format.drmInitData != null && !allowDrm ||
            requireLossless && !StreamQualityBadge.from(format).lossless) return false
        return mime == MimeTypes.AUDIO_RAW || MediaCodecUtil.getDecoderInfos(mime, false, false)
            .any { it.isFormatSupported(format) }
    }

    /** Let Media3 prepare adaptive manifests/init segments; progressive extractors cannot sniff an MPD.
     * No second player, audio sink or audio focus. Decoder input is still verified at activation.
     */
    suspend fun prepareAdaptiveSource(context: Context, item: MediaItem, expectedDurationMs: Long,
        stillCurrent: () -> Boolean, durationToleranceMs: Long = 5_000L, automatic: Boolean = false,
        currentPosition: () -> Long = { 0L }, requireLossless: Boolean = true): Boolean = prepareAdaptive(
        context, item, expectedDurationMs, stillCurrent, durationToleranceMs, automatic, currentPosition, requireLossless)

    suspend fun prepareAdaptiveFastStart(context: Context, item: MediaItem, expectedDurationMs: Long,
        stillCurrent: () -> Boolean, durationToleranceMs: Long, automatic: Boolean, currentPosition: () -> Long,
        requireLossless: Boolean): FastStartBuffer? {
        var prepared: FastStartBuffer? = null
        prepareAdaptive(context, item, expectedDurationMs, stillCurrent, durationToleranceMs, automatic,
            currentPosition, requireLossless) { prepared = it }
        return prepared
    }

    private suspend fun prepareAdaptive(context: Context, item: MediaItem, expectedDurationMs: Long,
        stillCurrent: () -> Boolean, durationToleranceMs: Long, automatic: Boolean, currentPosition: () -> Long,
        requireLossless: Boolean, onFastStartPrepared: ((FastStartBuffer) -> Unit)? = null): Boolean {
        if (automatic) return com.example.performance.BackgroundWorkCoordinator.startup(
            com.example.performance.BackgroundWorkCoordinator.StartupPriority.DEFERRED, requireForeground = false,
            label = "Adaptive lossless preparation") {
            prepareAdaptive(context, item, expectedDurationMs, stillCurrent, durationToleranceMs, automatic = false,
                currentPosition, requireLossless, onFastStartPrepared)
        }
        return withContext(com.example.performance.AppDispatchers.Io) {
            worker.withLock {
                val uri = item.localConfiguration?.uri ?: return@withLock false
                val configured = com.example.data.remote.StreamingAddons.prepareMediaItem(item)
                val allowDrm = configured.localConfiguration?.drmConfiguration != null
                fun allowed(): Boolean {
                    val policy = NetworkQualityMonitor.getInstance(context).sourcePolicySnapshot()
                    return policy.online && com.example.data.remote.StreamingAddons.isVerified(uri.toString(), policy)
                }
                if (!allowed()) return@withLock false
                data class Metadata(val formats: List<Format>, val durationMs: Long, val manifest: Any?)
                val manifests = onFastStartPrepared?.let { FastStartManifests() }
                val cache = MusicCache.createCacheDataSourceFactory(context, 5_000, 5_000)
                val upstream = manifests?.recording(cache) ?: cache
                val byteBudget = if (manifests != null) 24 * 1024 * 1024L else 4 * 1024 * 1024L
                val bytes = java.util.concurrent.atomic.AtomicLong()
                val factory = androidx.media3.datasource.DataSource.Factory {
                    val delegate = upstream.createDataSource()
                    object : androidx.media3.datasource.DataSource by delegate {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (bytes.get() >= byteBudget) throw IOException("Adaptive preparation byte budget reached")
                            return delegate.read(buffer, offset, minOf(length.toLong(), byteBudget - bytes.get()).toInt())
                                .also { if (it > 0) bytes.addAndGet(it.toLong()) }
                        }
                    }
                }
                try {
                    if (!withContext(Dispatchers.Main.immediate) { stillCurrent() }) return@withLock false
                    val metadata = if (contentType(configured) == C.CONTENT_TYPE_DASH) {
                        // DASH already describes the representations and their time ranges.
                        // Read it with the same parser Media3 uses for playback, without
                        // waiting for a separate MediaPeriod to report decoder properties.
                        val manifest = androidx.media3.exoplayer.upstream.ParsingLoadable.load(
                            factory.createDataSource(), androidx.media3.exoplayer.dash.manifest.DashManifestParser(),
                            uri, C.DATA_TYPE_MANIFEST)
                        if (manifest.dynamic || manifest.periodCount !in 1..8) return@withLock false
                        val groups = (0 until manifest.periodCount).flatMap { period ->
                            manifest.getPeriod(period).adaptationSets.also { sets ->
                                if (sets.size > 8 || sets.any { it.representations.size > 16 }) return@withLock false
                            }
                        }
                        Metadata(groups.flatMap { group -> group.representations.map { it.format } }, manifest.durationMs, manifest)
                    } else withContext(Dispatchers.Main.immediate) {
                        if (!stillCurrent()) return@withContext null
                        val helper = androidx.media3.exoplayer.offline.DownloadHelper.forMediaItem(configured,
                            androidx.media3.common.TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT, null, factory)
                        try {
                            withTimeoutOrNull(12_000L) { suspendCancellableCoroutine<Metadata> { continuation ->
                                helper.prepare(object : androidx.media3.exoplayer.offline.DownloadHelper.Callback {
                                    override fun onPrepared(value: androidx.media3.exoplayer.offline.DownloadHelper) {
                                        if (!continuation.isActive) return
                                        if (value.periodCount > 8 || (0 until value.periodCount).any {
                                            value.getTrackGroups(it).length > 8 || (0 until value.getTrackGroups(it).length).any { group ->
                                                value.getTrackGroups(it)[group].length > 16 }
                                        }) {
                                            continuation.resumeWithException(IOException("Adaptive track budget reached")); return
                                        }
                                        val formats = (0 until value.periodCount).flatMap { period ->
                                            val groups = value.getTrackGroups(period)
                                            (0 until groups.length).flatMap { group ->
                                                (0 until groups[group].length).map { groups[group].getFormat(it) }
                                            }
                                        }
                                        val duration = when (val manifest = value.manifest) {
                                            is androidx.media3.exoplayer.hls.HlsManifest -> manifest.mediaPlaylist.durationUs / 1000
                                            else -> C.TIME_UNSET
                                        }
                                        continuation.resume(Metadata(formats, duration, value.manifest))
                                    }
                                    override fun onPrepareError(value: androidx.media3.exoplayer.offline.DownloadHelper, error: IOException) {
                                        if (continuation.isActive) continuation.resumeWithException(error)
                                    }
                                })
                            } }
                        } finally { helper.release() }
                    } ?: run {
                        LosslessDiagnostics.record(item.mediaId, LosslessDiagnostics.Reason.LOOKUP_TIMEOUT, "adaptive-prebuffer")
                        return@withLock false
                    }
                    val supported = withContext(com.example.performance.AppDispatchers.CpuBounded) {
                        val audio = metadata.formats.filter { it.sampleMimeType?.startsWith("audio/") == true }
                        metadata.formats.none { it.sampleMimeType?.startsWith("video/") == true } &&
                            audio.any { supportsAdaptiveAudio(it, requireLossless, allowDrm) }
                    }
                    if (!supported || metadata.durationMs <= 0L || expectedDurationMs > 0L &&
                        abs(metadata.durationMs - expectedDurationMs) > durationToleranceMs) return@withLock false
                    suspend fun livePosition() = withContext(Dispatchers.Main.immediate) {
                        if (stillCurrent()) currentPosition().coerceAtLeast(0L) else null
                    }
                    if (manifests != null) {
                        val warmedRanges = mutableMapOf<Triple<String, Long, Long>, Long>()
                        repeat(3) {
                            val position = livePosition() ?: return@withLock false
                            val warmUntil = minOf(metadata.durationMs, position + FAST_START_READ_AHEAD_MS)
                            val window = FastStartBuffer(position, warmUntil, manifests)
                            if (!window.covers(position)) return@withLock false
                            val ranges = fastStartRanges(metadata.manifest, factory, position, warmUntil, allowDrm)
                                ?: return@withLock false
                            for (range in ranges) {
                                if (!allowed() || livePosition() == null) return@withLock false
                                val key = Triple(range.key ?: range.uri.toString(), range.position, range.length)
                                val encryptionKey = range.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)
                                val warmedLength = warmedRanges[key]
                                if (warmedLength != null && (encryptionKey || MusicCache.isRangeCached(context, range, warmedLength))) continue
                                val length = readCompleteRange(factory, range)
                                // HLS AES keys are retained with the manifests, never the media cache.
                                if (if (encryptionKey) length != 16L else !MusicCache.isRangeCached(context, range, length)) return@withLock false
                                warmedRanges[key] = length
                            }
                            if (!allowed()) return@withLock false
                            val latest = livePosition() ?: return@withLock false
                            if (window.covers(latest)) {
                                onFastStartPrepared(window)
                                return@withLock true
                            }
                            // The old source kept playing or the listener sought elsewhere.
                            // Warm the new playhead, reusing any already committed ranges.
                        }
                        LosslessDiagnostics.record(item.mediaId, LosslessDiagnostics.Reason.BUFFER_COVERAGE, "adaptive-prebuffer")
                        return@withLock false
                    }
                    val position = livePosition() ?: return@withLock false
                    val ranges = withContext(com.example.performance.AppDispatchers.CpuBounded) {
                        val dash = metadata.manifest as? androidx.media3.exoplayer.dash.manifest.DashManifest
                        if (dash == null || dash.dynamic || dash.periodCount == 0) emptyList() else {
                            val periodIndex = (0 until dash.periodCount).lastOrNull { dash.getPeriod(it).startMs <= position } ?: 0
                            val period = dash.getPeriod(periodIndex)
                            val representation = period.adaptationSets.filter { it.type == C.TRACK_TYPE_AUDIO }
                                .flatMap { it.representations }.filter { supportsAdaptiveAudio(it.format, requireLossless, allowDrm) }
                                .maxByOrNull { it.format.bitrate }
                            val base = representation?.baseUrls?.firstOrNull()?.url
                            if (representation == null || base == null) emptyList() else buildList {
                                fun addRange(range: androidx.media3.exoplayer.dash.manifest.RangedUri?) {
                                    if (range != null) add(DataSpec.Builder().setUri(range.resolveUri(base)).setPosition(range.start)
                                        .setLength(range.length).build())
                                }
                                addRange(representation.initializationUri)
                                addRange(representation.indexUri)
                                representation.index?.let { index ->
                                    val duration = dash.getPeriodDurationUs(periodIndex)
                                    addRange(index.getSegmentUrl(index.getSegmentNum((position - period.startMs).coerceAtLeast(0L) * 1000, duration)))
                                }
                            }
                        }
                    }
                    for (range in ranges.distinctBy { Triple(it.uri, it.position, it.length) }.take(3)) {
                        if (!allowed() || !withContext(Dispatchers.Main.immediate) { stillCurrent() }) return@withLock false
                        currentCoroutineContext().ensureActive()
                        val source = factory.createDataSource()
                        try {
                            source.open(range)
                            val buffer = ByteArray(32 * 1024)
                            var read = 0
                            while (read < 1024 * 1024) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer, 0, minOf(buffer.size, 1024 * 1024 - read))
                                if (count < 0) break
                                read += count
                            }
                            if (read == 0) return@withLock false
                        } finally { source.close() }
                    }
                    allowed() && withContext(Dispatchers.Main.immediate) { stillCurrent() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val kind = generateSequence<Throwable>(error) { it.cause }.take(4).joinToString(":") { it.javaClass.simpleName }
                    LosslessDiagnostics.record(item.mediaId, LosslessDiagnostics.Reason.PREBUFFER_FAILED, "adaptive-prebuffer:$kind")
                    false
                }
            }
        }
    }

    private suspend fun readCompleteRange(factory: DataSource.Factory, spec: DataSpec): Long {
        val source = factory.createDataSource()
        val context = currentCoroutineContext()
        try {
            val opened = source.open(spec)
            val expected = if (spec.length >= 0) spec.length else opened
            val buffer = ByteArray(32 * 1024)
            var read = 0L
            while (expected < 0 || read < expected) {
                context.ensureActive()
                val length = if (expected < 0) buffer.size else minOf(buffer.size.toLong(), expected - read).toInt()
                val count = source.read(buffer, 0, length)
                if (count == C.RESULT_END_OF_INPUT) break
                read += count
            }
            if (read == 0L || expected >= 0 && read != expected) throw IOException("Incomplete Fast Start audio range")
            return read
        } finally { source.close() }
    }

    /** Prepare the rendition ranges Media3 itself will request at a VOD seek. */
    private fun fastStartRanges(manifest: Any?, factory: DataSource.Factory,
        fromMs: Long, untilMs: Long, allowDrm: Boolean): List<DataSpec>? {
        val fromUs = fromMs * 1000
        val untilUs = untilMs * 1000
        val requests = linkedMapOf<Triple<String, Long, Long>, DataSpec>()
        fun add(range: DataSpec) {
            requests.putIfAbsent(Triple(range.key ?: range.uri.toString(), range.position, range.length), range)
            if (requests.size > 24) throw IOException("Fast Start segment count exceeds the read budget")
        }
        when (manifest) {
            is DashManifest -> {
                for (periodIndex in 0 until manifest.periodCount) {
                    val period = manifest.getPeriod(periodIndex)
                    val periodStart = period.startMs * 1000
                    val duration = manifest.getPeriodDurationUs(periodIndex)
                    val start = maxOf(0L, fromUs - periodStart)
                    val end = minOf(duration, untilUs - periodStart)
                    if (start >= end) continue
                    val audio = period.adaptationSets.filter { it.type == C.TRACK_TYPE_AUDIO }
                        .flatMap { it.representations }.filter { supportsAdaptiveAudio(it.format, false, allowDrm) }
                    if (audio.isEmpty()) return null
                    for (representation in audio) {
                        val baseUrl = representation.baseUrls.first().url
                        representation.initializationUri?.let { add(DashUtil.buildDataSpec(representation, baseUrl, it, 0)) }
                        representation.indexUri?.let { add(DashUtil.buildDataSpec(representation, baseUrl, it, 0)) }
                        val index = representation.index ?: DashUtil.loadChunkIndex(factory.createDataSource(),
                            C.TRACK_TYPE_AUDIO, representation)?.let {
                            DashWrappingSegmentIndex(it, representation.presentationTimeOffsetUs)
                        } ?: return null
                        var segment = index.getSegmentNum(start, duration)
                        val count = index.getSegmentCount(duration)
                        var covered = start
                        while (covered < end) {
                            if (count >= 0 && segment - index.firstSegmentNum >= count) return null
                            val time = index.getTimeUs(segment)
                            val next = time + index.getDurationUs(segment, duration)
                            if (time > covered || next <= covered) return null
                            // DASH passes a representation-specific cache key, even when it looks like the URI.
                            add(DashUtil.buildDataSpec(representation, baseUrl, index.getSegmentUrl(segment), 0))
                            covered = next
                            segment++
                        }
                    }
                }
            }
            is HlsManifest -> {
                val variants = manifest.multivariantPlaylist
                val uris = (variants.variants.map { it.url } + variants.audios.mapNotNull { it.url }).distinct()
                if (uris.size > 4) return null
                val playlists = if (uris.isEmpty()) listOf(manifest.mediaPlaylist) else uris.map { url ->
                    if (url.toString() == manifest.mediaPlaylist.baseUri) manifest.mediaPlaylist else ParsingLoadable.load(
                        factory.createDataSource(), HlsPlaylistParser(variants, null), url, C.DATA_TYPE_MANIFEST
                    ) as? HlsMediaPlaylist ?: return null
                }
                for (playlist in playlists) {
                    if (!playlist.hasEndTag) return null
                    var covered = fromUs
                    fun addSegment(segment: HlsMediaPlaylist.Segment) {
                        add(DataSpec.Builder().setUri(UriUtil.resolveToUri(playlist.baseUri, segment.url))
                            .setPosition(segment.byteRangeOffset).setLength(segment.byteRangeLength).build())
                        segment.fullSegmentEncryptionKeyUri?.let {
                            add(DataSpec.Builder().setUri(UriUtil.resolveToUri(playlist.baseUri, it))
                                .setFlags(DataSpec.FLAG_ALLOW_GZIP).build())
                        }
                    }
                    for (segment in playlist.segments) {
                        val end = segment.relativeStartTimeUs + segment.durationUs
                        if (end <= covered) continue
                        if (segment.relativeStartTimeUs > covered || segment.hasGapTag) return null
                        segment.initializationSegment?.let(::addSegment)
                        addSegment(segment)
                        covered = end
                        if (covered >= untilUs) break
                    }
                    if (covered < untilUs) return null
                }
            }
            else -> return null
        }
        return requests.values.toList().takeIf { it.isNotEmpty() }
    }

    suspend fun prepare(context: Context, item: MediaItem, expectedDurationMs: Long,
        currentPosition: () -> Long, stillCurrent: () -> Boolean, durationToleranceMs: Long = 5_000L,
        allowUnknownDuration: Boolean = false, automatic: Boolean = false, requireLossless: Boolean = true,
        fastStart: Boolean = false): Prepared? {
        val coordinator = com.example.performance.BackgroundWorkCoordinator
        suspend fun attempt(): Prepared? = coordinator.finishBoundedBatch {
            prepareAttempt(context, item, expectedDurationMs, currentPosition, stillCurrent,
                durationToleranceMs, allowUnknownDuration, automatic, requireLossless, fastStart)
        }
        if (!automatic) return attempt()
        while (withContext(Dispatchers.Main.immediate) { stillCurrent() }) {
            try {
                return coordinator.startup(com.example.performance.BackgroundWorkCoordinator.StartupPriority.DEFERRED, requireForeground = false,
                    label = "Automatic stream prebuffer") { attempt() }
            } catch (_: PreparationDeferred) {
                // The byte cache survives; all extractor/cache locks and the
                // coordinator lease have closed. A gesture is not a bad FLAC.
            }
        }
        return null
    }

    private suspend fun prepareAttempt(context: Context, item: MediaItem, expectedDurationMs: Long,
        currentPosition: () -> Long, stillCurrent: () -> Boolean, durationToleranceMs: Long,
        allowUnknownDuration: Boolean, automatic: Boolean, requireLossless: Boolean,
        fastStart: Boolean): Prepared? = withContext(com.example.performance.AppDispatchers.Io) {
        worker.withLock {
            fun checkpoint() {
                currentCheck(automatic)
            }
            checkpoint() // Recheck after waiting for the single preparation worker.
            LosslessDiagnostics.beginPreparation(item.mediaId)
            val uri = item.localConfiguration?.uri ?: return@withLock null
            fun allowed(): Boolean {
                if (uri.scheme in setOf("file", "content")) return true
                val policy = NetworkQualityMonitor.getInstance(context).sourcePolicySnapshot()
                if (!policy.online || requireLossless && !policy.maximum) return false
                val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
                return when {
                    host == "archive.org" || host.endsWith(".archive.org") -> policy.allowsArchive
                    !requireLossless && (host == "saavncdn.com" || host.endsWith(".saavncdn.com")) -> policy.allows(PlaybackSourceKind.JIOSAAVN)
                    else -> com.example.data.remote.StreamingAddons.isVerified(uri.toString(), policy)
                }
            }
            if (!allowed()) {
                LosslessDiagnostics.record(item.mediaId, LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED, "prebuffer")
                return@withLock null
            }
            if (!withContext(Dispatchers.Main.immediate) { stillCurrent() }) return@withLock null
            val source = MusicCache.createCacheDataSourceFactory(context.applicationContext,
                connectTimeoutMs = 5_000, readTimeoutMs = 5_000).createDataSource()
            val deadline = SystemClock.elapsedRealtime() + 20_000L
            val maxBytes = 24L * 1024 * 1024
            val jobContext = currentCoroutineContext()
            var bytes = 0L
            val reader = DataReader { buffer, offset, length ->
                jobContext.ensureActive()
                checkpoint()
                if (SystemClock.elapsedRealtime() >= deadline || bytes >= maxBytes)
                    throw PreparationFailure(LosslessDiagnostics.Reason.PREBUFFER_FAILED, "Optional preparation budget reached")
                source.read(buffer, offset, minOf(length.toLong(), maxBytes - bytes).toInt())
                    .also { if (it > 0) bytes += it }
            }
            var opened = false
            var fullLength = C.LENGTH_UNSET.toLong()
            var rangeSpec: DataSpec? = null
            var rangeStartBytes = 0L
            val readRanges = mutableListOf<Pair<DataSpec, Long>>()
            fun closeRange() {
                source.close()
                opened = false
                val spec = rangeSpec
                val length = bytes - rangeStartBytes
                if (fastStart && spec != null && length > 0L) readRanges.add(spec to length)
            }
            fun openAt(position: Long): DefaultExtractorInput {
                jobContext.ensureActive()
                checkpoint()
                // This is optional preparation, not the audible stream. Never
                // open another Archive range after a capped-network handover.
                if (!allowed())
                    throw PreparationFailure(LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED, "Active profile changed")
                if (opened) closeRange()
                val spec = DataSpec.Builder().setUri(uri).setPosition(position).build()
                rangeSpec = spec
                rangeStartBytes = bytes
                opened = true
                val remaining = source.open(spec)
                if (remaining >= 0L) fullLength = position + remaining
                return DefaultExtractorInput(reader, position, fullLength)
            }
            val extractors = if (requireLossless) listOf(FlacExtractor(), WavExtractor(), Mp4Extractor())
                else listOf(Mp4Extractor(), Mp3Extractor(), OggExtractor(), MatroskaExtractor(), FlacExtractor(), WavExtractor())
            try {
                var input = openAt(0L)
                val extractor = extractors.firstOrNull { candidate ->
                    try { candidate.sniff(input) } finally { input.resetPeekPosition() }
                } ?: throw PreparationFailure(LosslessDiagnostics.Reason.INVALID_FORMAT, "No supported audio container was detected")
                var format: Format? = null
                var seekMap: SeekMap? = null
                var firstSampleUs = Long.MAX_VALUE
                var lastSampleUs = Long.MIN_VALUE
                val discard = DummyTrackOutput()
                val output = object : TrackOutput {
                    override fun format(value: Format) { format = value }
                    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, part: Int): Int =
                        discard.sampleData(input, length, allowEndOfInput, part)
                    override fun sampleData(data: ParsableByteArray, length: Int, part: Int) =
                        discard.sampleData(data, length, part)
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
                        if (crypto != null) throw IOException("Encrypted lossless audio is unsupported")
                        firstSampleUs = minOf(firstSampleUs, timeUs)
                        lastSampleUs = maxOf(lastSampleUs, timeUs)
                    }
                }
                extractor.init(object : ExtractorOutput {
                    override fun track(id: Int, type: Int): TrackOutput {
                        if (type == C.TRACK_TYPE_VIDEO) throw IOException("Lossless candidate is not audio-only")
                        return if (type == C.TRACK_TYPE_AUDIO) output else discard
                    }
                    override fun endTracks() = Unit
                    override fun seekMap(value: SeekMap) { seekMap = value }
                })
                val nextPosition = PositionHolder()
                fun step(): Boolean {
                    jobContext.ensureActive()
                    checkpoint()
                    if (SystemClock.elapsedRealtime() >= deadline) throw PreparationFailure(
                        LosslessDiagnostics.Reason.PREBUFFER_FAILED, "Lossless preparation budget reached")
                    return when (extractor.read(input, nextPosition)) {
                        Extractor.RESULT_END_OF_INPUT -> false
                        Extractor.RESULT_SEEK -> { input = openAt(nextPosition.position); true }
                        else -> true
                    }
                }
                while (format == null || seekMap == null) if (!step())
                    throw PreparationFailure(LosslessDiagnostics.Reason.METADATA_INCOMPLETE, "No complete audio format")
                val verifiedFormat = format ?: return@withLock null
                val info = preparationAudioInfo(verifiedFormat, requireLossless) ?: throw PreparationFailure(
                    if (verifiedFormat.sampleMimeType.isNullOrBlank() ||
                        (verifiedFormat.sampleMimeType in setOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW) &&
                            (verifiedFormat.sampleRate <= 0 || verifiedFormat.channelCount <= 0)))
                        LosslessDiagnostics.Reason.METADATA_INCOMPLETE else LosslessDiagnostics.Reason.DECODER_UNSUPPORTED,
                    "Container did not contain a complete supported audio format")
                if (verifiedFormat.sampleMimeType != MimeTypes.AUDIO_RAW &&
                    MediaCodecUtil.getDecoderInfos(verifiedFormat.sampleMimeType!!, false, false)
                        .none { it.isFormatSupported(verifiedFormat) }) throw PreparationFailure(
                            LosslessDiagnostics.Reason.DECODER_UNSUPPORTED, "No compatible decoder")
                val map = seekMap ?: return@withLock null
                val actualDuration = map.durationUs.takeIf { it > 0L }?.div(1000L)
                if (actualDuration != null && expectedDurationMs > 0L &&
                    abs(actualDuration - expectedDurationMs) > durationToleranceMs) throw PreparationFailure(
                        LosslessDiagnostics.Reason.RECORDING_MISMATCH, "Actual duration conflicts with the recording")
                val initialPosition = withContext(Dispatchers.Main.immediate) {
                    if (stillCurrent()) currentPosition().coerceAtLeast(0L) else null
                } ?: return@withLock null
                // Unknown STREAMINFO duration does not prevent real FLAC playback
                // from the beginning. It does prevent an unproven mid-song switch.
                if (actualDuration == null && !allowUnknownDuration && initialPosition > 0L) throw PreparationFailure(
                    LosslessDiagnostics.Reason.SEEK_UNAVAILABLE, "Container duration cannot verify a position-preserving replacement")
                val bitrate = info.bitrateKbps.takeIf { it > 0 } ?: if (fullLength > 0L)
                    (fullLength * 8 / (actualDuration ?: expectedDurationMs).coerceAtLeast(1L))
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0

                // Seek using the real container index, never a bitrate-to-offset estimate.
                repeat(2) {
                    val position = withContext(Dispatchers.Main.immediate) {
                        if (stillCurrent()) currentPosition().coerceAtLeast(0L) else null
                    } ?: return@withLock null
                    val required = if (fastStart) FAST_START_BUFFER_MS else 750L
                    val duration = actualDuration ?: expectedDurationMs
                    if (duration > 0L && duration - position < maxOf(2_000L, required))
                        throw PreparationFailure(LosslessDiagnostics.Reason.BUFFER_COVERAGE, "Insufficient remaining audio for a safe replacement")
                    if (map.isSeekable) {
                        val point = map.getSeekPoints(position * 1000).first
                        input = openAt(point.position)
                        extractor.seek(point.position, point.timeUs)
                        firstSampleUs = Long.MAX_VALUE; lastSampleUs = Long.MIN_VALUE
                    }
                    // No fabricated byte offsets. For an unindexed file, continue
                    // reading real samples from the start within the same byte/time cap.
                    val requestedEnd = position + if (fastStart) FAST_START_READ_AHEAD_MS else 3_000L
                    while (lastSampleUs < requestedEnd * 1000) if (!step()) {
                        if (fastStart && lastSampleUs >= (position + required) * 1000) break
                        throw PreparationFailure(LosslessDiagnostics.Reason.PREBUFFER_FAILED, "Stream ended before the target position")
                    }
                    if (!map.isSeekable && position > 0L) {
                        if (actualDuration == null) throw PreparationFailure(LosslessDiagnostics.Reason.SEEK_UNAVAILABLE,
                            "No safe seek index or complete duration")
                        // FLAC with neither a seek table nor HTTP Content-Length can
                        // become seekable after a bounded complete disk-cache fill.
                        // CacheDataSource records the real length at EOF, allowing
                        // Media3's native binary seeker on activation. Never start
                        // an unseekable replacement audibly at the beginning.
                        while (step()) Unit
                        closeRange()
                        if (!MusicCache.fullyCachedResource(context, uri)) throw PreparationFailure(
                            LosslessDiagnostics.Reason.SEEK_UNAVAILABLE, "Cannot guarantee position-preserving activation")
                    }
                    val warmed = Prepared(info.copy(bitrateKbps = bitrate), firstSampleUs / 1000, lastSampleUs / 1000)
                    val latest = withContext(Dispatchers.Main.immediate) {
                        if (stillCurrent()) currentPosition().coerceAtLeast(0L) else null
                    } ?: return@withLock null
                    val ready = if (fastStart) FastStartBuffer(warmed.fromMs, warmed.untilMs).covers(latest)
                        else warmed.covers(latest)
                    if (ready) {
                        if (fastStart && opened) closeRange()
                        if (fastStart && !MusicCache.isLocal(uri) && readRanges.any { (spec, length) ->
                                !MusicCache.isRangeCached(context, spec, length)
                            }) throw PreparationFailure(LosslessDiagnostics.Reason.BUFFER_COVERAGE,
                            "The prepared audio ranges did not reach the playback cache")
                        return@withLock warmed
                    }
                }
                LosslessDiagnostics.record(item.mediaId, LosslessDiagnostics.Reason.BUFFER_COVERAGE, "prebuffer")
                null
            } catch (e: CancellationException) { throw e }
            catch (e: PreparationDeferred) { throw e }
            catch (e: Exception) {
                val reason = when (e) {
                    is PreparationFailure -> e.reason
                    is SocketTimeoutException -> LosslessDiagnostics.Reason.NETWORK_TIMEOUT
                    else -> LosslessDiagnostics.Reason.PREBUFFER_FAILED
                }
                LosslessDiagnostics.record(item.mediaId, reason, "prebuffer", "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                null
            }
            finally {
                extractors.forEach { it.release() }
                if (opened) source.close()
            }
        }
    }
}
