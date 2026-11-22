package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MusicTrack
import com.example.playback.ArtistRadio
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
    @Test fun aNewExplicitQueueIsNotDebouncedWhenItStartsOnTheSameRecording() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = PlaybackManager(ApplicationProvider.getApplicationContext<Application>())
        val selected = MusicTrack("first", "First", "Artist", "Album", 180_000, "", "https://audio.example/first.m4a")
        val second = selected.copy(id = "second", title = "Second")
        val third = selected.copy(id = "third", title = "Third")
        try {
            manager.playTrack(selected, listOf(selected, second, third))
            val discovery = manager.currentSelectionRevision
            assertTrue(discovery > 0)

            val shuffled = listOf(selected, third, second)
            manager.playTrack(selected, shuffled, isExplicitPlaylist = true)
            val firstShuffle = manager.currentSelectionRevision
            assertTrue("A shuffle starting with the current song must still select the shelf queue", firstShuffle > discovery)

            manager.playTrack(selected, shuffled.toList(), isExplicitPlaylist = true)
            assertEquals("An identical request should still be debounced", firstShuffle, manager.currentSelectionRevision)

            manager.playTrack(selected, listOf(selected, second, third), isExplicitPlaylist = true)
            assertTrue("A new shuffle order supersedes the first one", manager.currentSelectionRevision > firstShuffle)
        } finally {
            manager.release()
            Dispatchers.resetMain()
        }
    }

    @Test fun distinctPlaylistOccurrencesOfTheSameRecordingRespondToRapidSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = PlaybackManager(ApplicationProvider.getApplicationContext<Application>())
        val first = MusicTrack("same", "Same", "Artist", "Album", 180_000, "", "https://audio.example/same.m4a",
            playlistEntryId = 1)
        val second = first.copy(playlistEntryId = 2)
        val entries = listOf(first, second)
        try {
            manager.playTrack(first, entries, isExplicitPlaylist = true)
            manager.playTrack(second, entries, isExplicitPlaylist = true)
            assertEquals(second, manager.uiState.value.currentTrack)
        } finally {
            manager.release()
            Dispatchers.resetMain()
        }
    }

    @Test fun queueSelectionAndRetryKeepTheArtistShuffleMode() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = PlaybackManager(ApplicationProvider.getApplicationContext<Application>())
        val first = MusicTrack("first", "First", "Artist", "Album", 180_000, "", "https://audio.example/first.m4a")
        val second = first.copy(id = "second", title = "Second")
        val station = PlaybackManager::class.java.getDeclaredField("artistRadio").apply { isAccessible = true }
        try {
            manager.playArtistRadio(first, "Artist", listOf(first, second), shuffle = true)
            val expected = ArtistRadio("Artist", shuffle = true)
            assertEquals(expected, station.get(manager))
            manager.playQueuedTrack(second)
            assertEquals(expected, station.get(manager))
            manager.retryPlayback()
            assertEquals(expected, station.get(manager))
        } finally {
            manager.release()
            Dispatchers.resetMain()
        }
    }

    @Test fun regularQueueTransitionKeepsItsRefillGeneration() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = PlaybackManager(ApplicationProvider.getApplicationContext<Application>())
        val beforeRefill = PlaybackManager::class.java.getDeclaredField("queueGeneration").apply { isAccessible = true }
        val updateStation = PlaybackManager::class.java.getDeclaredMethod("updateArtistRadio", ArtistRadio::class.java)
            .apply { isAccessible = true }
        try {
            val initial = beforeRefill.getLong(manager)
            updateStation.invoke(manager, null)
            assertEquals(initial, beforeRefill.getLong(manager))
            updateStation.invoke(manager, ArtistRadio("Artist", shuffle = true))
            val artist = beforeRefill.getLong(manager)
            assertTrue(artist > initial)
            updateStation.invoke(manager, ArtistRadio("Artist", shuffle = true))
            assertEquals(artist, beforeRefill.getLong(manager))
        } finally {
            manager.release()
            Dispatchers.resetMain()
        }
    }

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
