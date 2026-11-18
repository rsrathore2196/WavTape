package com.example.ui.theme

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.SuccessResult
import com.example.performance.AppDispatchers
import com.example.ui.util.buildOptimizedImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** A small, cache-backed sample, requested only for an enabled theme or its open editor. */
@Composable
internal fun rememberTonalArtworkBitmap(source: String?, enabled: Boolean = true): Bitmap? {
    val context = LocalContext.current.applicationContext
    val image by produceState<Bitmap?>(null, context, source, enabled) {
        value = null
        if (!enabled || source.isNullOrBlank()) return@produceState
        try {
            val result = withContext(AppDispatchers.Io) {
                val request = buildOptimizedImageRequest(context, source, 128, track = null,
                    decodeSize = IntSize(128, 128), requireSquareSongArtwork = true,
                    allowVideoFallback = false).newBuilder()
                    .allowHardware(false).bitmapConfig(Bitmap.Config.ARGB_8888)
                    .memoryCacheKey("tonal-theme:$source").placeholderMemoryCacheKey(null as String?).build()
                context.imageLoader.execute(request) as? SuccessResult
            } ?: return@produceState
            value = withContext(AppDispatchers.CpuBounded) {
                (result.drawable as? BitmapDrawable)?.bitmap
                    ?: result.drawable.toBitmap(128, 128, Bitmap.Config.ARGB_8888)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Missing/offline artwork keeps the saved seed; it never prevents applying a theme.
            value = null
        }
    }
    // Coil owns cached bitmaps. Do not recycle images shared with other artwork readers.
    return image.takeIf { enabled && !source.isNullOrBlank() }
}
