package com.example

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.CustomThemeState
import com.example.data.local.ThemePresets
import com.example.ui.screens.settings.CustomThemingPage
import com.example.ui.screens.settings.tonalSecondaryAccent
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
import javax.imageio.ImageIO

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomThemingUiTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(ThemePresets.StudioNight.toCustomThemeState())
    private val updates = mutableListOf<CustomThemeState>()

    private fun show(initial: CustomThemeState = state) {
        state = initial
        compose.setContent {
            val colors = resolveAppThemeColors(state.isPresetDark, state)
            MaterialTheme(colorScheme = resolveColorScheme(colors.isDark, colors)) {
                CompositionLocalProvider(LocalAppColors provides LiquidGlass.settingsColors(colors)) {
                    Box(Modifier.fillMaxSize().background(colors.scaffoldBackground)) {
                        CustomThemingPage(state) { updates += it; state = it }
                    }
                }
            }
        }
    }

    private fun nextHue() {
        val actions = compose.onNodeWithContentDescription("Accent color spectrum")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.first { it.label == "Next hue" }.action()) }
    }

    @Test fun editedAccentPreviewsAndSavesTheSameTonalGradient() {
        show()
        val oldSecondary = state.secondaryAccentHex
        nextHue()
        val file = File.createTempFile("custom-accent-preview-", ".png")
        try {
            compose.onNodeWithTag("custom_theme_preview_accent").captureRoboImage(file.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            val preview = ImageIO.read(file)
            compose.onNodeWithTag("custom_theme_save").assertIsEnabled().performClick()
            compose.waitForIdle()
            assertEquals(1, updates.size)
            assertNotEquals(oldSecondary, state.secondaryAccentHex)
            val primary = Color(android.graphics.Color.parseColor(state.accentColorHex))
            val secondary = Color(android.graphics.Color.parseColor(state.secondaryAccentHex))
            for (fraction in listOf(.2f, .8f)) {
                val expected = lerp(primary, secondary, fraction)
                val actual = Color(preview.getRGB((preview.width * fraction).toInt(), (preview.height * .86f).toInt()))
                assertEquals(expected.red, actual.red, .025f)
                assertEquals(expected.green, actual.green, .025f)
                assertEquals(expected.blue, actual.blue, .025f)
            }
            compose.onNodeWithTag("custom_theme_save").assertIsNotEnabled()
        } finally { file.delete() }
    }

    @Test fun incomingSecondaryOnlyChangesRefreshTheDraftInsteadOfSavingAnOlderHue() {
        show(state.copy(presetId = "", currentThemeName = "Custom"))
        nextHue()
        compose.onNodeWithTag("custom_theme_save").assertIsEnabled()
        compose.runOnIdle { state = state.copy(secondaryAccentHex = "#FFEE88") }
        compose.waitForIdle()
        compose.onNodeWithTag("custom_theme_save").assertIsNotEnabled()
        assertTrue(updates.isEmpty())
    }

    @Test fun canvasOnlyChangesPreserveAnImportedPairAndItsAlphaChannels() {
        val imported = state.copy(presetId = "imported-theme", currentThemeName = "Imported",
            accentColorHex = "#804099FF", secondaryAccentHex = "#40FFAA88")
        show(imported)
        compose.onNodeWithTag("custom_theme_save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Studio Midnight").performScrollTo().performClick()
        compose.onNodeWithTag("custom_theme_save").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, updates.size)
        assertEquals("#071424", state.canvasColorHex)
        assertEquals(imported.accentColorHex, state.accentColorHex)
        assertEquals(imported.secondaryAccentHex, state.secondaryAccentHex)
    }

    @Test fun derivedStopsKeepGradientContentReadableIncludingBlackWhiteAndContrastBoundaries() {
        val choices = listOf(Color.Black, Color.White, Color(0xFF757575), Color(0xFF777777)) +
            (0 until 360 step 15).flatMap { hue -> listOf(.3f, .55f, .8f, 1f).map { value ->
                Color.hsv(hue.toFloat(), .75f, value)
            } }
        for (dark in listOf(false, true)) for (primary in choices) {
            val secondary = tonalSecondaryAccent(primary, dark)
            val foreground = DarkAppColors.copy(primaryAccent = primary, secondaryAccent = secondary).onAccentGradient
            for (step in 0..10) {
                val fill = lerp(primary, secondary, step / 10f)
                val ratio = (maxOf(fill.luminance(), foreground.luminance()) + .05f) /
                    (minOf(fill.luminance(), foreground.luminance()) + .05f)
                assertTrue("$primary → $secondary must retain readable content in dark=$dark", ratio >= 4.5f)
            }
            assertNotEquals(primary, secondary)
        }
    }
}
