package com.example.ui.theme

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import com.example.performance.AppDispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.OtherSettingsPreferences
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.graphics.ColorUtils
import com.example.data.local.CustomThemeState
import com.example.data.local.ThemePreferences
import com.example.data.local.ThemePresets
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private fun parseColorSafe(hex: String, fallback: Color): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex.trim()))
    } catch (_: Exception) {
        fallback
    }
}

private fun com.example.data.local.ThemeTonePalette.resolved(): TonalThemeColors = TonalThemeColors(
    primary = parseColorSafe(primary, Color.Unspecified),
    onPrimary = parseColorSafe(onPrimary, Color.Unspecified),
    primaryContainer = parseColorSafe(primaryContainer, Color.Unspecified),
    onPrimaryContainer = parseColorSafe(onPrimaryContainer, Color.Unspecified),
    secondary = parseColorSafe(secondary, Color.Unspecified),
    onSecondary = parseColorSafe(onSecondary, Color.Unspecified),
    secondaryContainer = parseColorSafe(secondaryContainer, Color.Unspecified),
    onSecondaryContainer = parseColorSafe(onSecondaryContainer, Color.Unspecified),
    tertiary = parseColorSafe(tertiary, Color.Unspecified),
    onTertiary = parseColorSafe(onTertiary, Color.Unspecified),
    tertiaryContainer = parseColorSafe(tertiaryContainer, Color.Unspecified),
    onTertiaryContainer = parseColorSafe(onTertiaryContainer, Color.Unspecified),
    background = parseColorSafe(background, Color.Unspecified),
    onBackground = parseColorSafe(onBackground, Color.Unspecified),
    surface = parseColorSafe(surface, Color.Unspecified),
    onSurface = parseColorSafe(onSurface, Color.Unspecified),
    surfaceVariant = parseColorSafe(surfaceVariant, Color.Unspecified),
    onSurfaceVariant = parseColorSafe(onSurfaceVariant, Color.Unspecified),
    surfaceContainerLowest = parseColorSafe(surfaceContainerLowest, Color.Unspecified),
    surfaceContainerLow = parseColorSafe(surfaceContainerLow, Color.Unspecified),
    surfaceContainer = parseColorSafe(surfaceContainer, Color.Unspecified),
    surfaceContainerHigh = parseColorSafe(surfaceContainerHigh, Color.Unspecified),
    surfaceContainerHighest = parseColorSafe(surfaceContainerHighest, Color.Unspecified),
    surfaceDim = parseColorSafe(surfaceDim, Color.Unspecified),
    surfaceBright = parseColorSafe(surfaceBright, Color.Unspecified),
    outline = parseColorSafe(outline, Color.Unspecified),
    outlineVariant = parseColorSafe(outlineVariant, Color.Unspecified)
)

/**
 * Resolves the complete high-performance [AppThemeColors] dynamically from the active [CustomThemeState].
 * Supports instant, smooth transitions across all 20 presets and customized palettes.
 */
