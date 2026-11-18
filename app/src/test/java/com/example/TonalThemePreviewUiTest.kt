package com.example

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ui.theme.*
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TonalThemePreviewUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun presetsStylesTogglesComponentsAndCompleteSwatchListRemainUsable() {
        var applied: Pair<TonalThemeOptions, Boolean>? = null
        compose.setContent { ThemePreviewScreen(onApply = { options, dark -> applied = options to dark }) }
        compose.onNodeWithTag("tonal_seed_WARM_AMBER_STUDIO").performClick()
        compose.onNodeWithTag("tonal_style_VIBRANT").performScrollTo().performClick()
        capture("light")
        compose.onNodeWithText("Dark theme").assertIsToggleable()
        compose.onNodeWithTag("tonal_dark_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("tonal_apply").performScrollTo().performClick()
        assertEquals(TonalThemePreset.WARM_AMBER_STUDIO.seedColor, applied!!.first.seedColor)
        assertEquals(PaletteStyle.VIBRANT, applied!!.first.style)
        assertTrue(applied!!.second)
        compose.onNodeWithTag("tonal_artwork_toggle").assertIsNotEnabled()
        capture("dark")
        compose.onNodeWithTag("tonal_role_list").performScrollToNode(hasTestTag("tonal_sample_button"))
        compose.onNodeWithTag("tonal_sample_button").performClick()
        compose.onNodeWithText("Selected").assertIsDisplayed()
        compose.onNodeWithTag("tonal_role_list").performScrollToNode(hasTestTag("tonal_swatch_surfaceContainerHighest"))
        compose.onNodeWithTag("tonal_swatch_surfaceContainerHighest").assertIsDisplayed()
        compose.onNodeWithTag("tonal_role_list").performScrollToNode(hasTestTag("tonal_swatch_scrim"))
        compose.onNodeWithTag("tonal_swatch_scrim").assertIsDisplayed()
    }

    private fun capture(name: String) {
        val file = File("build/reports/tonal-theme-preview/$name.png").apply { parentFile?.mkdirs() }
        compose.onNodeWithTag("tonal_theme_preview").captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
