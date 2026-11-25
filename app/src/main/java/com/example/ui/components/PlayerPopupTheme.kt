package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.performance.AppDispatchers
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.resolveColorScheme
import com.example.ui.util.PlayerArtworkSample
import com.example.ui.util.blurredPopupPixels
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// Settings-style dialogs use the app palette. The prepared cover is the first-frame fallback.
internal val LocalPlayerPopupColors = compositionLocalOf<AppThemeColors?> { null }
internal val LocalPlayerPopupArtwork = compositionLocalOf<PlayerPopupArtwork?> { null }

internal class PlayerPopupArtwork(val texture: ImageBitmap, val needsForegroundContrast: Boolean)

@Composable
internal fun rememberPlayerPopupArtwork(sample: PlayerArtworkSample?, playerVisible: Boolean): PlayerPopupArtwork? {
    val profile = LocalLiquidGlassProfile.current
    val configuration = LocalConfiguration.current
    val shortSide = minOf(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(240)
    val blurFraction = profile.blurRadius(30.dp, false).value / shortSide
    var prepared by remember(sample, blurFraction) { mutableStateOf<PlayerPopupArtwork?>(null) }
    LaunchedEffect(sample, blurFraction, playerVisible) {
        if (sample == null || !playerVisible || prepared != null) return@LaunchedEffect
        prepared = withContext(AppDispatchers.CpuBounded) {
            val pixels = sample.blurredPopupPixels(blurFraction)
            val bitmap = Bitmap.createBitmap(pixels, sample.width, sample.height, Bitmap.Config.ARGB_8888)
            bitmap.prepareToDraw()
            PlayerPopupArtwork(bitmap.asImageBitmap(), needsForegroundContrast = pixels.any { Color(it).luminance() >= .18f })
        }
    }
    return prepared
}

internal val PlayerGlassPopupColors by lazy {
    playerPopupColors(DarkAppColors.copy(primaryAccent = Color.White, secondaryAccent = Color.White))
        .copy(textSecondary = Color.White, textMuted = Color.White, cardBorder = Color.White.copy(alpha = .48f))
}

@Composable
internal fun needsPopupForegroundContrast(): Boolean {
    val artwork = LocalPlayerPopupArtwork.current
    val backdrop = LocalPlayerPopupBackdrop.current
    return LocalDynamicGlassEnabled.current && remember(backdrop) {
        artwork?.needsForegroundContrast == true || backdrop?.frameForPresentation()?.needsForegroundContrast == true
    }
}

/** Cast a short, fading shadow beneath the mark, leaving the surrounding glass as drawn. */
@Composable
internal fun Modifier.playerPopupControlContrast(translucent: Boolean = false): Modifier {
    if (!needsPopupForegroundContrast()) return this
    return drawWithCache {
        val bounds = Rect(Offset.Zero, size)
        val near = Paint().apply { colorFilter = ColorFilter.tint(Color.Black.copy(alpha = .7f)) }
        val far = Paint().apply { colorFilter = ColorFilter.tint(Color.Black.copy(alpha = .22f)) }
        val nearOffset = .8.dp.toPx()
        val farOffset = 1.6.dp.toPx()
        val shadowBounds = bounds.copy(bottom = bounds.bottom + farOffset)
        val shadowLayer = Paint()
        val removeFill = if (translucent) Paint().apply {
            blendMode = BlendMode.DstOut
            // The shadow must not show through the control's translucent paint.
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { this[3, 3] = 12f })
        } else null
        onDrawWithContent {
            val canvas = drawContext.canvas
            if (removeFill != null) canvas.saveLayer(shadowBounds, shadowLayer)
            translate(top = farOffset) {
                canvas.saveLayer(bounds, far)
                this@onDrawWithContent.drawContent()
                canvas.restore()
            }
            translate(top = nearOffset) {
                canvas.saveLayer(bounds, near)
                this@onDrawWithContent.drawContent()
                canvas.restore()
            }
            if (removeFill != null) {
                canvas.saveLayer(bounds, removeFill)
                drawContent()
                canvas.restore()
                canvas.restore()
            }
            drawContent()
        }
    }
}

@Composable
internal fun playerPopupSurfaceColor(): Color {
    val artwork = LocalPlayerPopupArtwork.current
    val backdrop = LocalPlayerPopupBackdrop.current
    return if (LocalDynamicGlassEnabled.current && remember(backdrop) { artwork != null || backdrop != null }) {
        Color.Transparent
    } else PlayerGlassPopupColors.cardBackground
}

/** Keep inset highlights subtle over the live glass. */
@Composable
internal fun playerPopupHighlight(alpha: Float): Color =
    Color.White.copy(alpha = if (LocalDynamicGlassEnabled.current) alpha * .25f else alpha)

/** Frost an inset over the glass drawn by its parent panel. */
@Composable
internal fun Modifier.playerPopupInsetGlass(shape: Shape, emphasized: Boolean = false): Modifier {
    val sheen = remember(emphasized) {
        Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (emphasized) .052f else .037f),
            Color.White.copy(alpha = if (emphasized) .024f else .012f)
        ))
    }
    val rim = remember(emphasized) {
        Brush.linearGradient(listOf(
            Color.White.copy(alpha = if (emphasized) .43f else .28f),
            Color.White.copy(alpha = if (emphasized) .19f else .11f)
        ))
    }
    return background(sheen, shape).border(.8.dp, rim, shape)
}

