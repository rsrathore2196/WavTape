package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MusicTrack
import com.example.playback.PlaybackManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class PlaybackSelectionReadinessTest {
    @Test fun selectingATrackReleasesAControllerAlreadyWaitingForPassiveRestoration() = runTest {
        // Leave the disk-restore job queued, as it would be while background CPU
        // admission is parked for a gesture or foreground playback resolution.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = PlaybackManager(ApplicationProvider.getApplicationContext<Application>())
        @Suppress("UNCHECKED_CAST")
        val restoration = PlaybackManager::class.java.getDeclaredField("sessionRestored")
            .apply { isAccessible = true }.get(manager) as CompletableDeferred<Unit>
        val connection = async(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            restoration.await()
        }
        try {
            assertFalse("A passive connection must still wait for the saved queue", connection.isCompleted)
            val requested = MusicTrack("chosen", "Chosen song", "Artist", "Album", 180_000,
                "", "https://audio.example/chosen.m4a")
            PlaybackManager::class.java.getDeclaredMethod("beginSelection", MusicTrack::class.java)
                .apply { isAccessible = true }.invoke(manager, requested)
            assertTrue("A tap supersedes restoration without waiting for its I/O or CPU admission", connection.isCompleted)
            assertEquals(requested, manager.uiState.value.currentTrack)
            assertTrue(manager.uiState.value.isLoading)
            assertTrue(manager.uiState.value.queue.isEmpty())
        } finally {
            connection.cancel()
            manager.release()
            Dispatchers.resetMain()
        }
    }
}
