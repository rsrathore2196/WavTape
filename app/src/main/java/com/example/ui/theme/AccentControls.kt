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
import androidx.compose.ui.unit.dp

/** One palette supplies every accent fill; no hue rotation or independent color generation. */
fun AppThemeColors.accentBrush(alpha: Float = 1f, over: Color? = null): Brush = Brush.horizontalGradient(
    listOf(primaryAccent, secondaryAccent).map { color ->
        color.copy(alpha = alpha).let { if (over == null) it else it.compositeOver(over) }
    })

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
    Button(onClick, modifier.then(if (enabled) Modifier.background(palette.accentBrush(), shape) else Modifier),
        enabled, shape, colors.copy(containerColor = Color.Transparent, contentColor = palette.onAccentGradient),
        elevation, border, contentPadding, interactionSource, content)
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
    Switch(checked, onCheckedChange, modifier.drawWithCache {
        val brush = palette.accentBrush()
        val track = Size(52.dp.toPx(), 32.dp.toPx())
        val origin = Offset((size.width - track.width) / 2f, (size.height - track.height) / 2f)
        onDrawBehind {
            if (enabled) drawRoundRect(brush, origin, track, CornerRadius(track.height / 2f))
        }
    }, thumbContent, enabled,
        // Material animates the opaque off color over the gradient. Its existing
        // track and thumb clocks remain the only animation owners.
        if (enabled) colors.copy(checkedTrackColor = Color.Transparent, checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = colors.uncheckedTrackColor.compositeOver(palette.cardBackground),
            checkedThumbColor = palette.onAccentGradient) else colors,
        interactionSource)
}