fun resolveAppThemeColors(darkTheme: Boolean, custom: CustomThemeState, highContrastTypography: Boolean = false): AppThemeColors {
    val isDark = darkTheme
    // System mode can change while a preset of the opposite brightness is saved.
    // Resolve the whole palette together instead of mixing light text with dark surfaces.
    val upgraded = ThemePresets.upgradeLegacy(custom)
    val effectiveCustom = if (isDark == upgraded.isPresetDark) upgraded else
        (if (isDark) ThemePresets.StudioNight else ThemePresets.CleanDay).toCustomThemeState()

    val neutralSurfaces = ThemePresets.isBuiltIn(effectiveCustom)
    val tones = ThemePresets.tonalPalette(effectiveCustom)?.resolved()
    val rawPrimaryAccent = parseColorSafe(
        effectiveCustom.accentColorHex,
        if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
    )

    val rawSecondaryAccent = parseColorSafe(
        effectiveCustom.secondaryAccentHex,
        rawPrimaryAccent
    )

    val isAmoled = isDark && if (effectiveCustom.presetId.isBlank() && effectiveCustom.currentThemeName == "Custom") {
        effectiveCustom.canvasColorHex == "#000000" && effectiveCustom.cardColorHex == "#000000"
    } else (effectiveCustom.isAmoled || effectiveCustom.canvasColorHex == "#000000")

    val scaffoldBg = if (isAmoled) {
        Color(0xFF000000)
    } else {
        parseColorSafe(effectiveCustom.canvasColorHex, if (isDark) Color(0xFF101014) else Color(0xFFFAFAFC))
    }

    val cardBg = if (isAmoled) {
        Color(0xFF000000)
    } else {
        parseColorSafe(effectiveCustom.cardColorHex, if (isDark) Color(0xFF18181E) else Color(0xFFFFFFFF))
    }

    val cardBorder = if (isAmoled) {
        Color(0xFF242424)
    } else {
        parseColorSafe(effectiveCustom.cardBorderHex, if (isDark) Color(0xFF282832) else Color(0xFFE2E8F0))
    }

    fun readable(color: Color, minimum: Double): Color {
        val surfaces = listOf(scaffoldBg, cardBg) + if (neutralSurfaces)
            listOf(tones?.surfaceContainerHighest ?: if (isDark) Color(0xFF2A2A2A) else Color(0xFFE8E8E8)) else emptyList()
        fun contrast(value: Color) = surfaces.minOf {
            ColorUtils.calculateContrast(value.copy(alpha = 1f).toArgb(), it.copy(alpha = 1f).toArgb())
        }
        var best = color
        var bestContrast = contrast(color)
        if (bestContrast >= minimum) return color
        val fallback = if (contrast(Color.Black) > contrast(Color.White)) Color.Black else Color.White
        for (step in 1..20) {
            val adjusted = Color(ColorUtils.blendARGB(color.toArgb(), fallback.toArgb(), step / 20f))
            val score = contrast(adjusted)
            if (score >= minimum) return adjusted
            if (score > bestContrast) { best = adjusted; bestContrast = score }
        }
        // Opposite-brightness custom surfaces can defeat either black or white.
        // Between adjacent surface luminances, the best shared contrast is at their geometric mean.
        // Try its nearest displayable gray values, retaining any better requested/legacy candidate.
        val luminances = surfaces.map { ColorUtils.calculateLuminance(it.copy(alpha = 1f).toArgb()) }.distinct().sorted()
        for ((low, high) in luminances.zipWithNext()) {
            val balanced = sqrt((low + .05) * (high + .05)) - .05
            val encoded = if (balanced <= .0031308) balanced * 12.92 else 1.055 * balanced.pow(1.0 / 2.4) - .055
            val channel = (encoded * 255).roundToInt()
            for (nearby in (channel - 1)..(channel + 1)) {
                val value = nearby.coerceIn(0, 255)
                val candidate = Color(android.graphics.Color.rgb(value, value, value))
                val score = contrast(candidate)
                if (score > bestContrast) { best = candidate; bestContrast = score }
            }
        }
        return best
    }

    val primaryAccent = if (neutralSurfaces) readable(rawPrimaryAccent, 4.5) else rawPrimaryAccent
    val secondaryAccent = if (neutralSurfaces) readable(rawSecondaryAccent, 4.5) else rawSecondaryAccent
    val minimumTextContrast = if (highContrastTypography) 7.0 else 4.5
    val textPrimary = readable(parseColorSafe(effectiveCustom.textPrimaryHex,
        if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)), minimumTextContrast)

    val textSecondary = readable(parseColorSafe(effectiveCustom.textSecondaryHex,
        if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)), minimumTextContrast)

    val textMuted = readable(parseColorSafe(
        effectiveCustom.textMutedHex,
        Color(0xFF64748B)
    ), minimumTextContrast)

    val bgGradientColors = if (isAmoled) {
        listOf(Color(0xFF000000), Color(0xFF000000))
    } else {
        effectiveCustom.bgGradientColorsHex.mapNotNull { hex ->
            try {
                Color(android.graphics.Color.parseColor(hex.trim()))
            } catch (_: Exception) {
                null
            }
        }.ifEmpty {
            if (isDark) listOf(Color(0xFF121218), Color(0xFF0A0A0E), Color(0xFF050508))
            else listOf(Color(0xFFFFFFFF), Color(0xFFF8FAFC), Color(0xFFF1F5F9))
        }
    }

    val cardElevated = if (tones != null) tones.surfaceContainerHighest else if (neutralSurfaces) {
        if (isDark) Color(0xFF242424) else Color(0xFFEEEEEE)
    } else if (isDark) {
        if (isAmoled) Color(0xFF0D0D0D)
        else try {
            Color(android.graphics.Color.parseColor(effectiveCustom.cardColorHex)).copy(alpha = 0.95f)
        } catch (_: Exception) {
            cardBg
        }
    } else {
        cardBg
    }

    val bottomBarBg = if (isDark) {
        if (isAmoled) Color(0xFF000000) else scaffoldBg
    } else {
        scaffoldBg
    }

    val chipBg = if (tones != null) tones.surfaceContainerHighest else if (isDark) {
        if (isAmoled) Color(0xFF121212) else cardElevated
    } else {
        if (neutralSurfaces) Color(0xFFEEEEEE) else Color(0xFFF1F5F9)
    }

    return AppThemeColors(
        isDark = isDark,
        isAmoled = isAmoled,
        screenBackground = Brush.verticalGradient(bgGradientColors),
        scaffoldBackground = scaffoldBg,
        cardBackground = cardBg,
        cardBackgroundElevated = cardElevated,
        cardBorder = cardBorder,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textMuted = textMuted,
        primaryAccent = primaryAccent,
        secondaryAccent = secondaryAccent,
        bottomBarBackground = bottomBarBg,
        bottomBarIndicator = primaryAccent.copy(alpha = if (isDark) 0.22f else 0.15f),
        bottomBarSelectedIcon = primaryAccent,
        bottomBarUnselectedIcon = textMuted,
        miniPlayerBackground = if (isDark) {
            if (isAmoled) Brush.horizontalGradient(listOf(Color(0xFF000000), Color(0xFF080808)))
            else Brush.horizontalGradient(listOf(cardBg, scaffoldBg))
        } else {
            Brush.horizontalGradient(listOf(scaffoldBg, cardBg))
        },
        miniPlayerBorder = cardBorder,
        inputBackground = tones?.surfaceContainerHighest ?: if (isDark) (if (isAmoled) Color(0xFF0A0A0A) else cardBg) else if (neutralSurfaces) Color(0xFFEEEEEE) else Color(0xFFF1F5F9),
        chipBackground = chipBg,
        chipBorder = cardBorder,
        dividerColor = if (isAmoled) Color(0xFF1A1A1A) else cardBorder.copy(alpha = 0.5f),
        neutralSurfaces = neutralSurfaces,
        highContrastTypography = highContrastTypography,
        tonalPalette = tones
    )
}

