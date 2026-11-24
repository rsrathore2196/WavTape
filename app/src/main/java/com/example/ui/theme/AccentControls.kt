package com.example.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/** One palette supplies every accent fill; no hue rotation or independent color generation. */
fun AppThemeColors.accentBrush(alpha: Float = 1f, over: Color? = null): Brush =
    artworkGlass?.brush(onAccentGradient) ?: Brush.horizontalGradient(
        listOf(primaryAccent, secondaryAccent).map { color ->
            color.copy(alpha = alpha).let { if (over == null) it else it.compositeOver(over) }
        }
    )

val AppThemeColors.onAccentGradient: Color
    get() = LiquidGlass.contrastColor(onPrimaryAccent,
        (0..4).map { lerp(primaryAccent, secondaryAccent, it / 4f) }, 4.5f)

/** Retains Material interaction, shape, disabled appearance and accessibility semantics. */
@Composable
fun AccentButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape, colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val palette = LocalAppColors.current
    // Button's Surface uses Color.Transparent so the accent gradient shows through; passing
    // non-zero elevation causes Surface to cast a shadow inside the transparent layer.
    val fill = if (!enabled) Modifier else if (palette.artworkGlass != null) {
        Modifier.accentBackground(palette, palette.primaryAccent, shape, palette.onAccentGradient)
    } else Modifier.background(palette.accentBrush(), shape)
    Button(onClick, modifier.then(fill),
        enabled, shape, colors.copy(containerColor = Color.Transparent, contentColor = palette.onAccentGradient),
        elevation = null, border, contentPadding, interactionSource, content)
}

/** Material controls with an existing explicit color keep their original ink and interaction. */
@Composable
internal fun ThemeAccentButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape, colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val palette = LocalAppColors.current
    val glass = enabled && palette.artworkGlass != null && palette.hasAccentFill(colors.containerColor)
    Button(onClick,
        if (glass) modifier.accentBackground(palette, colors.containerColor, shape, colors.contentColor) else modifier,
        enabled, shape, if (glass) colors.copy(containerColor = Color.Transparent) else colors,
        if (glass) null else elevation, border, contentPadding, interactionSource, content)
}

@Composable
internal fun ThemeAccentSurface(
    modifier: Modifier = Modifier, shape: Shape = androidx.compose.ui.graphics.RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface, contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp, shadowElevation: Dp = 0.dp, border: BorderStroke? = null,
    content: @Composable () -> Unit
) {
    val palette = LocalAppColors.current
    val glass = palette.artworkGlass != null && palette.hasAccentFill(color)
    Surface(if (glass) modifier.accentBackground(palette, color, shape, contentColor) else modifier,
        shape, if (glass) Color.Transparent else color, contentColor, tonalElevation,
        if (glass) 0.dp else shadowElevation, border, content)
}

@Composable
internal fun ThemeAccentSurface(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = androidx.compose.ui.graphics.RectangleShape, color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color), tonalElevation: Dp = 0.dp, shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null, interactionSource: MutableInteractionSource? = null, content: @Composable () -> Unit
) {
    val palette = LocalAppColors.current
    val glass = enabled && palette.artworkGlass != null && palette.hasAccentFill(color)
    Surface(onClick, if (glass) modifier.accentBackground(palette, color, shape, contentColor) else modifier,
        enabled, shape, if (glass) Color.Transparent else color, contentColor, tonalElevation,
        if (glass) 0.dp else shadowElevation, border, interactionSource, content)
}

/** Material's 52 x 32dp switch track shares the same fill without changing its thumb animation. */
@Composable
fun AccentSwitch(
    checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null, enabled: Boolean = true,
    colors: SwitchColors = liquidGlassSwitchColors(LocalAppColors.current),
    interactionSource: MutableInteractionSource? = null
) {
    val palette = LocalAppColors.current
    val artwork = palette.artworkGlass
    Switch(checked, onCheckedChange, modifier.drawWithCache {
        val brush = artwork?.brush(palette.onAccentGradient) ?: palette.accentBrush()
        val track = Size(52.dp.toPx(), 32.dp.toPx())
        val origin = Offset((size.width - track.width) / 2f, (size.height - track.height) / 2f)
        onDrawBehind {
            if (enabled) {
                if (artwork == null) drawRoundRect(brush, origin, track, CornerRadius(track.height / 2f))
                else inset(origin.x, origin.y, size.width - origin.x - track.width, size.height - origin.y - track.height) {
                    drawRoundRect(brush, cornerRadius = CornerRadius(track.height / 2f))
                }
            }
        }
    }, thumbContent, enabled,
        // Material animates the opaque off color over the gradient. Its existing
        // track and thumb clocks remain the only animation owners.
        if (enabled) colors.copy(checkedTrackColor = Color.Transparent, checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = colors.uncheckedTrackColor.compositeOver(palette.cardBackground),
            checkedThumbColor = palette.onAccentGradient) else colors,
        interactionSource)
}
