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
import org.junit.Assert.assertEquals
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

    @Test fun heroAndArtistRadioUseTheDisplayedNativeSeedWithoutListeningHistory() {
        val song = MusicTrack("yt_abcdefghijk", "Song", "Artist One", "Album", 180_000, "", "")
        var heroSelection: MusicTrack? = null
        var artistSelection: MusicTrack? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDynamicGlassEnabled provides false,
                LocalVisualEffectsActive provides false, LocalArtworkRequestsActive provides false) {
                HomeScreenContent(heroSeedTrack = song, onTrackClick = { _, _ -> }, onToggleFavorite = {},
                    serverShelves = listOf(ServerHomeShelf("native", "Quick picks", tracks = listOf(song))),
                    onPlayInfiniteRadio = { heroSelection = it }, onPlayArtistRadio = { artistSelection = it })
            }
        } }
        compose.onNodeWithTag("hero_play_infinite_radio_button").assertIsEnabled().performClick()
        assertEquals(song, heroSelection)
        compose.onNodeWithTag("home_screen").performScrollToNode(hasTestTag("artist_radio_shelf"))
        compose.onNodeWithTag("artist_radio_play_Artist One").assertIsDisplayed().performClick()
        assertEquals(song, artistSelection)
    }
}
