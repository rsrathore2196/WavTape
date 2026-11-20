package com.example

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.example.ui.components.playerCreditsTextColors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class PlayerCreditsContrastTest {
    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)

    @Test fun creditsChooseBlackOrWhiteAndKeepEveryTextRoleReadable() {
        val backgrounds = (0..255).map { Color(it, it, it) } + listOf(
            Color.Red, Color.Blue, Color.Green, Color.Yellow, Color.Magenta, Color.Cyan)
        backgrounds.forEach { background ->
            val colors = playerCreditsTextColors(background, background, true)
            assertTrue(colors.primary == Color.Black || colors.primary == Color.White)
            assertTrue("Primary on $background", contrast(colors.primary, background) >= 4.5f)
            assertTrue("Detail labels on $background", contrast(colors.muted, background) >= 4.5f)
            val headingSurface = Color.White.copy(alpha = .12f).compositeOver(background)
            assertTrue("Heading on $background", contrast(colors.heading, headingSurface) >= 4.5f)
        }
        assertEquals(Color.Black, playerCreditsTextColors(Color.White, Color.White, true).primary)
        assertEquals(Color.White, playerCreditsTextColors(Color.Black, Color.Black, true).primary)
    }

    @Test fun disablingAlbumBackgroundKeepsWhiteTextOnTheActualNeutralBackdrop() {
        val colors = playerCreditsTextColors(Color.White, Color.Yellow, false)
        assertEquals(Color.White, colors.primary)
        assertEquals(Color.White, colors.heading)
        assertTrue(contrast(colors.muted, Color(0xff101010)) >= 4.5f)
    }

    @Test fun artworkPaletteChangesCanSelectBothTextColorsWithoutChangingThePalette() {
        val light = Color(0xffe2e2e2)
        val dark = Color(0xff202020)
        assertEquals(Color.Black, playerCreditsTextColors(light, Color.White, true).primary)
        assertEquals(Color.White, playerCreditsTextColors(dark, Color.Black, true).primary)
        assertEquals(Color(0xffe2e2e2), light)
        assertEquals(Color(0xff202020), dark)
    }

    @Test fun pureWhiteAndPureDarkBackdropsAdaptPillAndHeadingContrast() {
        val onWhite = playerCreditsTextColors(Color.White, Color.White, true)
        assertEquals(Color.Black, onWhite.primary)
        assertEquals(Color.Black, onWhite.heading)
        assertTrue(onWhite.pillBackground.red == 0f && onWhite.pillBackground.alpha > 0f)
        val whitePillSurface = onWhite.pillBackground.compositeOver(Color.White)
        assertTrue(contrast(onWhite.heading, whitePillSurface) >= 7.0f)
        assertTrue(contrast(onWhite.muted, Color.White) >= 7.0f)

        val onBlack = playerCreditsTextColors(Color.Black, Color.Black, true)
        assertEquals(Color.White, onBlack.primary)
        assertEquals(Color.White, onBlack.heading)
        assertTrue(onBlack.pillBackground.red == 1f && onBlack.pillBackground.alpha > 0f)
        val blackPillSurface = onBlack.pillBackground.compositeOver(Color.Black)
        assertTrue(contrast(onBlack.heading, blackPillSurface) >= 7.0f)
        assertTrue(contrast(onBlack.muted, Color.Black) >= 7.0f)
    }

    @Test fun brightArtworkColorFieldUsesWhiteCreditsMatchingRenderedFieldPixels() {
        // Pure white, pure black, and bright pastel covers all render through ArtworkColorField (lum <= 0.15).
        for (fill in listOf(0xffffffff.toInt(), 0xff000000.toInt(), 0xffe8f1ff.toInt())) {
            val pixels = IntArray(120 * 120) { fill }
            val extracted = com.example.ui.util.extractPlayerArtworkColors(pixels, 120, 120)!!
            val samples = com.example.ui.components.artworkColorFieldBackdropSamples(extracted.colorField!!)
            val colors = playerCreditsTextColors(extracted.topEdgeColor, extracted.bottomEdgeColor, true, samples)
            assertEquals("Credits on rendered ArtworkColorField must stay white for 0x${Integer.toHexString(fill)}", Color.White, colors.primary)
            assertTrue(colors.heading == Color.White || colors.heading == Color.Black)
            if (fill == 0xffffffff.toInt() || fill == 0xff000000.toInt()) {
                assertEquals(Color.White, colors.heading)
            }
            samples.forEach { surface ->
                assertTrue("Primary contrast on $surface", contrast(colors.primary, surface) >= 4.5f)
                assertTrue("Muted contrast on $surface", contrast(colors.muted, surface) >= 4.5f)
                assertTrue("Heading contrast on $surface", contrast(colors.heading, colors.pillBackground.compositeOver(surface)) >= 4.5f)
            }
        }
    }
}
