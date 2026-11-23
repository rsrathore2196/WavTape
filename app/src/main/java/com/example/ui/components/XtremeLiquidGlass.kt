package com.example.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.components.backdrop.backdrops.LayerBackdrop
import com.example.ui.components.backdrop.backdrops.layerBackdrop
import com.example.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.example.ui.components.backdrop.drawBackdrop
import com.example.ui.components.backdrop.effects.blur
import com.example.ui.components.backdrop.effects.lens
import com.example.ui.components.backdrop.internal.ColorFilterEffect
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.LocalAppColors

import com.example.performance.LiquidGlassProfile
import com.example.performance.LiquidGlassProfileSetting

val LocalDynamicGlassEnabled = compositionLocalOf { true }
val LocalLiquidGlassProfile = compositionLocalOf { LiquidGlassProfile.BALANCED }

/**
 * One shared RenderNode source, using vendored Kyant backdrop renderer.
 * Controls remain outside their source; no bitmap readback or frame loop.
 */
class LiquidGlassBackdrop internal constructor(internal val source: LayerBackdrop) {
    // A source can explicitly override resolution. Otherwise the current profile
    // is authoritative immediately, including the first frame after a switch.
    internal var sampleScale by mutableStateOf<Float?>(null)
}

val LocalLiquidGlassBackdrop = compositionLocalOf<LiquidGlassBackdrop?> { null }

@Composable
@Suppress("UNUSED_PARAMETER")
fun rememberLiquidGlassBackdrop(captureVisibleRegions: Boolean = false): LiquidGlassBackdrop {
    // Retain the call-site API. The node renderer records the page once and each
    // reader samples only its own bounds at reduced resolution.
    val source = rememberLayerBackdrop()
    return remember(source) { LiquidGlassBackdrop(source) }
}

object XtremeLiquidGlassDefaults {
    val BlurRadius: Dp = 20.dp
    const val SurfaceTintAlpha: Float = 0.12f
    val RimBorderWidth: Dp = 0.8.dp
    val PillShape: Shape = CircleShape
    val Elevation: Dp = 10.dp
    const val BackdropSampleScale: Float = 0.33f
}

