package com.example

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.unit.dp
import com.example.data.local.CustomThemeState
import com.example.data.local.ThemePresets
import com.example.data.local.ThemeTonePalettes
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

    private fun color(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

    @Test fun everyPresetUsesItsExactTonalMaterialRolesWithReadableContent() {
        ThemePresets.allPresets.forEach { preset ->
            val state = preset.toCustomThemeState()
            val tones = ThemeTonePalettes.get(preset.accentName, preset.isDark)
            assertEquals(tones, ThemePresets.tonalPalette(state))
            val colors = resolveAppThemeColors(preset.isDark, state)
            assertTrue(colors.neutralSurfaces)
            assertEquals(if (preset.isAmoled) Color.Black else color(tones.background), colors.scaffoldBackground)
            assertEquals(if (preset.isAmoled) Color.Black else color(tones.surfaceContainerHigh), colors.cardBackground)
            assertEquals(color(tones.surfaceContainerHighest), colors.cardBackgroundElevated)
            assertEquals(color(tones.surfaceContainerHighest), colors.inputBackground)
            assertEquals(color(tones.surfaceContainerHighest), colors.chipBackground)
            assertEquals(color(tones.primary), colors.primaryAccent)
            assertEquals(color(tones.secondary), colors.secondaryAccent)
            val scheme = resolveColorScheme(preset.isDark, colors)
            val accentRoles = listOf(
                Triple("primary", tones.primary, scheme.primary),
                Triple("onPrimary", tones.onPrimary, scheme.onPrimary),
                Triple("primaryContainer", tones.primaryContainer, scheme.primaryContainer),
                Triple("onPrimaryContainer", tones.onPrimaryContainer, scheme.onPrimaryContainer),
                Triple("secondary", tones.secondary, scheme.secondary),
                Triple("onSecondary", tones.onSecondary, scheme.onSecondary),
                Triple("secondaryContainer", tones.secondaryContainer, scheme.secondaryContainer),
                Triple("onSecondaryContainer", tones.onSecondaryContainer, scheme.onSecondaryContainer),
                Triple("tertiary", tones.tertiary, scheme.tertiary),
                Triple("onTertiary", tones.onTertiary, scheme.onTertiary),
                Triple("tertiaryContainer", tones.tertiaryContainer, scheme.tertiaryContainer),
                Triple("onTertiaryContainer", tones.onTertiaryContainer, scheme.onTertiaryContainer)
            )
            accentRoles.forEach { (role, expected, actual) ->
                assertEquals("${preset.name}: $role", color(expected), actual)
            }
            val surfaceRoles = listOf(
                Triple("background", if (preset.isAmoled) "#000000" else tones.background, scheme.background),
                Triple("onBackground", tones.onBackground, scheme.onBackground),
                Triple("surface", if (preset.isAmoled) "#000000" else tones.surface, scheme.surface),
                Triple("onSurface", tones.onSurface, scheme.onSurface),
                Triple("surfaceVariant", tones.surfaceVariant, scheme.surfaceVariant),
                Triple("onSurfaceVariant", tones.onSurfaceVariant, scheme.onSurfaceVariant),
                Triple("surfaceContainerLowest", if (preset.isAmoled) "#000000" else tones.surfaceContainerLowest, scheme.surfaceContainerLowest),
                Triple("surfaceContainerLow", tones.surfaceContainerLow, scheme.surfaceContainerLow),
                Triple("surfaceContainer", tones.surfaceContainer, scheme.surfaceContainer),
                Triple("surfaceContainerHigh", tones.surfaceContainerHigh, scheme.surfaceContainerHigh),
                Triple("surfaceContainerHighest", tones.surfaceContainerHighest, scheme.surfaceContainerHighest),
                Triple("surfaceDim", if (preset.isAmoled) "#000000" else tones.surfaceDim, scheme.surfaceDim),
                Triple("surfaceBright", tones.surfaceBright, scheme.surfaceBright),
                Triple("outline", tones.outline, scheme.outline),
                Triple("outlineVariant", tones.outlineVariant, scheme.outlineVariant)
            )
            surfaceRoles.forEach { (role, expected, actual) ->
                assertEquals("${preset.name}: $role", color(expected), actual)
            }
            val surfaces = listOf(scheme.background, scheme.surface, scheme.surfaceVariant,
                scheme.surfaceDim, scheme.surfaceBright, scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest, colors.chipBackground, colors.inputBackground)
            surfaces.forEach { surface ->
                assertEquals(1f, surface.alpha, 0f)
                listOf(colors.textPrimary, colors.textSecondary, colors.textMuted).forEach { text ->
                    assertTrue("${preset.name}: $text on $surface", contrast(text, surface) >= 4.5f)
                }
            }
            for (elevation in listOf(0, 1, 6, 24)) {
                assertEquals("${preset.name}: elevation must preserve the neutral surface role",
                    scheme.surface, scheme.surfaceColorAtElevation(elevation.dp))
            }
            for ((background, content) in listOf(scheme.primary to scheme.onPrimary,
                scheme.secondary to scheme.onSecondary, scheme.tertiary to scheme.onTertiary,
                scheme.primaryContainer to scheme.onPrimaryContainer,
                scheme.secondaryContainer to scheme.onSecondaryContainer,
                scheme.tertiaryContainer to scheme.onTertiaryContainer)) {
                assertEquals(1f, background.alpha, 0f)
                assertTrue("${preset.name}: action contrast", contrast(content, background) >= 4.5f)
            }
            assertTrue(contrast(scheme.outline, scheme.surface) >= 3f)
            assertTrue(contrast(colors.primaryAccent, colors.scaffoldBackground) >= 4.5f)
        }
    }

    @Test fun allSevenMutedFamiliesAreAvailableInBothModesAndReplaceVersionFiveAccents() {
        val families = setOf("Crimson", "Violet", "Ocean", "Sage", "Amber", "Rose", "Mono")
        for (presets in listOf(ThemePresets.darkPresets, ThemePresets.lightPresets)) {
            assertEquals(families, presets.map { it.accentName }.toSet())
        }
        val expectedAccents = mapOf(
            "Crimson" to ("#D0A7A3" to "#775653"),
            "Violet" to ("#B4ADC5" to "#615B71"),
            "Ocean" to ("#9FB3C1" to "#4E616D"),
            "Sage" to ("#A3B69F" to "#526350"),
            "Amber" to ("#C5AD8C" to "#6F5B40"),
            "Rose" to ("#CDA7AD" to "#75565C"),
            "Mono" to ("#B0B0B0" to "#5E5E5E")
        )
        for (preset in ThemePresets.allPresets) {
            val previous = checkNotNull(ThemePresets.legacyPreset(preset.id, preset.name, version = 5))
            assertEquals(previous.id, preset.id)
            assertEquals(previous.name, preset.name)
            assertEquals(previous.isDark, preset.isDark)
            assertEquals(previous.isAmoled, preset.isAmoled)
            assertNotEquals(previous.accentColorHex, preset.accentColorHex)
            assertNotEquals(previous.secondaryAccentHex, preset.secondaryAccentHex)
            val expected = expectedAccents.getValue(preset.accentName)
            assertEquals(if (preset.isDark) expected.first else expected.second, preset.accentColorHex)
            assertEquals("Tonal", preset.accentShade)
            val tones = ThemeTonePalettes.get(preset.accentName, preset.isDark)
            assertEquals(tones.secondary, preset.secondaryAccentHex)
            val colors = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState())
            assertEquals(color(preset.accentColorHex), colors.primaryAccent)
            assertEquals(color(preset.secondaryAccentHex), colors.secondaryAccent)
        }
        assertEquals(ThemePresets.StudioNight.toCustomThemeState(), CustomThemeState())
    }

    @Test fun monoUsesNeutralAccentsInBothModesAndAmoledRetainsBlackSurfaces() {
        ThemePresets.allPresets.filter { it.accentName == "Mono" }.forEach { preset ->
            val colors = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState())
            for (accent in listOf(colors.primaryAccent, colors.secondaryAccent)) {
                neutral(accent)
                assertEquals(color(if (preset.isDark) "#B0B0B0" else "#5E5E5E"), accent)
            }
        }
        val amoled = resolveAppThemeColors(true, ThemePresets.AmoledPureBlack.toCustomThemeState())
        assertTrue(amoled.isAmoled)
        assertEquals(Color.Black, amoled.scaffoldBackground)
        assertEquals(Color.Black, amoled.cardBackground)
        assertEquals(Color.Black, amoled.bottomSheetBackground)
        assertEquals(Color.Black, amoled.bottomBarBackground)
        assertEquals(listOf(ThemePresets.AmoledPureBlack.id), ThemePresets.allPresets.filter { it.isAmoled }.map { it.id })
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

    @Test fun editingEitherResolvedAccentDiscardsPresetRolesWithoutLosingTheCopiedColors() {
        for (preset in listOf(ThemePresets.StudioNight, ThemePresets.CleanDay)) {
            val original = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState())
            val originalScheme = resolveColorScheme(preset.isDark, original)
            assertNotNull(original.accentRoles)
            for (edited in listOf(
                original.copy(primaryAccent = Color(0xFF4B6C8A)),
                original.copy(secondaryAccent = Color(0xFF885D78))
            )) {
                assertNotNull("The copy retains its source metadata so the anchor guard is exercised", edited.tonalPalette)
                assertNull(edited.accentRoles)
                val scheme = resolveColorScheme(preset.isDark, edited)
                assertEquals(edited.primaryAccent, scheme.primary)
                assertEquals(edited.secondaryAccent, scheme.secondary)
                assertEquals(contrastingContentColor(edited.primaryAccent), scheme.onPrimary)
                assertEquals(contrastingContentColor(edited.secondaryAccent), scheme.onSecondary)
                assertNotEquals(originalScheme.primaryContainer, scheme.primaryContainer)
                assertNotEquals(originalScheme.secondaryContainer, scheme.secondaryContainer)
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
