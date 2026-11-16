package com.example

import android.app.Application
import android.content.Context
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MusicTrack
import com.example.downloads.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OfflineDownloadIntegrityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val store by lazy { OfflineDownloads(context) }
    private val ids = mutableSetOf<String>()
    private fun track(id: String) = MusicTrack(id, "Song $id", "Artist", "Album", 120_000, "", "https://example.test/$id")

    @After fun close() { ids.forEach(store::releaseCache) }

    private suspend fun record(id: String, progressive: Boolean = true, bytes: Long = 128L): MusicTrack {
        ids += id
        val track = track(id)
        store.put(DownloadRecord(track, DownloadStatus.COMPLETE, bytes = bytes, total = bytes,
            plan = OfflinePlan(track.audioUrl, if (progressive) "audio/mpeg" else "application/x-mpegURL", "test", progressive)))
        return track
    }

    private fun span(id: String, key: String = "audio", offset: Long = 0L, size: Int = 128, length: Long = 128L): File {
        val cache = store.cache(id)
        val hole = cache.startReadWrite(key, offset, size.toLong())
        try {
            val file = cache.startFile(key, offset, size.toLong())
            file.writeBytes(ByteArray(size) { 42 })
            cache.commitFile(file, size.toLong())
            cache.applyContentMetadataMutations(key, ContentMetadataMutations().set("exo_len", length))
            return cache.getCachedSpans(key).first { it.position == offset }.file!!
        } finally { cache.releaseHoleSpan(hole) }
    }

    @Test fun metadataWithoutAudioDoesNotCountAsAPlayableDownload() = runBlocking(Dispatchers.IO) {
        val track = record("metadata-only")
        store.cache(track.id).applyContentMetadataMutations("audio", ContentMetadataMutations().set("exo_len", 128L))
        assertNull(store.localTrack(track))
        assertEquals(DownloadStatus.FAILED, store.records.value[track.id]?.status)
    }

    @Test fun partialAudioWithACompleteLedgerIsRejected() = runBlocking(Dispatchers.IO) {
        val track = record("partial")
        span(track.id, size = 64)
        assertNull(store.localTrack(track))
        assertEquals(DownloadStatus.FAILED, store.records.value[track.id]?.status)
    }

    @Test fun truncatedSpanIsRejectedEvenWhileTheCacheIndexIsStillOpen() = runBlocking(Dispatchers.IO) {
        val track = record("truncated")
        val file = span(track.id)
        assertTrue(store.retainCache(track.id))
        try {
            RandomAccessFile(file, "rw").use { it.setLength(32L) }
            assertNull(store.localTrack(track))
        } finally { store.releaseOwner(track.id) }
    }

    @Test fun aMissingAdaptiveSegmentDoesNotLeaveTheCollectionPlayable() = runBlocking(Dispatchers.IO) {
        val track = record("adaptive-missing", progressive = false, bytes = 384L)
        span(track.id, "manifest"); span(track.id, "segment-one")
        val missing = span(track.id, "segment-two")
        assertTrue(store.retainCache(track.id))
        try {
            assertTrue(missing.delete())
            assertNull(store.localTrack(track))
        } finally { store.releaseOwner(track.id) }
    }

    @Test fun completeProgressiveAudioAndAdaptiveByteRangesStayPlayable() = runBlocking(Dispatchers.IO) {
        val progressive = record("complete")
        span(progressive.id)
        assertTrue(store.localTrack(progressive)!!.audioUrl.startsWith(OfflineDownloads.SCHEME))
        val adaptive = record("byte-ranges", progressive = false, bytes = 256L)
        span(adaptive.id, "manifest")
        // HLS/DASH may intentionally download only a range within a larger resource.
        span(adaptive.id, "segment", offset = 256L, length = 1024L)
        assertTrue(store.localTrack(adaptive)!!.audioUrl.startsWith(OfflineDownloads.SCHEME))
    }

    @Test fun aCompletedDownloadWithUnknownHttpLengthRemainsPlayable() = runBlocking(Dispatchers.IO) {
        val track = record("unknown-http-length")
        val plan = store.records.value.getValue(track.id).plan!!
        val bytes = OfflineTransfer.save(store, track.id, plan, androidx.media3.datasource.DataSource.Factory {
            val source = androidx.media3.datasource.ByteArrayDataSource(ByteArray(128) { 42 })
            object : androidx.media3.datasource.DataSource by source {
                override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                    source.open(spec)
                    return androidx.media3.common.C.LENGTH_UNSET.toLong()
                }
            }
        })
        assertEquals(128L, bytes)
        assertNotNull(store.localTrack(track))
    }
}
