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

    @Test fun lightAndDarkSelectionsSurviveModeChangesAndRepositoryRecreation() = runBlocking {
        val dark = ThemePresets.EmeraldNebula.toCustomThemeState()
        val light = ThemePresets.CherryBlossom.toCustomThemeState()
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            ThemePreferences.saveThemeSelection(context, AppThemeMode.DARK, dark)
            assertEquals(ThemePresets.CleanDay.toCustomThemeState(), ThemePreferences.setThemeMode(context, AppThemeMode.LIGHT))
            ThemePreferences.saveThemeSelection(context, AppThemeMode.LIGHT, light)
            assertEquals(dark, ThemePreferences.setThemeMode(context, AppThemeMode.DARK))
            assertEquals(light, ThemePreferences.setThemeMode(context, AppThemeMode.LIGHT))
            repository.awaitPendingWrites()
        }
        ReactiveSettingsRepository(app).use { reopened ->
            reopened.preload()
            val context = client(reopened)
            assertEquals(light, ThemePreferences.getCustomThemeState(context))
            assertEquals(dark, ThemePreferences.setThemeMode(context, AppThemeMode.DARK))
            reopened.awaitPendingWrites()
        }
    }

    @Test fun systemModeUsesTheSavedPaletteForEachBrightnessWithoutOverwritingEither() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val day = client(repository)
            val night = client(repository, true)
            val dark = ThemePresets.VelvetNoir.toCustomThemeState()
            val light = ThemePresets.SageMist.toCustomThemeState()
            ThemePreferences.saveThemeSelection(day, AppThemeMode.DARK, dark)
            ThemePreferences.saveThemeSelection(day, AppThemeMode.LIGHT, light)
            ThemePreferences.setThemeMode(day, AppThemeMode.SYSTEM)
            assertEquals(light, ThemePreferences.getCustomThemeState(day))
            assertEquals(dark, ThemePreferences.getCustomThemeState(night))
            assertEquals(light, ThemePreferences.getCustomThemeState(day))
            assertEquals(AppThemeMode.SYSTEM, ThemePreferences.getThemeMode(night))
            repository.awaitPendingWrites()
        }
    }

    @Test fun unchangedLegacyPresetsUpgradeButEditedAndImportedPalettesKeepTheirValues() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            for (preset in ThemePresets.allPresets) {
                val legacy = checkNotNull(ThemePresets.legacyPreset(preset.id, preset.name)).toCustomThemeState()
                ThemePreferences.saveThemeSelection(context, if (preset.isDark) AppThemeMode.DARK else AppThemeMode.LIGHT, legacy)
                // Old backups predate both per-mode selections and palette versioning.
                repository.preferences("xtreme_theme_prefs").edit().remove("palette_version").apply()
                assertEquals(preset.toCustomThemeState(), ThemePreferences.getCustomThemeState(context))
                val edited = legacy.copy(canvasColorHex = "#121218", bgGradientColorsHex = listOf("#121218", "#121218"))
                ThemePreferences.saveCustomThemeState(context, edited)
                assertEquals(edited, ThemePreferences.getCustomThemeState(context))
                assertEquals(edited, ThemePresets.upgradeLegacy(edited))
            }
            val imported = ThemePresets.MinimalIvory.toCustomThemeState().copy(presetId = "imported-palette",
                currentThemeName = "Imported", canvasColorHex = "#FFF9F0")
            ThemePreferences.saveThemeSelection(context, AppThemeMode.LIGHT, imported)
            assertEquals(imported, ThemePreferences.getCustomThemeState(context))
            assertNull(ThemePresets.findByName(""))
            repository.awaitPendingWrites()
        }
    }

    @Test fun previousNeutralPresetsUpgradeWhileEditedAccentsSurviveReload() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            for (preset in ThemePresets.allPresets) {
                val previous = checkNotNull(ThemePresets.legacyPreset(preset.id, preset.name, version = 2)).toCustomThemeState()
                val mode = if (preset.isDark) AppThemeMode.DARK else AppThemeMode.LIGHT
                ThemePreferences.saveThemeSelection(context, mode, previous)
                repository.preferences("xtreme_theme_prefs").edit().putInt("palette_version", 2).apply()
                assertEquals(preset.toCustomThemeState(), ThemePreferences.getCustomThemeState(context))
                val edited = previous.copy(accentColorHex = "#1257E9")
                ThemePreferences.saveThemeSelection(context, mode, edited)
                assertEquals(edited, ThemePreferences.getCustomThemeState(context))
                assertEquals(edited, ThemePresets.upgradeLegacy(edited))
            }
            repository.awaitPendingWrites()
        }
    }

    @Test fun previousAccentPresetsUpgradeWhileCustomColorsAndSavedModesSurviveReload() = runBlocking {
        val dark = ThemePresets.StudioNight
        val light = ThemePresets.CleanDay
        val previousDark = checkNotNull(ThemePresets.legacyPreset(dark.id, dark.name, version = 6)).toCustomThemeState()
        val previousLight = checkNotNull(ThemePresets.legacyPreset(light.id, light.name, version = 6)).toCustomThemeState()
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            val preferences = repository.preferences("xtreme_theme_prefs")
            for (version in listOf(3, 4, 5, 6)) for (preset in ThemePresets.allPresets) {
                val previous = checkNotNull(ThemePresets.legacyPreset(preset.id, preset.name, version))
                assertEquals(previous.id, preset.id)
                assertEquals(previous.name, preset.name)
                assertEquals(previous.isDark, preset.isDark)
                assertEquals(previous.isAmoled, preset.isAmoled)
                val mode = if (preset.isDark) AppThemeMode.DARK else AppThemeMode.LIGHT
                ThemePreferences.saveThemeSelection(context, mode, previous.toCustomThemeState())
                preferences.edit().putInt("palette_version", version).apply()
                assertEquals(preset.toCustomThemeState(), ThemePreferences.getCustomThemeState(context))
                assertEquals(preset.toCustomThemeState(), ThemePresets.upgradeLegacy(previous.toCustomThemeState()))
                assertNotNull(ThemePresets.tonalPalette(ThemePreferences.getCustomThemeState(context)))

                val edited = previous.toCustomThemeState().copy(secondaryAccentHex = "#F07DE1")
                ThemePreferences.saveThemeSelection(context, mode, edited)
                preferences.edit().putInt("palette_version", version).apply()
                assertEquals(edited, ThemePreferences.getCustomThemeState(context))
                assertEquals(edited, ThemePresets.upgradeLegacy(edited))
                assertNull(ThemePresets.tonalPalette(edited))
            }
            ThemePreferences.saveThemeSelection(context, AppThemeMode.DARK, previousDark)
            val previousDarkValues = preferences.all.filterKeys { it.startsWith("saved_dark_") }
            ThemePreferences.saveThemeSelection(context, AppThemeMode.LIGHT, previousLight)
            // Saving the second mode upgrades the active preset before writing its history.
            // Restore the complete old snapshot to model a real installation with both modes on v6.
            val editor = preferences.edit()
            previousDarkValues.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    else -> error("Unexpected theme preference value for $key")
                }
            }
            editor.putInt("palette_version", 6).putInt("saved_dark_palette_version", 6)
                .putInt("saved_light_palette_version", 6).apply()
            repository.awaitPendingWrites()
        }
        ReactiveSettingsRepository(app).use { reopened ->
            reopened.preload()
            val context = client(reopened)
            assertEquals(light.toCustomThemeState(), ThemePreferences.getCustomThemeState(context))
            assertEquals(dark.toCustomThemeState(), ThemePreferences.setThemeMode(context, AppThemeMode.DARK))
            assertEquals(light.toCustomThemeState(), ThemePreferences.setThemeMode(context, AppThemeMode.LIGHT))
            reopened.awaitPendingWrites()
        }
    }

    @Test fun versionFiveSnapshotsRetainTheirPublishedValuesForMigration() {
        for (preset in ThemePresets.allPresets) {
            val previous = checkNotNull(ThemePresets.legacyPreset(preset.id, preset.name, version = 5))
            assertEquals("Vibrant", previous.accentShade)
            assertEquals(if (preset.isDark) {
                if (preset.isAmoled) "#000000" else "#0B0B0B"
            } else "#FFFFFF", previous.canvasColorHex)
            assertEquals(if (preset.isDark) {
                if (preset.isAmoled) "#000000" else "#171717"
            } else "#F5F5F5", previous.cardColorHex)
        }
        val dark = checkNotNull(ThemePresets.legacyPreset(ThemePresets.StudioNight.id, "", 5))
        assertEquals("Azure Pulse", dark.accentName)
        assertEquals("#4099FF", dark.accentColorHex)
        assertEquals("#66ADFF", dark.secondaryAccentHex)
        val light = checkNotNull(ThemePresets.legacyPreset(ThemePresets.CleanDay.id, "", 5))
        assertEquals("Cobalt Rush", light.accentName)
        assertEquals("#183FED", light.accentColorHex)
        assertEquals("#1332BE", light.secondaryAccentHex)
        val amoled = checkNotNull(ThemePresets.legacyPreset(ThemePresets.AmoledPureBlack.id, "", 5))
        assertEquals("#FFFFFF", amoled.accentColorHex)
        assertEquals("#E0E0E0", amoled.secondaryAccentHex)
    }

    @Test fun editedVersionFiveAndImportedPalettesKeepTheirValuesAndModeHistoryAfterRecreation() = runBlocking {
        val previous = checkNotNull(ThemePresets.legacyPreset(
            ThemePresets.StudioNight.id, ThemePresets.StudioNight.name, version = 5)).toCustomThemeState()
        val editedDark = previous.copy(accentColorHex = "#804099FF", secondaryAccentHex = "#40FFAA88",
            canvasColorHex = "#121218", cardColorHex = "#191820",
            bgGradientColorsHex = listOf("#121218", "#16121A"))
        val importedLight = ThemePresets.CleanDay.toCustomThemeState().copy(
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
            val palette = ThemePresets.CyberpunkNeon.toCustomThemeState()
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