/** Record-once source: composite that display list instead of drawing twice. */
@Composable
fun Modifier.xtremeSharedBackdropSource(
    enabled: Boolean = true,
    sampleScale: Float? = null,
    backdrop: LiquidGlassBackdrop? = LocalLiquidGlassBackdrop.current
): Modifier {
    if (!enabled || !LocalDynamicGlassEnabled.current || backdrop == null ||
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    val effectiveScale = sampleScale?.takeIf { it.isFinite() }?.coerceIn(0.1f, 1f)
    SideEffect { backdrop.sampleScale = effectiveScale }
    return layerBackdrop(backdrop.source)
}

/**
 * Keep Xtreme's existing shape, tint, rim, shadow and sharp foreground. Only the
 * backdrop renderer changes: one-third-resolution blur and cached lens effects.
 * Android 12 uses blur; Android 13+ also supports the AGSL refraction lens.
 */
@Composable
fun Modifier.xtremeLiquidGlassBackground(
    shape: Shape = XtremeLiquidGlassDefaults.PillShape,
    blurRadius: Dp = XtremeLiquidGlassDefaults.BlurRadius,
    surfaceTintAlpha: Float = XtremeLiquidGlassDefaults.SurfaceTintAlpha,
    tintColor: Color = LocalAppColors.current.primaryAccent,
    borderWidth: Dp = XtremeLiquidGlassDefaults.RimBorderWidth,
    elevation: Dp = XtremeLiquidGlassDefaults.Elevation,
    enabled: Boolean = LocalDynamicGlassEnabled.current,
    appColors: AppThemeColors = LocalAppColors.current,
    backdrop: LiquidGlassBackdrop? = LocalLiquidGlassBackdrop.current,
    baseFillAlpha: Float? = null,
    baseFillColor: Color? = null,
    interaction: DockGlassInteraction? = null,
    rimBrush: Brush? = null,
    neutralFinish: Boolean = false,
    onSample: (() -> Unit)? = null,
    floatingDock: Boolean = false,
    headerGlass: Boolean = false,
    unsampledDockFillAlpha: Float = 0.90f
): Modifier {
    val profile = LocalLiquidGlassProfile.current
    if (!enabled || !LocalDynamicGlassEnabled.current) return this
        .shadow(elevation, shape)
        .clip(shape)
        .background((baseFillColor?.takeUnless { appColors.isDark } ?: appColors.cardBackground).copy(alpha = 1f))
        .then(if (rimBrush != null) Modifier.border(BorderStroke(borderWidth, rimBrush), shape)
            else if (neutralFinish && !appColors.isDark) Modifier.border(BorderStroke(borderWidth,
                com.example.ui.theme.LiquidGlass.specularBorderBrush(appColors)), shape)
            else Modifier.border(borderWidth, appColors.cardBorder, shape))

    val density = LocalDensity.current
    val effectiveBlurRadius = if (floatingDock) profile.chromeBlurRadius(headerGlass) else profile.blurRadius(blurRadius, false)
    val radius = with(density) { effectiveBlurRadius.toPx() }
    val processingScale = backdrop?.sampleScale ?: profile.backdropSampleScale
    val useBackdrop = backdrop != null && radius > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val lensSize = with(density) { 24.dp.toPx() } * processingScale
    val pressDepth = with(density) { 2.dp.toPx() } * processingScale
    val chromeTone = remember(useBackdrop, floatingDock, headerGlass, appColors.isDark, profile.enableLens) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useBackdrop && floatingDock && profile.enableLens) {
            val brightness = if (appColors.isDark && !headerGlass) .05f else 0f
            val matrix = ColorMatrix().apply {
                setToSaturation(if (headerGlass) 1.6f else 1.1f)
                for (channel in 0..2) this[channel, 4] = brightness * 255f
            }
            ColorFilterEffect(null, ColorFilter.colorMatrix(matrix))
        } else null
    }
    val effects: com.example.ui.components.backdrop.BackdropEffectScope.() -> Unit =
        remember(radius, processingScale, lensSize, pressDepth, shape, interaction, profile, floatingDock, headerGlass, chromeTone) {
            {
                // Touch response; reads belong to the backdrop node, not the screen's composition.
                val press = interaction?.pressProgress?.coerceAtLeast(0f) ?: 0f
                renderEffect = chromeTone
                blur(radius * processingScale + pressDepth * press)
                if (profile.enableLens && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shape is CornerBasedShape) {
                    try {
                        val aperture = size.minDimension
                        val height = when {
                            headerGlass -> (14.dp.toPx() * processingScale).coerceAtMost(aperture / 3f)
                            floatingDock -> aperture / 6f
                            else -> lensSize
                        }
                        val amount = when {
                            headerGlass -> (28.dp.toPx() * processingScale).coerceAtMost(aperture / 2f)
                            floatingDock -> aperture / 2f
                            else -> lensSize
                        }
                        lens(
                            refractionHeight = height + pressDepth * press,
                            refractionAmount = amount,
                            depthEffect = profile.enableDepthEffect && !floatingDock,
                            chromaticAberration = profile.enableChromaticAberration && !floatingDock
                        )
                    } catch (_: Throwable) {
                        // Gracefully ignored if size is unspecified or shader unavailable
                    }
                }
            }
        }
    // Floating chrome uses the same restrained finish in both color modes.
    val dark = appColors.isDark || neutralFinish
    val rim = rimBrush ?: remember(dark, floatingDock) {
        Brush.linearGradient(listOf(
            Color.White.copy(alpha = if (floatingDock) 0.38f else if (dark) 0.48f else 0.92f),
            Color.White.copy(alpha = if (dark) 0.08f else 0.32f),
            Color.White.copy(alpha = if (dark) 0.06f else 0.15f),
            (if (dark) Color.White else Color(0xFF708090)).copy(alpha = 0.30f)
        ))
    }
    val effectiveElevation = (if (floatingDock && useBackdrop) elevation.coerceAtMost(8.dp) else elevation) *
        profile.shadowElevationMultiplier
    val shadowModifier = if (profile.enableShadows && effectiveElevation > 0.dp && baseFillAlpha != 0f) {
        Modifier.shadow(
            effectiveElevation,
            shape,
            clip = false,
            spotColor = Color.Black.copy(alpha = if (dark && !floatingDock) 0.42f else 0.14f),
            ambientColor = if (profile.shadowElevationMultiplier >= 1f) Color.Black.copy(alpha = 0.08f) else Color.Transparent
        )
    } else Modifier

    return this
        .then(shadowModifier)
        .clip(shape)
        .then(
            if (useBackdrop) Modifier.drawBackdrop(
                backdrop = requireNotNull(backdrop).source,
                shape = { shape },
                effects = effects,
                highlight = null,
                shadow = null,
                backdropScale = processingScale,
                onSample = onSample
            ) else Modifier
        )
        .drawWithCache {
            val lightDockFill = floatingDock && !appColors.isDark
            val base = baseFillColor ?: when {
                lightDockFill -> Color(0xFFD7DADE)
                dark -> Color(0xFF101013)
                else -> Color(0xFFF8FAFD)
            }
            val tint = tintColor.copy(alpha = surfaceTintAlpha.coerceIn(0f, 0.65f) * tintColor.alpha)
            val colorWash = Brush.linearGradient(listOf(tint, Color.Transparent, tint.copy(alpha = tint.alpha * 0.45f)),
                Offset.Zero, Offset(size.width, size.height))
            val sheen = if (floatingDock) {
                Brush.verticalGradient(0f to Color.White.copy(alpha = if (appColors.isDark) .07f else .08f),
                    .55f to Color.Transparent)
            } else Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.10f else 0.36f),
                0.32f to Color.White.copy(alpha = 0.015f), 0.65f to Color.Transparent,
                1f to Color.White.copy(alpha = if (dark) 0.055f else 0.14f))
            val reflection = if (profile.enableRadialReflections && !floatingDock) {
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = if (dark) 0.065f else 0.18f), Color.Transparent),
                    center = Offset(size.width * 0.72f, size.height * 1.6f),
                    radius = size.width.coerceAtLeast(size.height) * 0.65f
                )
            } else null
            // Cache the brush at the origin. Move the canvas, rather than
            // allocating a radial brush for every pointer/animation frame.
            val touchRadius = size.minDimension * 1.2f
            val touchGlow = if (interaction != null && touchRadius > 0f) Brush.radialGradient(
                listOf(Color.White, Color.Transparent), center = Offset.Zero, radius = touchRadius
            ) else null
            val defaultFillAlpha = if (useBackdrop || (backdrop == null && enabled)) {
                if (dark) 0.50f else 0.64f
            } else 0.90f
            val fillAlpha = if (floatingDock && !useBackdrop) unsampledDockFillAlpha
                else profile.surfaceFillAlpha(baseFillAlpha, defaultFillAlpha, floatingDock)

            onDrawWithContent {
                if (fillAlpha > 0f) drawRect(base.copy(alpha = fillAlpha))
                drawRect(colorWash)
                drawRect(sheen)
                if (reflection != null) drawRect(reflection)
                if (touchGlow != null && interaction != null) {
                    val press = interaction.pressProgress.coerceIn(0f, 1f)
                    val touch = interaction.touchPosition
                    if (press > 0f && touch.isSpecified) {
                        translate(touch.x, touch.y) {
                            drawCircle(touchGlow, radius = touchRadius, center = Offset.Zero,
                                alpha = 0.18f * press, blendMode = BlendMode.Plus)
                        }
                    }
                }
                drawContent()
            }
        }
        .border(BorderStroke(borderWidth, rim), shape)
}

