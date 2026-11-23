package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.MusicTrack
import com.example.data.model.ServerHomeShelf
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.HomeScreenContent
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.viewmodel.MostListenedArtist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeRadioUiTest {
    @get:Rule val compose = createComposeRule()

    private val song = MusicTrack("yt_abcdefghijk", "Song", "Artist One", "Album", 180_000, "", "")

    @Test fun heroPlaysTheDisplayedNativeSeedWithoutListeningHistory() {
        var heroSelection: MusicTrack? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDynamicGlassEnabled provides false,
                LocalVisualEffectsActive provides false, LocalArtworkRequestsActive provides false) {
                HomeScreenContent(heroSeedTrack = song, onTrackClick = { _, _ -> }, onToggleFavorite = {},
                    serverShelves = listOf(ServerHomeShelf("native", "Quick picks", tracks = listOf(song))),
                    onPlayInfiniteRadio = { heroSelection = it }, onPlayArtistRadio = {})
            }
        } }
        compose.onNodeWithTag("hero_play_infinite_radio_button").assertIsEnabled().performClick()
        assertEquals(song, heroSelection)
        compose.onNodeWithTag("home_screen").performScrollToKey("server_shelf_native")
        compose.onNodeWithText("A mix of songs to try").assertIsDisplayed()
        compose.onNodeWithTag("native_quick_picks_frame").assertIsDisplayed()
        compose.onNodeWithTag("most_listened_artist_row").assertDoesNotExist()
        compose.onNodeWithTag("artist_radio_shelf").assertDoesNotExist()
    }

    @Test fun mostListenedArtistsScrollWithSeparateRadioAndArtistPageActions() {
        val first = MostListenedArtist(song.artist, 8, listOf(song))
        val second = MostListenedArtist("Artist Two", 2,
            listOf(song.copy(id = "local_song", title = "Local song", artist = "Artist Two")))
        var radioSelection: MostListenedArtist? = null
        var openedArtist: String? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDynamicGlassEnabled provides false,
                LocalVisualEffectsActive provides false, LocalArtworkRequestsActive provides false) {
                HomeScreenContent(mostListenedArtists = listOf(first, second),
                    onTrackClick = { _, _ -> }, onToggleFavorite = {},
                    onPlayArtistRadio = { radioSelection = it }, onOpenArtist = { openedArtist = it.name })
            }
        } }
        compose.onNodeWithTag("home_screen").performScrollToKey("most_listened_artists")
        compose.onNodeWithText("Artists you play most often").assertIsDisplayed()
        val frame = compose.onNodeWithTag("home_artist_frame").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("most_listened_artist_card_Artist One").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(card.left > frame.left && card.right < frame.right)
        assertTrue(card.top > frame.top && card.bottom < frame.bottom)
        compose.onNodeWithTag("view_artist_Artist One").assertIsDisplayed().performClick()
        assertEquals(first.name, openedArtist)
        compose.onNodeWithTag("artist_radio_play_Artist One").assertIsDisplayed().performClick()
        assertEquals(first, radioSelection)

        compose.onNodeWithTag("most_listened_artist_row").performScrollToIndex(1)
        compose.onNodeWithTag("artist_radio_play_Artist Two").assertIsDisplayed().performClick()
        assertEquals(second, radioSelection)
        compose.onNodeWithTag("view_artist_Artist Two").assertIsDisplayed().performClick()
        assertEquals(second.name, openedArtist)
        compose.onNodeWithText("Artist Spotlight", ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithTag("artist_radio_shelf").assertDoesNotExist()
    }
}
