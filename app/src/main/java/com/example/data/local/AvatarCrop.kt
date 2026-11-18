package com.example.data.local

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max

/** Offsets are fractions of the square viewport, so resizing preserves the selected crop. */
internal data class AvatarCropTransform(
    val quarterTurns: Int = 0,
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
) {
    fun constrained(width: Int, height: Int): AvatarCropTransform {
        require(width > 0 && height > 0)
        val turns = ((quarterTurns % 4) + 4) % 4
        val scale = zoom.takeIf { it.isFinite() }?.coerceIn(1f, 5f) ?: 1f
        val w = if (turns % 2 == 0) width else height
        val h = if (turns % 2 == 0) height else width
        val cover = max(1f / w, 1f / h) * scale
        val maxX = max(0f, (w * cover - 1f) / 2f)
        val maxY = max(0f, (h * cover - 1f) / 2f)
        return copy(quarterTurns = turns, zoom = scale,
            offsetX = offsetX.takeIf { it.isFinite() }?.coerceIn(-maxX, maxX) ?: 0f,
            offsetY = offsetY.takeIf { it.isFinite() }?.coerceIn(-maxY, maxY) ?: 0f)
    }

    fun gesture(width: Int, height: Int, panX: Float, panY: Float, zoomChange: Float,
        focusX: Float, focusY: Float): AvatarCropTransform {
        val current = constrained(width, height)
        val nextZoom = (current.zoom * zoomChange).coerceIn(1f, 5f)
        val ratio = nextZoom / current.zoom
        return current.copy(zoom = nextZoom,
            offsetX = current.offsetX * ratio + (focusX - .5f) * (1f - ratio) + panX,
            offsetY = current.offsetY * ratio + (focusY - .5f) * (1f - ratio) + panY)
            .constrained(width, height)
    }

    fun matrix(width: Int, height: Int, viewport: Float): Matrix {
        val crop = constrained(width, height)
        val w = if (crop.quarterTurns % 2 == 0) width else height
        val h = if (crop.quarterTurns % 2 == 0) height else width
        val scale = max(viewport / w, viewport / h) * crop.zoom
        return Matrix().apply {
            postTranslate(-width / 2f, -height / 2f)
            postRotate(crop.quarterTurns * 90f)
            postScale(scale, scale)
            postTranslate(viewport * (.5f + crop.offsetX), viewport * (.5f + crop.offsetY))
        }
    }
}

/** The editor and stored image share this transform; the square is always entirely covered. */
internal fun renderAvatarCrop(source: Bitmap, transform: AvatarCropTransform, edge: Int = 512): Bitmap {
    require(edge in 1..512)
    return Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also { output ->
        Canvas(output).drawBitmap(source, transform.matrix(source.width, source.height, edge.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
}
