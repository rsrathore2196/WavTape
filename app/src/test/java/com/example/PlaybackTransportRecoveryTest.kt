package com.example

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MusicTrack
import com.example.playback.PlaybackManager
import com.example.playback.PlaybackRecoveryGuard
import com.example.playback.RepeatMode
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class PlaybackTransportRecoveryTest {
    @Test fun shuffleAndRepeatUseTheMediaSessionsActualModes() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val native = ExoPlayer.Builder(app).build()
        native.shuffleModeEnabled = true
        native.repeatMode = Player.REPEAT_MODE_ONE
        val session = MediaSession.Builder(app, native).build()
        val future = MediaController.Builder(app, session.token).buildAsync()
        Dispatchers.setMain(StandardTestDispatcher())
        val manager = PlaybackManager(app)
        suspend fun await(condition: () -> Boolean) = withTimeout(4_000L) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(5L)
            }
        }
        try {
            await { future.isDone }
            val controller = future.get()
            PlaybackManager::class.java.getDeclaredMethod("setController", MediaController::class.java)
                .apply { isAccessible = true }.invoke(manager, controller)
            @Suppress("UNCHECKED_CAST")
            val ready = PlaybackManager::class.java.getDeclaredField("controllerReady")
                .apply { isAccessible = true }.get(manager) as CompletableDeferred<MediaController>
            ready.complete(controller)
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                PlaybackManager::class.java.getDeclaredMethod("setupPlayerListener", Continuation::class.java)
                    .apply { isAccessible = true }.invoke(manager, continuation)
            }
            assertTrue(manager.uiState.value.isShuffle)
            assertEquals(RepeatMode.ONE, manager.uiState.value.repeatMode)

            manager.toggleShuffle()
            manager.toggleRepeat()
            await { !native.shuffleModeEnabled && native.repeatMode == Player.REPEAT_MODE_OFF }
            assertFalse(manager.uiState.value.isShuffle)
            assertEquals(RepeatMode.OFF, manager.uiState.value.repeatMode)
            manager.toggleRepeat()
            await { native.repeatMode == Player.REPEAT_MODE_ALL }
            assertEquals(RepeatMode.ALL, manager.uiState.value.repeatMode)

            native.shuffleModeEnabled = true
            native.repeatMode = Player.REPEAT_MODE_ONE
            await { manager.uiState.value.isShuffle && manager.uiState.value.repeatMode == RepeatMode.ONE }
        } finally {
            manager.release()
            MediaController.releaseFuture(future)
            session.release()
            native.release()
            Dispatchers.resetMain()
        }
    }

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
