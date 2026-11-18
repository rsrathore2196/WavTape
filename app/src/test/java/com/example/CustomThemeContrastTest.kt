package com.example

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.data.local.ThemePresets
import com.example.ui.theme.resolveAppThemeColors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class CustomThemeContrastTest {
    private fun contrast(foreground: Color, background: Color): Double =
        (maxOf(foreground.luminance(), background.luminance()) + .05).toDouble() /
            (minOf(foreground.luminance(), background.luminance()) + .05)

    private fun mixedTheme(card: String, highContrast: Boolean = false) = resolveAppThemeColors(true,
        ThemePresets.StudioNight.toCustomThemeState().copy(presetId = "", currentThemeName = "Custom",
            canvasColorHex = "#FFFFFF", cardColorHex = card,
            bgGradientColorsHex = listOf("#FFFFFF", "#FFFFFF"),
            textPrimaryHex = "#000000", textSecondaryHex = "#000000", textMutedHex = "#000000"), highContrast)

    @Test fun oppositeBrightnessSurfacesUseTheBestSharedContrastInsteadOfAnInvisibleFallback() {
        val colors = mixedTheme("#171717")
        assertEquals(Color.White, colors.scaffoldBackground)
        assertEquals(Color(0xFF171717), colors.cardBackground)
        val theoreticalMaximum = sqrt((colors.scaffoldBackground.luminance() + .05) /
            (colors.cardBackground.luminance() + .05))
        for (foreground in listOf(colors.textPrimary, colors.textSecondary, colors.textMuted)) {
            val shared = minOf(contrast(foreground, colors.scaffoldBackground), contrast(foreground, colors.cardBackground))
            assertTrue("The previous black fallback was only about 1.17:1", shared > 4.2)
            assertEquals(theoreticalMaximum, shared, .03)
            // A 4.5:1 shared foreground is mathematically impossible for this particular pair.
            assertTrue(shared < 4.5)
        }
    }

    @Test fun aReadableMidtoneIsFoundWhenTheOldCoarseSearchMissedIt() {
        val colors = mixedTheme("#000000")
        for (foreground in listOf(colors.textPrimary, colors.textSecondary, colors.textMuted)) {
            val shared = minOf(contrast(foreground, colors.scaffoldBackground), contrast(foreground, colors.cardBackground))
            assertTrue(shared >= 4.5)
            assertEquals(sqrt(21.0), shared, .03)
        }
        val enhanced = mixedTheme("#000000", highContrast = true)
        val achievable = minOf(contrast(enhanced.textPrimary, enhanced.scaffoldBackground),
            contrast(enhanced.textPrimary, enhanced.cardBackground))
        assertEquals(minOf(contrast(colors.textPrimary, colors.scaffoldBackground), contrast(colors.textPrimary, colors.cardBackground)),
            achievable, .001)
        assertTrue("Enhanced contrast cannot exceed the same physical limit", achievable < 7.0)
    }

    @Test fun normalPresetTypographyKeepsItsExactColorsAndContrast() {
        ThemePresets.allPresets.forEach { preset ->
            val colors = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState())
            for ((hex, actual) in listOf(preset.textPrimaryHex to colors.textPrimary,
                preset.textSecondaryHex to colors.textSecondary, preset.textMutedHex to colors.textMuted)) {
                assertEquals(preset.name, Color(android.graphics.Color.parseColor(hex)), actual)
                assertTrue(contrast(actual, colors.scaffoldBackground) >= 4.5)
                assertTrue(contrast(actual, colors.cardBackground) >= 4.5)
            }
        }
    }
}
