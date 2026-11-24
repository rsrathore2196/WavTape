package com.example.ui.util

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

/** A small, immutable, already softened texture pair. No full-screen blur runs while drawing. */
@Immutable
class LegacyArtworkColorField internal constructor(
    internal val pixels: IntArray,
    val dimension: Int,
    internal val bottomPixels: IntArray = pixels
) {
    override fun equals(other: Any?) = other is LegacyArtworkColorField &&
        dimension == other.dimension &&
        pixels.contentEquals(other.pixels) &&
        bottomPixels.contentEquals(other.bottomPixels)

    override fun hashCode(): Int {
        var result = 31 * dimension + pixels.contentHashCode()
        result = 31 * result + bottomPixels.contentHashCode()
        return result
    }
}

/**
 * WavTape 2.1 color adaptation from before the mirrored backdrop.
 * Preserve broad regions across the cover and build a seamless multi-color lower extension
 * that merges cleanly with the album art's actual bottom edge before blooming through
 * multiple colors extracted from across the cover.
 */
internal fun legacyArtworkColorField(pixels: IntArray, width: Int, height: Int, fallback: Color): LegacyArtworkColorField {
    val grid = 7
    val sums = Array(grid * grid) { DoubleArray(4) }
    // Dedicated bottom-edge strip (lowest ~10% of the cover) so the seam under the album art
    // matches the exact horizontal colors where the cover fades into the lower section.
    val edgeSums = Array(grid) { DoubleArray(4) }
    val topEdgeSums = Array(grid) { DoubleArray(4) }
    val edgeRows = (height * 0.10f).roundToInt().coerceIn(1, height)

    for (y in 0 until height) {
        val gridRow = (y * grid / height).coerceIn(0, grid - 1)
        val inTopEdge = y < edgeRows
        val inBottomEdge = y >= height - edgeRows
        for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            val alpha = ((pixel ushr 24) and 255) / 255.0
            if (alpha < .5) continue
            val r = (pixel ushr 16) and 255
            val g = (pixel ushr 8) and 255
            val b = pixel and 255
            // White borders carry no useful hue. They inherit the selected cover
            // tone instead of washing a colored subject into a pale background.
            if (maxOf(r, g, b) >= 210 && maxOf(r, g, b) - minOf(r, g, b) < 36) continue
            val gridCol = (x * grid / width).coerceIn(0, grid - 1)
            val cell = sums[gridRow * grid + gridCol]
            cell[0] += r * alpha; cell[1] += g * alpha; cell[2] += b * alpha; cell[3] += alpha
            if (inTopEdge) {
                val topWeight = alpha * (edgeRows - y)
                val edgeCell = topEdgeSums[gridCol]
                edgeCell[0] += r * topWeight; edgeCell[1] += g * topWeight
                edgeCell[2] += b * topWeight; edgeCell[3] += topWeight
            }
            if (inBottomEdge) {
                val distFromBottom = height - 1 - y
                val bottomWeight = alpha * (edgeRows - distFromBottom)
                val edgeCell = edgeSums[gridCol]
                edgeCell[0] += r * bottomWeight; edgeCell[1] += g * bottomWeight
                edgeCell[2] += b * bottomWeight; edgeCell[3] += bottomWeight
            }
        }
    }

    val safeFallback = playerBackdropTone(fallback)
    fun toTone(acc: DoubleArray, defaultColor: Color): Color =
        if (acc[3] == 0.0) defaultColor else playerBackdropTone(
            Color(
                (acc[0] / acc[3]).roundToInt().coerceIn(0, 255),
                (acc[1] / acc[3]).roundToInt().coerceIn(0, 255),
                (acc[2] / acc[3]).roundToInt().coerceIn(0, 255)
            )
        )

    val colors = Array(grid * grid) { index -> toTone(sums[index], safeFallback) }
    // Anchor top and bottom cover rows to the true top/bottom edge bands (falling back to the
    // adjacent grid cell when an edge strip is entirely white/transparent).
    for (col in 0 until grid) {
        if (topEdgeSums[col][3] > 0.0) {
            colors[col] = toTone(topEdgeSums[col], colors[col])
        }
        if (edgeSums[col][3] > 0.0) {
            colors[(grid - 1) * grid + col] = toTone(edgeSums[col], colors[(grid - 1) * grid + col])
        }
    }

    // Extract multi-region rich colors from across the entire album art (left, center, right bands)
    // so the bottom section below the artwork adapts the full album art color harmony rather than
    // flattening to a single color or stretching one edge row vertically.
    fun chromaScore(color: Color): Float {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        val chroma = max - min
        val brightness = color.luminance()
        // Favor distinct, genuinely visible colors present in the artwork over pure black shadows.
        return chroma * 2.2f + brightness * 0.55f
    }

    fun pickRichRegionColor(colRange: IntRange, rowRange: IntRange, defaultColor: Color): Color {
        var best = defaultColor
        var bestScore = chromaScore(defaultColor)
        for (row in rowRange) {
            for (col in colRange) {
                val acc = sums[row * grid + col]
                if (acc[3] <= 0.0) continue
                val candidate = colors[row * grid + col]
                val score = chromaScore(candidate)
                if (score > bestScore + 0.02f) {
                    bestScore = score
                    best = candidate
                }
            }
        }
        return best
    }

    fun blendColor(a: Color, b: Color, fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        return Color(
            a.red + (b.red - a.red) * f,
            a.green + (b.green - a.green) * f,
            a.blue + (b.blue - a.blue) * f
        )
    }

    val leftRich = pickRichRegionColor(0..2, 1 until grid, colors[(grid - 1) * grid])
    val centerRich = pickRichRegionColor(2..4, 1 until grid, colors[(grid - 1) * grid + grid / 2])
    val rightRich = pickRichRegionColor(4 until grid, 1 until grid, colors[grid * grid - 1])
    val upperLeftRich = pickRichRegionColor(0..3, 0..3, leftRich)
    val upperRightRich = pickRichRegionColor(3 until grid, 0..3, rightRich)

    fun smoothHorizontal(row: Array<Color>, radius: Int = 1): Array<Color> = Array(grid) { col ->
        var r = 0f; var g = 0f; var b = 0f; var w = 0f
        for (dc in -radius..radius) {
            val c = (col + dc).coerceIn(0, grid - 1)
            val weight = (radius + 1 - kotlin.math.abs(dc)).toFloat()
            r += row[c].red * weight
            g += row[c].green * weight
            b += row[c].blue * weight
            w += weight
        }
        Color(r / w, g / w, b / w)
    }

    val coverBottomRow = Array(grid) { col -> colors[(grid - 1) * grid + col] }
    val coverLowerMidRow = Array(grid) { col ->
        val lower = colors[(grid - 2) * grid + col]
        val mid = colors[(grid - 3) * grid + col]
        blendColor(lower, mid, 0.35f)
    }

    // 5 control rows for the bottom section below the album art:
    // Row 0: exact cover bottom edge (anchors 100% seamless merge into the album art)
    // Row 1: lower cover continuation + gentle horizontal bleed
    // Row 2: rich multi-region colors extracted from left/center/right of the album art
    // Row 3: gentle harmonic cross-drift of upper/lower cover hues for lush multi-tone depth
    // Row 4: blend the final cover colors without adding the original darkening pass.
    val bottomRow1 = smoothHorizontal(Array(grid) { col ->
        val t = col.toFloat() / (grid - 1)
        val regionAnchor = if (t < 0.5f) blendColor(leftRich, centerRich, t * 2f)
        else blendColor(centerRich, rightRich, (t - 0.5f) * 2f)
        val edgeBase = blendColor(coverBottomRow[col], coverLowerMidRow[col], 0.42f)
        blendColor(edgeBase, regionAnchor, 0.30f)
    })
    val bottomRow2 = smoothHorizontal(Array(grid) { col ->
        val t = col.toFloat() / (grid - 1)
        val regionAnchor = if (t < 0.5f) blendColor(leftRich, centerRich, t * 2f)
        else blendColor(centerRich, rightRich, (t - 0.5f) * 2f)
        val lowerCarry = blendColor(coverBottomRow[col], coverLowerMidRow[col], 0.50f)
        blendColor(lowerCarry, regionAnchor, 0.62f)
    })
    val bottomRow3 = smoothHorizontal(Array(grid) { col ->
        val t = col.toFloat() / (grid - 1)
        val crossRegion = if (t < 0.5f) blendColor(upperLeftRich, rightRich, t * 1.15f)
        else blendColor(centerRich, upperRightRich, (t - 0.5f) * 2f)
        blendColor(bottomRow2[col], crossRegion, 0.45f)
    })
    val bottomRow4 = smoothHorizontal(Array(grid) { col ->
        blendColor(bottomRow3[col], bottomRow2[col], 0.35f)
    })

    val bottomGridRows = arrayOf(coverBottomRow, bottomRow1, bottomRow2, bottomRow3, bottomRow4)
    val bottomRowCount = bottomGridRows.size

    val dimension = 40
    // Build one joint seamless vertical strip (height = 2 * dimension - 1 = 79) where row
    // (dimension - 1) is the shared boundary between the cover region and bottom section.
    // Joint smoothing guarantees C0 + C1 continuity across the album art bottom edge!
    val combinedHeight = dimension * 2 - 1
    var combined = IntArray(combinedHeight * dimension)

    // Upper half (rows 0 .. dimension - 1): cover-aligned 7x7 spatial color field
    for (y in 0 until dimension) {
        val fy = y.toFloat() / (dimension - 1) * (grid - 1)
        val row = fy.toInt().coerceAtMost(grid - 2)
        for (x in 0 until dimension) {
            val fx = x.toFloat() / (dimension - 1) * (grid - 1)
            val col = fx.toInt().coerceAtMost(grid - 2)
            val top = blendColor(colors[row * grid + col], colors[row * grid + col + 1], fx - col)
            val bot = blendColor(colors[(row + 1) * grid + col], colors[(row + 1) * grid + col + 1], fx - col)
            combined[y * dimension + x] = blendColor(top, bot, fy - row).toArgb()
        }
    }

    // Lower half (rows dimension .. combinedHeight - 1): multi-color bottom extension
    for (by in 1 until dimension) {
        val fy = by.toFloat() / (dimension - 1) * (bottomRowCount - 1)
        val row = fy.toInt().coerceAtMost(bottomRowCount - 2)
        val rowA = bottomGridRows[row]
        val rowB = bottomGridRows[row + 1]
        val destY = (dimension - 1) + by
        for (x in 0 until dimension) {
            val fx = x.toFloat() / (dimension - 1) * (grid - 1)
            val col = fx.toInt().coerceAtMost(grid - 2)
            val top = blendColor(rowA[col], rowA[col + 1], fx - col)
            val bot = blendColor(rowB[col], rowB[col + 1], fx - col)
            combined[destY * dimension + x] = blendColor(top, bot, fy - row).toArgb()
        }
    }

    // Bake softness once across the entire combined vertical field so the album art bottom seam
    // has zero slope discontinuity. The GPU only stretches two tiny opaque 6.4 KB textures.
    repeat(2) {
        val blurred = IntArray(combined.size)
        for (y in 0 until combinedHeight) for (x in 0 until dimension) {
            var r = 0; var g = 0; var b = 0
            for (dy in -1..1) for (dx in -1..1) {
                val weight = (if (dx == 0) 2 else 1) * (if (dy == 0) 2 else 1)
                val sy = (y + dy).coerceIn(0, combinedHeight - 1)
                val sx = (x + dx).coerceIn(0, dimension - 1)
                val pixel = combined[sy * dimension + sx]
                r += ((pixel ushr 16) and 255) * weight
                g += ((pixel ushr 8) and 255) * weight
                b += (pixel and 255) * weight
            }
            blurred[y * dimension + x] = (0xff shl 24) or ((r / 16) shl 16) or ((g / 16) shl 8) or (b / 16)
        }
        combined = blurred
    }

    val coverTexture = IntArray(dimension * dimension)
    val bottomTexture = IntArray(dimension * dimension)
    System.arraycopy(combined, 0, coverTexture, 0, dimension * dimension)
    // Start bottomTexture at row (dimension - 1) so row 0 of bottomTexture is bit-for-bit
    // identical to the final row of coverTexture -> zero visible seam at the bottom of the cover.
    System.arraycopy(combined, (dimension - 1) * dimension, bottomTexture, 0, dimension * dimension)

    return LegacyArtworkColorField(coverTexture, dimension, bottomTexture)
}
