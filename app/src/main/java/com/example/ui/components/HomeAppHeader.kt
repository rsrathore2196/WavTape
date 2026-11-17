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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalAppColors

/** A fixed accent canopy. Cached shapes/shaders never observe feed scroll. */
@Composable
internal fun HomeAppHeader(onOpenSettings: (() -> Unit)?, modifier: Modifier = Modifier, headerTag: String = "home_fixed_header") {
    val colors = LocalAppColors.current
    val surfaces = remember(colors.primaryAccent, colors.secondaryAccent) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(colors.primaryAccent.toArgb(), hsv)
        if (hsv[1] < .10f) android.graphics.Color.colorToHSV(colors.secondaryAccent.toArgb(), hsv)
        val hue = if (hsv[1] < .10f) 200f else hsv[0]
        listOf(16f, 38f, 60f).map { shift ->
            val vivid = Color.hsv((hue + shift) % 360f, .88f, .94f)
            // Preserve saturation while keeping white branding readable for any accent hue.
            var surface = vivid
            while (1.05f / (surface.luminance() + .05f) < 5.5f) surface = lerp(surface, Color.Black, .04f)
            surface
        }
    }
    val text = Color.White
    Box(
        modifier.fillMaxWidth().testTag(headerTag).drawWithCache {
            val surface = Path().apply {
                addRoundRect(RoundRect(0f, -1.dp.toPx(), size.width, size.height,
                    bottomLeftCornerRadius = CornerRadius(32.dp.toPx()),
                    bottomRightCornerRadius = CornerRadius(48.dp.toPx())))
            }
            val fill = Brush.linearGradient(surfaces, Offset.Zero, Offset(size.width, size.height))
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
        Row(
            Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppDynamicLogo(Modifier.size(38.dp), primaryAccent = text, secondaryAccent = text.copy(alpha = .78f),
                cutoutColor = surfaces.first(), showGlow = false)
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
                    .background(Color.White.copy(alpha = .14f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = .30f), CircleShape)
                    .clickable { onOpenSettings?.invoke() }.testTag("top_settings_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = text, modifier = Modifier.size(22.dp))
            }
        }
    }
}
