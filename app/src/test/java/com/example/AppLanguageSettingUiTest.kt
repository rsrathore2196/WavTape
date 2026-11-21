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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
class AppLanguageSettingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun systemIsSelectedByDefaultAndThePickerAppliesThePlatformAppLanguage() {
        compose.setContent { MaterialTheme { AppLanguageSetting() } }
        compose.onNodeWithTag("app_language_setting").assertTextContains("System default").performClick()
        compose.onNodeWithTag("app_language_system").assertIsSelected()
        compose.onNodeWithTag("app_language_en").assertIsNotSelected()
        compose.onNodeWithTag("app_language_hi").assertTextContains("हिन्दी").performClick()
        compose.runOnIdle { assertEquals("hi", AppLanguage.selection(compose.activity)) }
    }
}
