package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppThemeMode
import com.example.data.local.BackupSettings
import com.example.data.local.OtherSettingsPreferences
import com.example.data.local.StartupSettings
import com.example.ui.screens.settings.ThemesAppUiPage
import com.example.ui.theme.AppFontStyle
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.buildAppTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FontStyleSettingsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun catalogHasSystemDefaultAndAtLeast20CustomFontsWithValidResources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(AppFontStyle.SYSTEM, OtherSettingsPreferences.getAppFontStyle(context))
        assertEquals("System Font", AppFontStyle.SYSTEM.displayName)
        assertEquals(1.0f, AppFontStyle.SYSTEM.opticalScale)

        val customFonts = AppFontStyle.entries.filter { it != AppFontStyle.SYSTEM }
        assertTrue("Expected at least 20 custom fonts, found ${customFonts.size}", customFonts.size >= 20)
        customFonts.forEach { style ->
            val resId = style.fontRes
            assertNotNull("Missing fontRes for ${style.displayName}", resId)
            assertTrue("Invalid opticalScale for ${style.displayName}", style.opticalScale in 0.80f..1.10f)
            assertTrue("Invalid lineHeightScale for ${style.displayName}", style.lineHeightScale in 0.95f..1.15f)
            assertNotNull("Could not open font resource for ${style.displayName}", context.resources.openRawResource(resId!!))
            val typography = buildAppTypography(style)
            assertEquals(style.fontFamily, typography.bodyLarge.fontFamily)
            assertEquals(style.fontFamily, typography.titleLarge.fontFamily)
            assertEquals(style.fontFamily, typography.labelSmall.fontFamily)
        }
    }

    @Test
    fun backupAndRestorePreservesSelectedFontStyleAndHandlesUnknownFallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        OtherSettingsPreferences.setAppFontStyle(context, AppFontStyle.PLAYFAIR_DISPLAY)
        assertEquals(AppFontStyle.PLAYFAIR_DISPLAY, StartupSettings.read(context).appFontStyle)

        val captured = BackupSettings.capture(context)
        val encoded = BackupSettings.encode(captured)
        val decoded = BackupSettings.decode(encoded)
        assertEquals(
            AppFontStyle.PLAYFAIR_DISPLAY.id,
            decoded["xtreme_other_settings"]?.get("app_font_style")
        )

        // Change to another font, then restore backup and confirm Playfair Display returns.
        OtherSettingsPreferences.setAppFontStyle(context, AppFontStyle.SYNE)
        assertEquals(AppFontStyle.SYNE, OtherSettingsPreferences.getAppFontStyle(context))

        withContext(Dispatchers.IO) {
            BackupSettings.write(context, decoded, replaceMissing = true)
        }
        assertEquals(AppFontStyle.PLAYFAIR_DISPLAY, OtherSettingsPreferences.getAppFontStyle(context))
        assertEquals(AppFontStyle.PLAYFAIR_DISPLAY, StartupSettings.read(context).appFontStyle)

        // Unknown / blank IDs safely fall back to System Font.
        assertEquals(AppFontStyle.SYSTEM, AppFontStyle.fromId(null))
        assertEquals(AppFontStyle.SYSTEM, AppFontStyle.fromId(""))
        assertEquals(AppFontStyle.SYSTEM, AppFontStyle.fromId("nonexistent_future_font"))
    }

    @Test
    fun appearanceSettingsShowsFontStyleAndUpdatesSelectionFromPopup() {
        var selectedFont by mutableStateOf(AppFontStyle.SYSTEM)

        composeRule.setContent {
            MyApplicationTheme(darkTheme = true, appFontStyle = selectedFont) {
                ThemesAppUiPage(
                    isDarkMode = true,
                    themeMode = AppThemeMode.DARK,
                    onSelectThemeMode = {},
                    appFontStyle = selectedFont,
                    onSelectAppFontStyle = { selectedFont = it }
                )
            }
        }

        composeRule.onNodeWithTag("appearance_font_style_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("appearance_font_style_current", useUnmergedTree = true)
            .assertTextEquals("System Font")

        composeRule.onNodeWithTag("appearance_font_style_button").performClick()
        composeRule.onNodeWithTag("font_style_picker_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("font_option_system").assertIsDisplayed()
        composeRule.onNodeWithTag("font_style_options_list")
            .performScrollToNode(hasTestTag("font_option_outfit"))
        composeRule.onNodeWithTag("font_option_outfit").performClick()

        assertEquals(AppFontStyle.OUTFIT, selectedFont)

        composeRule.onNodeWithTag("font_style_done_button").performClick()
        composeRule.onNodeWithTag("appearance_font_style_current", useUnmergedTree = true)
            .assertTextEquals("Outfit")
        composeRule.onNodeWithText("Font Style", useUnmergedTree = true).assertIsDisplayed()
    }
}
