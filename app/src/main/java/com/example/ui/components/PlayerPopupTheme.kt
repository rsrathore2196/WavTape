package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.performance.AppDispatchers
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LightAppColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.resolveColorScheme
import com.example.ui.util.PlayerArtworkSample
import com.example.ui.util.blurredPopupPixels
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// Settings-style dialogs use the app palette. The six glass panels share one prepared cover.
internal val LocalPlayerPopupColors = compositionLocalOf<AppThemeColors?> { null }
internal val LocalPlayerPopupArtwork = compositionLocalOf<PlayerPopupArtwork?> { null }

internal class PlayerPopupArtwork(val texture: ImageBitmap, val darkContent: Boolean)

@Composable
internal fun rememberPlayerPopupArtwork(sample: PlayerArtworkSample?, playerVisible: Boolean): PlayerPopupArtwork? {
    val profile = LocalLiquidGlassProfile.current
    val configuration = LocalConfiguration.current
    val shortSide = minOf(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(240)
    val blurFraction = profile.blurRadius(24.dp, false).value / shortSide
    var prepared by remember(sample, blurFraction) { mutableStateOf<PlayerPopupArtwork?>(null) }
    LaunchedEffect(sample, blurFraction, playerVisible) {
        if (sample == null || !playerVisible || prepared != null) return@LaunchedEffect
        prepared = withContext(AppDispatchers.CpuBounded) {
            val pixels = sample.blurredPopupPixels(blurFraction)
            val averageLuminance = pixels.sumOf { Color(it).luminance().toDouble() } / pixels.size
            PlayerPopupArtwork(Bitmap.createBitmap(pixels, sample.width, sample.height, Bitmap.Config.ARGB_8888)
                .asImageBitmap(), darkContent = averageLuminance >= .18)
        }
    }
    return prepared
}

private val darkGlassColors by lazy {
    playerPopupColors(DarkAppColors.copy(primaryAccent = Color.White, secondaryAccent = Color.White))
        .copy(textSecondary = Color(0xFFFAFAFA), textMuted = Color(0xFFF5F5F5))
}
private val lightGlassColors by lazy {
    playerPopupColors(LightAppColors.copy(primaryAccent = Color.Black, secondaryAccent = Color.Black))
        .copy(textSecondary = Color(0xFF151515), textMuted = Color(0xFF202020))
}

@Composable
internal fun playerGlassPopupColors(): AppThemeColors =
    if (LocalDynamicGlassEnabled.current && LocalPlayerPopupArtwork.current?.darkContent == true) lightGlassColors
    else darkGlassColors

@Composable
internal fun playerPopupSurfaceColor(): Color =
    if (LocalDynamicGlassEnabled.current && LocalPlayerPopupArtwork.current != null) Color.Transparent
    else darkGlassColors.cardBackground

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

/** One bitmap draw per panel. Animating a popup never captures or filters the player window. */
@Composable
internal fun Modifier.playerPopupGlass(shape: Shape): Modifier {
    if (!LocalDynamicGlassEnabled.current) return this
    val texture = LocalPlayerPopupArtwork.current?.texture ?: return this
    val rim = remember {
        Brush.linearGradient(listOf(Color.White.copy(alpha = .49f),
            Color.White.copy(alpha = .08f), Color.White.copy(alpha = .22f)))
    }
    return clip(shape).drawWithCache {
        val sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = .024f), .28f to Color.Transparent)
        val scale = maxOf(size.width / texture.width, size.height / texture.height).coerceAtLeast(.001f)
        val width = (size.width / scale).roundToInt().coerceIn(1, texture.width)
        val height = (size.height / scale).roundToInt().coerceIn(1, texture.height)
        val crop = IntOffset((texture.width - width) / 2, (texture.height - height) / 2)
        val panel = IntSize(size.width.roundToInt(), size.height.roundToInt())
        onDrawBehind {
            drawImage(texture, srcOffset = crop, srcSize = IntSize(width, height), dstSize = panel,
                filterQuality = FilterQuality.Medium)
            drawRect(sheen)
        }
    }.border(.8.dp, rim, shape)
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
    val typography = MaterialTheme.typography
    val artwork = LocalPlayerPopupArtwork.current
    val outline = if (LocalDynamicGlassEnabled.current && artwork != null &&
        (colors === darkGlassColors || colors === lightGlassColors))
        Shadow(if (artwork.darkContent) Color.White else Color.Black, blurRadius = with(LocalDensity.current) { 2.dp.toPx() })
    else null
    // The halo is confined to glyphs so mixed-luminance covers retain their original colors.
    val popupTypography = remember(typography, outline) {
        if (outline == null) typography else typography.copy(
            titleLarge = typography.titleLarge.copy(shadow = outline),
            titleMedium = typography.titleMedium.copy(shadow = outline),
            titleSmall = typography.titleSmall.copy(shadow = outline),
            bodyLarge = typography.bodyLarge.copy(shadow = outline),
            bodyMedium = typography.bodyMedium.copy(shadow = outline),
            bodySmall = typography.bodySmall.copy(shadow = outline),
            labelLarge = typography.labelLarge.copy(shadow = outline),
            labelMedium = typography.labelMedium.copy(shadow = outline),
            labelSmall = typography.labelSmall.copy(shadow = outline)
        )
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = popupTypography, content = content)
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
