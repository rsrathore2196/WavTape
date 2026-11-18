package com.example.ui.theme

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Self-contained, interactive preview; selections do not overwrite a saved app theme.
 * Pass a decoded album cover to enable the artwork toggle. To apply selections in an
 * app settings host, supply [onApply] and persist the options in that host's state owner.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemePreviewScreen(
    modifier: Modifier = Modifier,
    initialOptions: TonalThemeOptions = TonalThemeOptions(),
    initiallyDark: Boolean = false,
    artworkBitmap: Bitmap? = null,
    onApply: ((TonalThemeOptions, Boolean) -> Unit)? = null
) {
    var seed by rememberSaveable { mutableIntStateOf(initialOptions.seedColor) }
    var style by rememberSaveable { mutableStateOf(initialOptions.style) }
    var dark by rememberSaveable { mutableStateOf(initiallyDark) }
    var system by rememberSaveable { mutableStateOf(initialOptions.useSystemDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) }
    var artwork by rememberSaveable { mutableStateOf(initialOptions.useAlbumArtwork) }
    val options = remember(seed, style, system, artwork) { TonalThemeOptions(seed, style, system, artwork) }
    TonalMaterialTheme(options, dark, artworkBitmap) {
        val scheme = MaterialTheme.colorScheme
        val surfaces = listOf(
            "surfaceContainerLowest" to scheme.surfaceContainerLowest,
            "surfaceContainerLow" to scheme.surfaceContainerLow,
            "surfaceContainer" to scheme.surfaceContainer,
            "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            "surfaceContainerHighest" to scheme.surfaceContainerHighest,
            "surface" to scheme.surface,
            "surfaceDim" to scheme.surfaceDim,
            "surfaceBright" to scheme.surfaceBright,
            "surfaceVariant" to scheme.surfaceVariant
        )
        val accents = listOf(
            "primary" to scheme.primary, "onPrimary" to scheme.onPrimary,
            "primaryContainer" to scheme.primaryContainer, "onPrimaryContainer" to scheme.onPrimaryContainer,
            "secondary" to scheme.secondary, "onSecondary" to scheme.onSecondary,
            "secondaryContainer" to scheme.secondaryContainer, "onSecondaryContainer" to scheme.onSecondaryContainer,
            "tertiary" to scheme.tertiary, "onTertiary" to scheme.onTertiary,
            "tertiaryContainer" to scheme.tertiaryContainer, "onTertiaryContainer" to scheme.onTertiaryContainer
        )
        val remaining = listOf(
            "background" to scheme.background, "onBackground" to scheme.onBackground,
            "onSurface" to scheme.onSurface, "onSurfaceVariant" to scheme.onSurfaceVariant,
            "surfaceTint" to scheme.surfaceTint, "outline" to scheme.outline,
            "outlineVariant" to scheme.outlineVariant, "inversePrimary" to scheme.inversePrimary,
            "inverseSurface" to scheme.inverseSurface, "inverseOnSurface" to scheme.inverseOnSurface,
            "error" to scheme.error, "onError" to scheme.onError,
            "errorContainer" to scheme.errorContainer, "onErrorContainer" to scheme.onErrorContainer,
            "scrim" to scheme.scrim
        )
        Surface(modifier.fillMaxSize().testTag("tonal_theme_preview"), color = scheme.background) {
            LazyColumn(modifier = Modifier.testTag("tonal_role_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Tonal themes", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Explore color, contrast and surface depth", color = scheme.onSurfaceVariant)
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TonalThemePreset.entries.forEach { preset ->
                            FilterChip(selected = seed == preset.seedColor && !system,
                                onClick = { seed = preset.seedColor; system = false },
                                label = { Text(preset.title) },
                                leadingIcon = { Box(Modifier.size(14.dp).background(Color(preset.seedColor), CircleShape)) },
                                modifier = Modifier.testTag("tonal_seed_${preset.name}"))
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaletteStyle.entries.forEach { recipe ->
                            FilterChip(selected = style == recipe, onClick = { style = recipe }, enabled = !system,
                                label = { Text(recipe.title) }, modifier = Modifier.testTag("tonal_style_${recipe.name}"))
                        }
                    }
                }
                item {
                    PreviewToggle("Dark theme", dark, { dark = it }, "tonal_dark_toggle")
                    PreviewToggle("System wallpaper colors", system, { system = it }, "tonal_system_toggle",
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PreviewToggle("Match album artwork", artwork, { artwork = it }, "tonal_artwork_toggle",
                        enabled = !system && (artwork || (artworkBitmap != null && !artworkBitmap.isRecycled)))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) Text("Wallpaper colors require Android 12 or newer.",
                        color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (artworkBitmap == null) Text("Album cover unavailable", color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    if (onApply != null) Button(onClick = { onApply(options, dark) }, modifier = Modifier.testTag("tonal_apply")) {
                        Text("Apply theme")
                    }
                }
                item { Text("Components", style = MaterialTheme.typography.titleLarge) }
                item { ThemeComponentPreview() }
                item {
                    val primaryContrast = TonalContrast.ratio(scheme.onPrimary, scheme.primary)
                    val surfaceContrast = TonalContrast.ratio(scheme.onSurface, scheme.surface)
                    Text(String.format(Locale.ROOT, "Text contrast · Primary %.2f:1 · Surface %.2f:1", primaryContrast, surfaceContrast),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                item { Text("Surface hierarchy", style = MaterialTheme.typography.titleLarge) }
                items(surfaces.chunked(2), key = { "surfaces_${it.first().first}" }) { SwatchRow(it) }
                item { Text("Accent roles", style = MaterialTheme.typography.titleLarge) }
                items(accents.chunked(2), key = { "accents_${it.first().first}" }) { SwatchRow(it) }
                item { Text("Content, outlines and status", style = MaterialTheme.typography.titleLarge) }
                items(remaining.chunked(2), key = { "remaining_${it.first().first}" }) { SwatchRow(it) }
            }
        }
    }
}

@Composable
private fun PreviewToggle(title: String, value: Boolean, onChange: (Boolean) -> Unit, tag: String, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().toggleable(value, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .testTag(tag).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(value, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SwatchRow(roles: List<Pair<String, Color>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        roles.forEach { (name, color) ->
            Surface(color = color, contentColor = contrastingContentColor(color), shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f).testTag("tonal_swatch_$name")) {
                Column(Modifier.padding(12.dp).heightIn(min = 72.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(name, style = MaterialTheme.typography.labelMedium)
                    Text(String.format(Locale.ROOT, "#%06X", color.toArgb() and 0xFFFFFF), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (roles.size == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ThemeComponentPreview() {
    var checked by rememberSaveable { mutableStateOf(true) }
    var volume by rememberSaveable { mutableFloatStateOf(.65f) }
    var selected by rememberSaveable { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tonight's rotation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Your favorite sounds, a fresh perspective.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { selected = !selected }, modifier = Modifier.testTag("tonal_sample_button")) {
                    Text(if (selected) "Selected" else "Primary")
                }
                FilledTonalButton(onClick = { checked = !checked }) { Text("Secondary") }
            }
            PreviewToggle("Sample switch", checked, { checked = it }, "tonal_sample_switch")
            Slider(volume, { volume = it }, modifier = Modifier.testTag("tonal_sample_slider").semantics { contentDescription = "Sample level" })
        }
    }
}

@Preview(name = "Tonal palette · Light", showBackground = true)
@Composable
private fun LightTonalPreview() = ThemePreviewScreen()

@Preview(name = "Tonal palette · Dark", showBackground = true)
@Composable
private fun DarkTonalPreview() = ThemePreviewScreen(initiallyDark = true)
