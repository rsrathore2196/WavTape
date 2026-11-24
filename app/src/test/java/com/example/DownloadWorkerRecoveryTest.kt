package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.data.model.MusicTrack
import com.example.downloads.*
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadWorkerRecoveryTest {
    @Test fun rejectedForegroundStartLeavesARetryableFailureInsteadOfAStuckQueuedDownload() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = OfflineDownloads::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = owner.get(null)
        val store = OfflineDownloads(context)
        owner.set(null, store)
        try {
            store.load()
            val track = track("queued")
            store.put(DownloadRecord(track))
            val worker = TestListenableWorkerBuilder.from(context, OfflineDownloadWorker::class.java)
                .setInputData(workDataOf("track_id" to track.id))
                .setForegroundUpdater { _, _, _ -> SettableFuture.create<Void>().apply {
                    setException(IllegalStateException("Foreground start denied"))
                } }.build()
            assertEquals(ListenableWorker.Result.failure(), worker.doWork())
            assertEquals(DownloadStatus.FAILED, store.records.value[track.id]?.status)
            assertNotNull(store.records.value[track.id]?.error)
            assertEquals(DownloadStatus.FAILED, OfflineDownloads(context).also { it.load() }.records.value[track.id]?.status)
        } finally {
            owner.set(null, previous)
        }
    }

    @Test @Config(sdk = [28])
    fun rejectedWorkEnqueueLeavesEveryTrackInTheBatchRetryable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(runCatching { WorkManager.getInstance(context) }.isFailure)
        val tracks = listOf(track("first"), track("second"))
        val store = OfflineDownloads(context)
        assertTrue(runCatching { store.enqueue(tracks) }.isFailure)
        val restored = OfflineDownloads(context).also { it.load() }
        assertEquals(listOf(DownloadStatus.FAILED, DownloadStatus.FAILED),
            tracks.map { restored.records.value[it.id]?.status })
    }

    private fun track(id: String) = MusicTrack(id, "Song", "Artist", "Album", 120_000, "", "https://example.test/$id")
}
