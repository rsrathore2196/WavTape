package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MusicTrack
import com.example.playback.PlaybackManager
import com.example.playback.PlaybackRecoveryGuard
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
class PlaybackTransportRecoveryTest {
    @Test fun manualTransportKeepsSameItemRecoveryAvailableAndInvalidatesTheOldAttempt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = PlaybackManager(ApplicationProvider.getApplicationContext<Application>())
        try {
            val track = MusicTrack("current", "Current song", "Artist", "Album", 180_000,
                "", "https://audio.example/current.m4a")
            PlaybackManager::class.java.getDeclaredMethod("beginSelection", MusicTrack::class.java)
                .apply { isAccessible = true }.invoke(manager, track)
            val guard = PlaybackManager::class.java.getDeclaredField("recoveryGuard")
                .apply { isAccessible = true }.get(manager) as PlaybackRecoveryGuard
            val oldAttempt = checkNotNull(guard.claim(track.id))
            checkNotNull(guard.claim(track.id))
            assertNull(guard.claim(track.id))
            val pendingRecovery = launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
            PlaybackManager::class.java.getDeclaredField("recoveryJob")
                .apply { isAccessible = true }.set(manager, pendingRecovery)
            val selection = manager.uiState.value

            // Previous can restart this same item, and Next can reach an unchanged queue end.
            // Neither path is guaranteed to emit onMediaItemTransition to restore ownership.
            manager.cancelRecoveryForTransport()

            assertTrue(pendingRecovery.isCancelled)
            assertFalse(guard.isCurrent(track.id, oldAttempt))
            assertNotNull("The retained current recording still needs bounded source recovery", guard.claim(track.id))
            assertNotNull(guard.claim(track.id))
            assertNull("Transport commands must not make retries unbounded", guard.claim(track.id))
            assertEquals("Resetting recovery must not replace the selected track or queue", selection, manager.uiState.value)
        } finally {
            manager.release()
            Dispatchers.resetMain()
        }
    }
}
