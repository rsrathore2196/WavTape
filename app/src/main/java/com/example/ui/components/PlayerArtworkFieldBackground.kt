package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.ui.util.ArtworkColorField
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Only the cover's small blurred texture is tiled. The foreground keeps its original pixels. */
@Composable
internal fun PlayerArtworkFieldBackground(field: ArtworkColorField, artworkBounds: State<Rect?>?,
    modifier: Modifier = Modifier, animateChanges: Boolean = true) {
    val cover = remember(field) { ReflectedArtwork(field) }
    var shown by remember { mutableStateOf(cover) }
    var incoming by remember { mutableStateOf<ReflectedArtwork?>(null) }
    val opacity = remember { Animatable(0f) }
    val origin = remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(cover, animateChanges) {
        if (!animateChanges) {
            shown = cover
            incoming = null
            return@LaunchedEffect
        }
        incoming?.let { shown = it }
        if (shown !== cover) {
            incoming = cover
            opacity.snapTo(0f)
            opacity.animateTo(1f, tween(380))
            shown = cover
        }
        incoming = null
    }
    Canvas(modifier.fillMaxSize().onGloballyPositioned { origin.value = it.positionInRoot() }) {
        val minSide = size.minDimension
        val artwork = artworkBounds?.value?.translate(-origin.value)
            ?.takeIf { it.width >= minSide / 3f && it.height >= minSide / 3f }
            ?: Rect((size.width - minSide) / 2f, (size.height - minSide) / 2f,
                (size.width + minSide) / 2f, (size.height + minSide) / 2f)
        if (minSide < 1f) return@Canvas
        clipRect {
            drawReflectedCover(shown, artwork, 1f)
            incoming?.let { drawReflectedCover(it, artwork, opacity.value) }
        }
    }
}

private class ReflectedArtwork(private val field: ArtworkColorField) {
    val upper = image(field.pixels)
    private var cachedHeight = 0
    private var cachedLower = upper

    fun lower(cropHeight: Int): ImageBitmap {
        // Only a change in the source crop rebuilds the small seam-blended texture.
        if (cachedHeight != cropHeight) {
            cachedLower = image(field.lowerReflection(cropHeight))
            cachedHeight = cropHeight
        }
        return cachedLower
    }

    private fun image(pixels: IntArray) =
        Bitmap.createBitmap(pixels, field.width, field.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun DrawScope.drawReflectedCover(cover: ReflectedArtwork, artwork: Rect, alpha: Float) {
    val image = cover.upper
    // Crop the reference texture with the same center-fill rule as PlayerCoverArtwork.
    val scale = maxOf(artwork.width / image.width, artwork.height / image.height)
    val srcWidth = (artwork.width / scale).roundToInt().coerceIn(1, image.width)
    val srcHeight = (artwork.height / scale).roundToInt().coerceIn(1, image.height)
    val source = IntOffset((image.width - srcWidth) / 2, (image.height - srcHeight) / 2)
    val sourceSize = IntSize(srcWidth, srcHeight)
    val width = artwork.width.roundToInt().coerceAtLeast(1)
    val height = artwork.height.roundToInt().coerceAtLeast(1)
    val destSize = IntSize(width, height)
    val left = artwork.left.roundToInt()
    val top = artwork.top.roundToInt()
    val firstRow = floor(-top.toFloat() / height).toInt()
    val lastRow = ceil((size.height - top) / height).toInt()
    val firstColumn = floor(-left.toFloat() / width).toInt()
    val lastColumn = ceil((size.width - left) / width).toInt()
    for (row in firstRow until lastRow) for (column in firstColumn until lastColumn) {
        val x = left + column * width
        val y = top + row * height
        // Reflect outwards from the visible cover. Neighboring tiles share the same edge.
        scale(if (column % 2 == 0) 1f else -1f, if (row % 2 == 0) 1f else -1f,
            pivot = Offset(x + width / 2f, y + height / 2f)) {
            drawImage(if (row > 0) cover.lower(srcHeight) else image, srcOffset = source, srcSize = sourceSize,
                dstOffset = IntOffset(x, y), dstSize = destSize, alpha = alpha)
        }
    }
}
