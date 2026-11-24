package com.example

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppThemeMode
import com.example.data.local.OtherSettingsPreferences
import com.example.data.local.ThemePreferences
import com.example.data.local.ThemePresets
import com.example.data.local.ThemeSelection
import com.example.playback.AudioEffectsState
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.util.LocalArtworkRequestsActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port-notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairedAppearanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var selection by mutableStateOf(ThemeSelection(AppThemeMode.SYSTEM,
        ThemePresets.forMode(ThemePresets.allPresets.first(), false).toCustomThemeState()))

    @Before fun clear() {
        context.getSharedPreferences("xtreme_theme_prefs", 0).edit().clear().commit()
        ThemePreferences.saveThemeSelection(context, selection.mode, selection.palette)
    }

    private fun refreshSelection() {
        selection = ThemeSelection(ThemePreferences.getThemeMode(context),
            ThemePreferences.getCustomThemeState(context))
    }

    private fun show() {
        compose.setContent {
            val dark = ThemePreferences.isDark(context, selection.mode)
            MyApplicationTheme(darkTheme = dark, customThemeState = selection.palette) {
                CompositionLocalProvider(LocalDynamicGlassEnabled provides false,
                    LocalVisualEffectsActive provides false, LocalArtworkRequestsActive provides false) {
                    SettingsScreen(effectsState = AudioEffectsState(), isDarkMode = dark,
                        themeMode = selection.mode, userProfile = null,
                        onSelectThemeMode = { ThemePreferences.setThemeMode(context, it); refreshSelection() },
                        onToggleDarkMode = {}, onCrystalClarityToggle = {}, onToggleEqualizer = {},
                        onOpenEqualizer = {}, onSelectPreset = {}, customThemeState = selection.palette,
                        onApplyPreset = { preset ->
                            ThemePreferences.saveThemeSelection(context, selection.mode,
                                ThemePresets.forMode(preset, dark).toCustomThemeState())
                            refreshSelection()
                        })
                }
            }
        }
    }

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun pureLiquidGlassDefaultsOffAndSavesBothSwitchValues() {
        assertFalse(OtherSettingsPreferences.playerExperienceSettings(context).pureLiquidGlassUi)
        show()
        compose.onNodeWithText("Appearance").performScrollTo().performClick()
        val toggle = compose.onNodeWithTag("switch_pure_liquid_glass_ui").performScrollTo()
        compose.onNodeWithText("Pure Liquid Glass UI").assertIsDisplayed()
        compose.onNodeWithText("Makes the Overall UI Elements have the Liquid Glass Effect based on Playing Track Album Art")
            .assertIsDisplayed()

        toggle.performClick()
        assertTrue(OtherSettingsPreferences.playerExperienceSettings(context).pureLiquidGlassUi)
        back()
        compose.onNodeWithText("Appearance").performScrollTo().performClick()
        compose.onNodeWithTag("switch_pure_liquid_glass_ui").performScrollTo().performClick()
        assertFalse(OtherSettingsPreferences.playerExperienceSettings(context).pureLiquidGlassUi)
    }

    @Test fun appearanceKeepsOnePresetChooserAndPreservesTheChosenAppearanceMode() {
        show()
        compose.onNodeWithText("Appearance").performScrollTo().performClick()
        compose.onNodeWithText("Custom Theme Colors").assertExists()
        compose.onNodeWithText("9 Material palettes + AMOLED").assertExists()
        compose.onNodeWithText("Tonal Themes").assertDoesNotExist()
        compose.onNodeWithTag("open_tonal_themes").assertDoesNotExist()

        for ((index, mode) in listOf(AppThemeMode.SYSTEM, AppThemeMode.DARK, AppThemeMode.LIGHT).withIndex()) {
            if (mode != AppThemeMode.SYSTEM) {
                compose.onNodeWithText(if (mode == AppThemeMode.DARK) "Dark" else "Light")
                    .performScrollTo().performClick()
            }
            compose.onNodeWithTag("choose_theme_preset_card").performScrollTo().performClick()
            compose.onNodeWithText("A palette for every mood").assertDoesNotExist()
            compose.onNodeWithText("Choose a preset to apply it. Your selection is saved automatically.")
                .assertDoesNotExist()
            for (filter in listOf("All", "Dark", "Light")) {
                compose.onNodeWithText(filter, useUnmergedTree = true).assertDoesNotExist()
            }
            val chosen = ThemePresets.allPresets[index + 1]
            compose.onNodeWithText(chosen.name).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(mode, ThemePreferences.getThemeMode(context))
            assertEquals(chosen.id, selection.palette.presetId)
            assertEquals(ThemePreferences.isDark(context, mode), selection.palette.isPresetDark)

            // A system back gesture returns to the existing Appearance overview.
            back()
            compose.onNodeWithTag("choose_theme_preset_card").assertIsDisplayed()
            compose.onNodeWithText("Tonal Themes").assertDoesNotExist()
        }
    }
}
