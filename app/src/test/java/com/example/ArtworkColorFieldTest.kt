package com.example

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.ui.util.extractPlayerArtworkColors
import org.junit.Assert.*
import org.junit.Test

class ArtworkColorFieldTest {
    @Test fun bothReflectionsKeepTheOriginalShadowAndMidtoneColors() {
        val warmShadow = 0xff402028.toInt()
        val blueMidtone = 0xff426192.toInt()
        val pixels = IntArray(64 * 64) { if (it / 64 < 32) warmShadow else blueMidtone }
        val field = extractPlayerArtworkColors(pixels, 64, 64)!!.colorField!!

        for (texture in listOf(field.pixels, field.lowerReflection(field.height))) {
            assertEquals(warmShadow, texture[field.width * 16])
            assertEquals(blueMidtone, texture[field.width * 48])
        }
    }

    @Test fun horizontalArtworkColorsRemainVisibleEvenWhenTheEdgeAveragesAreIdentical() {
        val pixels = IntArray(120 * 120) { if (it % 120 < 60) 0xffd02030.toInt() else 0xff2040c0.toInt() }
        val field = extractPlayerArtworkColors(pixels, 120, 120)!!.colorField!!
        val left = Color(field.pixels[field.width * (field.height / 2) + 4])
        val right = Color(field.pixels[field.width * (field.height / 2) + field.width - 5])
        assertTrue(left.red > left.blue * 2)
        assertTrue(right.blue > right.red * 2)
    }

    @Test fun brightRegionsKeepTheirNeutralHueAndTheSubjectStillShowsThrough() {
        val pixels = IntArray(120 * 120) { -1 }
        for (y in 40 until 80) for (x in 40 until 80) pixels[y * 120 + x] = 0xff1838bc.toInt()
        val field = extractPlayerArtworkColors(pixels, 120, 120)!!.colorField!!
        for (texture in listOf(field.pixels, field.lowerReflection(field.height))) {
            val corner = Color(texture.first())
            val subject = Color(texture[field.width * (field.height / 2) + field.width / 2])
            assertEquals(corner.red, corner.blue, 0f)
            assertTrue(1.05f / (corner.luminance() + .05f) >= 4.5f)
            assertTrue(subject.blue > subject.red * 2)
        }
    }

    @Test fun darkArtworkStaysDarkWhileItsInteriorColorIsPreserved() {
        val pixels = IntArray(120 * 120) { 0xff000000.toInt() }
        for (y in 48 until 72) for (x in 48 until 72) pixels[y * 120 + x] = 0xffd02030.toInt()
        val field = extractPlayerArtworkColors(pixels, 120, 120)!!.colorField!!
        assertEquals(Color.Black, Color(field.pixels.first()))
        assertTrue(Color(field.pixels[field.width * (field.height / 2) + field.width / 2]).red > .25f)
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
    }

    @Test fun blurPreservesTheShapeAndTheSpatialOrderOfRealArtwork() {
        val width = 120
        val height = 60
        val pixels = IntArray(width * height) { index ->
            if (index / width < height / 2) 0xffff0000.toInt() else 0xff0000ff.toInt()
        }
        val field = extractPlayerArtworkColors(pixels, width, height)!!.colorField!!
        assertEquals(64, field.width)
        assertEquals(32, field.height)
        val upper = Color(field.pixels.first())
        val center = Color(field.pixels[field.width * (field.height / 2)])
        val lower = Color(field.pixels.last())
        assertTrue(upper.red > lower.red && lower.blue > upper.blue)
        assertTrue(center.red > 0f && center.blue > 0f)
    }

    @Test fun lowerReflectionRetainsMoreDetailAndMatchesTheVisibleEdgeForBothCrops() {
        val pixels = IntArray(64 * 64) { index ->
            if (index % 64 in 29..35) 0xffffffff.toInt() else 0xff000000.toInt()
        }
        val field = extractPlayerArtworkColors(pixels, 64, 64)!!.colorField!!
        for (cropHeight in listOf(64, 40)) {
            val lower = field.lowerReflection(cropHeight)
            val edge = ((field.height - cropHeight) / 2 + cropHeight - 1) * field.width
            assertArrayEquals(field.pixels.copyOfRange(edge, edge + field.width),
                lower.copyOfRange(edge, edge + field.width))
            val detail = (field.height / 2) * field.width + 32
            assertTrue(Color(lower[detail]).red > Color(field.pixels[detail]).red)
        }
    }
}