/**
 * Builds Material 3 ColorScheme from the dynamic AppThemeColors.
 */
fun resolveColorScheme(darkTheme: Boolean, appColors: AppThemeColors): ColorScheme {
    val tones = appColors.accentRoles
    val primaryContainer = tones?.primaryContainer ?: appColors.primaryAccent.copy(alpha = if (darkTheme) .16f else .10f)
        .compositeOver(appColors.cardBackground)
    val secondaryContainer = tones?.secondaryContainer ?: appColors.secondaryAccent.copy(alpha = if (darkTheme) .12f else .08f)
        .compositeOver(appColors.cardBackground)
    val minimumTextContrast = if (appColors.highContrastTypography) 7f else 4.5f
    fun onContainer(background: Color) = LiquidGlass.contrastColor(appColors.textPrimary, listOf(background), minimumTextContrast)
    val scheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = appColors.primaryAccent, onPrimary = appColors.onPrimaryAccent,
        primaryContainer = primaryContainer, onPrimaryContainer = tones?.onPrimaryContainer ?: onContainer(primaryContainer),
        secondary = appColors.secondaryAccent, onSecondary = appColors.onSecondaryAccent,
        secondaryContainer = secondaryContainer, onSecondaryContainer = tones?.onSecondaryContainer ?: onContainer(secondaryContainer),
        tertiary = tones?.tertiary ?: appColors.secondaryAccent, onTertiary = tones?.onTertiary ?: appColors.onSecondaryAccent,
        tertiaryContainer = tones?.tertiaryContainer ?: secondaryContainer,
        onTertiaryContainer = tones?.onTertiaryContainer ?: onContainer(secondaryContainer),
        background = appColors.scaffoldBackground, onBackground = appColors.textPrimary,
        surface = if (appColors.isAmoled) Color.Black else tones?.surface ?: appColors.cardBackground,
        onSurface = appColors.textPrimary,
        surfaceVariant = tones?.surfaceVariant ?: appColors.cardBackgroundElevated, onSurfaceVariant = appColors.textSecondary,
        // Explicit surface roles prevent library defaults and accent elevation washes leaking into presets.
        surfaceTint = if (appColors.isAmoled) Color.Black else tones?.surface ?: appColors.cardBackground,
        surfaceDim = if (appColors.isAmoled) Color.Black else tones?.surfaceDim ?: appColors.scaffoldBackground,
        surfaceBright = tones?.surfaceBright ?: appColors.cardBackgroundElevated,
        surfaceContainerLowest = if (appColors.isAmoled) Color.Black else tones?.surfaceContainerLowest ?: appColors.scaffoldBackground,
        surfaceContainerLow = tones?.surfaceContainerLow ?: lerp(appColors.scaffoldBackground, appColors.cardBackground, .65f),
        surfaceContainer = tones?.surfaceContainer ?: appColors.cardBackground,
        surfaceContainerHigh = tones?.surfaceContainerHigh ?: appColors.cardBackgroundElevated,
        surfaceContainerHighest = tones?.surfaceContainerHighest ?: lerp(appColors.cardBackgroundElevated, appColors.textPrimary, .025f),
        outline = LiquidGlass.contrastColor(tones?.outline ?: appColors.cardBorder,
            listOf(appColors.scaffoldBackground, appColors.cardBackground), 3f),
        outlineVariant = tones?.outlineVariant ?: appColors.cardBorder,
        inverseSurface = appColors.textPrimary, inverseOnSurface = appColors.scaffoldBackground,
        inversePrimary = LiquidGlass.contrastColor(appColors.primaryAccent, listOf(appColors.textPrimary), 4.5f)
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    customThemeState: CustomThemeState? = null,
    dynamicColor: Boolean = false,
    textScale: Float = 0.95f,
    uiScale: Float = 0.94f,
    tonalTheme: TonalThemeOptions? = null,
    artworkBitmap: Bitmap? = null,
    animateTonalColors: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val playerExperienceFlow = remember(context) { OtherSettingsPreferences.observePlayerExperience(context) }
    val playerExperience by playerExperienceFlow.collectAsStateWithLifecycle(
        initialValue = OtherSettingsPreferences.playerExperienceSettings(context))
    val custom = customThemeState ?: remember { ThemePreferences.getCustomThemeState(context) }
    val legacyColors = remember(darkTheme, custom, playerExperience.dynamicAlbumGlow, playerExperience.highContrastTypography) {
        resolveAppThemeColors(darkTheme, custom, playerExperience.highContrastTypography)
            .copy(dynamicAlbumGlow = playerExperience.dynamicAlbumGlow)
    }
    val legacyScheme = remember(darkTheme, legacyColors) { resolveColorScheme(darkTheme, legacyColors) }
    val tonalOptions = tonalTheme ?: if (dynamicColor) TonalThemeOptions(useSystemDynamicColor = true) else null
    val colorScheme = if (tonalOptions == null) legacyScheme else animateTonalColorScheme(
        rememberTonalColorScheme(tonalOptions, darkTheme, artworkBitmap, legacyScheme), animateTonalColors)
    val appColors = if (tonalOptions == null) legacyColors else remember(colorScheme, darkTheme, playerExperience) {
        colorScheme.asAppThemeColors(darkTheme, playerExperience.dynamicAlbumGlow, playerExperience.highContrastTypography)
    }

    val statusBarAppearance = remember { StatusBarAppearance() }
    val headerDarkIcons = statusBarAppearance.headerDarkIcons
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge headers paint behind the status bar, including on older Android versions.
            val statusBarBg = if (headerDarkIcons != null) Color.Transparent else appColors.scaffoldBackground
            val navBarBg = appColors.bottomBarBackground
            window.statusBarColor = statusBarBg.toArgb()
            window.navigationBarColor = navBarBg.toArgb()
            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            // During a tonal light/dark animation, use the visible surface rather than
            // switching system glyph contrast ahead of the animated background.
            windowInsetsController.isAppearanceLightStatusBars = headerDarkIcons ?:
                if (tonalOptions != null) contrastingContentColor(appColors.scaffoldBackground).luminance() < .5f else !darkTheme
            windowInsetsController.isAppearanceLightNavigationBars =
                if (tonalOptions != null) contrastingContentColor(navBarBg).luminance() < .5f else !darkTheme
        }
    }

    val currentDensity = androidx.compose.ui.platform.LocalDensity.current
    val customDensity = remember(currentDensity, textScale, uiScale) {
        androidx.compose.ui.unit.Density(
            density = currentDensity.density * uiScale,
            fontScale = currentDensity.fontScale * textScale
        )
    }

    CompositionLocalProvider(
        LocalAppColors provides appColors,
        LocalStatusBarAppearance provides statusBarAppearance,
        androidx.compose.ui.platform.LocalDensity provides customDensity
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
    }
}


