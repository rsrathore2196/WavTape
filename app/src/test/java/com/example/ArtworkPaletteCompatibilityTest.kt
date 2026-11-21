package com.example

import androidx.compose.ui.graphics.toArgb
import com.example.ui.util.extractPlayerArtworkColors
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Frozen output covers the palette and every pixel of the edge-anchored cover texture. */
class ArtworkPaletteCompatibilityTest {
    @Test fun paletteAndTextureRemainIdentical() {
        val expected = listOf(
            "4651c51fcf0de895f906815634ac3b4f7bb51803eaa59824828b678f1d2c0caf",
            "70414cbb8b87eb3772a3b1905e934318e505dc0a68ac51026a3f5e875417d5bf",
            "01da4ba804a3d9e7ed20abe6d9e48123bac84ce22fe630a4e1c4d720663a509e",
            "8e338297142ee9a912a197b8c1045908506ad29048bd46af8e5122c06119121c",
            "3519e801416f4006e1b17c5c7d71d875a29c20f07a74c3a372f7014c8e29ff48",
            "b4250e55bfdd9c72809f670698bffd7b2a5b606a754c9a97f0fe9bcf53a08527",
            "bfc4888d0ec6a8f720cd5080790536d205613f6c06e6d2c0cc9592833723a502"
        )
        val actual = fixtures().map { (width, pixels) ->
            val colors = extractPlayerArtworkColors(pixels, width, pixels.size / width)!!
            val values = intArrayOf(colors.dominantColor.toArgb(), colors.accentColor.toArgb(),
                colors.vibrantColor.toArgb(), colors.topEdgeColor.toArgb(), colors.bottomEdgeColor.toArgb()) + colors.colorField!!.pixels
            val bytes = ByteBuffer.allocate(values.size * 4).apply { values.forEach(::putInt) }.array()
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        assertEquals(expected, actual)
    }

    private fun fixtures(): List<Pair<Int, IntArray>> {
        val random = java.util.Random(734L)
        return listOf(
            120 to IntArray(120 * 120) { 0xff1838bc.toInt() },
            120 to IntArray(120 * 120) { i -> if (i % 120 in 40..79 && i / 120 in 40..79) 0xff00796b.toInt() else -1 },
            120 to IntArray(120 * 120) { random.nextInt() },
            120 to IntArray(120 * 120) { random.nextInt() or (0xff shl 24) },
            3 to intArrayOf(-1, 0x00123456, 0x801838bc.toInt(), 0xff000000.toInt(), 0xffff0000.toInt(), -1, 0xff0000ff.toInt(), -1, -1),
            1 to intArrayOf(0xffe8f1ff.toInt()),
            256 to IntArray(256 * 128) { i -> (0xff shl 24) or ((i shr 10) shl 19) or (((i shr 5) and 31) shl 11) or ((i and 31) shl 3) }
        )
    }
}
