package com.example

import android.app.Application
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import com.example.data.local.ThemePresets
import com.example.ui.components.AccentPageHeader
import com.example.ui.components.HomeAppHeader
import com.example.ui.theme.LocalAccentHeaderVisible
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StatusBarAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34, 35, 36], application = Application::class)
class StatusBarAppearanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun assertBars(darkStatusIcons: Boolean, darkNavigationIcons: Boolean) {
        compose.runOnIdle {
            val window = compose.activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertEquals("Status icons must follow the visible surface", darkStatusIcons, controller.isAppearanceLightStatusBars)
            assertEquals("Navigation bar styling is unchanged", darkNavigationIcons && Build.VERSION.SDK_INT >= 26,
                controller.isAppearanceLightNavigationBars)
        }
    }

    @Test fun headerIconsFollowAccentContrastAcrossThemeChangesInsteadOfTheGlobalMode() {
        var preset by mutableStateOf(ThemePresets.AmoledPureBlack)
        compose.setContent {
            MyApplicationTheme(darkTheme = preset.isDark, customThemeState = preset.toCustomThemeState()) {
                HomeAppHeader({})
            }
        }
        assertBars(darkStatusIcons = true, darkNavigationIcons = false)
        compose.runOnIdle { assertEquals(Color.Transparent.toArgb(), compose.activity.window.statusBarColor) }
        compose.runOnIdle { preset = ThemePresets.StudioNight }
        assertBars(darkStatusIcons = true, darkNavigationIcons = false)
        compose.runOnIdle { preset = ThemePresets.CleanDay }
        // Light mode has a deep blue header, requiring white status icons.
        assertBars(darkStatusIcons = false, darkNavigationIcons = true)
        compose.runOnIdle { preset = ThemePresets.AmoledPureBlack }
        assertBars(darkStatusIcons = true, darkNavigationIcons = false)
    }

    @Test fun retainedTabHeadersCannotOverrideSettingsOrPlayerAndRestoreWhenUncovered() {
        var selected by mutableIntStateOf(0)
        var covered by mutableStateOf(false)
        var preset by mutableStateOf(ThemePresets.AmoledPureBlack)
        compose.setContent {
            MyApplicationTheme(darkTheme = preset.isDark, customThemeState = preset.toCustomThemeState()) {
                // All three pages stay composed, as they do in the app's retained tab host.
                Box {
                    repeat(3) { page ->
                        CompositionLocalProvider(LocalAccentHeaderVisible provides (!covered && selected == page)) {
                            if (page == 0) HomeAppHeader({})
                            else AccentPageHeader(if (page == 1) "Search Online Music" else "Your Library",
                                "Music", "header_$page")
                        }
                    }
                }
            }
        }
        for (page in 0..2) {
            compose.runOnIdle { selected = page }
            assertBars(darkStatusIcons = true, darkNavigationIcons = false)
        }
        compose.runOnIdle { covered = true }
        assertBars(darkStatusIcons = false, darkNavigationIcons = false)
        // A theme update while headers remain mounted must not reclaim the status bar.
        compose.runOnIdle { preset = ThemePresets.CleanDay }
        assertBars(darkStatusIcons = true, darkNavigationIcons = true)
        compose.runOnIdle { covered = false }
        assertBars(darkStatusIcons = false, darkNavigationIcons = true)
        compose.runOnIdle { covered = true; preset = ThemePresets.AmoledPureBlack }
        assertBars(darkStatusIcons = false, darkNavigationIcons = false)
        compose.runOnIdle { covered = false }
        assertBars(darkStatusIcons = true, darkNavigationIcons = false)
    }

    @Test fun libraryCollectionDisposalRestoresPageIconsAndBackRestoresHeaderIcons() {
        var collectionOpen by mutableStateOf(false)
        compose.setContent {
            MyApplicationTheme(darkTheme = true, customThemeState = ThemePresets.AmoledPureBlack.toCustomThemeState()) {
                if (collectionOpen) Text("Liked Songs")
                else AccentPageHeader("Your Library", "Saved music", "library_header")
            }
        }
        assertBars(darkStatusIcons = true, darkNavigationIcons = false)
        compose.runOnIdle { collectionOpen = true }
        assertBars(darkStatusIcons = false, darkNavigationIcons = false)
        compose.runOnIdle { collectionOpen = false }
        assertBars(darkStatusIcons = true, darkNavigationIcons = false)
    }

    @Test fun outgoingOwnerCannotClearTheIncomingHeadersPreference() {
        val state = StatusBarAppearance()
        val outgoing = Any()
        val incoming = Any()
        state.showHeader(outgoing, false)
        state.showHeader(incoming, true)
        state.hideHeader(outgoing)
        assertEquals(true, state.headerDarkIcons)
        state.hideHeader(incoming)
        assertNull(state.headerDarkIcons)
    }
}
