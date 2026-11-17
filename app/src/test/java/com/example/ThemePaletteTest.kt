package com.example

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.unit.dp
import com.example.data.local.ThemePresets
import com.example.ui.theme.*
import com.example.ui.util.playerAppearanceColors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class ThemePaletteTest {
    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)

    private fun neutral(color: Color) {
        assertEquals(color.red, color.green, .001f)
        assertEquals(color.green, color.blue, .001f)
    }

    @Test fun everyPresetHasNeutralSurfacesAndCompleteMaterialRolesWithReadableContent() {
        ThemePresets.allPresets.forEach { preset ->
            val colors = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState())
            assertTrue(colors.neutralSurfaces)
            assertEquals(if (preset.isDark) {
                if (preset.isAmoled) Color.Black else Color(0xff0b0b0b)
            } else Color.White, colors.scaffoldBackground)
            val scheme = resolveColorScheme(preset.isDark, colors)
            val surfaces = listOf(scheme.background, scheme.surface, scheme.surfaceVariant,
                scheme.surfaceDim, scheme.surfaceBright, scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest, colors.chipBackground, colors.inputBackground)
            surfaces.forEach { surface ->
                neutral(surface)
                assertEquals(1f, surface.alpha, 0f)
                listOf(colors.textPrimary, colors.textSecondary, colors.textMuted).forEach { text ->
                    assertTrue("${preset.name}: $text on $surface", contrast(text, surface) >= 4.5f)
                }
            }
            for (elevation in listOf(0, 1, 6, 24)) neutral(scheme.surfaceColorAtElevation(elevation.dp))
            for ((background, content) in listOf(scheme.primary to scheme.onPrimary,
                scheme.secondary to scheme.onSecondary, scheme.primaryContainer to scheme.onPrimaryContainer,
                scheme.secondaryContainer to scheme.onSecondaryContainer)) {
                assertEquals(1f, background.alpha, 0f)
                assertTrue("${preset.name}: action contrast", contrast(content, background) >= 4.5f)
            }
            assertTrue(contrast(scheme.outline, scheme.surface) >= 3f)
            assertTrue(contrast(colors.primaryAccent, colors.scaffoldBackground) >= 4.5f)
        }
    }

    @Test fun everyPresetHasDistinctSaturatedTonalGradientsAndUpdatesThePreviousPalette() {
        val pairs = ThemePresets.allPresets.map { it.accentColorHex to it.secondaryAccentHex }
        assertEquals(20, pairs.distinct().size)
        for (preset in ThemePresets.allPresets) {
            val previous = checkNotNull(ThemePresets.legacyPreset(preset.id, preset.name, version = 4))
            assertNotEquals(previous.accentColorHex, preset.accentColorHex)
            assertNotEquals(previous.secondaryAccentHex, preset.secondaryAccentHex)
            assertNotEquals(preset.accentColorHex, preset.secondaryAccentHex)
            for (hex in listOf(preset.accentColorHex, preset.secondaryAccentHex)) {
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(hex), hsv)
                if (preset.isAmoled) assertEquals(0f, hsv[1], 0f)
                else assertTrue("${preset.name} should have colorful gradient endpoints", hsv[1] >= .35f)
            }
            val colors = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState())
            assertNotEquals(colors.primaryAccent, colors.secondaryAccent)
            // Check the resolved colors too: contrast correction must retain the tonal pair.
            for ((start, end) in listOf(
                android.graphics.Color.parseColor(preset.accentColorHex) to android.graphics.Color.parseColor(preset.secondaryAccentHex),
                colors.primaryAccent.toArgb() to colors.secondaryAccent.toArgb()
            )) {
                val first = FloatArray(3).also { android.graphics.Color.colorToHSV(start, it) }
                val last = FloatArray(3).also { android.graphics.Color.colorToHSV(end, it) }
                val difference = kotlin.math.abs(first[0] - last[0])
                assertTrue("${preset.name} gradient must use shades of one hue", minOf(difference, 360f - difference) < 2f)
            }
        }
        assertEquals(ThemePresets.StudioNight.toCustomThemeState(), com.example.data.local.CustomThemeState())
    }

    @Test fun presetBaseHuesAreSeparatedAndAmoledUsesOnlyWhiteShades() {
        val colored = ThemePresets.allPresets.filterNot { it.isAmoled }
        for (resolved in listOf(false, true)) {
            val hues = colored.map { preset ->
                val color = if (resolved) resolveAppThemeColors(preset.isDark, preset.toCustomThemeState()).primaryAccent.toArgb()
                    else android.graphics.Color.parseColor(preset.accentColorHex)
                FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }[0]
            }
            for (first in hues.indices) for (second in first + 1 until hues.size) {
                val difference = kotlin.math.abs(hues[first] - hues[second])
                assertTrue("${colored[first].name} and ${colored[second].name} must not look like duplicate hues",
                    minOf(difference, 360f - difference) >= 15f)
            }
        }
        val amoled = resolveAppThemeColors(true, ThemePresets.AmoledPureBlack.toCustomThemeState())
        assertEquals(Color.White, amoled.primaryAccent)
        neutral(amoled.secondaryAccent)
        assertTrue(amoled.secondaryAccent.red >= .85f)
        assertEquals(Color.Black, amoled.scaffoldBackground)
    }

    @Test fun gradientContentRemainsReadableAcrossEveryBuiltInAccentPair() {
        ThemePresets.allPresets.forEach { preset ->
            val colors = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState())
            for (step in 0..10) {
                val surface = androidx.compose.ui.graphics.lerp(colors.primaryAccent, colors.secondaryAccent, step / 10f)
                assertTrue("${preset.name} gradient content contrast", contrast(colors.onAccentGradient, surface) >= 4.5f)
            }
        }
    }

    @Test fun highContrastIncludesElevatedSurfacesAndCustomColorsAreNotNeutralized() {
        ThemePresets.allPresets.forEach { preset ->
            val colors = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState(), true)
            val scheme = resolveColorScheme(preset.isDark, colors)
            for (surface in listOf(scheme.background, scheme.surface, scheme.surfaceContainerHighest)) {
                for (text in listOf(colors.textPrimary, colors.textSecondary, colors.textMuted)) {
                    assertTrue("${preset.name}: enhanced text", contrast(text, surface) >= 7f)
                }
            }
        }
        val custom = ThemePresets.CherryBlossom.toCustomThemeState().copy(presetId = "", currentThemeName = "Custom",
            canvasColorHex = "#FFF0F8", cardColorHex = "#FFF7F2",
            bgGradientColorsHex = listOf("#FFF0F8", "#FFF0F8"))
        val colors = resolveAppThemeColors(false, custom)
        assertFalse(colors.neutralSurfaces)
        assertEquals(Color(0xfffff0f8), colors.scaffoldBackground)
        assertEquals(Color(0xfffff7f2), colors.cardBackground)
        assertEquals(Color(0xfffff0f8), LiquidGlass.settingsColors(colors).scaffoldBackground)
    }

    @Test fun settingsRetainsAccentActionsButDoesNotPropagateItsFinishIntoThePlayer() {
        ThemePresets.allPresets.forEach { preset ->
            val original = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState())
            val settings = LiquidGlass.settingsColors(original)
            assertTrue(settings.settingsSurface)
            assertEquals(original.primaryAccent, settings.primaryAccent)
            assertEquals(original.cardBackground, settings.cardBackground)
            assertFalse(original.settingsSurface)
            val player = playerAppearanceColors(settings)
            assertFalse(player.settingsSurface)
            assertFalse(player.neutralSurfaces)
            assertEquals(original.primaryAccent, player.primaryAccent)
            assertEquals(original.secondaryAccent, player.secondaryAccent)
        }
    }
}
