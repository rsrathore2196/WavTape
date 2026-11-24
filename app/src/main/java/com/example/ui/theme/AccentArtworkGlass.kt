package com.example.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.MusicTrack
import com.example.performance.AppDispatchers
import com.example.ui.components.contrastSafePopupPixel
import com.example.ui.util.ArtworkColorField
import com.example.ui.util.TrackPaletteCache
import com.example.ui.util.playerPaletteKey
import com.example.ui.util.playerPreviewPaletteKey
import com.example.ui.util.prepareCachedAccentArtwork
import kotlinx.coroutines.withContext

/** The playing cover's existing blur, shared by accent fills while the option is active. */
@Immutable
class AccentArtworkGlass internal constructor(field: ArtworkColorField) {
    private val dark = texture(field, ::contrastSafePopupPixel)
    private val light = texture(field) { ColorUtils.blendARGB(it, android.graphics.Color.WHITE, .88f) }

    private fun texture(field: ArtworkColorField, shade: (Int) -> Int): Bitmap =
        Bitmap.createBitmap(IntArray(field.pixels.size) { shade(field.pixels[it]) },
            field.width, field.height, Bitmap.Config.ARGB_8888)

    internal fun brush(foreground: Color): Brush {
        // Keep the control's original ink. Choose a glass tone behind it.
        val bitmap = if (foreground != Color.Unspecified && foreground.luminance() < .18f) light else dark
        return object : ShaderBrush() {
            override fun createShader(size: Size): Shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                if (Build.VERSION.SDK_INT >= 33) setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                setLocalMatrix(Matrix().apply { setScale(size.width / bitmap.width, size.height / bitmap.height) })
            }
        }
    }
}

@Composable
internal fun rememberAccentArtworkGlass(enabled: Boolean, track: MusicTrack?): AccentArtworkGlass? {
    if (!enabled || track == null) return null
    val full = playerPaletteKey(track, true, Color.White, Color.Black)
    val preview = playerPreviewPaletteKey(track)
    val context = LocalContext.current.applicationContext
    LaunchedEffect(full, preview) {
        if (TrackPaletteCache.colorsForArtwork(full, preview)?.colorField == null) {
            prepareCachedAccentArtwork(context, track, preview)
        }
    }
    return key(full, preview) {
        val field by remember { TrackPaletteCache.observeArtwork(full, preview) }
            .collectAsStateWithLifecycle(TrackPaletteCache.colorsForArtwork(full, preview))
        val reflection = field?.colorField
        if (reflection == null) null else key(reflection) {
            produceState<AccentArtworkGlass?>(null) {
                value = withContext(AppDispatchers.CpuBounded) { AccentArtworkGlass(reflection) }
            }.value
        }
    }
}

internal fun AppThemeColors.hasAccentFill(color: Color): Boolean =
    color.alpha > 0f && (color.copy(alpha = 1f) == primaryAccent.copy(alpha = 1f) ||
        color.copy(alpha = 1f) == secondaryAccent.copy(alpha = 1f))

/** Replace a color-backed accent fill while preserving non-accent, inactive and disabled colors. */
@Composable
internal fun Modifier.accentBackground(
    colors: AppThemeColors, fill: Color, shape: Shape = RectangleShape, foreground: Color = colors.textPrimary
): Modifier {
    val artwork = colors.artworkGlass
    if (artwork == null || !colors.hasAccentFill(fill)) return background(fill, shape)
    val brush = remember(artwork, foreground) { artwork.brush(foreground) }
    return background(brush, shape).border(.8.dp, Color.White.copy(alpha = .22f), shape)
}

@Composable
internal fun Modifier.accentBackground(
    fill: Color, shape: Shape = RectangleShape, foreground: Color = LocalAppColors.current.textPrimary
): Modifier = accentBackground(LocalAppColors.current, fill, shape, foreground)
