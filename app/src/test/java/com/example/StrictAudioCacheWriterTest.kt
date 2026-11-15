package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import com.example.service.StrictAudioCacheWriter
import com.example.service.StreamedAudioPrefetch
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StrictAudioCacheWriterTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val key = "stream:verified-recording:opus:251:1024"
    private val spec get() = DataSpec.Builder().setUri("https://audio.example/track.opus").setKey(key).build()

    private fun cache(): SimpleCache = SimpleCache(java.io.File(context.noBackupFilesDir,
        "strict-writer-${java.util.UUID.randomUUID()}"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))

    private class Upstream(val length: Long, val responseMime: String = "audio/ogg", val rangeSupport: Boolean = true, val afterRead: () -> Unit = {}) : DataSource.Factory {
        val opens = AtomicInteger()
        val creations = AtomicInteger()
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        override fun createDataSource(): DataSource {
            creations.incrementAndGet()
            return object : DataSource {
                var position = 0L
                var openedAt = 0L
                var requestedLength = 0L
                override fun addTransferListener(listener: TransferListener) {}
                override fun open(dataSpec: DataSpec): Long { opens.incrementAndGet(); position = dataSpec.position; openedAt = position; requestedLength = minOf(dataSpec.length, length - position); return length - position }
                override fun read(buffer: ByteArray, offset: Int, amount: Int): Int {
                    if (position >= length) return C.RESULT_END_OF_INPUT
                    val count = minOf(amount.toLong(), length - position).toInt()
                    buffer.fill(42, offset, offset + count)
                    position += count
                    reads.incrementAndGet(); afterRead()
                    return count
                }
                override fun getResponseHeaders(): Map<String, List<String>> = buildMap {
                    put("Content-Type", listOf(responseMime))
                    put("Content-Length", listOf((if (rangeSupport) requestedLength else length).toString()))
                    if (rangeSupport) put("Content-Range", listOf("bytes $openedAt-${openedAt + requestedLength - 1}/$length"))
                }
                override fun getUri(): Uri = Uri.parse("https://audio.example/track.opus")
                override fun close() { closes.incrementAndGet() }
            }
        }
    }

    @Test fun segmentedAndNonHttpSourcesNeverStartAnOptionalReader() = runBlocking(Dispatchers.IO) {
        val cache = cache()
        val upstream = Upstream(1024)
        try {
            for (uri in listOf("https://audio.example/master.m3u8", "https://audio.example/audio.mpd",
                "xtreme-sabr://audio/recording", "file:///music/song.flac", "content://music/song", "smb://server/music")) {
                StrictAudioCacheWriter.fetch(cache, upstream, spec.buildUpon().setUri(uri).build(), 1024, 1024) { true }
            }
            assertEquals(0, upstream.creations.get())
            assertEquals(0, upstream.opens.get())
        } finally { cache.release() }
    }

    @Test fun aManifestResponseAtAnUnmarkedUrlCannotBeCommittedAsAudio() = runBlocking(Dispatchers.IO) {
        val cache = cache()
        val upstream = Upstream(1024, responseMime = "application/dash+xml")
        try {
            assertThrows(IOException::class.java) {
                runBlocking { StrictAudioCacheWriter.fetch(cache, upstream, spec, 1024, 1024) { true } }
            }
            assertEquals(1, upstream.opens.get())
            assertEquals(0, upstream.reads.get())
            assertEquals(0L, cache.cacheSpace)
        } finally { cache.release() }
    }

    @Test fun aPlaybackWriterLockPreventsAnyUpstreamCreationOrNetworkOpen() = runBlocking(Dispatchers.IO) {
        val cache = cache()
        val upstream = Upstream(1024)
        val playback = cache.startReadWrite(key, 0, 1024)
        try {
            StrictAudioCacheWriter.fetch(cache, upstream, spec, 1024, 1024) { true }
            assertEquals(0, upstream.creations.get())
            assertEquals(0, upstream.opens.get())
            assertEquals(0L, cache.cacheSpace)
        } finally { cache.releaseHoleSpan(playback); cache.release() }
    }

    @Test fun cacheAllocationFailureCannotDownloadUncachedBytes() = runBlocking(Dispatchers.IO) {
        val owner = cache()
        val upstream = Upstream(1024)
        val cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, method, args ->
            if (method.name == "startFile") throw Cache.CacheException("Disk is full")
            try { method.invoke(owner, *(args ?: emptyArray())) }
            catch (error: InvocationTargetException) { throw error.cause!! }
        } as Cache
        try {
            assertThrows(IOException::class.java) {
                runBlocking { StrictAudioCacheWriter.fetch(cache, upstream, spec, 1024, 1024) { true } }
            }
            assertEquals(0, upstream.creations.get())
            assertEquals(0, upstream.opens.get())
            val available = owner.startReadWriteNonBlocking(key, 0, 1024)!!
            owner.releaseHoleSpan(available)
        } finally { owner.release() }
    }

    @Test fun revokingAdmissionDuringAReadDeletesUncommittedBytesAndReleasesTheHole() = runBlocking(Dispatchers.IO) {
        val cache = cache()
        var allowed = true
        val upstream = Upstream(1024 * 1024) { allowed = false }
        try {
            StrictAudioCacheWriter.fetch(cache, upstream, spec, 1024 * 1024, 1024 * 1024) { allowed }
            assertEquals(1, upstream.opens.get())
            assertEquals(1, upstream.reads.get())
            assertEquals(1, upstream.closes.get())
            assertEquals(0L, cache.cacheSpace)
            val available = cache.startReadWriteNonBlocking(key, 0, 1024)!!
            cache.releaseHoleSpan(available)
        } finally { cache.release() }
    }

    @Test fun coroutineCancellationClosesAndDeletesAnIncompleteChunk() = runBlocking(Dispatchers.IO) {
        val cache = cache()
        val readStarted = CompletableDeferred<Unit>()
        val upstream = Upstream(1024 * 1024) {
            readStarted.complete(Unit)
            Thread.sleep(30_000) // runInterruptible must stop this simulated blocking upstream.
        }
        try {
            val request = async { StrictAudioCacheWriter.fetch(cache, upstream, spec, 1024 * 1024, 1024 * 1024) { true } }
            withTimeout(5_000) { readStarted.await() }
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertEquals(0L, cache.cacheSpace)
            assertEquals(1, upstream.closes.get())
            val available = cache.startReadWriteNonBlocking(key, 0, 1024)!!
            cache.releaseHoleSpan(available)
        } finally { cache.release() }
    }

    @Test fun playbackPreemptsAnOptionalWriterWithoutInvalidatingOtherCachedRecordings() = runBlocking(Dispatchers.IO) {
        val cache = cache()
        val readStarted = CompletableDeferred<Unit>()
        val blocked = Upstream(1024 * 1024) {
            readStarted.complete(Unit)
            Thread.sleep(30_000)
        }
        val other = spec.buildUpon().setKey("different-recording").build()
        try {
            StrictAudioCacheWriter.fetch(cache, Upstream(1024), other, 1024, 1024) { true }
            val request = async { StrictAudioCacheWriter.fetch(cache, blocked, spec, 1024 * 1024, 1024 * 1024) { true } }
            withTimeout(5_000) { readStarted.await() }
            StrictAudioCacheWriter.preempt(key)
            request.join()
            assertTrue(request.isCancelled)
            assertEquals(1, blocked.closes.get())
            assertEquals(1, blocked.reads.get())
            assertFalse(cache.isCached(key, 0, 1024))
            assertTrue(cache.isCached("different-recording", 0, 1024))
            val playback = cache.startReadWriteNonBlocking(key, 0, 1024)!!
            cache.releaseHoleSpan(playback)
        } finally { StrictAudioCacheWriter.preemptAll(); cache.release() }
    }

    @Test fun committedSpansAreReusedAndTheRemainingBytesUseBoundedChunks() = runBlocking(Dispatchers.IO) {
        val cache = cache()
        val size = StreamedAudioPrefetch.OPENING_BYTES + StreamedAudioPrefetch.CHUNK_BYTES + 1024
        val upstream = Upstream(size)
        try {
            StrictAudioCacheWriter.fetch(cache, upstream, spec, size, StreamedAudioPrefetch.OPENING_BYTES) { true }
            assertEquals(1, upstream.opens.get())
            assertEquals(StreamedAudioPrefetch.OPENING_BYTES, cache.cacheSpace)
            StrictAudioCacheWriter.fetch(cache, upstream, spec, size, size) { true }
            assertEquals(3, upstream.opens.get())
            assertTrue(cache.isCached(key, 0, size))
            assertEquals(size, cache.cacheSpace)
            StrictAudioCacheWriter.fetch(cache, upstream, spec, size, size) { true }
            assertEquals(3, upstream.opens.get())
        } finally { cache.release() }
    }

    @Test fun unknownLengthUsesTheVerifiedResponseLengthAndCoverageStillDeterminesCompleteness() = runBlocking(Dispatchers.IO) {
        val cache = cache()
        val size = 2 * StreamedAudioPrefetch.OPENING_BYTES
        val upstream = Upstream(size)
        try {
            StrictAudioCacheWriter.fetch(cache, upstream, spec, null, StreamedAudioPrefetch.OPENING_BYTES) { true }
            assertEquals(size, androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(key)))
            assertFalse(cache.isCached(key, 0, size))
            assertEquals(1L, cache.getContentMetadata(key).get(StrictAudioCacheWriter.RANGE_SUPPORTED, 0L))
            StrictAudioCacheWriter.fetch(cache, upstream, spec, size, size) { true }
            assertTrue(cache.isCached(key, 0, size))
        } finally { cache.release() }
    }

    @Test fun aSourceWithoutRangeEvidenceGetsOnlyTheOpeningChunk() = runBlocking(Dispatchers.IO) {
        val cache = cache()
        val size = 4 * StreamedAudioPrefetch.OPENING_BYTES
        val upstream = Upstream(size, rangeSupport = false)
        try {
            StrictAudioCacheWriter.fetch(cache, upstream, spec, size, size) { true }
            assertEquals(1, upstream.opens.get())
            assertEquals(StreamedAudioPrefetch.OPENING_BYTES, cache.cacheSpace)
            assertFalse(cache.isCached(key, 0, size))
            StrictAudioCacheWriter.fetch(cache, upstream, spec, size, size) { true }
            assertEquals(1, upstream.opens.get())
        } finally { cache.release() }
    }

    @Test fun shortReadCannotBecomeACompleteOfflineRepresentation() = runBlocking(Dispatchers.IO) {
        val cache = cache()
        val upstream = Upstream(512)
        try {
            assertThrows(IOException::class.java) {
                runBlocking { StrictAudioCacheWriter.fetch(cache, upstream, spec, 1024, 1024) { true } }
            }
            assertEquals(0L, cache.cacheSpace)
            assertFalse(cache.isCached(key, 0, 1024))
            assertEquals(1, upstream.closes.get())
        } finally { cache.release() }
    }
}
