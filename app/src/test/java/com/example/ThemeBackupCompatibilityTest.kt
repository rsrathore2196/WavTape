package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppThemeMode
import com.example.data.local.BackupSettings
import com.example.data.local.ThemePreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemeBackupCompatibilityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clear() {
        context.getSharedPreferences("xtreme_theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun setting(type: String, value: Any) = JSONObject().put("type", type).put("value", value)

    @Test fun previousBackupsRestoreTheirAppearanceModeWhileDiscardingRemovedTonalOptions() = runBlocking {
        val previousTheme = JSONObject()
            .put("selected_theme_mode", setting("string", "light"))
            .put("tonal_enabled", setting("boolean", true))
            .put("tonal_seed_argb", setting("int", 0xFF6750A4.toInt()))
            .put("tonal_style", setting("string", "FIDELITY"))
            .put("tonal_system_colors", setting("boolean", true))
            .put("tonal_album_art", setting("boolean", true))
        val decoded = BackupSettings.decode(JSONObject().put("xtreme_theme_prefs", previousTheme))

        assertEquals(mapOf("xtreme_theme_prefs" to mapOf("selected_theme_mode" to "light")), decoded)
        BackupSettings.write(context, decoded)
        assertEquals(AppThemeMode.LIGHT, ThemePreferences.getThemeMode(context))
        assertFalse(context.getSharedPreferences("xtreme_theme_prefs", Context.MODE_PRIVATE)
            .all.keys.any { it.startsWith("tonal_") })
    }

    @Test fun retiredOptionsStillOnTheDeviceAreExcludedFromNewBackups() = runBlocking {
        context.getSharedPreferences("xtreme_theme_prefs", Context.MODE_PRIVATE).edit()
            .putString("selected_theme_mode", "dark")
            .putBoolean("tonal_enabled", true).putInt("tonal_seed_argb", 0xFF6750A4.toInt())
            .putString("tonal_style", "VIBRANT").putBoolean("tonal_system_colors", false)
            .putBoolean("tonal_album_art", true).commit()

        assertEquals(mapOf("xtreme_theme_prefs" to mapOf("selected_theme_mode" to "dark")),
            BackupSettings.read(context, setOf("xtreme_theme_prefs")))
    }

    @Test fun compatibilityExceptionsDoNotPermitOtherUnknownSettings() {
        assertThrows(IllegalStateException::class.java) {
            BackupSettings.decode(JSONObject().put("xtreme_theme_prefs",
                JSONObject().put("unknown_theme_option", setting("boolean", true))))
        }
        assertThrows(IllegalStateException::class.java) {
            BackupSettings.decode(JSONObject().put("xtreme_other_settings",
                JSONObject().put("tonal_enabled", setting("boolean", true))))
        }
    }
}
