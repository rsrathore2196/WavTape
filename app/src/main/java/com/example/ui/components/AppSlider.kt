package com.example.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.example.ui.theme.LocalAppColors

/** Shared lyrics-style slider with Material's native touch, keyboard and accessibility behavior. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    accent: Color = LocalAppColors.current.primaryAccent,
    partTagPrefix: String = "app_slider"
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent,
        inactiveTrackColor = LocalAppColors.current.textSecondary.copy(alpha = .25f))
    Slider(value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
        valueRange = valueRange, onValueChangeFinished = onValueChangeFinished,
        colors = colors, interactionSource = interactionSource,
        thumb = { SliderDefaults.Thumb(interactionSource, Modifier.testTag("${partTagPrefix}_thumb"), colors, enabled) },
        track = { state -> SliderDefaults.Track(state, Modifier.testTag("${partTagPrefix}_track"),
            enabled = enabled, colors = colors) })
}