/**
 * Standalone Material 3 wrapper for a screen, preview, or a new app host. Existing app
 * chrome can opt in through MyApplicationTheme(tonalTheme = options) so both the
 * Material and custom glass components share the very same animated role values.
 * This wrapper deliberately leaves window bars and density to its host.
 */
@Composable
fun TonalMaterialTheme(
    options: TonalThemeOptions = TonalThemeOptions(),
    darkTheme: Boolean = isSystemInDarkTheme(),
    artworkBitmap: Bitmap? = null,
    animateColors: Boolean = true,
    content: @Composable () -> Unit
) {
    val fallback = remember(darkTheme) { if (darkTheme) darkColorScheme() else lightColorScheme() }
    val scheme = animateTonalColorScheme(rememberTonalColorScheme(options, darkTheme, artworkBitmap, fallback), animateColors)
    val colors = remember(scheme, darkTheme) { scheme.asAppThemeColors(darkTheme) }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}

/** System wallpaper colors take precedence over album colors. API 24–30 uses the seed. */
@Composable
private fun rememberTonalColorScheme(
    options: TonalThemeOptions,
    darkTheme: Boolean,
    artwork: Bitmap?,
    fallback: ColorScheme
): ColorScheme {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val system = remember(context, configuration, options.useSystemDynamicColor, darkTheme) {
        if (options.useSystemDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            TonalContrast.protect(if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))
        } else null
    }
    if (system != null) return system
    val image = artwork.takeIf { options.useAlbumArtwork && !options.useSystemDynamicColor }
    val generation = if (image != null && !image.isRecycled) image.generationId else 0
    // Image quantization depends on the image/base seed only, not on the style or
    // dark-mode switch. Those changes can reuse the extracted seed directly.
    val seed = if (image == null) options.seedColor else rememberArtworkSeed(image, generation, options.seedColor)
    val scheme by produceState(
        ThemePaletteGenerator.cached(seed, darkTheme, options.style) ?: fallback,
        seed, options.style, darkTheme
    ) {
        value = withContext(AppDispatchers.CpuBounded) { ThemePaletteGenerator.generate(seed, darkTheme, options.style) }
    }
    return scheme
}

