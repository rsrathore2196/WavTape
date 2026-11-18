package com.example

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.ui.theme.*
import com.example.ui.theme.colorutilities.hct.Hct
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cbrt

class TonalPaletteEngineTest {
    private val seeds = TonalThemePreset.entries.map { it.seedColor } + listOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFFFF0000.toInt(),
        0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt(), 0xFF00FFFF.toInt()
    )

    @Test fun allStylesAndSeedsHonorToneRolesAndOpaqueSurfaceHierarchyInBothModes() {
        for (seed in seeds) for (style in PaletteStyle.entries) for (dark in listOf(false, true)) {
            val s = ThemePaletteGenerator.generate(seed, dark, style)
            val context = "${seed.toUInt().toString(16)} / $style / dark=$dark"
            val tones = listOf(
                s.primary to if (dark) 80 else 40, s.onPrimary to if (dark) 20 else 100,
                s.primaryContainer to if (dark) 30 else 90, s.onPrimaryContainer to if (dark) 90 else 10,
                s.secondary to if (dark) 80 else 40, s.tertiary to if (dark) 80 else 40,
                s.surface to if (dark) 6 else 98,
                s.surfaceContainerLowest to if (dark) 4 else 100,
                s.surfaceContainerLow to if (dark) 10 else 96,
                s.surfaceContainer to if (dark) 12 else 94,
                s.surfaceContainerHigh to if (dark) 17 else 92,
                s.surfaceContainerHighest to if (dark) 22 else 90,
                s.outlineVariant to if (dark) 30 else 80
            )
            for ((color, expected) in tones) {
                // Independent CIE L* calculation catches RGB/HSL approximations and missing mappings.
                assertEquals(context, expected.toDouble(), tone(color), .65)
                assertEquals(context, 1f, color.alpha, 0f)
            }
            assertEquals(context, s.surface, s.background)
            val surfaces = listOf(s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer,
                s.surfaceContainerHigh, s.surfaceContainerHighest).map(::tone)
            surfaces.zipWithNext().forEach { (a, b) -> assertTrue(context, if (dark) a < b else a > b) }
            pairs(s).forEach { (foreground, background) -> assertTrue(context, TonalContrast.ratio(foreground, background) >= 4.5f) }
            for (surface in listOf(s.surfaceDim, s.surfaceBright, s.surfaceContainerLowest, s.surfaceContainerLow,
                s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)) {
                assertTrue(context, TonalContrast.ratio(s.onSurface, surface) >= 4.5f)
            }
        }
    }

    @Test fun neutralsRetainSeedHueWithSubtleChromaRatherThanTheAccentOrFlatGray() {
        for (seed in TonalThemePreset.entries) for (style in PaletteStyle.entries) for (dark in listOf(false, true)) {
            val scheme = ThemePaletteGenerator.generate(seed.seedColor, dark, style)
            val neutral = Hct.fromInt(scheme.surfaceContainer.toArgb())
            val variant = Hct.fromInt(scheme.outlineVariant.toArgb())
            val hue = Hct.fromInt(seed.seedColor).hue
            for (color in listOf(neutral, variant)) {
                assertTrue("$seed $style: chroma=${color.chroma}", color.chroma in 4.0..8.7)
                val delta = abs(color.hue - hue)
                assertTrue("Seed-hue tint is preserved", minOf(delta, 360 - delta) < 8.0)
            }
        }
    }

    @Test fun stylesUseDistinctGoogleAccentRecipesAndFidelityPreservesTheSeed() {
        val seed = 0xFF6750A4.toInt()
        val schemes = PaletteStyle.entries.associateWith { ThemePaletteGenerator.generate(seed, false, it) }
        val source = Hct.fromInt(seed)
        assertEquals(source.hue, Hct.fromInt(schemes.getValue(PaletteStyle.FIDELITY).primary.toArgb()).hue, 1.0)
        assertEquals(source.chroma, Hct.fromInt(schemes.getValue(PaletteStyle.FIDELITY).primary.toArgb()).chroma, 1.0)
        assertTrue(Hct.fromInt(schemes.getValue(PaletteStyle.VIBRANT).primary.toArgb()).chroma >
            Hct.fromInt(schemes.getValue(PaletteStyle.TONAL_SPOT).primary.toArgb()).chroma + 15)
        assertEquals(4, schemes.values.map { Triple(it.primary, it.secondary, it.tertiary) }.distinct().size)
        assertEquals(36, allRoles(schemes.values.first()).size)
        schemes.values.forEach { allRoles(it).forEach { color -> assertEquals(1f, color.alpha, 0f) } }
    }

    @Test fun contrastGuardRepairsBadContentWithoutChangingTheBackgroundPalette() {
        val original = ThemePaletteGenerator.generate(seeds.first(), false)
        val protected = TonalContrast.protect(original.copy(onPrimary = original.primary,
            onSurface = Color.Transparent, onSurfaceVariant = original.surfaceVariant))
        assertEquals(original.primary, protected.primary)
        assertEquals(original.surface, protected.surface)
        assertEquals(original.surfaceVariant, protected.surfaceVariant)
        pairs(protected).forEach { (a, b) -> assertTrue(TonalContrast.ratio(a, b) >= 4.5f) }
        assertEquals(1f, TonalContrast.ratio(Color.Transparent, Color.White), .0001f)
    }

    @Test fun alphaIsNormalizedAndConcurrentRequestsReturnOneStableCachedScheme() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val schemes = executor.invokeAll((1..8).map { Callable {
                ThemePaletteGenerator.generate(0x336F4AD2, true, PaletteStyle.FIDELITY)
            } }).map { it.get() }
            schemes.forEach { assertSame(schemes.first(), it) }
            assertSame(schemes.first(), ThemePaletteGenerator.generate(0xFF6F4AD2.toInt(), true, PaletteStyle.FIDELITY))
        } finally { executor.shutdownNow() }
    }

    private fun tone(color: Color): Double {
        fun linear(v: Float) = if (v <= .04045) v / 12.92 else Math.pow((v + .055) / 1.055, 2.4)
        val y = .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
        return if (y <= 216.0 / 24389.0) 24389.0 / 27.0 * y else 116 * cbrt(y) - 16
    }
}

internal fun pairs(s: ColorScheme): List<Pair<Color, Color>> = listOf(
    s.onPrimary to s.primary, s.onPrimaryContainer to s.primaryContainer,
    s.onSecondary to s.secondary, s.onSecondaryContainer to s.secondaryContainer,
    s.onTertiary to s.tertiary, s.onTertiaryContainer to s.tertiaryContainer,
    s.onError to s.error, s.onErrorContainer to s.errorContainer,
    s.onSurface to s.surface, s.onSurfaceVariant to s.surfaceVariant,
    s.onBackground to s.background, s.inverseOnSurface to s.inverseSurface,
    s.inversePrimary to s.inverseSurface
)

internal fun allRoles(s: ColorScheme): List<Color> = listOf(
    s.primary, s.onPrimary, s.primaryContainer, s.onPrimaryContainer, s.inversePrimary,
    s.secondary, s.onSecondary, s.secondaryContainer, s.onSecondaryContainer,
    s.tertiary, s.onTertiary, s.tertiaryContainer, s.onTertiaryContainer,
    s.background, s.onBackground, s.surface, s.onSurface, s.surfaceVariant, s.onSurfaceVariant,
    s.surfaceTint, s.inverseSurface, s.inverseOnSurface, s.error, s.onError, s.errorContainer,
    s.onErrorContainer, s.outline, s.outlineVariant, s.scrim, s.surfaceDim, s.surfaceBright,
    s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest
)
