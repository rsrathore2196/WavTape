package com.example.ui.theme

// Accent recipes adapted from Google's ColorSpec2021 (Copyright Google LLC,
// Apache-2.0). See colorutilities/README.md for the pinned source and license.

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import com.example.performance.AppDispatchers
import com.example.ui.theme.colorutilities.blend.Blend
import com.example.ui.theme.colorutilities.dislike.DislikeAnalyzer
import com.example.ui.theme.colorutilities.hct.Hct
import com.example.ui.theme.colorutilities.palettes.TonalPalette
import com.example.ui.theme.colorutilities.temperature.TemperatureCache
import com.example.ui.theme.colorutilities.utils.MathUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Google's 2021 accent-palette recipes, with the explicit role tones documented below. */
enum class PaletteStyle(val title: String) {
    TONAL_SPOT("Tonal Spot"), VIBRANT("Vibrant"), EXPRESSIVE("Expressive"), FIDELITY("Fidelity")
}

enum class TonalThemePreset(val title: String, val seedColor: Int) {
    MIDNIGHT_INDIGO("Midnight Indigo", 0xFF5359D9.toInt()),
    WARM_AMBER_STUDIO("Warm Amber Studio", 0xFFE5A224.toInt()),
    EMERALD_PULSE("Emerald Pulse", 0xFF008B68.toInt()),
    ROSE_QUARTZ("Rose Quartz", 0xFFBD527E.toInt())
}

/** Opt in from the app's theme owner; a null option preserves existing saved/custom themes. */
@Immutable
data class TonalThemeOptions(
    val seedColor: Int = TonalThemePreset.MIDNIGHT_INDIGO.seedColor,
    val style: PaletteStyle = PaletteStyle.TONAL_SPOT,
    val useSystemDynamicColor: Boolean = false,
    val useAlbumArtwork: Boolean = false
)

/**
 * Complete opaque sRGB Material 3 schemes. Call on a worker for uncached user/image seeds;
 * Theme.kt does so automatically. The bounded cache contains colors, never Bitmaps/Contexts.
 *
 * Accent recipes follow Google's ColorSpec2021. Role tones deliberately remain standard
 * across styles: Fidelity retains the seed's hue/chroma, rather than changing container
 * tones to the seed's tone. Neutrals always retain the seed hue at chroma 6/8, including
 * Expressive/Vibrant (whose upstream neutrals otherwise rotate or have higher chroma).
 */
