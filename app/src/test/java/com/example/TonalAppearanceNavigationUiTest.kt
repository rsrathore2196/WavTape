package com.example

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.playback.AudioEffectsState
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.*
import com.example.ui.util.LocalArtworkRequestsActive
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TonalAppearanceNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var selection by mutableStateOf(ThemeSelection(AppThemeMode.LIGHT, ThemePresets.CleanDay.toCustomThemeState()))
    private var visiblePrimary: Color? = null

    @Before fun clear() {
        context.getSharedPreferences("xtreme_theme_prefs", 0).edit().clear().commit()
        ThemePreferences.saveThemeSelection(context, AppThemeMode.LIGHT, ThemePresets.CleanDay.toCustomThemeState())
    }
    private fun readSelection() = ThemeSelection(ThemePreferences.getThemeMode(context),
        ThemePreferences.getCustomThemeState(context), ThemePreferences.getTonalTheme(context))

    private fun show() {
        selection = readSelection()
        compose.setContent {
            val dark = ThemePreferences.isDark(context, selection.mode)
            MyApplicationTheme(darkTheme = dark, customThemeState = selection.palette,
                tonalTheme = selection.tonalTheme, animateTonalColors = false, textScale = 1f, uiScale = 1f) {
                val primary = MaterialTheme.colorScheme.primary
                SideEffect { visiblePrimary = primary }
                CompositionLocalProvider(LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false,
                    LocalArtworkRequestsActive provides false) {
                    SettingsScreen(effectsState = AudioEffectsState(), isDarkMode = dark, themeMode = selection.mode,
                        userProfile = null, onSelectThemeMode = { ThemePreferences.setThemeMode(context, it); selection = readSelection() },
                        onToggleDarkMode = {}, onCrystalClarityToggle = {}, onToggleEqualizer = {},
                        onOpenEqualizer = {}, onSelectPreset = {}, customThemeState = selection.palette,
                        onApplyPreset = { preset ->
                            ThemePreferences.saveThemeSelection(context, if (preset.isDark) AppThemeMode.DARK else AppThemeMode.LIGHT,
                                preset.toCustomThemeState()); selection = readSelection()
                        }, tonalTheme = selection.tonalTheme,
                        onApplyTonalTheme = { options, isDark ->
                            ThemePreferences.saveTonalTheme(context, options, if (isDark) AppThemeMode.DARK else AppThemeMode.LIGHT)
                            selection = readSelection()
                        })
                }
            }
        }
    }
    private fun openAppearance() { compose.onNodeWithText("Appearance").performScrollTo().performClick() }
    private fun openTonal() {
        compose.onNodeWithTag("open_tonal_themes").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tonal_theme_preview").assertIsDisplayed()
    }
    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun replacementEntryOpensAppliesPersistsAndReopensTheActualThemeControls() {
        show(); openAppearance()
        compose.onNodeWithText("Current Theme").assertDoesNotExist()
        compose.onNodeWithText("Save Theme").assertDoesNotExist()
        compose.onNodeWithText("Custom Theme Colors").assertExists()
        compose.onNodeWithText("Choose Theme Preset").assertExists()
        openTonal()
        compose.onNodeWithTag("tonal_seed_ROSE_QUARTZ").performClick()
        compose.onNodeWithTag("tonal_style_FIDELITY").performScrollTo().performClick()
        compose.onNodeWithTag("tonal_dark_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("tonal_apply").performScrollTo().performClick()
        compose.waitForIdle()
        val expected = TonalThemeOptions(TonalThemePreset.ROSE_QUARTZ.seedColor, PaletteStyle.FIDELITY)
        assertEquals(expected, ThemePreferences.getTonalTheme(context))
        assertEquals(AppThemeMode.DARK, ThemePreferences.getThemeMode(context))
        val color = ThemePaletteGenerator.generate(expected.seedColor, true, expected.style).primary
        compose.waitUntil(5_000) { compose.waitForIdle(); visiblePrimary == color }
        back(); openTonal()
        compose.onNodeWithTag("tonal_seed_ROSE_QUARTZ").assertIsSelected()
        compose.onNodeWithTag("tonal_style_FIDELITY").assertIsSelected()
        compose.onNodeWithTag("tonal_dark_toggle").assertIsOn()
        back()
        compose.onNodeWithTag("choose_theme_preset_card").performScrollTo().performClick()
        compose.onNodeWithText("Studio Night").performScrollTo().performClick()
        compose.waitForIdle()
        assertNull(ThemePreferences.getTonalTheme(context))
        assertEquals(ThemePresets.StudioNight.id, ThemePreferences.getCustomThemeState(context).presetId)
    }

    @Test fun savedArtworkMatchingCanBeTurnedOffWhenTheCurrentCoverIsUnavailable() {
        ThemePreferences.saveTonalTheme(context, TonalThemeOptions(useAlbumArtwork = true), AppThemeMode.LIGHT)
        show(); openAppearance(); openTonal()
        compose.onNodeWithTag("tonal_artwork_toggle").performScrollTo().assertIsOn().assertIsEnabled().performClick()
        compose.onNodeWithTag("tonal_apply").performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(ThemePreferences.getTonalTheme(context)!!.useAlbumArtwork)
    }
}
