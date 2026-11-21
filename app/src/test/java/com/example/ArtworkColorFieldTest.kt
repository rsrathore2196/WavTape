package com.example

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.ui.util.extractPlayerArtworkColors
import org.junit.Assert.*
import org.junit.Test

class ArtworkColorFieldTest {
    @Test fun horizontalArtworkColorsRemainVisibleEvenWhenTheEdgeAveragesAreIdentical() {
        val pixels = IntArray(120 * 120) { if (it % 120 < 60) 0xffd02030.toInt() else 0xff2040c0.toInt() }
        val field = extractPlayerArtworkColors(pixels, 120, 120)!!.colorField!!
        val left = Color(field.pixels[field.dimension * 20 + 4])
        val right = Color(field.pixels[field.dimension * 20 + 35])
        assertTrue(left.red > left.blue * 2)
        assertTrue(right.blue > right.red * 2)
    }

    @Test fun nearWhiteRegionsInheritTheAvailableSubjectColorWithoutBecomingWhite() {
        val pixels = IntArray(120 * 120) { -1 }
        for (y in 40 until 80) for (x in 40 until 80) pixels[y * 120 + x] = 0xff1838bc.toInt()
        val field = extractPlayerArtworkColors(pixels, 120, 120)!!.colorField!!
        for (pixel in field.pixels) {
            val color = Color(pixel)
            assertTrue(color.blue > color.red * 2)
            assertTrue(color.luminance() < .18f)
        }
    }

    @Test fun darkArtworkStaysDarkWhileItsInteriorColorIsPreserved() {
        val pixels = IntArray(120 * 120) { 0xff000000.toInt() }
        for (y in 48 until 72) for (x in 48 until 72) pixels[y * 120 + x] = 0xffd02030.toInt()
        val field = extractPlayerArtworkColors(pixels, 120, 120)!!.colorField!!
        assertEquals(Color.Black, Color(field.pixels.first()))
        assertTrue(Color(field.pixels[field.dimension * 20 + 20]).red > .25f)
    }

    @Test fun texturesAreSmallOpaqueDeterministicAndReadableBehindWhiteControls() {
        val pixels = IntArray(120 * 120) { index ->
            val r = (index * 31) and 255
            val g = (index / 120 * 17) and 255
            val b = index and 255
            (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        val first = extractPlayerArtworkColors(pixels, 120, 120)!!.colorField!!
        val second = extractPlayerArtworkColors(pixels, 120, 120)!!.colorField!!
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertTrue(first.pixels.size <= 4096)
        for (pixel in first.pixels) {
            val color = Color(pixel)
            assertEquals(1f, color.alpha, 0f)
            assertTrue("Background must support white controls: $color", color.luminance() <= .18f)
        }
        for (pixel in first.bottomPixels) {
            val color = Color(pixel)
            assertEquals(1f, color.alpha, 0f)
            assertTrue("Bottom field must support white controls: $color", color.luminance() <= .18f)
        }
    }

    @Test fun bottomFieldMergesSeamlesslyWithCoverBottomRowAndAdaptsMultipleArtworkColors() {
        // Multi-color album art (warm red-orange left, rich teal-blue right) with a dark bottom edge.
        val w = 120
        val h = 120
        val pixels = IntArray(w * h) { index ->
            val x = index % w
            val y = index / w
            when {
                y >= h - 10 -> 0xff0d0d12.toInt()
                x < w / 2 -> 0xffd84315.toInt()
                else -> 0xff00838f.toInt()
            }
        }
        val field = extractPlayerArtworkColors(pixels, w, h)!!.colorField!!
        val dim = field.dimension
        assertEquals(dim * dim, field.bottomPixels.size)

        // Row 0 of bottomPixels must be bit-for-bit identical to the final row of pixels -> zero seam.
        for (x in 0 until dim) {
            assertEquals(
                "Bottom field top row must match cover field bottom row at x=$x",
                field.pixels[(dim - 1) * dim + x],
                field.bottomPixels[x]
            )
        }

        // Lower region of bottomPixels must bloom multiple distinct hues extracted from the artwork
        // rather than repeating only the dark bottom edge row vertically.
        val lowerRow = dim * (dim * 2 / 3)
        val lowerLeft = Color(field.bottomPixels[lowerRow + 4])
        val lowerRight = Color(field.bottomPixels[lowerRow + dim - 5])
        assertTrue("Left bottom field must carry warm red artwork color: $lowerLeft", lowerLeft.red > lowerLeft.blue * 1.4f)
        assertTrue("Right bottom field must carry cool teal/blue artwork color: $lowerRight", lowerRight.blue > lowerRight.red * 1.4f)
        assertNotEquals("Bottom field must transition beyond its top seam row", field.bottomPixels[4], field.bottomPixels[lowerRow + 4])
    }
}
