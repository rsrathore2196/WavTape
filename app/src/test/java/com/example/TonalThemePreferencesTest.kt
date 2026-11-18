package com.example

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
class TonalThemePreferencesTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private val options = TonalThemeOptions(TonalThemePreset.ROSE_QUARTZ.seedColor, PaletteStyle.FIDELITY,
        useSystemDynamicColor = true, useAlbumArtwork = true)

    @Before fun clear() { app.getSharedPreferences("xtreme_theme_prefs", 0).edit().clear().commit() }
    private fun client(repository: ReactiveSettingsRepository) = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = repository.preferences(name)
    }

    @Test fun optionsModeAndStartupSnapshotSurviveRepositoryRecreation() = runBlocking {
        ReactiveSettingsRepository(app).use { repo ->
            repo.preload()
            val context = client(repo)
            ThemePreferences.saveTonalTheme(context, options, AppThemeMode.DARK)
            assertEquals(options, ThemePreferences.getTonalTheme(context))
            assertEquals(AppThemeMode.DARK, ThemePreferences.getThemeMode(context))
            repo.awaitPendingWrites()
        }
        ReactiveSettingsRepository(app).use { repo ->
            repo.preload()
            val context = client(repo)
            val settings = withContext(Dispatchers.IO) { StartupSettings.read(context) }
            assertEquals(options, settings.tonalTheme)
            assertEquals(AppThemeMode.DARK, settings.themeMode)
            repo.awaitPendingWrites()
        }
    }

    @Test fun modeControlsPreserveTonalSelectionAndChoosingAnExistingPaletteSwitchesBack() {
        val previous = ThemePresets.EmeraldNebula.toCustomThemeState()
        ThemePreferences.saveThemeSelection(app, AppThemeMode.DARK, previous)
        ThemePreferences.saveTonalTheme(app, options, AppThemeMode.DARK)
        ThemePreferences.setThemeMode(app, AppThemeMode.LIGHT)
        assertEquals(options, ThemePreferences.getTonalTheme(app))
        ThemePreferences.setThemeMode(app, AppThemeMode.SYSTEM)
        assertEquals(options, ThemePreferences.getTonalTheme(app))
        assertEquals(previous, ThemePreferences.getThemeForDarkMode(app, true))
        ThemePreferences.saveThemeSelection(app, AppThemeMode.DARK, previous)
        assertNull(ThemePreferences.getTonalTheme(app))
        assertEquals(previous, ThemePreferences.getCustomThemeState(app))
        ThemePreferences.saveTonalTheme(app, options, AppThemeMode.DARK)
        ThemePreferences.saveCustomThemeState(app, previous.copy(presetId = "", currentThemeName = "Custom"))
        assertNull(ThemePreferences.getTonalTheme(app))
    }

    @Test fun themeBackupsPreserveOptionsAndOlderThemeGroupsDeactivateThem() = runBlocking {
        ThemePreferences.saveThemeSelection(app, AppThemeMode.DARK, ThemePresets.StudioNight.toCustomThemeState())
        val legacy = BackupSettings.read(app, setOf("xtreme_theme_prefs"))
        ThemePreferences.saveTonalTheme(app, options, AppThemeMode.LIGHT)
        val encoded = BackupSettings.encode(BackupSettings.read(app, setOf("xtreme_theme_prefs")))
        val decoded = BackupSettings.decode(encoded)
        app.getSharedPreferences("xtreme_theme_prefs", 0).edit().clear().commit()
        withContext(Dispatchers.IO) { BackupSettings.write(app, decoded) }
        assertEquals(options, ThemePreferences.getTonalTheme(app))
        assertEquals(AppThemeMode.LIGHT, ThemePreferences.getThemeMode(app))
        withContext(Dispatchers.IO) { BackupSettings.write(app, legacy) }
        assertNull(ThemePreferences.getTonalTheme(app))
        assertEquals(AppThemeMode.DARK, ThemePreferences.getThemeMode(app))
    }

    @Test fun coldStartupWarmsASavedSeedWithoutChangingThePreviousPalette() = runBlocking {
        val previous = ThemePreferences.getCustomThemeState(app)
        val saved = options.copy(useSystemDynamicColor = false, useAlbumArtwork = false)
        ThemePreferences.saveTonalTheme(app, saved, AppThemeMode.DARK)
        StartupSettingsCache(app).use { cache ->
            val loaded = cache.load()
            assertEquals(saved, loaded.tonalTheme)
            assertNotNull(ThemePaletteGenerator.cached(saved.seedColor, true, saved.style))
        }
        assertEquals(previous, ThemePreferences.getThemeForDarkMode(app, previous.isPresetDark))
    }

    @Test fun futureStyleNamesFallBackSafelyWhileOlderInstallsRemainUnchanged() {
        assertNull(ThemePreferences.getTonalTheme(app))
        ThemePreferences.saveTonalTheme(app, options, AppThemeMode.DARK)
        app.getSharedPreferences("xtreme_theme_prefs", 0).edit().putString("tonal_style", "FUTURE_STYLE").commit()
        assertEquals(PaletteStyle.TONAL_SPOT, ThemePreferences.getTonalTheme(app)!!.style)
    }
}
