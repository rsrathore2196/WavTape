package com.example

import android.app.Application
import android.content.Context
import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import com.example.data.local.AppThemeMode
import com.example.data.local.BackupSettings
import com.example.data.local.OtherSettingsPreferences
import com.example.data.local.StartupSettings
import com.example.ui.screens.settings.ThemesAppUiPage
import com.example.ui.theme.AppFontStyle
import com.example.ui.theme.LocalAppFontStyle
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.buildAppTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        val resolver = createFontFamilyResolver(context)
        val systemTypeface = resolver.resolve(FontFamily.Default, FontWeight.Normal).value
        assertTrue("Expected at least 20 custom fonts, found ${customFonts.size}", customFonts.size >= 20)
        customFonts.forEach { style ->
            val resId = style.fontRes
            assertNotNull("Missing fontRes for ${style.displayName}", resId)
            assertTrue("Invalid opticalScale for ${style.displayName}", style.opticalScale in 0.80f..1.10f)
            assertTrue("Invalid lineHeightScale for ${style.displayName}", style.lineHeightScale in 0.95f..1.15f)
            context.resources.openRawResource(resId!!).use {
                assertTrue("Empty font resource for ${style.displayName}", it.read() != -1)
            }
            val normal = resolver.resolve(style.fontFamily, FontWeight.Normal).value as Typeface
            val bold = resolver.resolve(style.fontFamily, FontWeight.Bold).value as Typeface
            assertNotEquals("Found the system fallback for ${style.displayName}", systemTypeface, normal)
            assertTrue("Could not resolve bold for ${style.displayName}", bold.isBold)
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        var selectedFont by mutableStateOf(AppFontStyle.SYSTEM)
        var appliedStyle = AppFontStyle.SYSTEM
        var appliedFontFamily: FontFamily? = null

        composeRule.setContent {
            MyApplicationTheme(darkTheme = true, appFontStyle = selectedFont) {
                val style = LocalAppFontStyle.current
                val family = MaterialTheme.typography.bodyLarge.fontFamily
                SideEffect { appliedStyle = style; appliedFontFamily = family }
                ThemesAppUiPage(
                    isDarkMode = true,
                    themeMode = AppThemeMode.DARK,
                    onSelectThemeMode = {},
                    appFontStyle = selectedFont,
                    onSelectAppFontStyle = {
                        OtherSettingsPreferences.setAppFontStyle(context, it)
                        selectedFont = it
                    }
                )
            }
        }

        composeRule.onNodeWithTag("appearance_font_style_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("appearance_font_style_current", useUnmergedTree = true)
            .assertTextEquals("System Font")
        composeRule.onNodeWithText("Mini Player Controls").assertDoesNotExist()
        composeRule.onNodeWithTag("switch_mini_player_heart").assertDoesNotExist()
        composeRule.onNodeWithTag("switch_mini_player_next").assertDoesNotExist()
        val fontY = composeRule.onNodeWithTag("appearance_font_style_button").fetchSemanticsNode().positionInRoot.y
        assertTrue(composeRule.onNodeWithText("Custom Theme Colors", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y < fontY)
        assertTrue(composeRule.onNodeWithText("Player Screen Visuals", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y > fontY)

        composeRule.onNodeWithTag("appearance_font_style_button").performClick()
        composeRule.onNodeWithTag("font_style_picker_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("font_option_system").assertIsDisplayed()
        composeRule.onNodeWithTag("font_style_options_list")
            .performScrollToNode(hasTestTag("font_option_outfit"))
        composeRule.onNodeWithTag("font_option_outfit").performClick()

        composeRule.onNodeWithTag("font_option_outfit").assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(AppFontStyle.OUTFIT, selectedFont)
            assertEquals(AppFontStyle.OUTFIT, appliedStyle)
            assertEquals(AppFontStyle.OUTFIT.fontFamily, appliedFontFamily)
            assertEquals(AppFontStyle.OUTFIT, OtherSettingsPreferences.getAppFontStyle(context))
        }

        composeRule.onNodeWithTag("font_style_done_button").performClick()
        composeRule.onNodeWithTag("appearance_font_style_current", useUnmergedTree = true)
            .assertTextEquals("Outfit")
        composeRule.onNodeWithText("Font Style", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("appearance_font_style_button").performClick()
        composeRule.onNodeWithTag("font_option_outfit").assertIsSelected()
        composeRule.onNodeWithTag("font_style_reset_button").performClick()
        composeRule.onNodeWithTag("font_style_done_button").performClick()
        composeRule.onNodeWithTag("appearance_font_style_current", useUnmergedTree = true)
            .assertTextEquals("System Font")
        assertEquals(AppFontStyle.SYSTEM, OtherSettingsPreferences.getAppFontStyle(context))
    }
}
