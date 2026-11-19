package com.example

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.example.data.local.ThemePresets
import com.example.ui.screens.settings.*
import com.example.ui.theme.*
import com.example.ui.theme.colorutilities.hct.Hct
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
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemePresetUiTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(tag: String, name: String): java.awt.image.BufferedImage {
        val file = File("build/reports/theme-review/$name.png").apply { parentFile?.mkdirs() }
        compose.onNodeWithTag(tag, useUnmergedTree = true).captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        return ImageIO.read(file)
    }

    @Test fun presetSelectionUpdatesTheRenderedPreviewWithoutChangingTheGridNavigation() {
        val initial = ThemePresets.allPresets.first()
        val alternate = ThemePresets.allPresets[4]
        var selected by mutableStateOf(initial)
        var dark by mutableStateOf(false)
        compose.setContent {
            val active = ThemePresets.forMode(selected, dark)
            val colors = resolveAppThemeColors(dark, active.toCustomThemeState())
            MaterialTheme(colorScheme = resolveColorScheme(colors.isDark, colors)) {
                CompositionLocalProvider(LocalAppColors provides LiquidGlass.settingsColors(colors)) {
                    Box(Modifier.fillMaxSize().background(colors.scaffoldBackground)) {
                        ThemePresetsPage(selected.toCustomThemeState(), { selected = it })
                    }
                }
            }
        }
        compose.onNodeWithText("A palette for every mood").assertDoesNotExist()
        compose.onNodeWithText("Choose a preset to apply it. Your selection is saved automatically.").assertDoesNotExist()
        for (filter in listOf("All", "Dark", "Light")) {
            compose.onNodeWithText(filter, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithText(alternate.name).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(ThemePresets.forMode(alternate, false), selected)
        val image = capture("theme_preview_${selected.id}", "light-preset")
        val lightColors = resolveAppThemeColors(false, selected.toCustomThemeState())
        assertEquals(lightColors.scaffoldBackground.toArgb(), image.getRGB(image.width / 2, 6))

        // Changing appearance mode retains the same selected tile and refreshes its palette.
        compose.runOnIdle { dark = true }
        compose.onNodeWithText(alternate.name).assertIsDisplayed()
        compose.onNodeWithContentDescription("Selected theme", useUnmergedTree = true).assertIsDisplayed()
        val darkImage = capture("theme_preview_${selected.id}", "dark-preset")
        val darkVariant = ThemePresets.forMode(alternate, true)
        val darkColors = resolveAppThemeColors(true, darkVariant.toCustomThemeState())
        assertEquals(darkColors.scaffoldBackground.toArgb(), darkImage.getRGB(darkImage.width / 2, 6))
        assertNotEquals(image.getRGB(image.width / 2, 6), darkImage.getRGB(darkImage.width / 2, 6))

        compose.onNodeWithText(initial.name).performScrollTo().performClick()
        assertEquals(ThemePresets.forMode(initial, true), selected)
    }

    @Test fun settingsUsesTonalGlassWithReadableLabelsAndFunctionalAccentControls() {
        val themes = listOf(
            ThemePresets.forMode(ThemePresets.allPresets[1], true),
            ThemePresets.forMode(ThemePresets.allPresets[3], false),
            ThemePresets.forMode(ThemePresets.allPresets.last(), true)
        )
        var preset by mutableStateOf(themes.first())
        var checked by mutableStateOf(true)
        var opens = 0
        compose.setContent {
            val colors = LiquidGlass.settingsColors(resolveAppThemeColors(preset.isDark, preset.toCustomThemeState()))
            MaterialTheme(colorScheme = resolveColorScheme(colors.isDark, colors)) {
                CompositionLocalProvider(LocalAppColors provides colors) {
                    Column(Modifier.fillMaxWidth().background(colors.scaffoldBackground)
                        .padding(16.dp).testTag("settings_theme_surface")) {
                        SettingsCategoryCardGroup(SettingsCategory.entries, colors.isDark, { opens++ })
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp)
                            .liquidGlassCard(colors, tintAccent = true).padding(16.dp)) {
                            Text("Example control", color = colors.textPrimary, modifier = Modifier.weight(1f))
                            Switch(checked, { checked = it }, colors = liquidGlassSwitchColors(colors),
                                modifier = Modifier.testTag("example_theme_switch"))
                        }
                    }
                }
            }
        }
        for (theme in themes) {
            compose.runOnIdle { preset = theme }
            val colors = resolveAppThemeColors(theme.isDark, theme.toCustomThemeState())
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Example control").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(colors.textPrimary, layouts.single().layoutInput.style.color)
            val image = capture("settings_theme_surface", when {
                theme.isAmoled -> "settings-amoled"
                theme.isDark -> "settings-dark"
                else -> "settings-light"
            })
            // Material neutrals retain a subtle seed tint, without becoming accent-filled cards.
            val pixel = java.awt.Color(image.getRGB(image.width / 2, 12))
            assertTrue("${theme.name}: settings surface keeps low chroma", Hct.fromInt(pixel.rgb).chroma <= 12.0)
            assertTrue("${theme.name}: labels remain readable on rendered glass",
                ColorUtils.calculateContrast(colors.textPrimary.toArgb(), pixel.rgb) >= 4.5)
            SettingsCategory.entries.forEach { category ->
                val file = File.createTempFile("category-icon-", ".png")
                try {
                    val surface = compose.onNodeWithTag("${category.tag}_icon_surface", useUnmergedTree = true)
                    surface.assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
                    surface
                        .captureRoboImage(file.absolutePath, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                    val icon = ImageIO.read(file)
                    var primaryPixels = 0
                    var secondaryPixels = 0
                    var foregroundPixels = 0
                    fun close(value: java.awt.Color, expected: Color, tolerance: Int): Boolean =
                        value.alpha > 240 && kotlin.math.abs(value.red - expected.red * 255) <= tolerance &&
                            kotlin.math.abs(value.green - expected.green * 255) <= tolerance &&
                            kotlin.math.abs(value.blue - expected.blue * 255) <= tolerance
                    for (y in 0 until icon.height) for (x in 0 until icon.width) {
                        val value = java.awt.Color(icon.getRGB(x, y), true)
                        // A little tolerance allows the first/last gradient pixels and rounded border antialiasing.
                        if (close(value, colors.primaryAccent, 12)) primaryPixels++
                        if (close(value, colors.secondaryAccent, 12)) secondaryPixels++
                        if (x in icon.width / 4 until icon.width * 3 / 4 &&
                            y in icon.height / 4 until icon.height * 3 / 4 &&
                            close(value, colors.onAccentGradient, 4)) foregroundPixels++
                    }
                    assertTrue("${theme.name}: ${category.title} displays the selected primary accent", primaryPixels > 10)
                    assertTrue("${theme.name}: ${category.title} displays the selected secondary accent", secondaryPixels > 10)
                    assertTrue("${theme.name}: ${category.title} renders a contrasting glyph", foregroundPixels > 10)
                    for (accent in listOf(colors.primaryAccent, colors.secondaryAccent)) {
                        val light = maxOf(colors.onAccentGradient.luminance(), accent.luminance())
                        val dark = minOf(colors.onAccentGradient.luminance(), accent.luminance())
                        assertTrue("${theme.name}: icon must remain readable over its accent", (light + .05f) / (dark + .05f) >= 4.5f)
                    }
                } finally { file.delete() }
            }
        }
        compose.onNodeWithText("Appearance").performClick()
        assertEquals(1, opens)
        compose.onNodeWithTag("example_theme_switch").performClick()
        assertFalse(checked)
    }
}
