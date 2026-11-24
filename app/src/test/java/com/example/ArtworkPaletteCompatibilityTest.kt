package com.example

import androidx.compose.ui.graphics.toArgb
import com.example.ui.util.extractPlayerArtworkColors
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Frozen output covers the palette and every pixel of the untinted mirrored cover texture. */
class ArtworkPaletteCompatibilityTest {
    @Test fun paletteAndTextureRemainIdentical() {
        val expected = listOf(
            "f3e3620691618f5c208c2748cd618d589681981492512ebc1165203e5f095183",
            "d1007e06f43ebc15f85cc63bb558af539a917501f6cc2047ecd279c39b5c8f6e",
            "37e31e124238737071d5fb5cf6712d7b18cc0b2b04aec91261683dd7a26d24e9",
            "ba2bcc619161ae5af1c864fcf565f8bc819a711a8201a8346d9e59f785d560ed",
            "b606bf861aed26612ee0f67e177a8952dd0749d546d554a98cfcfe19eebd578a",
            "45c86f0ca47d2f23676ae61c9f412bc8349ccd2207d1afb0072d64a1ed0196c6",
            "e64794d45cf7cc4d47feda23afd9204e2f9aa94656ee87baf0b46cdbd45ddd97"
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
