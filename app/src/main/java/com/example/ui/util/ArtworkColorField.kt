package com.example.ui.util

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.pow
import kotlin.math.roundToInt

/** A small, preblurred image of the selected cover, retaining its original proportions. */
@Immutable
class ArtworkColorField internal constructor(
    internal val pixels: IntArray,
    internal val width: Int,
    internal val height: Int
) {
    override fun equals(other: Any?) = other is ArtworkColorField &&
        width == other.width && height == other.height && pixels.contentEquals(other.pixels)

    override fun hashCode() = 31 * (31 * width + height) + pixels.contentHashCode()
}

internal fun playerBackdropTone(color: Color): Color {
    val luminance = color.luminance()
    val scale = if (luminance > .16f) (.15f / luminance).pow(1f / 2.2f) else 1f
    return Color(
        (color.red * scale * 255).roundToInt(),
        (color.green * scale * 255).roundToInt(),
        (color.blue * scale * 255).roundToInt()
    )
}

/** Downsample and blur the actual image once on the artwork preparation dispatcher. */
internal fun artworkColorField(pixels: IntArray, width: Int, height: Int): ArtworkColorField {
    val scale = minOf(1f, 64f / maxOf(width, height))
    val outWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val outHeight = (height * scale).roundToInt().coerceAtLeast(1)
    val image = IntArray(outWidth * outHeight)
    for (y in 0 until outHeight) for (x in 0 until outWidth) {
        val startX = x * width / outWidth
        val endX = (x + 1) * width / outWidth
        val startY = y * height / outHeight
        val endY = (y + 1) * height / outHeight
        var red = 0
        var green = 0
        var blue = 0
        for (sy in startY until endY) for (sx in startX until endX) {
            val pixel = pixels[sy * width + sx]
            val alpha = pixel ushr 24
            val matte = (255 - alpha) * 16
            red += ((pixel ushr 16 and 255) * alpha + matte) / 255
            green += ((pixel ushr 8 and 255) * alpha + matte) / 255
            blue += ((pixel and 255) * alpha + matte) / 255
        }
        val count = (endX - startX) * (endY - startY)
        // A neutral scrim keeps white controls legible on bright or white covers.
        image[y * outWidth + x] = opaqueRgb(red * 45 / count / 100, green * 45 / count / 100, blue * 45 / count / 100)
    }
    val scratch = IntArray(image.size)
    val radius = (maxOf(outWidth, outHeight) * 0.05f).roundToInt().coerceAtLeast(1)
    repeat(3) {
        blurPass(image, scratch, outWidth, outHeight, radius, horizontal = true)
        blurPass(scratch, image, outWidth, outHeight, radius, horizontal = false)
    }
    return ArtworkColorField(image, outWidth, outHeight)
}

private fun blurPass(source: IntArray, target: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val count = 2 * radius + 1
    for (y in 0 until height) for (x in 0 until width) {
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val sx = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
            val sy = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
            val pixel = source[sy * width + sx]
            red += pixel ushr 16 and 255
            green += pixel ushr 8 and 255
            blue += pixel and 255
        }
        target[y * width + x] = opaqueRgb(red / count, green / count, blue / count)
    }
}

private fun opaqueRgb(red: Int, green: Int, blue: Int): Int =
    (0xff shl 24) or (red shl 16) or (green shl 8) or blue
