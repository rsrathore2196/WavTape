package com.example

import android.app.Application
import android.graphics.Bitmap
import com.example.ui.theme.*
import com.example.ui.theme.colorutilities.blend.Blend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TonalArtworkSeedTest {
    @Test fun sampledArtworkIsHarmonizedWithoutConsumingOrChangingTheCallerBitmap() = runBlocking {
        val source = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val base = TonalThemePreset.MIDNIGHT_INDIGO.seedColor
        try {
            val extracted = ThemePaletteGenerator.extractHarmonizedSeed(source, base)
            // Palette quantizes 8-bit RGB to 5-bit bins; solid red becomes #F80000.
            assertEquals(Blend.harmonize(0xFFF80000.toInt(), base), extracted)
            assertNotEquals(base, extracted)
            assertFalse(source.isRecycled)
            assertEquals(android.graphics.Color.RED, source.getPixel(500, 250))
            for (dark in listOf(false, true)) pairs(ThemePaletteGenerator.generate(extracted, dark)).forEach { (a, b) ->
                assertTrue(TonalContrast.ratio(a, b) >= 4.5f)
            }
        } finally { source.recycle() }
    }

    @Test fun missingBlankOrRecycledArtworkKeepsTheOpaqueBaseTheme() = runBlocking {
        val base = TonalThemePreset.EMERALD_PULSE.seedColor
        assertEquals(base, ThemePaletteGenerator.extractHarmonizedSeed(null, base))
        val empty = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try { assertEquals(base, ThemePaletteGenerator.extractHarmonizedSeed(empty, base)) }
        finally { empty.recycle() }
        assertEquals(base, ThemePaletteGenerator.extractHarmonizedSeed(empty, base))
        assertEquals(base, ThemePaletteGenerator.extractHarmonizedSeed(null, base and 0xFFFFFF))
    }
}
