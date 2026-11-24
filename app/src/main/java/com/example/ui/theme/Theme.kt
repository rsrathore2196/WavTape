package com.example.ui.theme

import android.app.Activity
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

/**
 * Resolves the complete high-performance [AppThemeColors] dynamically from the active [CustomThemeState].
 * Resolves one connected Material palette in the requested mode, or a saved custom palette.
 */
fun resolveAppThemeColors(darkTheme: Boolean, custom: CustomThemeState, highContrastTypography: Boolean = false): AppThemeColors {
    val isDark = darkTheme
    // System mode can change while a preset of the opposite brightness is saved.
    // Resolve the whole palette together instead of mixing light text with dark surfaces.
    val upgraded = ThemePresets.upgradeLegacy(custom)
    val effectiveCustom = ThemePresets.forMode(upgraded, isDark)

    val neutralSurfaces = ThemePresets.isBuiltIn(effectiveCustom)
    val tones = ThemePresets.tonalPalette(effectiveCustom)
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
    textScale: Float = 0.95f,
    uiScale: Float = 0.94f,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val playerExperienceFlow = remember(context) { OtherSettingsPreferences.observePlayerExperience(context) }
    val playerExperience by playerExperienceFlow.collectAsStateWithLifecycle(
        initialValue = OtherSettingsPreferences.playerExperienceSettings(context))
    val custom = customThemeState ?: remember { ThemePreferences.getCustomThemeState(context) }
    val appColors = remember(darkTheme, custom, playerExperience.dynamicAlbumGlow, playerExperience.highContrastTypography,
        playerExperience.pureLiquidGlassUi) {
        resolveAppThemeColors(darkTheme, custom, playerExperience.highContrastTypography)
            .copy(dynamicAlbumGlow = playerExperience.dynamicAlbumGlow, pureLiquidGlassUi = playerExperience.pureLiquidGlassUi)
    }
    val colorScheme = remember(darkTheme, appColors) { resolveColorScheme(darkTheme, appColors) }

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
            windowInsetsController.isAppearanceLightStatusBars = headerDarkIcons ?: !darkTheme
            windowInsetsController.isAppearanceLightNavigationBars = !darkTheme
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
