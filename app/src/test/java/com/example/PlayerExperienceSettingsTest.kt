package com.example

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.OtherSettingsPreferences
import com.example.data.local.PlayerEffect
import com.example.data.local.PlayerExperienceSettings
import com.example.data.local.ReactiveSettingsRepository
import com.example.data.local.ThemePresets
import com.example.ui.theme.resolveAppThemeColors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class PlayerExperienceSettingsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() = runBlocking { withContext(Dispatchers.IO) {
        app.getSharedPreferences("xtreme_other_settings", 0).edit().clear().commit()
    }; Unit }

    private fun client(repository: ReactiveSettingsRepository) = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = repository.preferences(name)
    }

    @Test fun crossfadeAndHighContrastAreOffByDefault() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            assertEquals(PlayerExperienceSettings(true, false, false), OtherSettingsPreferences.playerExperienceSettings(context))
            assertEquals(PlayerExperienceSettings(true, false, false),
                withTimeout(2_000) { OtherSettingsPreferences.observePlayerExperience(context).first() })
        }
    }

    @Test fun legacyEffectIsObservedAndSurvivesOpeningANewSettingsRepository() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            assertEquals(PlayerEffect.DEFAULT, OtherSettingsPreferences.playerExperienceSettings(context).playerEffect)
            val observed = async { withTimeout(3_000) {
                OtherSettingsPreferences.observePlayerExperience(context).first { it.playerEffect == PlayerEffect.LEGACY }
            } }
            OtherSettingsPreferences.setPlayerEffect(context, PlayerEffect.LEGACY)
            assertEquals(PlayerEffect.LEGACY, observed.await().playerEffect)
            repository.awaitPendingWrites()
        }
        ReactiveSettingsRepository(app).use { reopened ->
            reopened.preload()
            val current = OtherSettingsPreferences.playerExperienceSettings(client(reopened))
            assertEquals(PlayerExperienceSettings(playerEffect = PlayerEffect.LEGACY), current)
        }
    }
    @Test fun switchingGlowOffBeforePreloadCannotBeOverwrittenAndSurvivesReopening() = runBlocking {
        withContext(Dispatchers.IO) { app.getSharedPreferences("xtreme_other_settings", 0).edit()
            .putBoolean("player_dynamic_album_glow", true).commit() }
        ReactiveSettingsRepository(app).use { repository ->
            val context = client(repository)
            OtherSettingsPreferences.setDynamicAlbumGlow(context, false)
            repository.preload()
            repository.awaitPendingWrites()
            assertFalse(OtherSettingsPreferences.playerExperienceSettings(context).dynamicAlbumGlow)
        }
        ReactiveSettingsRepository(app).use { reopened ->
            reopened.preload()
            assertFalse(OtherSettingsPreferences.playerExperienceSettings(client(reopened)).dynamicAlbumGlow)
        }
    }
    @Test fun rapidIndependentChoicesPersistTheirLastValuesWithoutEnablingOtherOptions() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            OtherSettingsPreferences.setCrossfade(context, true)
            OtherSettingsPreferences.setHighContrastTypography(context, true)
            OtherSettingsPreferences.setDynamicAlbumGlow(context, false)
            OtherSettingsPreferences.setCrossfade(context, false)
            repository.awaitPendingWrites()
            assertEquals(PlayerExperienceSettings(false, true, false), OtherSettingsPreferences.playerExperienceSettings(context))
        }
        ReactiveSettingsRepository(app).use { reopened ->
            reopened.preload()
            assertEquals(PlayerExperienceSettings(false, true, false), OtherSettingsPreferences.playerExperienceSettings(client(reopened)))
        }
    }
    @Test fun highContrastActuallyImprovesTextWithoutChangingPresetSurfaces() {
        for (preset in ThemePresets.allPresets.flatMap { preset ->
            listOf(ThemePresets.forMode(preset, false), ThemePresets.forMode(preset, true))
        }) {
            val normal = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState())
            val enabled = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState(), highContrastTypography = true)
            assertEquals(normal.scaffoldBackground, enabled.scaffoldBackground)
            assertEquals(normal.cardBackground, enabled.cardBackground)
            assertEquals(normal.cardBackgroundElevated, enabled.cardBackgroundElevated)
            assertEquals(normal.primaryAccent, enabled.primaryAccent)
            assertFalse(normal.highContrastTypography)
            assertTrue(enabled.highContrastTypography)
            for (text in listOf(enabled.textPrimary, enabled.textSecondary, enabled.textMuted)) {
                for (surface in listOf(enabled.scaffoldBackground, enabled.cardBackground, enabled.cardBackgroundElevated)) {
                    assertTrue("${preset.name} (${if (preset.isDark) "dark" else "light"}) contrast",
                        ColorUtils.calculateContrast(text.toArgb(), surface.toArgb()) >= 7.0)
                }
            }
        }
    }

    @Test fun spatialAndSilenceAreOffByDefaultAndPersistIndependently() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            assertFalse(OtherSettingsPreferences.playerExperienceSettings(context).spatialAudio)
            assertFalse(OtherSettingsPreferences.playerExperienceSettings(context).skipSilence)
            OtherSettingsPreferences.setSpatialAudio(context, true)
            assertTrue(OtherSettingsPreferences.playerExperienceSettings(context).spatialAudio)
            assertFalse(OtherSettingsPreferences.playerExperienceSettings(context).skipSilence)
            OtherSettingsPreferences.setSkipSilence(context, true)
            val observed = withTimeout(2_000) { OtherSettingsPreferences.observePlayerExperience(context).first() }
            assertTrue(observed.spatialAudio); assertTrue(observed.skipSilence)
            assertFalse(observed.crossfade)
            repository.awaitPendingWrites()
        }
        ReactiveSettingsRepository(app).use { reopened ->
            reopened.preload()
            val context = client(reopened)
            assertTrue(OtherSettingsPreferences.playerExperienceSettings(context).spatialAudio)
            assertTrue(OtherSettingsPreferences.playerExperienceSettings(context).skipSilence)
            OtherSettingsPreferences.setSpatialAudio(context, false)
            assertTrue(OtherSettingsPreferences.playerExperienceSettings(context).skipSilence)
            reopened.awaitPendingWrites()
        }
    }
    @Test fun outputPrecisionPersistsAcrossRecreationAndDoesNotChangeDspOrSilenceSettings() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            assertEquals(com.example.playback.OutputPrecision.PCM_16,
                OtherSettingsPreferences.playerExperienceSettings(context).outputPrecision)
            OtherSettingsPreferences.setOutputPrecision(context, com.example.playback.OutputPrecision.FLOAT_32)
            val observed = withTimeout(2_000) { OtherSettingsPreferences.observePlayerExperience(context).first() }
            assertEquals(com.example.playback.OutputPrecision.FLOAT_32, observed.outputPrecision)
            assertFalse(observed.spatialAudio); assertFalse(observed.skipSilence); assertFalse(observed.crossfade)
            repository.awaitPendingWrites()
        }
        ReactiveSettingsRepository(app).use { reopened ->
            reopened.preload()
            val context = client(reopened)
            assertEquals(com.example.playback.OutputPrecision.FLOAT_32,
                OtherSettingsPreferences.playerExperienceSettings(context).outputPrecision)
            OtherSettingsPreferences.setOutputPrecision(context, com.example.playback.OutputPrecision.PCM_16)
            reopened.awaitPendingWrites()
            assertEquals(com.example.playback.OutputPrecision.PCM_16,
                OtherSettingsPreferences.playerExperienceSettings(context).outputPrecision)
        }
    }
    @Test fun lyricsAwakeSurvivesRecreation() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            assertFalse(OtherSettingsPreferences.playbackIntegrations(context).keepLyricsAwake)
            OtherSettingsPreferences.setKeepLyricsAwake(context, true)
            repository.awaitPendingWrites()
        }
        ReactiveSettingsRepository(app).use { reopened ->
            reopened.preload()
            assertTrue(OtherSettingsPreferences.playbackIntegrations(client(reopened)).keepLyricsAwake)
        }
    }
    @Test fun fullScreenAlbumArtIsOnByDefaultAndPersists() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            assertTrue(OtherSettingsPreferences.playerExperienceSettings(context).fullScreenAlbumArt)
            OtherSettingsPreferences.setFullScreenAlbumArt(context, false)
            assertFalse(OtherSettingsPreferences.playerExperienceSettings(context).fullScreenAlbumArt)
            val observed = withTimeout(2_000) { OtherSettingsPreferences.observePlayerExperience(context).first() }
            assertFalse(observed.fullScreenAlbumArt)
            repository.awaitPendingWrites()
        }
        ReactiveSettingsRepository(app).use { reopened ->
            reopened.preload()
            val context = client(reopened)
            assertFalse(OtherSettingsPreferences.playerExperienceSettings(context).fullScreenAlbumArt)
            OtherSettingsPreferences.setFullScreenAlbumArt(context, true)
            assertTrue(OtherSettingsPreferences.playerExperienceSettings(context).fullScreenAlbumArt)
            reopened.awaitPendingWrites()
        }
    }
}