/** The already frosted image stays in screen space as the panel moves over it. */
@Composable
internal fun Modifier.playerPopupGlass(shape: Shape, onMotion: (() -> Unit)? = LocalPlayerPopupMotion.current): Modifier {
    if (!LocalDynamicGlassEnabled.current) return this
    val texture = LocalPlayerPopupArtwork.current?.texture
    val backdrop = LocalPlayerPopupBackdrop.current
    val fallback = remember(backdrop) { texture }
    if (fallback == null && backdrop == null) return this
    val frame = remember(backdrop) { backdrop?.frameForPresentation() }
    if (backdrop != null) DisposableEffect(backdrop) {
        backdrop.retain(frame)
        onDispose { backdrop.release() }
    }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy()) }
    val rim = remember {
        Brush.linearGradient(listOf(Color.White.copy(alpha = .49f),
            Color.White.copy(alpha = .08f), Color.White.copy(alpha = .22f)))
    }
    val positioning = if (backdrop != null) Modifier.onGloballyPositioned { coordinates = it } else Modifier
    // Keep the rows' display list separate so remapping the still does not redraw their glyphs.
    val foreground = if (backdrop != null) Modifier.graphicsLayer() else Modifier
    return clip(shape).then(positioning).drawWithCache {
        val sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = .035f), .28f to Color.Transparent)
        val panel = IntSize(size.width.roundToInt(), size.height.roundToInt())
        onDrawBehind {
            when {
                frame != null -> {
                    onMotion?.invoke()
                    if (!drawPlayerFrame(frame, coordinates)) return@onDrawBehind
                }
                fallback != null -> drawImage(fallback, dstSize = panel, filterQuality = FilterQuality.Medium)
                else -> drawRect(PlayerGlassPopupColors.cardBackground)
            }
            drawRect(sheen)
        }
    }.border(.8.dp, rim, shape).then(foreground)
}

private fun DrawScope.drawPlayerFrame(frame: PlayerPopupFrame, coordinates: LayoutCoordinates?): Boolean {
    if (coordinates?.isAttached != true) return false
    val topLeft = coordinates.screenToLocal(frame.originOnScreen)
    val bottomRight = coordinates.screenToLocal(frame.originOnScreen +
        Offset(frame.screenSize.width.toFloat(), frame.screenSize.height.toFloat()))
    if (!topLeft.isSpecified || !bottomRight.isSpecified) return false
    val width = bottomRight.x - topLeft.x
    val height = bottomRight.y - topLeft.y
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return false
    withTransform({
        translate(topLeft.x, topLeft.y)
        scale(width / frame.image.width, height / frame.image.height, pivot = Offset.Zero)
    }) {
        drawImage(frame.image, srcSize = IntSize(frame.image.width, frame.image.height), filterQuality = FilterQuality.Medium)
    }
    return true
}

internal fun playerPopupColors(source: AppThemeColors): AppThemeColors {
    val background = if (source.isDark) Color.Black else Color.White
    val elevated = if (source.isDark) Color(0xFF181818) else Color(0xFFF2F2F2)
    val foreground = if (source.isDark) Color.White else Color(0xFF101010)
    val tintedControl = if (source.isDark) Color(0xFF303030) else Color(0xFFDEDEDE)
    fun accent(color: Color) = LiquidGlass.contrastColor(color, listOf(background, elevated, tintedControl), 4.5f)
    return source.copy(
        scaffoldBackground = background,
        screenBackground = Brush.verticalGradient(listOf(background, background)),
        cardBackground = background,
        cardBackgroundElevated = elevated,
        inputBackground = elevated,
        chipBackground = elevated,
        textPrimary = foreground,
        textSecondary = if (source.isDark) Color(0xFFBFBFBF) else Color(0xFF505050),
        textMuted = if (source.isDark) Color(0xFFA0A0A0) else Color(0xFF606060),
        cardBorder = if (source.isDark) Color(0xFF606060) else Color(0xFF909090),
        chipBorder = if (source.isDark) Color(0xFF606060) else Color(0xFF909090),
        dividerColor = foreground.copy(alpha = 0.15f),
        primaryAccent = accent(source.primaryAccent),
        secondaryAccent = accent(source.secondaryAccent),
        dynamicGlassEnabled = false,
        dynamicAlbumGlow = false
    )
}

@Composable
internal fun rememberPlayerPopupColors(fallback: AppThemeColors = LocalAppColors.current): AppThemeColors {
    val source = LocalPlayerPopupColors.current ?: fallback
    return remember(source) { playerPopupColors(source) }
}

@Composable
internal fun PlayerPopupTheme(colors: AppThemeColors, content: @Composable () -> Unit) {
    val scheme = remember(colors) { resolveColorScheme(colors.isDark, colors) }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
    }
}

@Composable
internal fun playerPopupSwitchColors(colors: AppThemeColors) = SwitchDefaults.colors(
    checkedThumbColor = colors.onPrimaryAccent,
    checkedTrackColor = colors.primaryAccent,
    checkedBorderColor = colors.primaryAccent,
    uncheckedThumbColor = colors.textSecondary,
    uncheckedTrackColor = colors.cardBackgroundElevated,
    uncheckedBorderColor = colors.cardBorder
)