object ThemePaletteGenerator {
    private data class Key(val seed: Int, val dark: Boolean, val style: PaletteStyle)
    private val cache = object : LinkedHashMap<Key, ColorScheme>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ColorScheme>?): Boolean = size > 64
    }

    fun generate(seedColor: Color, isDark: Boolean, style: PaletteStyle = PaletteStyle.TONAL_SPOT): ColorScheme =
        generate(seedColor.toArgb(), isDark, style)

    fun generate(seedColor: Int, isDark: Boolean, style: PaletteStyle = PaletteStyle.TONAL_SPOT): ColorScheme {
        val key = Key(seedColor or (0xFF shl 24), isDark, style)
        cached(key)?.let { return it }
        // Do not hold a global lock while Fidelity's temperature complement is calculated.
        val generated = create(key)
        return synchronized(cache) { cache[key] ?: generated.also { cache[key] = it } }
    }

    internal fun cached(seedColor: Int, isDark: Boolean, style: PaletteStyle): ColorScheme? =
        cached(Key(seedColor or (0xFF shl 24), isDark, style))

    private fun cached(key: Key): ColorScheme? = synchronized(cache) { cache[key] }

    private fun create(key: Key): ColorScheme {
        val seed = Hct.fromInt(key.seed)
        val hue = seed.hue
        fun palette(h: Double, c: Double) = TonalPalette.fromHueAndChroma(MathUtils.sanitizeDegreesDouble(h), c)
        val primary = when (key.style) {
            PaletteStyle.TONAL_SPOT -> palette(hue, 36.0)
            PaletteStyle.VIBRANT -> palette(hue, 200.0)
            PaletteStyle.EXPRESSIVE -> palette(hue + 240.0, 40.0)
            PaletteStyle.FIDELITY -> palette(hue, seed.chroma)
        }
        val expressiveHues = doubleArrayOf(0.0, 21.0, 51.0, 121.0, 151.0, 191.0, 271.0, 321.0, 360.0)
        val vibrantHues = doubleArrayOf(0.0, 41.0, 61.0, 101.0, 131.0, 181.0, 251.0, 301.0, 360.0)
        val secondary = when (key.style) {
            PaletteStyle.TONAL_SPOT -> palette(hue, 16.0)
            PaletteStyle.FIDELITY -> palette(hue, max(seed.chroma - 32.0, seed.chroma * .5))
            PaletteStyle.EXPRESSIVE -> palette(rotatedHue(hue, expressiveHues,
                doubleArrayOf(45.0, 95.0, 45.0, 20.0, 45.0, 90.0, 45.0, 45.0, 45.0)), 24.0)
            PaletteStyle.VIBRANT -> palette(rotatedHue(hue, vibrantHues,
                doubleArrayOf(18.0, 15.0, 10.0, 12.0, 15.0, 18.0, 15.0, 12.0, 12.0)), 24.0)
        }
        val tertiary = when (key.style) {
            PaletteStyle.TONAL_SPOT -> palette(hue + 60.0, 24.0)
            PaletteStyle.FIDELITY -> TonalPalette.fromHct(DislikeAnalyzer.fixIfDisliked(TemperatureCache(seed).complement))
            PaletteStyle.EXPRESSIVE -> palette(rotatedHue(hue, expressiveHues,
                doubleArrayOf(120.0, 120.0, 20.0, 45.0, 20.0, 15.0, 20.0, 120.0, 120.0)), 32.0)
            PaletteStyle.VIBRANT -> palette(rotatedHue(hue, vibrantHues,
                doubleArrayOf(35.0, 30.0, 20.0, 25.0, 30.0, 35.0, 30.0, 25.0, 25.0)), 32.0)
        }
        val neutral = palette(hue, 6.0)
        val variant = palette(hue, 8.0)
        val error = palette(25.0, 84.0)
        fun TonalPalette.at(light: Int, dark: Int) = Color(tone(if (key.dark) dark else light))
        // All 36 roles supported by the app's Material 3 version are explicit: no default
        // purple/error/surface colors can accidentally leak into the generated scheme.
        return TonalContrast.protect(ColorScheme(
            primary = primary.at(40, 80), onPrimary = primary.at(100, 20),
            primaryContainer = primary.at(90, 30), onPrimaryContainer = primary.at(10, 90),
            inversePrimary = primary.at(80, 40),
            secondary = secondary.at(40, 80), onSecondary = secondary.at(100, 20),
            secondaryContainer = secondary.at(90, 30), onSecondaryContainer = secondary.at(10, 90),
            tertiary = tertiary.at(40, 80), onTertiary = tertiary.at(100, 20),
            tertiaryContainer = tertiary.at(90, 30), onTertiaryContainer = tertiary.at(10, 90),
            background = neutral.at(98, 6), onBackground = neutral.at(10, 90),
            surface = neutral.at(98, 6), onSurface = neutral.at(10, 90),
            surfaceVariant = variant.at(90, 30), onSurfaceVariant = variant.at(30, 80),
            surfaceTint = primary.at(40, 80),
            inverseSurface = neutral.at(20, 90), inverseOnSurface = neutral.at(95, 20),
            error = error.at(40, 80), onError = error.at(100, 20),
            errorContainer = error.at(90, 30), onErrorContainer = error.at(10, 90),
            outline = variant.at(50, 60), outlineVariant = variant.at(80, 30),
            scrim = Color.Black,
            surfaceDim = neutral.at(87, 6), surfaceBright = neutral.at(98, 24),
            surfaceContainerLowest = neutral.at(100, 4),
            surfaceContainerLow = neutral.at(96, 10),
            surfaceContainer = neutral.at(94, 12),
            surfaceContainerHigh = neutral.at(92, 17),
            surfaceContainerHighest = neutral.at(90, 22)
        ))
    }

    private fun rotatedHue(hue: Double, hues: DoubleArray, rotations: DoubleArray): Double {
        for (i in 0 until hues.lastIndex) {
            if (hue >= hues[i] && hue < hues[i + 1]) return MathUtils.sanitizeDegreesDouble(hue + rotations[i])
        }
        return hue
    }

    /**
     * Optional album-art seed extraction, never on Main. The caller retains ownership and
     * must keep [bitmap] alive until return. Sampling is bounded to 128px/16 swatches; no
     * Bitmaps are cached. Invalid/empty artwork returns the opaque base seed.
     */
    suspend fun extractHarmonizedSeed(bitmap: Bitmap?, baseSeed: Int): Int = withContext(AppDispatchers.CpuBounded) {
        val fallback = baseSeed or (0xFF shl 24)
        if (bitmap == null || bitmap.isRecycled) return@withContext fallback
        var scaled: Bitmap? = null
        var readable: Bitmap? = null
        try {
            currentCoroutineContext().ensureActive()
            val scale = minOf(1f, 128f / max(bitmap.width, bitmap.height))
            scaled = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).roundToInt()),
                max(1, (bitmap.height * scale).roundToInt()), true)
            readable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && scaled.config == Bitmap.Config.HARDWARE) scaled.copy(Bitmap.Config.ARGB_8888, false) else scaled
            val palette = Palette.from(readable ?: return@withContext fallback)
                .maximumColorCount(16).resizeBitmapArea(128 * 128).generate()
            currentCoroutineContext().ensureActive()
            val extracted = palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: return@withContext fallback
            Blend.harmonize(extracted, fallback)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            fallback
        } catch (_: IllegalStateException) {
            fallback
        } finally {
            readable?.takeIf { it !== bitmap && it !== scaled }?.recycle()
            scaled?.takeIf { it !== bitmap }?.recycle()
        }
    }
}

