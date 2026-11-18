package com.example

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import com.example.data.local.ThemePresets
import com.example.ui.theme.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 31, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TonalThemeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun appWrapperSharesExactMaterialAndGlassRolesAndCanReturnToSavedThemes() {
        val seed = TonalThemePreset.EMERALD_PULSE.seedColor
        val generated = ThemePaletteGenerator.generate(seed, false, PaletteStyle.EXPRESSIVE)
        var options by mutableStateOf<TonalThemeOptions?>(TonalThemeOptions(seed, PaletteStyle.EXPRESSIVE))
        var material: ColorScheme? = null
        var app: AppThemeColors? = null
        val legacy = ThemePresets.CleanDay.toCustomThemeState()
        compose.setContent {
            MyApplicationTheme(darkTheme = false, customThemeState = legacy, tonalTheme = options,
                animateTonalColors = false) {
                val colors = MaterialTheme.colorScheme
                val glass = LocalAppColors.current
                SideEffect { material = colors; app = glass }
                Box { }
            }
        }
        compose.waitUntil(5_000) { material?.primary == generated.primary }
        compose.runOnIdle {
            assertEquals(allRoles(generated), allRoles(material!!))
            assertEquals(generated.primary, app!!.primaryAccent)
            assertEquals(generated.primaryContainer, app!!.accentRoles!!.primaryContainer)
            assertEquals(generated.surfaceContainer, app!!.cardBackground)
            assertEquals(generated.surface, app!!.scaffoldBackground)
            assertEquals(generated.onSurface, app!!.textPrimary)
            assertEquals(generated.onPrimary, app!!.onPrimaryAccent)
            val bars = WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
            assertTrue(bars.isAppearanceLightStatusBars)
            options = null
        }
        val saved = resolveAppThemeColors(false, legacy)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Saved primary must restore after tonal options clear", saved.primaryAccent, app?.primaryAccent)
            assertEquals(saved, app)
        }
    }

    @Test fun systemColorsUseApi31GuardAndTakePrecedenceOverArtwork() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val seed = TonalThemePreset.WARM_AMBER_STUDIO.seedColor
        val seeded = ThemePaletteGenerator.generate(seed, false)
        var dark by mutableStateOf(false)
        var latest: ColorScheme? = null
        compose.setContent {
            TonalMaterialTheme(TonalThemeOptions(seed, useSystemDynamicColor = true, useAlbumArtwork = true),
                dark, bitmap, animateColors = false) {
                val current = MaterialTheme.colorScheme
                SideEffect { latest = current }
            }
        }
        val expected = if (Build.VERSION.SDK_INT >= 31)
            TonalContrast.protect(dynamicLightColorScheme(compose.activity)) else seeded
        compose.waitUntil(5_000) { latest?.primary == expected.primary }
        compose.runOnIdle { assertEquals(allRoles(expected), allRoles(latest!!)); dark = true }
        compose.waitForIdle()
        val darkExpected = if (Build.VERSION.SDK_INT >= 31)
            TonalContrast.protect(dynamicDarkColorScheme(compose.activity)) else ThemePaletteGenerator.generate(seed, true)
        compose.waitUntil(5_000) { latest?.primary == darkExpected.primary }
        compose.runOnIdle { assertEquals(allRoles(darkExpected), allRoles(latest!!)) }
        bitmap.recycle()
    }

    @Test @Config(sdk = [34])
    fun albumToggleUsesHarmonizedRolesThenRestoresTheBaseSeedAndKeepsTheBitmap() = runBlocking {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val seed = TonalThemePreset.MIDNIGHT_INDIGO.seedColor
        val base = ThemePaletteGenerator.generate(seed, false, PaletteStyle.VIBRANT)
        val harmonized = ThemePaletteGenerator.extractHarmonizedSeed(bitmap, seed)
        val album = ThemePaletteGenerator.generate(harmonized, false, PaletteStyle.VIBRANT)
        var options by mutableStateOf(TonalThemeOptions(seed, PaletteStyle.VIBRANT))
        var current: ColorScheme? = null
        var glass: AppThemeColors? = null
        try {
            compose.setContent {
                TonalMaterialTheme(options, false, bitmap, animateColors = false) {
                    val colors = MaterialTheme.colorScheme
                    val appColors = LocalAppColors.current
                    SideEffect { current = colors; glass = appColors }
                }
            }
            compose.waitUntil(5_000) { current?.primary == base.primary }
            compose.runOnIdle { options = options.copy(useAlbumArtwork = true) }
            compose.waitForIdle()
            compose.waitUntil(5_000) { current?.primary == album.primary }
            compose.runOnIdle {
                assertEquals(allRoles(album), allRoles(current!!))
                assertEquals(album.primary, glass!!.primaryAccent)
                options = options.copy(useAlbumArtwork = false)
            }
            compose.waitForIdle()
            compose.waitUntil(5_000) { current?.primary == base.primary }
            assertFalse(bitmap.isRecycled)
            assertEquals(android.graphics.Color.RED, bitmap.getPixel(0, 0))
        } finally { bitmap.recycle() }
    }

    @Test @Config(sdk = [34])
    fun everyAnimatedFramePreservesContentContrastAndActuallyInterpolatesColors() {
        val light = ThemePaletteGenerator.generate(TonalThemePreset.MIDNIGHT_INDIGO.seedColor, false)
        val dark = ThemePaletteGenerator.generate(TonalThemePreset.ROSE_QUARTZ.seedColor, true, PaletteStyle.VIBRANT)
        var target by mutableStateOf(light)
        var current: ColorScheme? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val scheme = animateTonalColorScheme(target)
            SideEffect { current = scheme }
        }
        compose.runOnIdle { target = dark }
        var sawIntermediate = false
        repeat(28) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                val frame = current!!
                if (frame.primary != light.primary && frame.primary != dark.primary) sawIntermediate = true
                pairs(frame).forEach { (foreground, background) ->
                    assertTrue("Intermediate frame keeps readable text", TonalContrast.ratio(foreground, background) >= 4.5f)
                }
            }
        }
        assertTrue("Changing themes must animate rather than jump", sawIntermediate)
        assertEquals(allRoles(dark), allRoles(current!!))
    }
}
