package com.example

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.data.local.AppLanguage
import com.example.ui.screens.settings.AppLanguageSetting
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppLanguageSettingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun systemIsSelectedByDefaultAndThePickerAppliesThePlatformAppLanguage() {
        compose.setContent { MaterialTheme { AppLanguageSetting() } }
        compose.onNodeWithTag("app_language_setting").assertTextContains("System default").performClick()
        compose.onNodeWithTag("app_language_system").assertIsSelected()
        val search = compose.onNodeWithTag("app_language_search")
        search.performTextReplacement("does not exist")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No languages found").fetchSemanticsNodes().isNotEmpty() }
        search.performTextReplacement("tamil")
        compose.onNodeWithTag("app_language_ta").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals("ta", AppLanguage.selection(compose.activity)) }
    }

    @Test fun pickerScrollsToLanguagesBeyondTheFirstPage() {
        compose.setContent { MaterialTheme { AppLanguageSetting() } }
        compose.onNodeWithTag("app_language_setting").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("app_language_af").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("app_language_list", useUnmergedTree = true).performScrollToNode(hasTestTag("app_language_zu"))
        compose.onNodeWithTag("app_language_zu").assertIsDisplayed()
    }
}