@Composable
private fun rememberArtworkSeed(image: Bitmap, generation: Int, baseSeed: Int): Int {
    val seed by produceState(baseSeed, image, generation, baseSeed) {
        value = ThemePaletteGenerator.extractHarmonizedSeed(image, baseSeed)
    }
    return seed
}

/**
 * HCT/bitmap work never happens per animation frame. Only role colors interpolate.
 * Compose respects Android's animator-duration scale (including reduced motion).
 * A role's foreground is guarded again each frame: cross-fading black/white text
 * across a light/dark change must never pass through an unreadable midpoint.
 */
@Composable
internal fun animateTonalColorScheme(target: ColorScheme, animate: Boolean = true): ColorScheme {
    @Composable fun role(color: Color, name: String): Color = animateColorAsState(
        color, if (animate) tween(350, easing = FastOutSlowInEasing) else snap(), label = "tonal_$name").value
    return TonalContrast.protect(target.copy(
        primary = role(target.primary, "primary"),
        onPrimary = role(target.onPrimary, "onPrimary"),
        primaryContainer = role(target.primaryContainer, "primaryContainer"),
        onPrimaryContainer = role(target.onPrimaryContainer, "onPrimaryContainer"),
        inversePrimary = role(target.inversePrimary, "inversePrimary"),
        secondary = role(target.secondary, "secondary"),
        onSecondary = role(target.onSecondary, "onSecondary"),
        secondaryContainer = role(target.secondaryContainer, "secondaryContainer"),
        onSecondaryContainer = role(target.onSecondaryContainer, "onSecondaryContainer"),
        tertiary = role(target.tertiary, "tertiary"),
        onTertiary = role(target.onTertiary, "onTertiary"),
        tertiaryContainer = role(target.tertiaryContainer, "tertiaryContainer"),
        onTertiaryContainer = role(target.onTertiaryContainer, "onTertiaryContainer"),
        background = role(target.background, "background"),
        onBackground = role(target.onBackground, "onBackground"),
        surface = role(target.surface, "surface"),
        onSurface = role(target.onSurface, "onSurface"),
        surfaceVariant = role(target.surfaceVariant, "surfaceVariant"),
        onSurfaceVariant = role(target.onSurfaceVariant, "onSurfaceVariant"),
        surfaceTint = role(target.surfaceTint, "surfaceTint"),
        inverseSurface = role(target.inverseSurface, "inverseSurface"),
        inverseOnSurface = role(target.inverseOnSurface, "inverseOnSurface"),
        error = role(target.error, "error"),
        onError = role(target.onError, "onError"),
        errorContainer = role(target.errorContainer, "errorContainer"),
        onErrorContainer = role(target.onErrorContainer, "onErrorContainer"),
        outline = role(target.outline, "outline"),
        outlineVariant = role(target.outlineVariant, "outlineVariant"),
        scrim = role(target.scrim, "scrim"),
        surfaceDim = role(target.surfaceDim, "surfaceDim"),
        surfaceBright = role(target.surfaceBright, "surfaceBright"),
        surfaceContainerLowest = role(target.surfaceContainerLowest, "surfaceContainerLowest"),
        surfaceContainerLow = role(target.surfaceContainerLow, "surfaceContainerLow"),
        surfaceContainer = role(target.surfaceContainer, "surfaceContainer"),
        surfaceContainerHigh = role(target.surfaceContainerHigh, "surfaceContainerHigh"),
        surfaceContainerHighest = role(target.surfaceContainerHighest, "surfaceContainerHighest")
    ))
}

