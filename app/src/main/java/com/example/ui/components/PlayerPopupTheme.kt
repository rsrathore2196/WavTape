package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.resolveColorScheme
import com.example.ui.util.ArtworkColorField
import kotlin.math.roundToInt

// Settings-style dialogs use the app palette. Artwork panels share one player palette.
internal val LocalPlayerPopupColors = compositionLocalOf<AppThemeColors?> { null }
internal val LocalPlayerPopupArtwork = compositionLocalOf<ArtworkColorField?> { null }
internal val PlayerGlassPopupColors by lazy {
    playerPopupColors(DarkAppColors.copy(primaryAccent = Color.White, secondaryAccent = Color.White))
        .copy(textSecondary = Color(0xFFFAFAFA), textMuted = Color(0xFFF5F5F5))
}

@Composable
internal fun playerPopupSurfaceColor(): Color =
    if (LocalPlayerPopupArtwork.current == null) PlayerGlassPopupColors.cardBackground else Color.Transparent

/** Keep the highlight visible without washing out the labels on bright cover art. */
@Composable
internal fun playerPopupHighlight(alpha: Float): Color =
    Color.White.copy(alpha = if (LocalPlayerPopupArtwork.current == null) alpha else alpha * .25f)

/** Dialog and sheet windows have separate render trees. Reuse the player's already-blurred cover texture. */
@Composable
internal fun Modifier.playerPopupArtwork(shape: Shape): Modifier {
    val field = LocalPlayerPopupArtwork.current ?: return this
    val texture = remember(field) {
        val pixels = IntArray(field.pixels.size) { contrastSafePopupPixel(field.pixels[it]) }
        Bitmap.createBitmap(pixels, field.width, field.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val rim = remember {
        Brush.linearGradient(listOf(Color.White.copy(alpha = .49f),
            Color.White.copy(alpha = .08f), Color.White.copy(alpha = .22f)))
    }
    return clip(shape).drawWithCache {
        val sheen = Brush.verticalGradient(
            0f to Color.White.copy(alpha = .024f), .28f to Color.Transparent, 1f to Color.Transparent
        )
        val panel = IntSize(size.width.roundToInt(), size.height.roundToInt())
        onDrawBehind {
            drawImage(texture, dstSize = panel, filterQuality = FilterQuality.Medium)
            drawRect(sheen)
        }
    }.border(.8.dp, rim, shape)
}

private fun contrastSafePopupPixel(pixel: Int): Int {
    val color = Color(pixel)
    val luminance = color.luminance()
    val excess = luminance - .12f
    if (excess <= 0f) return pixel
    // Compress bright texels in linear light, preserving their hue and every darker texel.
    val headroom = .029f
    val scale = (.12f + headroom * excess / (headroom + excess)) / luminance
    val linear = color.convert(ColorSpaces.LinearSrgb)
    return Color(linear.red * scale, linear.green * scale, linear.blue * scale,
        colorSpace = ColorSpaces.LinearSrgb).toArgb()
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
        MaterialTheme(colorScheme = scheme, content = content)
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
