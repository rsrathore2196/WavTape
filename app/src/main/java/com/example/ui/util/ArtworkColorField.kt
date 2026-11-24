package com.example.ui.util

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow
import kotlin.math.roundToInt

/** Small preblurred textures of the selected cover at two strengths. */
@Immutable
class ArtworkColorField internal constructor(
    internal val pixels: IntArray,
    internal val width: Int,
    internal val height: Int,
    private val lighterPixels: IntArray
) {
    override fun equals(other: Any?) = other is ArtworkColorField &&
        width == other.width && height == other.height && pixels.contentEquals(other.pixels) &&
        lighterPixels.contentEquals(other.lighterPixels)

    override fun hashCode() = 31 * (31 * (31 * width + height) + pixels.contentHashCode()) +
        lighterPixels.contentHashCode()

    internal fun lowerReflection(cropHeight: Int): IntArray {
        val result = lighterPixels.copyOf()
        val edgeRow = (height - cropHeight) / 2 + cropHeight - 1
        val blendRows = (cropHeight * 0.15f).roundToInt().coerceAtLeast(1)
        // Bilinear filtering can read the next texel just beyond a center crop.
        val paddedEdge = (edgeRow + 1).coerceAtMost(height - 1)
        for (y in (edgeRow - blendRows + 1).coerceAtLeast(0)..paddedEdge) {
            val t = (edgeRow - y).coerceAtLeast(0).toFloat() / blendRows
            val blend = t * t * (3f - 2f * t)
            for (x in 0 until width) {
                val index = y * width + x
                val soft = pixels[index]
                val clear = lighterPixels[index]
                fun channel(shift: Int): Int {
                    val from = (soft ushr shift) and 255
                    val to = (clear ushr shift) and 255
                    return (from + (to - from) * blend).roundToInt()
                }
                result[index] = opaqueRgb(channel(16), channel(8), channel(0))
            }
        }
        return result
    }
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

/** Reused by the player and glass panels before either applies its own blur. */
@Immutable
class PlayerArtworkSample internal constructor(
    internal val pixels: IntArray,
    internal val width: Int,
    internal val height: Int
)

internal fun samplePlayerArtwork(pixels: IntArray, width: Int, height: Int): PlayerArtworkSample {
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
        image[y * outWidth + x] = opaqueRgb(red / count, green / count, blue / count)
    }
    return PlayerArtworkSample(image, outWidth, outHeight)
}

internal fun artworkColorField(pixels: IntArray, width: Int, height: Int): ArtworkColorField =
    artworkColorField(samplePlayerArtwork(pixels, width, height))

/** The existing reflected player texture keeps its original highlight handling. */
internal fun artworkColorField(sample: PlayerArtworkSample): ArtworkColorField {
    val outWidth = sample.width
    val outHeight = sample.height
    val image = sample.pixels.copyOf()
    val scratch = IntArray(image.size)
    val radius = (maxOf(outWidth, outHeight) * 0.05f).roundToInt().coerceAtLeast(1)
    fun smooth() {
        blurPass(image, scratch, outWidth, outHeight, radius, horizontal = true)
        blurPass(scratch, image, outWidth, outHeight, radius, horizontal = false)
    }
    smooth()
    val lighter = image.copyOf()
    repeat(2) { smooth() }
    for (index in image.indices) {
        image[index] = softenReflectionHighlights(image[index])
        lighter[index] = softenReflectionHighlights(lighter[index])
    }
    return ArtworkColorField(image, outWidth, outHeight, lighter)
}

/** Apply the popup blur at texture resolution, with no color or luminance adjustment. */
internal fun PlayerArtworkSample.blurredPopupPixels(blurFraction: Float): IntArray {
    val radius = (maxOf(width, height) * blurFraction).roundToInt()
    if (radius <= 0) return pixels
    val scratch = IntArray(pixels.size)
    val blurred = IntArray(pixels.size)
    blurPass(pixels, scratch, width, height, radius, horizontal = true)
    blurPass(scratch, blurred, width, height, radius, horizontal = false)
    repeat(2) {
        blurPass(blurred, scratch, width, height, radius, horizontal = true)
        blurPass(scratch, blurred, width, height, radius, horizontal = false)
    }
    return blurred
}

private fun softenReflectionHighlights(pixel: Int): Int {
    val color = Color(pixel)
    val luminance = color.luminance()
    val excess = luminance - .12f
    if (excess <= 0f) return pixel

    // Ease into a luminance ceiling to preserve detail and white control contrast on bright covers.
    val headroom = .06f
    val scale = (.12f + headroom * excess / (headroom + excess)) / luminance
    val linear = color.convert(ColorSpaces.LinearSrgb)
    return Color(linear.red * scale, linear.green * scale, linear.blue * scale,
        colorSpace = ColorSpaces.LinearSrgb).toArgb()
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