/** Give player controls the floating dock's glass, with a brighter bevel around the smaller shapes. */
@Composable
fun Modifier.playerLiquidGlassButton(
    colors: AppThemeColors,
    shape: Shape = CircleShape,
    elevation: Dp = 4.dp,
    isActive: Boolean = false,
    translucency: Float = 0.75f,
    borderWidth: Dp = 0.8.dp
): Modifier {
    val profile = LocalLiquidGlassProfile.current
    val bevel = remember(isActive) {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (isActive) 0.65f else 0.52f),
            0.34f to Color.White.copy(alpha = 0.18f),
            0.68f to Color.White.copy(alpha = 0.06f),
            1f to Color.White.copy(alpha = 0.32f)
        )
    }
    return xtremeLiquidGlassBackground(
        shape = shape,
        floatingDock = true,
        neutralFinish = true,
        elevation = elevation.coerceAtLeast(3.dp),
        appColors = com.example.ui.util.playerAppearanceColors(colors),
        enabled = colors.dynamicGlassEnabled,
        tintColor = Color.Transparent,
        surfaceTintAlpha = 0f,
        borderWidth = borderWidth,
        baseFillColor = Color.White,
        baseFillAlpha = 0.05f,
        unsampledDockFillAlpha = 0.08f,
        rimBrush = bevel,
        backdrop = LocalLiquidGlassBackdrop.current.takeIf { profile.allowSecondaryBackdropSampling }
    )
}

