package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MusicTrack
import com.example.data.repository.MusicRepository
import com.example.downloads.DownloadIndex
import com.example.downloads.OfflineDownloads
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadLookupIsolationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val owner = OfflineDownloads::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val previous = owner.get(null)
    private val repository by lazy { MusicRepository(context) }
    private val track = MusicTrack("local-file", "Song", "Artist", "Album", 120_000, "", "file:///storage/song.flac")

    private fun index(read: () -> ByteArray?) {
        owner.set(null, OfflineDownloads(context, object : DownloadIndex {
            override fun read() = read.invoke()
            override fun write(bytes: ByteArray) = error("Playback must not replace a failed download index")
        }))
    }

    @After fun cleanup() {
        owner.set(null, previous)
        (MusicRepository::class.java.getDeclaredField("initialCatalogScope").apply { isAccessible = true }
            .get(repository) as CoroutineScope).cancel()
    }

    @Test fun unavailableDownloadStorageDoesNotBlockAnUnrelatedLocalRecording() = runBlocking {
        index { throw IOException("Download storage unavailable") }
        assertEquals(track.audioUrl, repository.resolvePlayableTrack(track).audioUrl)
    }

    @Test fun malformedDownloadMetadataDoesNotBlockNormalPlaybackResolution() = runBlocking {
        index { "{broken index".toByteArray() }
        assertEquals(track.audioUrl, repository.resolvePlayableTrack(track).audioUrl)
    }

    @Test fun cancellingDownloadLookupStillCancelsThePlaybackRequest() = runBlocking {
        val cancelled = CancellationException("Selection replaced")
        index { throw cancelled }
        val failure = runCatching { repository.resolvePlayableTrack(track) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(cancelled.message, failure?.message)
    }

    @Test fun explicitlyOfflineRequestsNeverTurnIntoStreamingRequests() = runBlocking {
        index { error("An explicit offline URI must stay with the offline media source") }
        val offline = track.copy(audioUrl = "${OfflineDownloads.SCHEME}://audio/saved")
        assertEquals(offline, repository.resolvePlayableTrack(offline))
    }
}
