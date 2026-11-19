package com.example

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.unit.dp
import com.example.data.local.CustomThemeState
import com.example.data.local.ThemePresets
import com.example.data.local.MaterialThemePresets
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

    private fun variants() = ThemePresets.allPresets.flatMap { preset ->
        listOf(ThemePresets.forMode(preset, false), ThemePresets.forMode(preset, true))
    }

    @Test fun everyPairedPresetUsesItsMaterialRolesWithReadableContent() {
        variants().forEach { preset ->
            val state = preset.toCustomThemeState()
            val tones = checkNotNull(MaterialThemePresets.roles(preset.id, preset.isDark))
            assertEquals(tones, ThemePresets.tonalPalette(state))
            val colors = resolveAppThemeColors(preset.isDark, state)
            assertTrue(colors.neutralSurfaces)
            assertEquals(tones.background, colors.scaffoldBackground)
            assertEquals(tones.surfaceContainerHigh, colors.cardBackground)
            assertEquals(tones.surfaceContainerHighest, colors.cardBackgroundElevated)
            assertEquals(tones.surfaceContainerHighest, colors.inputBackground)
            assertEquals(tones.surfaceContainerHighest, colors.chipBackground)
            assertEquals(tones.primary, colors.primaryAccent)
            assertEquals(tones.secondary, colors.secondaryAccent)
            val scheme = resolveColorScheme(preset.isDark, colors)
            val roles = listOf(
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
                Triple("onTertiaryContainer", tones.onTertiaryContainer, scheme.onTertiaryContainer),
                Triple("background", tones.background, scheme.background),
                Triple("onBackground", tones.onBackground, scheme.onBackground),
                Triple("surface", tones.surface, scheme.surface),
                Triple("onSurface", tones.onSurface, scheme.onSurface),
                Triple("surfaceVariant", tones.surfaceVariant, scheme.surfaceVariant),
                Triple("onSurfaceVariant", tones.onSurfaceVariant, scheme.onSurfaceVariant),
                Triple("surfaceContainerLowest", tones.surfaceContainerLowest, scheme.surfaceContainerLowest),
                Triple("surfaceContainerLow", tones.surfaceContainerLow, scheme.surfaceContainerLow),
                Triple("surfaceContainer", tones.surfaceContainer, scheme.surfaceContainer),
                Triple("surfaceContainerHigh", tones.surfaceContainerHigh, scheme.surfaceContainerHigh),
                Triple("surfaceContainerHighest", tones.surfaceContainerHighest, scheme.surfaceContainerHighest),
                Triple("surfaceDim", tones.surfaceDim, scheme.surfaceDim),
                Triple("surfaceBright", tones.surfaceBright, scheme.surfaceBright),
                Triple("outlineVariant", tones.outlineVariant, scheme.outlineVariant)
            )
            roles.forEach { (role, expected, actual) ->
                assertEquals("${preset.name} (${preset.isDark}): $role", expected, actual)
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
                assertEquals("${preset.name}: elevation must preserve the chosen surface role",
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

    @Test fun resolvingAnOppositeBrightnessVariantKeepsTheSelectedThemeIdentity() {
        for (preset in ThemePresets.allPresets) for (dark in listOf(false, true)) {
            val matching = ThemePresets.forMode(preset, dark).toCustomThemeState()
            val opposite = ThemePresets.forMode(preset, !dark).toCustomThemeState()
            val expected = resolveAppThemeColors(dark, matching)
            val actual = resolveAppThemeColors(dark, opposite)
            assertEquals(matching.presetId, opposite.presetId)
            assertEquals(expected.isAmoled, actual.isAmoled)
            assertEquals(expected.primaryAccent, actual.primaryAccent)
            assertEquals(expected.secondaryAccent, actual.secondaryAccent)
            assertEquals(expected.cardBackground, actual.cardBackground)
            assertEquals(expected.tonalPalette, actual.tonalPalette)
            assertEquals(expected.scaffoldBackground, actual.scaffoldBackground)
            assertEquals(expected.cardBackgroundElevated, actual.cardBackgroundElevated)
            assertEquals(expected.inputBackground, actual.inputBackground)
            assertEquals(expected.chipBackground, actual.chipBackground)
            assertEquals(expected.textPrimary, actual.textPrimary)
            assertEquals(expected.textSecondary, actual.textSecondary)
            assertEquals(expected.textMuted, actual.textMuted)
        }
        assertEquals(MaterialThemePresets.DEFAULT_ID, CustomThemeState().presetId)
        assertEquals(ThemePresets.default(true).toCustomThemeState(), CustomThemeState())
    }

    @Test fun nightInkKeepsNeutralAccentsAndTrueBlackSurfacesOnlyInDarkMode() {
        val canonical = checkNotNull(ThemePresets.findById(MaterialThemePresets.AMOLED_ID))
        for (dark in listOf(false, true)) {
            val state = ThemePresets.forMode(canonical, dark).toCustomThemeState()
            val colors = resolveAppThemeColors(dark, state)
            val scheme = resolveColorScheme(dark, colors)
            listOf(colors.primaryAccent, colors.secondaryAccent, scheme.tertiary).forEach(::neutral)
            assertEquals(dark, colors.isAmoled)
            if (dark) {
                assertEquals(Color.White, colors.primaryAccent)
                listOf(colors.scaffoldBackground, colors.cardBackground, colors.bottomSheetBackground,
                    colors.bottomBarBackground, scheme.surface, scheme.surfaceVariant,
                    scheme.surfaceContainerLowest, scheme.surfaceContainerLow, scheme.surfaceContainer,
                    scheme.surfaceContainerHigh, scheme.surfaceContainerHighest).forEach {
                    assertEquals(Color.Black, it)
                }
            } else {
                assertTrue(colors.scaffoldBackground.luminance() > .9f)
                assertTrue(contrast(colors.textPrimary, colors.scaffoldBackground) >= 4.5f)
            }
        }
        assertEquals(listOf(MaterialThemePresets.AMOLED_ID),
            ThemePresets.allPresets.filter { it.isAmoled }.map { it.id })
    }

    @Test fun gradientContentRemainsReadableAcrossEveryBuiltInAccentPair() {
        variants().forEach { preset ->
            val colors = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState())
            for (step in 0..10) {
                val surface = androidx.compose.ui.graphics.lerp(colors.primaryAccent, colors.secondaryAccent, step / 10f)
                assertTrue("${preset.name} gradient content contrast", contrast(colors.onAccentGradient, surface) >= 4.5f)
            }
        }
    }

    @Test fun editingEitherResolvedAccentDiscardsPresetRolesWithoutLosingTheCopiedColors() {
        for (preset in listOf(ThemePresets.default(true), ThemePresets.default(false))) {
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
        variants().forEach { preset ->
            val colors = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState(), true)
            val scheme = resolveColorScheme(preset.isDark, colors)
            for (surface in listOf(scheme.background, scheme.surface, scheme.surfaceContainerHighest)) {
                for (text in listOf(colors.textPrimary, colors.textSecondary, colors.textMuted)) {
                    assertTrue("${preset.name}: enhanced text", contrast(text, surface) >= 7f)
                }
            }
        }
        val custom = ThemePresets.forMode(ThemePresets.allPresets[3], false).toCustomThemeState().copy(presetId = "", currentThemeName = "Custom",
            canvasColorHex = "#FFF0F8", cardColorHex = "#FFF7F2",
            bgGradientColorsHex = listOf("#FFF0F8", "#FFF0F8"))
        val colors = resolveAppThemeColors(false, custom)
        assertFalse(colors.neutralSurfaces)
        assertEquals(Color(0xfffff0f8), colors.scaffoldBackground)
        assertEquals(Color(0xfffff7f2), colors.cardBackground)
        assertEquals(Color(0xfffff0f8), LiquidGlass.settingsColors(colors).scaffoldBackground)
    }

    @Test fun settingsRetainsAccentActionsButDoesNotPropagateItsFinishIntoThePlayer() {
        variants().forEach { preset ->
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
