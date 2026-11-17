package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.onAccentGradient
import com.example.ui.theme.accentBrush
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider

/** A fixed accent canopy. Cached shapes/shaders never observe feed scroll. */
@Composable
internal fun HomeAppHeader(onOpenSettings: (() -> Unit)?, modifier: Modifier = Modifier, headerTag: String = "home_fixed_header") {
    val colors = LocalAppColors.current
    val text = colors.onAccentGradient
    AccentHeaderSurface(modifier, headerTag) {
        HomeHeaderContent(text, onOpenSettings)
    }
}

/** Shared background only; each tab supplies its own heading and actions. */
@Composable
internal fun AccentHeaderSurface(modifier: Modifier = Modifier, headerTag: String,
    content: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier.fillMaxWidth().testTag(headerTag).drawWithCache {
            val surface = Path().apply {
                addRoundRect(RoundRect(0f, -1.dp.toPx(), size.width, size.height,
                    bottomLeftCornerRadius = CornerRadius(32.dp.toPx()),
                    bottomRightCornerRadius = CornerRadius(48.dp.toPx())))
            }
            val fill = colors.accentBrush()
            val sheen = Brush.radialGradient(
                listOf(Color.White.copy(alpha = .08f), Color.Transparent),
                center = Offset(size.width * .12f, -size.height * .4f), radius = size.width * .75f
            )
            val fold = Path().apply {
                moveTo(size.width * .66f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(size.width * .40f, size.height)
                quadraticTo(size.width * .60f, size.height * .75f, size.width * .66f, 0f)
                close()
            }
            val foldedLight = Brush.linearGradient(listOf(Color.White.copy(alpha = .09f), Color.Transparent),
                Offset(size.width * .70f, size.height), Offset(size.width, 0f))
            onDrawBehind {
                drawPath(surface, fill)
                clipPath(surface) {
                    drawRect(sheen)
                    drawPath(fold, foldedLight)
                    drawPath(surface, Color.White.copy(alpha = .15f), style = Stroke(1.dp.toPx()))
                }
            }
        },
        contentAlignment = Alignment.TopCenter
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onAccentGradient, content = content)
    }
}

@Composable
private fun HomeHeaderContent(text: Color, onOpenSettings: (() -> Unit)?) {
    val colors = LocalAppColors.current
        Row(
            Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppDynamicLogo(Modifier.size(48.dp).testTag("home_header_logo"), primaryAccent = text, secondaryAccent = text.copy(alpha = .78f),
                cutoutColor = colors.primaryAccent, showGlow = false)
            BoxWithConstraints(Modifier.weight(1f)) {
                // Keep the complete brand on compact screens and with larger system text.
                val fontScale = LocalDensity.current.fontScale
                val titleSize = minOf(21f, (maxWidth.value - 12f) / (8.5f * fontScale)).sp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("XTREME", color = text, fontWeight = FontWeight.Black, fontSize = titleSize,
                        letterSpacing = 1.sp, maxLines = 1)
                    Spacer(Modifier.width(5.dp))
                    Text("PLAYER", color = text, fontWeight = FontWeight.ExtraBold, fontSize = titleSize,
                        letterSpacing = 1.sp, maxLines = 1)
                }
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(text.copy(alpha = .12f), CircleShape)
                    .border(1.dp, text.copy(alpha = .25f), CircleShape)
                    .clickable { onOpenSettings?.invoke() }.testTag("top_settings_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = text, modifier = Modifier.size(22.dp))
            }
        }
    }

@Composable
internal fun AccentPageHeader(title: String, subtitle: String, headerTag: String,
    actions: @Composable RowScope.() -> Unit = {}) {
    AccentHeaderSurface(headerTag = headerTag) {
        Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, color = LocalContentColor.current, style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black, letterSpacing = (-.6).sp, maxLines = 2)
                Text(subtitle, color = LocalContentColor.current.copy(alpha = .85f),
                    style = MaterialTheme.typography.bodySmall)
            }
            actions()
        }
    }
}