@Composable
fun Modifier.playerPrimaryLiquidGlassButton(
    colors: AppThemeColors,
    shape: Shape = CircleShape,
    elevation: Dp = 10.dp,
    translucency: Float = 0.82f,
    borderWidth: Dp = 1.dp
): Modifier = playerLiquidGlassButton(colors, shape, elevation, translucency = translucency, borderWidth = borderWidth)

/**
 * Reusable modifier alias for backwards compatibility.
 * Note: To prevent child blurring, prefer using [XtremeLiquidGlassContainer] or applying
 * [xtremeLiquidGlassBackground] on a dedicated Layer 1 background Box.
 */
@Composable
fun Modifier.xtremeLiquidGlass(
    shape: Shape = XtremeLiquidGlassDefaults.PillShape,
    blurRadius: Dp = XtremeLiquidGlassDefaults.BlurRadius,
    surfaceTintAlpha: Float = XtremeLiquidGlassDefaults.SurfaceTintAlpha,
    tintColor: Color = LocalAppColors.current.primaryAccent,
    borderWidth: Dp = XtremeLiquidGlassDefaults.RimBorderWidth,
    elevation: Dp = XtremeLiquidGlassDefaults.Elevation,
    enabled: Boolean = LocalDynamicGlassEnabled.current,
    appColors: AppThemeColors = LocalAppColors.current
): Modifier = xtremeLiquidGlassBackground(
    shape = shape,
    blurRadius = blurRadius,
    surfaceTintAlpha = surfaceTintAlpha,
    tintColor = tintColor,
    borderWidth = borderWidth,
    elevation = elevation,
    enabled = enabled,
    appColors = appColors
)

/**
 * Reusable Liquid Glass Container enforcing the 2-layer stacked architecture:
 * LAYER 1: Glass background surface only (blur, AGSL shader, tint, sheen, border)
 * LAYER 2: Crisp foreground content, completely unblurred and sharp.
 */
@Composable
fun XtremeLiquidGlassContainer(
    modifier: Modifier = Modifier,
    shape: Shape = XtremeLiquidGlassDefaults.PillShape,
    blurRadius: Dp = XtremeLiquidGlassDefaults.BlurRadius,
    surfaceTintAlpha: Float = XtremeLiquidGlassDefaults.SurfaceTintAlpha,
    tintColor: Color = LocalAppColors.current.primaryAccent,
    borderWidth: Dp = XtremeLiquidGlassDefaults.RimBorderWidth,
    elevation: Dp = XtremeLiquidGlassDefaults.Elevation,
    enabled: Boolean = LocalDynamicGlassEnabled.current,
    appColors: AppThemeColors = LocalAppColors.current,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        // LAYER 1: GLASS BACKGROUND SURFACE ONLY
        Box(
            modifier = Modifier
                .matchParentSize()
                .xtremeLiquidGlassBackground(
                    shape = shape,
                    blurRadius = blurRadius,
                    surfaceTintAlpha = surfaceTintAlpha,
                    tintColor = tintColor,
                    borderWidth = borderWidth,
                    elevation = elevation,
                    enabled = enabled,
                    appColors = appColors
                )
        )

        // LAYER 2: UNBLURRED FOREGROUND CONTENT
        content()
    }
}

/**
 * Reusable Liquid Glass Surface component wrapping content with Xtreme Liquid Glass styling
 * using the 2-layer stacked architecture so child composables remain sharp.
 */
@Composable
fun XtremeLiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = XtremeLiquidGlassDefaults.PillShape,
    blurRadius: Dp = XtremeLiquidGlassDefaults.BlurRadius,
    surfaceTintAlpha: Float = XtremeLiquidGlassDefaults.SurfaceTintAlpha,
    tintColor: Color = LocalAppColors.current.primaryAccent,
    borderWidth: Dp = XtremeLiquidGlassDefaults.RimBorderWidth,
    elevation: Dp = XtremeLiquidGlassDefaults.Elevation,
    enabled: Boolean = LocalDynamicGlassEnabled.current,
    appColors: AppThemeColors = LocalAppColors.current,
    content: @Composable () -> Unit
) {
    XtremeLiquidGlassContainer(
        modifier = modifier,
        shape = shape,
        blurRadius = blurRadius,
        surfaceTintAlpha = surfaceTintAlpha,
        tintColor = tintColor,
        borderWidth = borderWidth,
        elevation = elevation,
        enabled = enabled,
        appColors = appColors
    ) {
        content()
    }
}