/** Guards endpoints, Android's dynamic schemes, and animated intermediate role pairings. */
object TonalContrast {
    /** WCAG relative luminance, compositing translucent content over an opaque surface. */
    fun ratio(foreground: Color, background: Color): Float {
        val content = foreground.compositeOver(background).luminance()
        val surface = background.luminance()
        return (maxOf(content, surface) + .05f) / (minOf(content, surface) + .05f)
    }

    private fun readable(foreground: Color, background: Color): Color {
        val opaque = foreground.copy(alpha = 1f)
        if (ratio(opaque, background) >= 4.5f) return opaque
        return if (ratio(Color.Black, background) >= ratio(Color.White, background)) Color.Black else Color.White
    }

    fun protect(scheme: ColorScheme): ColorScheme = scheme.copy(
        onPrimary = readable(scheme.onPrimary, scheme.primary),
        onPrimaryContainer = readable(scheme.onPrimaryContainer, scheme.primaryContainer),
        onSecondary = readable(scheme.onSecondary, scheme.secondary),
        onSecondaryContainer = readable(scheme.onSecondaryContainer, scheme.secondaryContainer),
        onTertiary = readable(scheme.onTertiary, scheme.tertiary),
        onTertiaryContainer = readable(scheme.onTertiaryContainer, scheme.tertiaryContainer),
        onError = readable(scheme.onError, scheme.error),
        onErrorContainer = readable(scheme.onErrorContainer, scheme.errorContainer),
        onBackground = readable(scheme.onBackground, scheme.background),
        onSurface = readable(scheme.onSurface, scheme.surface),
        onSurfaceVariant = readable(scheme.onSurfaceVariant, scheme.surfaceVariant),
        inverseOnSurface = readable(scheme.inverseOnSurface, scheme.inverseSurface),
        inversePrimary = readable(scheme.inversePrimary, scheme.inverseSurface)
    )
}
