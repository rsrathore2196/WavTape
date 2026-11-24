package com.example.ui.components

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.performance.AppDispatchers
import com.example.ui.util.blurPopupPixels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal val LocalPlayerPopupBackdrop = compositionLocalOf<PlayerPopupBackdrop?> { null }

internal class PlayerPopupFrame(val image: ImageBitmap, val originOnScreen: Offset, val screenSize: IntSize,
    val needsForegroundContrast: Boolean)

/** The panels share one still and keep it until the last closing animation is disposed. */
internal class PlayerPopupBackdrop(
    private val scope: CoroutineScope,
    private val capture: suspend (LayoutCoordinates) -> PlayerPopupFrame?
) {
    var source: LayoutCoordinates? = null
        set(value) {
            field = value
            captureWhenReady()
        }
    var frame by mutableStateOf<PlayerPopupFrame?>(null)
        private set
    private var readers = 0
    private var requestedSize: IntSize? = null
    private var request: Job? = null

    fun retain() {
        readers++
        captureWhenReady()
    }

    private fun captureWhenReady() {
        if (readers == 0) return
        val coordinates = source?.takeIf { it.isAttached } ?: return
        val size = coordinates.size
        if (size.width < 1 || size.height < 1 || requestedSize == size) return
        // A resize invalidates the frame, but ordinary player redraws do not.
        request?.cancel()
        requestedSize = size
        frame = null
        request = scope.launch {
            try {
                frame = capture(coordinates)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // An unavailable window buffer leaves the already prepared artwork visible.
                android.util.Log.d("PlayerPopupBackdrop", "Player frame unavailable", error)
            }
        }
    }

    fun release() {
        if (--readers != 0) return
        request?.cancel()
        request = null
        requestedSize = null
        frame = null
    }
}

@Composable
internal fun rememberPlayerPopupBackdrop(enabled: Boolean): PlayerPopupBackdrop? {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    val context = LocalContext.current
    val window = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>()
            .firstOrNull()?.window
    } ?: return null
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val profile = LocalLiquidGlassProfile.current
    val scale = minOf(.25f, profile.backdropSampleScale)
    val radius = with(LocalDensity.current) { (profile.blurRadius(30.dp, false).toPx() * scale).roundToInt() }
    return remember(window, view, scope, scale, radius) {
        PlayerPopupBackdrop(scope) { coordinates -> capturePlayerWindow(window, view, coordinates, scale, radius) }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private suspend fun capturePlayerWindow(window: Window, view: View, coordinates: LayoutCoordinates,
    scale: Float, radius: Int): PlayerPopupFrame? {
    if (!coordinates.isAttached || !view.isAttachedToWindow || !view.isHardwareAccelerated) return null
    val visible = coordinates.boundsInWindow()
    if (visible.isEmpty) return null
    val area = Rect(floor(visible.left).toInt(), floor(visible.top).toInt(),
        ceil(visible.right).toInt(), ceil(visible.bottom).toInt())
    val location = IntArray(2)
    view.getLocationInWindow(location)
    val windowX = location[0]
    val windowY = location[1]
    view.getLocationOnScreen(location)
    val origin = Offset((area.left + location[0] - windowX).toFloat(), (area.top + location[1] - windowY).toFloat())
    val size = IntSize(area.width(), area.height())
    val destination = withContext(AppDispatchers.CpuBounded) {
        Bitmap.createBitmap((size.width * scale).roundToInt().coerceAtLeast(1),
            (size.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    }
    // Read the Activity's last rendered frame. Popup and dialog windows are separate surfaces.
    // On older Android releases the copy request can wait on the render thread.
    val copied = withContext(AppDispatchers.Io) {
        suspendCancellableCoroutine { continuation ->
            PixelCopy.request(window, area, destination, { result ->
                if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
            }, Handler(Looper.getMainLooper()))
        }
    }
    if (!copied) return null
    return withContext(AppDispatchers.CpuBounded) {
        val width = destination.width
        val height = destination.height
        val pixels = IntArray(width * height)
        destination.getPixels(pixels, 0, width, 0, 0, width, height)
        val blurred = blurPopupPixels(pixels, width, height, radius)
        destination.setPixels(blurred, 0, width, 0, 0, width, height)
        PlayerPopupFrame(destination.asImageBitmap(), origin, size, blurred.any { Color(it).luminance() >= .18f })
    }
}
