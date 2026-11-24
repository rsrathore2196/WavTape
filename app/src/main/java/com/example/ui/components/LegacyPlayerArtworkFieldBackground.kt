package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.ui.util.LegacyArtworkColorField
import com.example.ui.util.ExtractedTrackColors
import com.example.ui.util.playerBackdropTone
import kotlin.math.roundToInt

@Composable
internal fun LegacyPlayerBackdrop(palette: ExtractedTrackColors?, artworkBounds: State<Rect?>?,
    modifier: Modifier, animateChanges: Boolean) {
    val field = palette?.legacyColorField
    when {
        field != null -> LegacyPlayerArtworkFieldBackground(field, artworkBounds, modifier, animateChanges)
        palette == null -> Box(modifier.background(Color(0xff101010)))
        else -> {
            // The edge colors are available from the palette cache before the textures are prepared.
            val spec = tween<Color>(650, easing = LinearOutSlowInEasing)
            val top = animateColorAsState(playerBackdropTone(palette.topEdgeColor), spec, label = "legacy_top_edge")
            val bottom = animateColorAsState(playerBackdropTone(palette.bottomEdgeColor), spec, label = "legacy_bottom_edge")
            val origin = remember { mutableStateOf(Offset.Zero) }
            Box(modifier.onGloballyPositioned { origin.value = it.positionInRoot() }.drawWithCache {
                val bounds = artworkBounds?.value
                val startY = bounds?.top?.minus(origin.value.y) ?: 0f
                val endY = bounds?.bottom?.minus(origin.value.y) ?: size.height
                val start = top.value
                val end = bottom.value
                val stops = Array(25) { index ->
                    val t = index / 24f
                    (0.24f + t * 0.52f) to lerp(start, end, t * t * (3f - 2f * t))
                }
                val brush = Brush.verticalGradient(*stops, startY = startY, endY = maxOf(startY + 1f, endY))
                onDrawBehind { drawRect(brush) }
            })
        }
    }
}

/** Retain the previous prepared textures until the next ones are ready; animate only their opacity. */
@Composable
private fun LegacyPlayerArtworkFieldBackground(field: LegacyArtworkColorField, artworkBounds: State<Rect?>?,
    modifier: Modifier = Modifier, animateChanges: Boolean = true) {
    val coverImage = remember(field) {
        Bitmap.createBitmap(field.pixels, field.dimension, field.dimension, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val bottomImage = remember(field) {
        Bitmap.createBitmap(field.bottomPixels, field.dimension, field.dimension, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    var shownCover by remember { mutableStateOf(coverImage) }
    var shownBottom by remember { mutableStateOf(bottomImage) }
    var incomingCover by remember { mutableStateOf<ImageBitmap?>(null) }
    var incomingBottom by remember { mutableStateOf<ImageBitmap?>(null) }
    val opacity = remember { Animatable(0f) }
    val origin = remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(coverImage, bottomImage, animateChanges) {
        if (!animateChanges) {
            shownCover = coverImage
            shownBottom = bottomImage
            incomingCover = null
            incomingBottom = null
            return@LaunchedEffect
        }
        val prevCover = incomingCover
        val prevBottom = incomingBottom
        if (prevCover != null && prevBottom != null) {
            shownCover = prevCover
            shownBottom = prevBottom
        }
        incomingCover = null
        incomingBottom = null
        if (shownCover === coverImage && shownBottom === bottomImage) return@LaunchedEffect
        incomingCover = coverImage
        incomingBottom = bottomImage
        opacity.snapTo(0f)
        opacity.animateTo(1f, tween(380))
        shownCover = coverImage
        shownBottom = bottomImage
        incomingCover = null
        incomingBottom = null
    }
    Canvas(modifier.fillMaxSize().onGloballyPositioned { origin.value = it.positionInRoot() }) {
        if (size.minDimension < 1f) return@Canvas
        val bounds = artworkBounds?.value
        val top = ((bounds?.top ?: origin.value.y) - origin.value.y).roundToInt().coerceIn(0, size.height.roundToInt())
        val bottom = ((bounds?.bottom ?: (origin.value.y + size.height)) - origin.value.y).roundToInt()
            .coerceIn(top, size.height.roundToInt())
        clipRect {
            drawArtworkField(shownCover, shownBottom, top, bottom, 1f)
            val nextCover = incomingCover
            val nextBottom = incomingBottom
            if (nextCover != null && nextBottom != null) {
                drawArtworkField(nextCover, nextBottom, top, bottom, opacity.value)
            }
        }
    }
}

private fun DrawScope.drawArtworkField(
    coverImage: ImageBitmap,
    bottomImage: ImageBitmap,
    top: Int,
    bottom: Int,
    alpha: Float
) {
    val width = size.width.roundToInt().coerceAtLeast(1)
    if (top > 0) drawImage(
        coverImage,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(coverImage.width, 1),
        dstSize = IntSize(width, top),
        alpha = alpha
    )
    if (bottom > top) drawImage(
        coverImage,
        dstOffset = IntOffset(0, top),
        dstSize = IntSize(width, bottom - top),
        alpha = alpha
    )
    val remaining = size.height.roundToInt() - bottom
    if (remaining > 0) drawImage(
        bottomImage,
        dstOffset = IntOffset(0, bottom),
        dstSize = IntSize(width, remaining),
        alpha = alpha
    )
}
