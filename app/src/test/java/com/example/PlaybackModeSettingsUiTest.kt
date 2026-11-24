package com.example

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AudioQualityPreferences
import com.example.data.local.ThemePresets
import com.example.playback.PlaybackMode
import com.example.ui.screens.settings.PlaybackModeSetting
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h740dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackModeSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initialRowShowsTheSavedModeBeforeLifecycleCollectionStarts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AudioQualityPreferences.setPlaybackMode(context, PlaybackMode.BEST_QUALITY)
        compose.setContent {
            val stoppedOwner = remember {
                object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
                }
            }
            MyApplicationTheme(customThemeState = ThemePresets.StudioNight.toCustomThemeState()) {
                CompositionLocalProvider(LocalLifecycleOwner provides stoppedOwner) { PlaybackModeSetting() }
            }
        }
        compose.onNodeWithText("Best Quality").assertIsDisplayed()
    }

    @Test fun theTwoPlaybackModesAreSelectableAndPersisted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(PlaybackMode.FAST_START, AudioQualityPreferences.playbackMode(context))
        compose.setContent {
            MyApplicationTheme(customThemeState = ThemePresets.StudioNight.toCustomThemeState()) {
                PlaybackModeSetting()
            }
        }
        compose.onNodeWithText("Fast Start").assertIsDisplayed()
        compose.onNodeWithText("Playback Mode").performClick()
        PlaybackMode.entries.forEach { mode ->
            compose.onNodeWithText(mode.description).assertIsDisplayed()
        }
        compose.onNodeWithText(PlaybackMode.FAST_START.description).assertIsSelected()
        compose.onNodeWithText(PlaybackMode.BEST_QUALITY.description).assertIsNotSelected().performClick()
        assertEquals(PlaybackMode.BEST_QUALITY, AudioQualityPreferences.playbackMode(context))
        compose.waitUntil(5_000L) { compose.onAllNodesWithText("Best Quality").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Playback Mode").performClick()
        compose.onNodeWithText(PlaybackMode.BEST_QUALITY.description).assertIsSelected()
        compose.onNodeWithText(PlaybackMode.FAST_START.description).performClick()
        assertEquals(PlaybackMode.FAST_START, AudioQualityPreferences.playbackMode(context))
    }
}
