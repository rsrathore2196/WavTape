package com.example

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.example.data.local.MaterialThemePresets
import com.example.ui.theme.colorutilities.hct.Hct
import com.example.ui.theme.colorutilities.utils.ColorUtils
import org.junit.Assert.*
import org.junit.Test

class MaterialThemePresetsTest {
    @Test fun tenChoicesKeepTheirIdentityWhenAppearanceChanges() {
        val light = MaterialThemePresets.all(dark = false)
        val dark = MaterialThemePresets.all(dark = true)

        assertEquals(10, light.size)
        assertEquals(light.map { it.id }, dark.map { it.id })
        assertEquals(light.map { it.name }, dark.map { it.name })
        assertEquals(10, light.map { it.name }.toSet().size)
        assertEquals(10, light.map { it.accentColorHex }.toSet().size)
        assertEquals(10, dark.map { it.accentColorHex }.toSet().size)
        assertTrue(light.none { it.isDark || it.isAmoled })
        assertTrue(dark.all { it.isDark })
        assertEquals(1, dark.count { it.isAmoled })
        assertEquals(MaterialThemePresets.DEFAULT_ID, MaterialThemePresets.default(false).id)
        assertEquals(MaterialThemePresets.DEFAULT_ID, MaterialThemePresets.default(true).id)
        assertNull(MaterialThemePresets.findById("missing", dark = true))
        assertNull(MaterialThemePresets.roles("missing", dark = true))
    }

    @Test fun allAccentAndSurfaceForegroundPairsRemainAccessible() {
        for (dark in listOf(false, true)) {
            for (preset in MaterialThemePresets.all(dark)) {
                val roles = checkNotNull(MaterialThemePresets.roles(preset.id, dark))
                val pairs = listOf(
                    roles.primary to roles.onPrimary,
                    roles.secondary to roles.onSecondary,
                    roles.tertiary to roles.onTertiary,
                    roles.primaryContainer to roles.onPrimaryContainer,
                    roles.secondaryContainer to roles.onSecondaryContainer,
                    roles.tertiaryContainer to roles.onTertiaryContainer,
                    roles.background to roles.onBackground,
                    roles.surface to roles.onSurface,
                    roles.surfaceVariant to roles.onSurfaceVariant
                )
                pairs.forEach { (background, foreground) ->
                    assertTrue("${preset.name}, dark=$dark: $foreground on $background", contrast(background, foreground) >= 4.5f)
                    assertEquals(1f, background.alpha, 0f)
                    assertEquals(1f, foreground.alpha, 0f)
                }
                val surfaceLevels = listOf(
                    roles.surfaceContainerLowest,
                    roles.surfaceContainerLow,
                    roles.surfaceContainer,
                    roles.surfaceContainerHigh,
                    roles.surfaceContainerHighest
                )
                val tones = surfaceLevels.map { ColorUtils.lstarFromArgb(it.toArgb()) }
                if (!preset.isAmoled) {
                    tones.zipWithNext().forEach { (a, b) ->
                        assertTrue("${preset.name}: surface hierarchy $tones", if (dark) a < b else a > b)
                    }
                }
                assertEquals(if (dark) { if (preset.isAmoled) 0.0 else 6.0 } else 98.0,
                    ColorUtils.lstarFromArgb(roles.surface.toArgb()), .5)
            }
        }
    }

    @Test fun materialThemesHaveSoftDistinctAccentsAndMultipleColourLayers() {
        for (dark in listOf(false, true)) {
            MaterialThemePresets.all(dark).filter { it.id != MaterialThemePresets.AMOLED_ID }.forEach { preset ->
                val roles = checkNotNull(MaterialThemePresets.roles(preset.id, dark))
                val primary = Hct.fromInt(roles.primary.toArgb())
                assertEquals(if (dark) 80.0 else 40.0, primary.tone, .5)
                assertTrue("${preset.name}: moderate chroma", primary.chroma in 15.0..43.0)
                assertNotEquals(roles.primary, roles.secondary)
                assertNotEquals(roles.primary, roles.tertiary)
                assertNotEquals(roles.primaryContainer, roles.secondaryContainer)
                assertNotEquals(roles.secondaryContainer, roles.tertiaryContainer)
                assertTrue("${preset.name}: tinted neutrals", Hct.fromInt(roles.surfaceContainer.toArgb()).chroma in 3.0..9.0)
            }
        }
        val default = checkNotNull(MaterialThemePresets.roles(MaterialThemePresets.DEFAULT_ID, dark = false))
        assertTrue(Hct.fromInt(default.primary.toArgb()).hue in 250.0..280.0)
        assertTrue(Hct.fromInt(default.secondary.toArgb()).hue in 185.0..220.0)
    }

    @Test fun nightInkHasTrueBlackDarkSurfacesAndAReadableNeutralLightCompanion() {
        val dark = checkNotNull(MaterialThemePresets.roles(MaterialThemePresets.AMOLED_ID, dark = true))
        val light = checkNotNull(MaterialThemePresets.roles(MaterialThemePresets.AMOLED_ID, dark = false))

        assertEquals(Color.Black, dark.background)
        assertEquals(Color.Black, dark.surface)
        assertEquals(Color.Black, dark.surfaceContainerLowest)
        assertEquals(Color.Black, dark.surfaceContainerLow)
        assertEquals(Color.Black, dark.surfaceContainer)
        assertEquals(Color.Black, dark.surfaceContainerHigh)
        assertEquals(Color.Black, dark.surfaceContainerHighest)
        assertEquals(Color.Black, dark.surfaceVariant)
        assertEquals(Color.Black, dark.surfaceBright)
        assertEquals(Color.White, dark.primary)
        assertTrue(light.surface.luminance() > .9f)
        for (color in listOf(dark.primary, dark.secondary, dark.tertiary, light.primary, light.surface)) {
            assertEquals(color.red, color.green, .001f)
            assertEquals(color.green, color.blue, .001f)
        }
    }

    @Test fun immutableResolvedPalettesAreReusedAcrossRepeatedReads() {
        assertSame(MaterialThemePresets.all(true), MaterialThemePresets.all(true))
        assertSame(MaterialThemePresets.default(false), MaterialThemePresets.default(false))
        assertSame(MaterialThemePresets.roles(MaterialThemePresets.DEFAULT_ID, true),
            MaterialThemePresets.roles(MaterialThemePresets.DEFAULT_ID, true))
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
}
