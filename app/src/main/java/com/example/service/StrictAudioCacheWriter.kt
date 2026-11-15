package com.example.service

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.FileOutputStream
import java.io.IOException

/** Optional writes acquire a cache hole before opening upstream. No cache-bypass fallback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object StrictAudioCacheWriter {
    internal const val RANGE_SUPPORTED = "xtreme_range_supported"
    private val contentRange = Regex("(?i)bytes\\s+([0-9]+)-([0-9]+)/([0-9]+|\\*)")
    private class Writer(val job: Job) {
        val source = AtomicReference<DataSource?>()
        fun closeSource() { runCatching { source.getAndSet(null)?.close() } }
        fun preempt() { job.cancel(CancellationException("Playback has priority over audio prefetch")); closeSource() }
    }
    private val writers = mutableMapOf<String, MutableSet<Writer>>()

    fun preempt(key: String) {
        val selected = synchronized(writers) { writers[key]?.toList().orEmpty() }
        selected.forEach(Writer::preempt)
    }
    fun preemptAll() {
        val selected = synchronized(writers) { writers.values.flatMap { it.toList() } }
        selected.forEach(Writer::preempt)
    }

    @OptIn(InternalCoroutinesApi::class)
    suspend fun fetch(cache: Cache, upstream: DataSource.Factory, spec: DataSpec,
        totalLength: Long?, budget: Long, allowed: () -> Boolean): Unit = coroutineScope {
        // Serialized transports belong to their Media3 demux/session owner.
        if (spec.uri.scheme !in setOf("http", "https") ||
            spec.uri.path.orEmpty().endsWith(".m3u8", true) || spec.uri.path.orEmpty().endsWith(".mpd", true)) return@coroutineScope
        val coroutine = currentCoroutineContext()
        fun admitted(): Boolean { coroutine.ensureActive(); return allowed() }
        val key = requireNotNull(spec.key) { "Prefetch requires a representation key" }
        val writer = Writer(coroutine.job)
        synchronized(writers) { writers.getOrPut(key) { mutableSetOf() }.add(writer) }
        val cancellation = coroutine.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) writer.closeSource()
        }
        try {
            var position = spec.position
            var verifiedTotal = totalLength
            var ranges = cache.getContentMetadata(key).get(RANGE_SUPPORTED, 0L) == 1L
            val end = Math.addExact(position, budget.coerceAtLeast(0L))
            while (position < minOf(end, verifiedTotal ?: Long.MAX_VALUE) && admitted()) {
                if (position > spec.position && !ranges) return@coroutineScope
                val chunk = minOf(if (position == 0L) StreamedAudioPrefetch.OPENING_BYTES else StreamedAudioPrefetch.CHUNK_BYTES,
                    minOf(end, verifiedTotal ?: Long.MAX_VALUE) - position)
                val cached = cache.getCachedLength(key, position, chunk)
                if (cached > 0L) { position += cached; continue }
                if (cached == 0L) return@coroutineScope
                val holeLength = minOf(-cached, chunk)
                val hole = cache.startReadWriteNonBlocking(key, position, holeLength) ?: return@coroutineScope
                if (hole.isCached) { position += minOf(hole.length, chunk); continue }
                var file: java.io.File? = null
                var committedLength = holeLength
                try {
                    if (!admitted()) return@coroutineScope
                    // Cache allocation failures must stop before even constructing an HTTP reader.
                    file = cache.startFile(key, position, holeLength)
                    val source = upstream.createDataSource()
                    writer.source.set(source)
                    coroutine.ensureActive()
                    val request = spec.buildUpon().setPosition(position).setLength(holeLength).build()
                    runInterruptible(Dispatchers.IO) {
                        source.open(request)
                        fun header(name: String) = source.responseHeaders.entries.firstOrNull { it.key.equals(name, true) }
                            ?.value?.firstOrNull().orEmpty()
                        val responseType = header("content-type")
                        if (responseType.contains("mpegurl", true) || responseType.contains("dash+xml", true))
                            throw IOException("Segmented audio is not eligible for progressive prefetch")
                        val rangeHeader = header("content-range")
                        val range = contentRange.matchEntire(rangeHeader.trim())
                        if (rangeHeader.isNotBlank() && range == null) throw IOException("Invalid audio prefetch range")
                        val advertisedLength = header("content-length").toLongOrNull()?.takeIf { it > 0L }
                        val resourceLength = range?.groupValues?.get(3)?.toLongOrNull()
                            ?: advertisedLength?.takeIf { position == 0L && range == null }
                        if (resourceLength != null) {
                            if (verifiedTotal != null && verifiedTotal != resourceLength)
                                throw IOException("Audio representation length changed")
                            verifiedTotal = resourceLength
                        }
                        committedLength = minOf(holeLength, verifiedTotal?.minus(position) ?: holeLength)
                        if (committedLength <= 0L) throw IOException("Audio prefetch range exceeds the recording")
                        if (range != null) {
                            val first = range.groupValues[1].toLong()
                            val last = range.groupValues[2].toLong()
                            if (first != position || last < first || last - first + 1L < committedLength ||
                                advertisedLength != null && advertisedLength != last - first + 1L)
                                throw IOException("Audio prefetch received a different range")
                        } else if (position > 0L) throw IOException("Audio source did not honor range requests")
                        ranges = ranges || range != null || header("accept-ranges").equals("bytes", true)
                        var written = 0L
                        FileOutputStream(file).use { output ->
                            val bytes = ByteArray(64 * 1024)
                            while (written < committedLength && admitted()) {
                                val read = source.read(bytes, 0, minOf(bytes.size.toLong(), committedLength - written).toInt())
                                if (read == C.RESULT_END_OF_INPUT) break
                                if (read <= 0) throw IOException("Audio prefetch made no progress")
                                if (!admitted()) return@runInterruptible
                                output.write(bytes, 0, read)
                                written += read
                            }
                        }
                        if (!admitted()) return@runInterruptible
                        if (written != committedLength) throw IOException("Incomplete audio prefetch chunk")
                        cache.commitFile(checkNotNull(file), written)
                        file = null
                        if (!cache.isCached(key, position, written)) throw IOException("Audio cache rejected prefetch bytes")
                        val metadata = ContentMetadataMutations()
                        verifiedTotal?.let { metadata.set("exo_len", it) }
                        if (ranges) metadata.set(RANGE_SUPPORTED, 1L)
                        cache.applyContentMetadataMutations(key, metadata)
                    }
                } finally {
                    writer.closeSource()
                    file?.delete()
                    cache.releaseHoleSpan(hole)
                }
                position += committedLength
            }
        } finally {
            cancellation.dispose()
            writer.closeSource()
            synchronized(writers) {
                writers[key]?.let { owners ->
                    owners.remove(writer)
                    if (owners.isEmpty()) writers.remove(key)
                }
            }
        }
    }
}
