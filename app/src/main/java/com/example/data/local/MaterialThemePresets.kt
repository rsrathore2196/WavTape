package com.example.data.local

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.ui.theme.TonalThemeColors
import com.example.ui.theme.colorutilities.hct.Hct
import com.example.ui.theme.colorutilities.palettes.TonalPalette
import java.util.Locale

/**
 * The selectable Material 3 themes. A theme has one identity and two tonal schemes;
 * changing the appearance mode never selects a different named preset.
 *
 * The softly coloured accents have independent primary, secondary and tertiary HCT
 * palettes. Neutrals retain a little of the primary hue, while standard Material tones
 * provide the surface hierarchy and readable foregrounds in both modes.
 */
object MaterialThemePresets {
    const val DEFAULT_ID = "material_tideglass"
    const val AMOLED_ID = "material_night_ink"

    private data class Recipe(
        val id: String,
        val name: String,
        val subtitle: String,
        val accentName: String,
        val primaryHue: Double,
        val primaryChroma: Double,
        val secondaryHue: Double,
        val secondaryChroma: Double,
        val tertiaryHue: Double,
        val tertiaryChroma: Double,
        val amoled: Boolean = false
    )

    private data class Resolved(val preset: AppThemePreset, val roles: TonalThemeColors)

    private class PairedTheme(val recipe: Recipe) {
        // TonalPalette itself is mutable. Keep it local to generation and publish only
        // immutable colours; lazy's synchronized publication also handles startup races.
        val light: Resolved by lazy { resolve(recipe, dark = false) }
        val dark: Resolved by lazy { resolve(recipe, dark = true) }

        fun resolve(dark: Boolean): Resolved = if (dark) this.dark else light
    }

    private val themes = listOf(
        PairedTheme(Recipe(
            DEFAULT_ID, "Tideglass", "Ribbon blue, aqua and periwinkle", "Ribbon Blue",
            // Sampled from the WavTape mark: preserve its blue/cyan identity, with
            // moderated chroma so controls and containers feel like Material surfaces.
            Hct.fromInt(0xFF0875F9.toInt()).hue, 42.0,
            Hct.fromInt(0xFF00D9E7.toInt()).hue, 32.0,
            285.0, 28.0
        )),
        PairedTheme(Recipe(
            "material_mosslight", "Mosslight", "Soft fern, mint and warm linen", "Fern",
            155.0, 34.0, 175.0, 22.0, 80.0, 26.0
        )),
        PairedTheme(Recipe(
            "material_saffron_haze", "Saffron Haze", "Honey, clay and toasted gold", "Saffron",
            80.0, 38.0, 55.0, 24.0, 105.0, 28.0
        )),
        PairedTheme(Recipe(
            "material_petal_drift", "Petal Drift", "Blush, mauve and rosewood", "Petal",
            8.0, 34.0, 340.0, 22.0, 35.0, 26.0
        )),
        PairedTheme(Recipe(
            "material_lilac_loom", "Lilac Loom", "Lilac, iris and soft blue", "Lilac",
            305.0, 36.0, 280.0, 24.0, 245.0, 26.0
        )),
        PairedTheme(Recipe(
            "material_apricot_veil", "Apricot Veil", "Apricot, peach and muted coral", "Apricot",
            42.0, 36.0, 65.0, 24.0, 15.0, 28.0
        )),
        PairedTheme(Recipe(
            "material_lagoon_mist", "Lagoon Mist", "Sea teal, eucalyptus and mist blue", "Lagoon",
            195.0, 34.0, 165.0, 22.0, 230.0, 28.0
        )),
        PairedTheme(Recipe(
            "material_olive_muse", "Olive Muse", "Olive, oat and fresh sage", "Olive",
            118.0, 34.0, 95.0, 22.0, 145.0, 26.0
        )),
        PairedTheme(Recipe(
            "material_mulberry_silk", "Mulberry Silk", "Mulberry, orchid and dusky rose", "Mulberry",
            342.0, 36.0, 315.0, 24.0, 12.0, 26.0
        )),
        PairedTheme(Recipe(
            AMOLED_ID, "Night Ink", "Pure black with clean neutral accents", "Neutral",
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, amoled = true
        ))
    )
    private val byId = themes.associateBy { it.recipe.id }
    private val lightPresets: List<AppThemePreset> by lazy { themes.map { it.light.preset } }
    private val darkPresets: List<AppThemePreset> by lazy { themes.map { it.dark.preset } }

