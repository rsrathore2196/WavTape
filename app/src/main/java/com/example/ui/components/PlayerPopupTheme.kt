package com.example.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.ui.components.backdrop.BackdropEffectScope
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.resolveColorScheme
import com.example.ui.components.backdrop.backdrops.LayerBackdrop
import com.example.ui.components.backdrop.backdrops.layerBackdrop
import com.example.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.example.ui.components.backdrop.drawBackdrop
import com.example.ui.components.backdrop.effects.blur
import com.example.ui.components.backdrop.effects.colorControls

// Settings-style dialogs use the app palette. Player glass reads the player window underneath.
internal val LocalPlayerPopupColors = compositionLocalOf<AppThemeColors?> { null }
internal val LocalPlayerPopupBackdrop = compositionLocalOf<PlayerPopupBackdrop?> { null }

internal class PlayerPopupBackdrop(val layer: LayerBackdrop) {
    private var readers by mutableIntStateOf(0)
    val inUse: Boolean get() = readers > 0
    fun addReader() { readers++ }
    fun removeReader() { readers-- }
}

@Composable
internal fun rememberPlayerPopupBackdrop(): PlayerPopupBackdrop {
    val layer = rememberLayerBackdrop(screenCoordinates = true)
    return remember(layer) { PlayerPopupBackdrop(layer) }
}

@Composable
internal fun Modifier.playerPopupBackdropSource(backdrop: PlayerPopupBackdrop): Modifier =
    if (backdrop.inUse) layerBackdrop(backdrop.layer) else this

internal val PlayerGlassPopupColors by lazy {
    playerPopupColors(DarkAppColors.copy(primaryAccent = Color.White, secondaryAccent = Color.White))
        .copy(textSecondary = Color(0xFFFAFAFA), textMuted = Color(0xFFF5F5F5))
}

@Composable
internal fun playerPopupSurfaceColor(): Color =
    if (LocalDynamicGlassEnabled.current) Color.Transparent else PlayerGlassPopupColors.cardBackground

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

/** Frost the actual player behind this panel. Foreground controls stay sharp. */
@Composable
internal fun Modifier.playerPopupGlass(shape: Shape): Modifier {
    if (!LocalDynamicGlassEnabled.current) return this
    val profile = LocalLiquidGlassProfile.current
    val scale = profile.backdropSampleScale
    val radius = with(LocalDensity.current) { profile.blurRadius(24.dp, false).toPx() } * scale
    val scene = LocalPlayerPopupBackdrop.current.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && radius > 0f }
    if (scene != null) DisposableEffect(scene) {
        scene.addReader()
        onDispose { scene.removeReader() }
    }
    val rim = remember {
        Brush.linearGradient(listOf(Color.White.copy(alpha = .49f),
            Color.White.copy(alpha = .08f), Color.White.copy(alpha = .22f)))
    }
    val sheen = remember {
        Brush.verticalGradient(0f to Color.White.copy(alpha = .024f), .28f to Color.Transparent)
    }
    val sampledBackdrop = if (scene != null) {
        val outline = remember(shape) { { shape } }
        val effects: BackdropEffectScope.() -> Unit = remember(radius) {
            { blur(radius); colorControls(saturation = 0f) }
        }
        Modifier.drawBackdrop(backdrop = scene.layer, shape = outline, backdropScale = scale,
            effects = effects, highlight = null, shadow = null)
    } else Modifier
    // A neutral transparent fill keeps the original white labels legible on bright artwork or controls.
    return clip(shape).then(sampledBackdrop)
        .background(Color.Black.copy(alpha = .58f))
        .background(sheen)
        .border(.8.dp, rim, shape)
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
