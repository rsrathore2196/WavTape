package com.example

import android.app.Application
import android.content.Context
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppThemeMode
import com.example.playback.AudioEffectsState
import com.example.ui.components.*
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h850dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScrollingUiTest {
    @get:Rule val compose = createComposeRule()

    private fun showSettings() {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false) {
                val pager = rememberPagerState(pageCount = { 3 })
                val transition = updateTransition(3, label = "settings_scroll_test")
                Box(Modifier.fillMaxSize()) {
                    // Exercise real Settings touch input above the retained feed's input blocker.
                    RetainedPrimaryTabHost(pager, transition, covered = false, inputBlocked = true) { Text("Feed $it") }
                    SettingsNavigationOverlay(transition) {
                        SettingsScreen(AudioEffectsState(), true, AppThemeMode.DARK, null,
                            onSelectThemeMode = {}, onToggleDarkMode = {}, onCrystalClarityToggle = {},
                            onToggleEqualizer = {}, onOpenEqualizer = {}, onSelectPreset = {})
                    }
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.scrollOffset() = fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun SemanticsNodeInteraction.swipe() {
        performTouchInput { swipeUp(startY = height * .7f, endY = height * .2f, durationMillis = 400) }
        compose.waitForIdle()
    }

    @Test fun overviewAndSubpagesScrollIndependentlyAndRestoreTheirOwnPosition() {
        showSettings()
        val overview = compose.onNodeWithTag("settings_overview")
        repeat(3) { overview.swipe() }
        assertTrue("Touch gestures scroll Settings", overview.scrollOffset() > 0f)
        assertLastRowClearsDock()
        val overviewOffset = overview.scrollOffset()
        compose.onNodeWithTag("settings_cat_about").performClick()
        compose.waitForIdle()
        overview.assertDoesNotExist()
        val about = compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("settings_page_ABOUT")))
        assertEquals(0f, about.scrollOffset(), .5f)
        about.swipe()
        val aboutOffset = about.scrollOffset()
        assertTrue("The About page has its own working touch scroll", aboutOffset > 0f)
        compose.onNodeWithTag("settings_back_button").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        assertEquals("Returning must not reset or reuse the child scroll position", overviewOffset, overview.scrollOffset(), .5f)
        compose.onNodeWithTag("settings_cat_about").performClick()
        compose.waitForIdle()
        assertEquals(aboutOffset, about.scrollOffset(), 1f)
        compose.onNodeWithTag("settings_back_button").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("settings_cat_music").performScrollTo().performClick()
        compose.waitForIdle()
        val music = compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("settings_page_MUSIC_PLAYBACK")))
        assertEquals("A different subpage starts at its own position", 0f, music.scrollOffset(), .5f)
        music.swipe()
        assertTrue(music.scrollOffset() > 0f)
        compose.onNodeWithText("Output Precision").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Local Stutter-Free LRU Cache").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w740dp-h400dp-land")
    fun shortLandscapeViewportCanScrollPastEveryCategory() {
        showSettings()
        val overview = compose.onNodeWithTag("settings_overview")
        repeat(8) { overview.swipe() }
        assertLastRowClearsDock()
        compose.onNodeWithTag("settings_cat_about").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings_page_ABOUT").assertIsDisplayed()
    }

    @Test fun subSettingsHeaderTitleAndDescriptionAreScaledUpByTwentyFivePercent() {
        showSettings()
        compose.onNodeWithTag("settings_cat_about").performScrollTo().performClick()
        compose.waitForIdle()
        val titleLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("settings_subpage_title")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        val subtitleLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("settings_subpage_subtitle")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(subtitleLayouts) }
        assertEquals(27.5.sp, titleLayouts.single().layoutInput.style.fontSize)
        assertEquals(14.375.sp, subtitleLayouts.single().layoutInput.style.fontSize)
    }

    private fun assertLastRowClearsDock() {
        val bottom = compose.onNodeWithTag("settings_cat_about").fetchSemanticsNode().boundsInRoot.bottom
        val viewport = compose.onNodeWithTag("settings_overview").fetchSemanticsNode().boundsInRoot
        val density = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.density
        assertTrue("The final category must be reachable above the floating controls", bottom <= viewport.bottom - 150f * density)
    }
}