    /** Exactly ten choices, in the same order and with the same IDs in either mode. */
    fun all(dark: Boolean): List<AppThemePreset> = if (dark) darkPresets else lightPresets

    fun findById(id: String, dark: Boolean): AppThemePreset? = byId[id]?.resolve(dark)?.preset

    fun default(dark: Boolean): AppThemePreset = checkNotNull(findById(DEFAULT_ID, dark))

    fun roles(id: String, dark: Boolean): TonalThemeColors? = byId[id]?.resolve(dark)?.roles

    private fun resolve(recipe: Recipe, dark: Boolean): Resolved {
        val primary = TonalPalette.fromHueAndChroma(recipe.primaryHue, recipe.primaryChroma)
        val secondary = TonalPalette.fromHueAndChroma(recipe.secondaryHue, recipe.secondaryChroma)
        val tertiary = TonalPalette.fromHueAndChroma(recipe.tertiaryHue, recipe.tertiaryChroma)
        val neutral = TonalPalette.fromHueAndChroma(recipe.primaryHue, if (recipe.amoled) 0.0 else 6.0)
        val variant = TonalPalette.fromHueAndChroma(recipe.primaryHue, if (recipe.amoled) 0.0 else 8.0)
        fun TonalPalette.at(light: Int, night: Int) = Color(tone(if (dark) night else light))
        val amoled = recipe.amoled && dark
        val roles = TonalThemeColors(
            primary = primary.at(40, if (amoled) 100 else 80),
            onPrimary = primary.at(100, 20),
            primaryContainer = primary.at(90, 30),
            onPrimaryContainer = primary.at(10, 90),
            secondary = secondary.at(40, 80),
            onSecondary = secondary.at(100, 20),
            secondaryContainer = secondary.at(90, 30),
            onSecondaryContainer = secondary.at(10, 90),
            tertiary = tertiary.at(40, 80),
            onTertiary = tertiary.at(100, 20),
            tertiaryContainer = tertiary.at(90, 30),
            onTertiaryContainer = tertiary.at(10, 90),
            background = neutral.at(98, if (amoled) 0 else 6),
            onBackground = neutral.at(10, 90),
            surface = neutral.at(98, if (amoled) 0 else 6),
            onSurface = neutral.at(10, 90),
            surfaceVariant = variant.at(90, if (amoled) 0 else 30),
            onSurfaceVariant = variant.at(30, 80),
            surfaceContainerLowest = neutral.at(100, if (amoled) 0 else 4),
            surfaceContainerLow = neutral.at(96, if (amoled) 0 else 10),
            surfaceContainer = neutral.at(94, if (amoled) 0 else 12),
            surfaceContainerHigh = neutral.at(92, if (amoled) 0 else 17),
            surfaceContainerHighest = neutral.at(90, if (amoled) 0 else 22),
            surfaceDim = neutral.at(87, if (amoled) 0 else 6),
            surfaceBright = neutral.at(98, if (amoled) 0 else 24),
            outline = variant.at(50, 60),
            outlineVariant = variant.at(80, 30)
        )
        return Resolved(
            AppThemePreset(
                id = recipe.id,
                name = recipe.name,
                subtitle = recipe.subtitle,
                isDark = dark,
                accentName = recipe.accentName,
                accentShade = "Material 3",
                accentColorHex = roles.primary.hex(),
                secondaryAccentHex = roles.secondary.hex(),
                canvasColorName = "${recipe.name} canvas",
                canvasColorHex = roles.background.hex(),
                cardColorName = "${recipe.name} surface",
                cardColorHex = roles.surfaceContainerHigh.hex(),
                cardBorderHex = roles.outlineVariant.hex(),
                textPrimaryHex = roles.onSurface.hex(),
                textSecondaryHex = roles.onSurfaceVariant.hex(),
                // Remains readable even on the highest surface container in either mode.
                textMutedHex = variant.at(40, 80).hex(),
                bgGradientColorsHex = listOf(
                    roles.surfaceContainerLow.hex(), roles.background.hex(), roles.surfaceContainerLowest.hex()
                ),
                cardGradientColorsHex = listOf(roles.surfaceContainerHigh.hex(), roles.surfaceContainer.hex()),
                bottomSheetGradientColorsHex = listOf(roles.surfaceContainerHigh.hex(), roles.surfaceContainerLow.hex()),
                bgGradientName = "${recipe.name} tonal canvas",
                cardGradientName = "${recipe.name} tonal surface",
                bottomSheetGradientName = "${recipe.name} tonal sheet",
                isAmoled = amoled
            ),
            roles
        )
    }

    private fun Color.hex(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)
}
