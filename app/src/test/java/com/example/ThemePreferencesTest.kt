package com.example

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class ThemePreferencesTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() = runBlocking {
        withContext(Dispatchers.IO) { app.getSharedPreferences("xtreme_theme_prefs", 0).edit().clear().commit() }
        Unit
    }
    private fun client(repository: ReactiveSettingsRepository, dark: Boolean = false): Context {
        val config = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val configured = app.createConfigurationContext(config)
        return object : ContextWrapper(configured) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = repository.preferences(name)
        }
    }

    @Test fun selectedThemeKeepsItsIdentityThroughModeChangesAndRepositoryRecreation() = runBlocking {
        val preset = ThemePresets.allPresets[1]
        val dark = ThemePresets.forMode(preset, true).toCustomThemeState()
        val light = ThemePresets.forMode(preset, false).toCustomThemeState()
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            ThemePreferences.saveThemeSelection(context, AppThemeMode.DARK, dark)
            assertEquals(light, ThemePreferences.setThemeMode(context, AppThemeMode.LIGHT))
            assertEquals(dark, ThemePreferences.setThemeMode(context, AppThemeMode.DARK))
            assertEquals(light, ThemePreferences.setThemeMode(context, AppThemeMode.LIGHT))
            assertEquals(dark.presetId, light.presetId)
            repository.awaitPendingWrites()
        }
        ReactiveSettingsRepository(app).use { reopened ->
            reopened.preload()
            val context = client(reopened)
            assertEquals(AppThemeMode.LIGHT, ThemePreferences.getThemeMode(context))
            assertEquals(light, ThemePreferences.getCustomThemeState(context))
            assertEquals(dark, ThemePreferences.setThemeMode(context, AppThemeMode.DARK))
            reopened.awaitPendingWrites()
        }
    }

    @Test fun systemBrightnessChangesUseTheSameThemeWithoutOverwritingTheSelection() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val day = client(repository)
            val night = client(repository, true)
            for (preset in ThemePresets.allPresets) {
                val dark = ThemePresets.forMode(preset, true).toCustomThemeState()
                val light = ThemePresets.forMode(preset, false).toCustomThemeState()
                ThemePreferences.saveThemeSelection(day, AppThemeMode.SYSTEM, dark)
                assertEquals(light, ThemePreferences.getCustomThemeState(day))
                assertEquals(dark, ThemePreferences.getCustomThemeState(night))
                assertEquals(light, ThemePreferences.getCustomThemeState(day))
                assertEquals(AppThemeMode.SYSTEM, ThemePreferences.getThemeMode(night))
            }
            repository.awaitPendingWrites()
        }
    }

    @Test fun choosingAPresetRetainsTheExistingAppearanceMode() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            val preset = ThemePresets.allPresets[4]
            for (mode in AppThemeMode.entries) {
                ThemePreferences.setThemeMode(context, mode)
                ThemePreferences.saveCustomThemeState(context, preset.toCustomThemeState())
                assertEquals(mode, ThemePreferences.getThemeMode(context))
                val expected = ThemePresets.forMode(preset, ThemePreferences.isDark(context, mode)).toCustomThemeState()
                assertEquals(expected, ThemePreferences.getCustomThemeState(context))
            }
            repository.awaitPendingWrites()
        }
    }

    @Test fun partialCurrentVersionLightSelectionUsesTheLightVariantDefaults() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            val preset = ThemePresets.allPresets[3]
            repository.preferences("xtreme_theme_prefs").edit()
                .putString("selected_theme_mode", AppThemeMode.LIGHT.storageKey)
                .putInt("palette_version", ThemePresets.PALETTE_VERSION)
                .putString("custom_preset_id", preset.id)
                .putBoolean("custom_preset_is_dark", false)
                .apply()
            val expected = ThemePresets.forMode(preset, false).toCustomThemeState()
            assertEquals(expected, ThemePreferences.getCustomThemeState(context))
            assertEquals(ThemePresets.forMode(preset, true).toCustomThemeState(),
                ThemePreferences.setThemeMode(context, AppThemeMode.DARK))
            repository.awaitPendingWrites()
        }
    }

    @Test fun retiredTonalOptionsAreIgnoredAndRemovedOnTheNextThemeSelection() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            val palette = ThemePresets.forMode(ThemePresets.allPresets[1], false).toCustomThemeState()
            ThemePreferences.saveThemeSelection(context, AppThemeMode.LIGHT, palette)
            val preferences = repository.preferences("xtreme_theme_prefs")
            preferences.edit().putBoolean("tonal_enabled", true)
                .putInt("tonal_seed_argb", 0xFFFF0000.toInt())
                .putString("tonal_style", "VIBRANT")
                .putBoolean("tonal_system_colors", true)
                .putBoolean("tonal_album_art", true).apply()
            assertEquals(palette, ThemePreferences.getCustomThemeState(context))
            assertEquals(AppThemeMode.LIGHT, ThemePreferences.getThemeMode(context))

            val next = ThemePresets.forMode(ThemePresets.allPresets[6], false).toCustomThemeState()
            ThemePreferences.saveCustomThemeState(context, next)
            assertEquals(next, ThemePreferences.getCustomThemeState(context))
            assertEquals(AppThemeMode.LIGHT, ThemePreferences.getThemeMode(context))
            for (key in listOf("tonal_enabled", "tonal_seed_argb", "tonal_style", "tonal_system_colors", "tonal_album_art")) {
                assertFalse("Retired option $key must not survive a theme save", preferences.contains(key))
            }
            repository.awaitPendingWrites()
        }
    }

    @Test fun untouchedPublishedPalettesMigrateButEditedPalettesKeepTheirValues() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            val preferences = repository.preferences("xtreme_theme_prefs")
            for (version in 1..7) for (published in HistoricalThemePresets.allPresets) {
                val old = checkNotNull(ThemePresets.legacyPreset(published.id, published.name, version))
                val previous = old.toCustomThemeState()
                val expected = (if (old.isAmoled) {
                    checkNotNull(MaterialThemePresets.findById(MaterialThemePresets.AMOLED_ID, old.isDark))
                } else ThemePresets.default(old.isDark)).toCustomThemeState()
                val mode = if (old.isDark) AppThemeMode.DARK else AppThemeMode.LIGHT
                ThemePreferences.saveThemeSelection(context, mode, previous)
                // Model installations and backups written before the new catalog existed.
                preferences.edit().apply {
                    if (version == 1) remove("palette_version") else putInt("palette_version", version)
                }.apply()
                assertEquals("${old.name} version $version", expected, ThemePreferences.getCustomThemeState(context))
                assertEquals(expected, ThemePresets.upgradeLegacy(previous))
                assertNotNull(ThemePresets.tonalPalette(expected))
                assertNull(ThemePresets.findById(old.id))

                val edited = previous.copy(accentColorHex = "#1257E9", secondaryAccentHex = "#F07DE1")
                ThemePreferences.saveThemeSelection(context, mode, edited)
                preferences.edit().putInt("palette_version", version).apply()
                assertEquals(edited, ThemePreferences.getCustomThemeState(context))
                assertEquals(edited, ThemePresets.upgradeLegacy(edited))
                assertNull(ThemePresets.tonalPalette(edited))
            }
            assertNull(ThemePresets.findByName(""))
            repository.awaitPendingWrites()
        }
    }

    @Test fun versionFiveSnapshotsRetainTheirPublishedValuesForMigration() {
        for (preset in HistoricalThemePresets.allPresets) {
            val previous = checkNotNull(ThemePresets.legacyPreset(preset.id, preset.name, version = 5))
            assertEquals("Vibrant", previous.accentShade)
            assertEquals(if (preset.isDark) {
                if (preset.isAmoled) "#000000" else "#0B0B0B"
            } else "#FFFFFF", previous.canvasColorHex)
            assertEquals(if (preset.isDark) {
                if (preset.isAmoled) "#000000" else "#171717"
            } else "#F5F5F5", previous.cardColorHex)
        }
        val dark = checkNotNull(ThemePresets.legacyPreset("dark_studio_night", "", 5))
        assertEquals("Azure Pulse", dark.accentName)
        assertEquals("#4099FF", dark.accentColorHex)
        assertEquals("#66ADFF", dark.secondaryAccentHex)
        val light = checkNotNull(ThemePresets.legacyPreset("light_clean_day", "", 5))
        assertEquals("Cobalt Rush", light.accentName)
        assertEquals("#183FED", light.accentColorHex)
        assertEquals("#1332BE", light.secondaryAccentHex)
        val amoled = checkNotNull(ThemePresets.legacyPreset("dark_amoled_black", "", 5))
        assertEquals("#FFFFFF", amoled.accentColorHex)
        assertEquals("#E0E0E0", amoled.secondaryAccentHex)
    }

    @Test fun editedHistoricalAndImportedPalettesKeepTheirValuesAndModeHistoryAfterRecreation() = runBlocking {
        val previous = checkNotNull(ThemePresets.legacyPreset("dark_studio_night", "Studio Night", version = 5))
            .toCustomThemeState()
        val editedDark = previous.copy(accentColorHex = "#804099FF", secondaryAccentHex = "#40FFAA88",
            canvasColorHex = "#121218", cardColorHex = "#191820",
            bgGradientColorsHex = listOf("#121218", "#16121A"))
        val importedLight = ThemePresets.default(false).toCustomThemeState().copy(
            presetId = "imported-palette", currentThemeName = "Imported",
            accentColorHex = "#804A7590", secondaryAccentHex = "#40807068",
            canvasColorHex = "#FFF9F0", cardColorHex = "#FFF2E6",
            bgGradientColorsHex = listOf("#FFF9F0", "#FAEFE0"))
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            ThemePreferences.saveThemeSelection(context, AppThemeMode.DARK, editedDark)
            ThemePreferences.saveThemeSelection(context, AppThemeMode.LIGHT, importedLight)
            ThemePreferences.setThemeMode(context, AppThemeMode.SYSTEM)
            repository.preferences("xtreme_theme_prefs").edit()
                .putInt("palette_version", 5).putInt("saved_dark_palette_version", 5)
                .putInt("saved_light_palette_version", 5).apply()
            repository.awaitPendingWrites()
        }
        ReactiveSettingsRepository(app).use { reopened ->
            reopened.preload()
            val day = client(reopened)
            val night = client(reopened, true)
            assertEquals(AppThemeMode.SYSTEM, ThemePreferences.getThemeMode(day))
            assertEquals(importedLight, ThemePreferences.getCustomThemeState(day))
            assertEquals(editedDark, ThemePreferences.getCustomThemeState(night))
            assertEquals(editedDark, ThemePresets.upgradeLegacy(editedDark))
            assertEquals(importedLight, ThemePresets.upgradeLegacy(importedLight))
            assertNull(ThemePresets.tonalPalette(editedDark))
            assertNull(ThemePresets.tonalPalette(importedLight))
            assertEquals(editedDark, ThemePreferences.setThemeMode(day, AppThemeMode.DARK))
            assertEquals(importedLight, ThemePreferences.setThemeMode(day, AppThemeMode.LIGHT))
            reopened.awaitPendingWrites()
        }
    }

    @Test fun selectingThemePublishesModeAndPaletteInOnePreferenceSnapshot() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            val palette = ThemePresets.allPresets[4].toCustomThemeState()
            val prefs = repository.preferences("xtreme_theme_prefs")
            var mismatches = 0
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                if (ThemePreferences.getThemeMode(context) != AppThemeMode.DARK ||
                    ThemePreferences.getCustomThemeState(context) != palette) mismatches++
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            ThemePreferences.saveThemeSelection(context, AppThemeMode.DARK, palette)
            assertEquals(0, mismatches)
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            repository.awaitPendingWrites()
        }
    }
}