/** Adapter for the app's existing glass controls; no separate competing accent palette. */
internal fun ColorScheme.asAppThemeColors(
    dark: Boolean,
    dynamicAlbumGlow: Boolean = true,
    highContrastTypography: Boolean = false
): AppThemeColors = AppThemeColors(
    isDark = dark,
    screenBackground = Brush.verticalGradient(listOf(background, background)),
    scaffoldBackground = background,
    cardBackground = surfaceContainer,
    cardBackgroundElevated = surfaceContainerHigh,
    cardBorder = outlineVariant,
    textPrimary = onSurface,
    textSecondary = onSurfaceVariant,
    textMuted = onSurfaceVariant,
    primaryAccent = primary,
    secondaryAccent = secondary,
    bottomBarBackground = surfaceContainer,
    bottomBarIndicator = primaryContainer,
    bottomBarSelectedIcon = primary,
    bottomBarUnselectedIcon = onSurfaceVariant,
    miniPlayerBackground = Brush.horizontalGradient(listOf(surfaceContainer, surfaceContainerHigh)),
    miniPlayerBorder = outlineVariant,
    inputBackground = surfaceContainerHighest,
    chipBackground = surfaceContainerHigh,
    chipBorder = outlineVariant,
    dividerColor = outlineVariant,
    dynamicAlbumGlow = dynamicAlbumGlow,
    highContrastTypography = highContrastTypography,
    neutralSurfaces = true,
    tonalPalette = TonalThemeColors(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceDim = surfaceDim,
        surfaceBright = surfaceBright,
        outline = outline,
        outlineVariant = outlineVariant
    )
)
