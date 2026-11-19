package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalAppColors

internal val TabHeaderBaseColor = Color(0xFF101013)
internal val TabHeaderLightBaseColor = Color(0xFFF8FAFD)
internal fun tabHeaderAccentColor(accent: Color, isDark: Boolean = true): Color = com.example.ui.theme.LiquidGlass.contrastColor(
    accent, if (isDark) listOf(TabHeaderBaseColor, Color(0xFF606064))
    else listOf(Color(0xFFBFC0C2), Color.White), minimum = 3f)
private val LocalTabHeaderBackdrop = compositionLocalOf<LiquidGlassBackdrop?> { null }

/** The header samples its scrolling content, never its ancestor's recording of itself. */
@Composable
internal fun TabHeaderScaffold(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    val backdrop = rememberLiquidGlassBackdrop()
    Scaffold(modifier = modifier, containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp), topBar = {
            CompositionLocalProvider(LocalTabHeaderBackdrop provides backdrop, content = header)
        }) { insets ->
        Box(Modifier.fillMaxSize()
            .xtremeSharedBackdropSource(enabled = LocalVisualEffectsActive.current, backdrop = backdrop)
            .background(LocalAppColors.current.scaffoldBackground)) {
            content(insets)
        }
    }
}

@Composable
internal fun HomeAppHeader(onOpenSettings: (() -> Unit)?, modifier: Modifier = Modifier, headerTag: String = "home_fixed_header") {
    GlassHeaderSurface(modifier, headerTag) {
        HomeHeaderContent(onOpenSettings)
    }
}

/** The docks' neutral glass material, with mode-appropriate content contrast. */
@Composable
private fun GlassHeaderSurface(modifier: Modifier = Modifier, headerTag: String,
    content: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    val baseColor = if (colors.isDark) TabHeaderBaseColor else TabHeaderLightBaseColor
    val surfaceColors = remember(colors, baseColor) {
        colors.copy(cardBackground = baseColor, cardBorder = Color.White.copy(alpha = .18f))
    }
    com.example.ui.theme.AccentHeaderStatusBar(foreground = colors.textPrimary)
    Box(modifier.fillMaxWidth().testTag(headerTag), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.matchParentSize().testTag("${headerTag}_glass")
            .xtremeLiquidGlassBackground(
                shape = AbsoluteRoundedCornerShape(bottomLeft = 32.dp, bottomRight = 48.dp),
                floatingDock = true,
                surfaceTintAlpha = 0f,
                tintColor = Color.Transparent,
                borderWidth = 0.8.dp,
                elevation = 6.dp,
                appColors = surfaceColors,
                backdrop = LocalTabHeaderBackdrop.current,
                baseFillAlpha = XtremeLiquidGlassDefaults.FloatingDockDarkFillAlpha,
                neutralFinish = true,
                innerWhiteTintAlpha = if (colors.isDark) 0f else .07f
            )
            // Covered rows must not receive taps through the translucent chrome.
            .pointerInput(Unit) { detectTapGestures(onTap = {}) })
        CompositionLocalProvider(LocalContentColor provides colors.textPrimary, content = content)
    }
}

@Composable
private fun HomeHeaderContent(onOpenSettings: (() -> Unit)?) {
    val colors = LocalAppColors.current
    val foreground = LocalContentColor.current
    Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().statusBarsPadding()
        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppDynamicLogo(Modifier.size(48.dp).testTag("home_header_logo"),
            primaryAccent = tabHeaderAccentColor(colors.primaryAccent, colors.isDark),
            secondaryAccent = tabHeaderAccentColor(colors.secondaryAccent, colors.isDark),
            cutoutColor = if (colors.isDark) TabHeaderBaseColor else TabHeaderLightBaseColor, showGlow = false)
        BoxWithConstraints(Modifier.weight(1f)) {
            // Retain the complete brand at compact widths and larger system text sizes.
            val fontScale = LocalDensity.current.fontScale
            val titleSize = (minOf(21f, (maxWidth.value - 12f) / (8.5f * fontScale)) * 1.1f).sp
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = tabHeaderAccentColor(colors.primaryAccent, colors.isDark),
                    fontWeight = FontWeight.Black)) { append("Wav") }
                append("Tape")
            }, color = foreground, fontWeight = FontWeight.ExtraBold, fontSize = titleSize,
                letterSpacing = 1.sp, maxLines = 1)
        }
        Box(Modifier.size(48.dp)
            .clickable { onOpenSettings?.invoke() }.testTag("top_settings_button"),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp).clip(CircleShape)
                .background(foreground.copy(alpha = .12f), CircleShape)
                .border(1.dp, foreground.copy(alpha = .25f), CircleShape)
                .testTag("top_settings_button_surface"), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Settings, "Settings", tint = foreground, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
internal fun AccentPageHeader(title: String, subtitle: String, headerTag: String,
    actions: @Composable RowScope.() -> Unit = {}) {
    GlassHeaderSurface(headerTag = headerTag) {
        Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, color = LocalContentColor.current, style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black, letterSpacing = (-.6).sp, maxLines = 2)
                Text(subtitle, color = LocalContentColor.current.copy(alpha = .90f),
                    style = MaterialTheme.typography.bodySmall)
            }
            actions()
        }
    }
}
