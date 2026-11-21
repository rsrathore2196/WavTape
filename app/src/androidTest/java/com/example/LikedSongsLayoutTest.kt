package com.example

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.model.MusicTrack
import com.example.ui.screens.LibraryScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LikedSongsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun likedSongsUsesCollectionLayoutAndPreservesPlayAndShuffleQueues() {
        val favorites = (1..30).map { index ->
            MusicTrack("liked-layout-$index", "Favorite $index", "Artist", "Album", 180_000,
                "", "https://example.test/$index", isLiked = true)
        }
        val played = AtomicReference<Pair<MusicTrack, List<MusicTrack>>?>()
        compose.setContent { MyApplicationTheme {
            LibraryScreen(favorites, emptyList(), onTrackClick = { track, queue -> played.set(track to queue) },
                onToggleFavorite = {}, onOpenPlaylist = {}, onCreatePlaylistClick = {})
        } }
        compose.onNodeWithTag("open_liked_songs").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("playlist_profile_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist_profile_artwork").assertDoesNotExist()
        compose.onNodeWithTag("landscape_playlist_artwork").assertDoesNotExist()
        compose.onNodeWithTag("liked_songs_play").assertDoesNotExist()
        compose.onNodeWithTag("liked_songs_shuffle").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play all").performScrollTo().assertIsDisplayed()
        val title = compose.onNodeWithTag("playlist_profile_name").fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithContentDescription("Play all").fetchSemanticsNode().boundsInRoot
        val shuffle = compose.onNodeWithContentDescription("Shuffle").fetchSemanticsNode().boundsInRoot
        assertTrue("Actions follow the playlist details", play.top >= title.bottom)
        assertEquals("Play and shuffle share the same row", play.center.y, shuffle.center.y, 1f)
        assertTrue("Shuffle sits to the left of play", shuffle.right < play.left)

        compose.onRoot().captureToImage().asAndroidBitmap().let { image ->
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "liked-songs-layout.png")
                .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithContentDescription("Play all").performClick()
        compose.runOnIdle {
            assertEquals(favorites.map { it.id }, played.get()!!.second.map { it.id })
            assertEquals(favorites.first().id, played.get()!!.first.id)
            played.set(null)
        }
        compose.onNodeWithContentDescription("Shuffle").performClick()
        compose.waitUntil(30_000) { played.get() != null }
        compose.runOnIdle {
            val (selected, queue) = checkNotNull(played.get())
            assertEquals(favorites.size, queue.size)
            assertEquals(favorites.map { it.id }.toSet(), queue.map { it.id }.toSet())
            assertEquals(queue.first().id, selected.id)
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("track_item_liked-layout-24"))
        compose.onNodeWithContentDescription("Play all").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Shuffle").assertIsNotDisplayed()
    }
}
