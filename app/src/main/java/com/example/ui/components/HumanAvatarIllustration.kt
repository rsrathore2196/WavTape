package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalAppColors

internal enum class HumanAvatarStyle { SWEPT, LONG, CURLS, BOB, GLASSES, HEADSCARF, TURBAN, SILVER, AFRO }

/** Original flat portraits: natural skin/hair colors, with theme-colored clothes and headphones. */
@Composable
internal fun rememberHumanAvatar(style: HumanAvatarStyle): ImageVector {
    val colors = LocalAppColors.current
    return remember(style, colors.primaryAccent, colors.secondaryAccent) {
        humanAvatar(style, colors.primaryAccent, colors.secondaryAccent)
    }
}

private fun humanAvatar(style: HumanAvatarStyle, primary: Color, secondary: Color): ImageVector {
    val skin = Color(when (style) {
        HumanAvatarStyle.SWEPT -> 0xFFF1BC94
        HumanAvatarStyle.LONG -> 0xFFDDA17E
        HumanAvatarStyle.CURLS -> 0xFF955E43
        HumanAvatarStyle.BOB -> 0xFFF3CEAC
        HumanAvatarStyle.GLASSES -> 0xFFC88963
        HumanAvatarStyle.HEADSCARF -> 0xFFEAB28F
        HumanAvatarStyle.TURBAN -> 0xFFBA7B54
        HumanAvatarStyle.SILVER -> 0xFFD9AB86
        HumanAvatarStyle.AFRO -> 0xFFAC7152
    })
    val hair = Color(when (style) {
        HumanAvatarStyle.LONG -> 0xFF54312E
        HumanAvatarStyle.BOB -> 0xFF7D4935
        HumanAvatarStyle.SILVER -> 0xFFCDD5E0
        else -> 0xFF252C42
    })
    val shade = lerp(skin, Color(0xFF713B30), .17f)
    val ink = Color(0xFF293149)
    val outfit = Brush.linearGradient(listOf(lerp(primary, ink, .26f), lerp(secondary, ink, .24f)),
        Offset(20f, 72f), Offset(80f, 100f))
    val builder = ImageVector.Builder("Listener$style", 100.dp, 100.dp, 100f, 100f)
    with(builder) {
        // Hair and headwear behind the face, neck and shoulders.
        when (style) {
            HumanAvatarStyle.LONG, HumanAvatarStyle.BOB -> shape(hair) {
                moveTo(25f, 44f); curveTo(21f, 18f, 32f, 10f, 50f, 11f)
                curveTo(71f, 10f, 80f, 23f, 75f, 49f)
                lineTo(if (style == HumanAvatarStyle.LONG) 80f else 73f, 85f)
                quadTo(50f, 95f, 21f, 85f); close()
            }
            HumanAvatarStyle.AFRO -> {
                for ((x, y) in listOf(29f to 35f, 26f to 51f, 36f to 21f, 51f to 18f, 66f to 25f, 74f to 40f, 73f to 56f))
                    ellipse(hair, x, y, 13f, 15f)
            }
            HumanAvatarStyle.HEADSCARF -> shape(lerp(secondary, ink, .35f)) {
                moveTo(19f, 83f); lineTo(26f, 32f); curveTo(27f, 2f, 72f, 2f, 75f, 32f)
                lineTo(82f, 84f); quadTo(51f, 97f, 19f, 83f); close()
            }
            else -> ellipse(hair, 50f, 34f, 23f, 24f)
        }
        shape(skin) {
            moveTo(42f, 60f); lineTo(58f, 60f); lineTo(60f, 76f)
            quadTo(50f, 87f, 40f, 76f); close()
        }
        shape(shade) {
            moveTo(42f, 60f); lineTo(58f, 60f); lineTo(58f, 70f)
            quadTo(48f, 72f, 42f, 66f); close()
        }
        path(fill = outfit) {
            moveTo(12f, 103f); lineTo(15f, 89f); quadTo(18f, 78f, 39f, 73f)
            quadTo(50f, 84f, 61f, 73f); quadTo(83f, 78f, 86f, 89f)
            lineTo(89f, 103f); close()
        }
        line(Color.White.copy(alpha = .20f), 1.4f) {
            moveTo(27f, 88f); lineTo(26f, 100f); moveTo(73f, 88f); lineTo(75f, 100f)
        }
        // Shirt collars, a hoodie, or a scarf add variety beyond the hairstyle.
        when (style) {
            HumanAvatarStyle.SILVER, HumanAvatarStyle.GLASSES -> {
                shape(Color(0xFFE4EEF5)) {
                    moveTo(39f, 73f); lineTo(50f, 81f); lineTo(41f, 90f); lineTo(33f, 77f); close()
                    moveTo(61f, 73f); lineTo(50f, 81f); lineTo(59f, 90f); lineTo(67f, 77f); close()
                }
                shape(secondary) { moveTo(50f, 81f); lineTo(54f, 86f); lineTo(52f, 100f); lineTo(47f, 100f); lineTo(46f, 86f); close() }
            }
            HumanAvatarStyle.HEADSCARF -> shape(secondary) {
                moveTo(32f, 59f); quadTo(30f, 75f, 51f, 82f); quadTo(67f, 77f, 70f, 65f)
                quadTo(65f, 88f, 45f, 90f); quadTo(27f, 79f, 32f, 59f); close()
            }
            else -> line(Color.White.copy(alpha = .55f), 1.7f) {
                moveTo(39f, 75f); quadTo(50f, 87f, 61f, 75f)
            }
        }
        ellipse(shade, 33f, 48f, 5f, 7f)
        ellipse(shade, 67f, 48f, 5f, 7f)
        shape(skin) {
            moveTo(33f, 33f); curveTo(33f, 18f, 67f, 18f, 67f, 33f)
            lineTo(67f, 49f); curveTo(67f, 62f, 59f, 69f, 50f, 69f)
            curveTo(40f, 69f, 33f, 62f, 33f, 49f); close()
        }
        shape(shade.copy(alpha = .45f)) {
            moveTo(62f, 31f); quadTo(72f, 59f, 54f, 68f)
            curveTo(63f, 66f, 67f, 58f, 67f, 49f); lineTo(67f, 33f); close()
        }
        when (style) {
            HumanAvatarStyle.SWEPT -> shape(hair) {
                moveTo(29f, 43f); lineTo(27f, 29f); curveTo(24f, 9f, 46f, 7f, 59f, 14f)
                quadTo(67f, 17f, 74f, 12f); curveTo(77f, 23f, 72f, 30f, 64f, 34f)
                lineTo(61f, 27f); quadTo(51f, 39f, 35f, 34f); lineTo(34f, 43f); close()
            }
            HumanAvatarStyle.LONG -> shape(hair) {
                moveTo(28f, 44f); curveTo(20f, 12f, 58f, 4f, 70f, 24f)
                lineTo(70f, 44f); lineTo(65f, 39f); lineTo(60f, 26f)
                quadTo(48f, 35f, 34f, 37f); lineTo(34f, 44f); close()
            }
            HumanAvatarStyle.CURLS -> {
                ellipse(hair, 50f, 28f, 22f, 12f)
                for ((x, y) in listOf(31f to 29f, 35f to 20f, 45f to 15f, 56f to 16f, 65f to 23f, 69f to 32f))
                    ellipse(hair, x, y, 7f, 8f)
            }
            HumanAvatarStyle.BOB -> shape(hair) {
                moveTo(27f, 44f); lineTo(28f, 26f); curveTo(33f, 9f, 64f, 9f, 71f, 28f)
                lineTo(72f, 45f); lineTo(64f, 39f); lineTo(62f, 32f)
                lineTo(52f, 36f); lineTo(52f, 29f); lineTo(34f, 38f); lineTo(34f, 45f); close()
            }
            HumanAvatarStyle.GLASSES -> shape(hair) {
                moveTo(29f, 42f); lineTo(28f, 26f); quadTo(35f, 5f, 56f, 15f)
                quadTo(75f, 12f, 71f, 38f); lineTo(65f, 41f); lineTo(62f, 28f)
                quadTo(51f, 34f, 35f, 31f); lineTo(34f, 42f); close()
            }
            HumanAvatarStyle.HEADSCARF -> {
                shape(secondary) {
                    moveTo(28f, 46f); curveTo(18f, 7f, 72f, 4f, 74f, 34f); lineTo(69f, 48f)
                    lineTo(65f, 34f); quadTo(49f, 35f, 40f, 24f); quadTo(31f, 33f, 32f, 48f); close()
                }
                line(Color.White.copy(alpha = .35f), 1.7f) { moveTo(33f, 31f); quadTo(37f, 19f, 48f, 18f) }
            }
            HumanAvatarStyle.TURBAN -> {
                shape(primary) {
                    moveTo(27f, 39f); curveTo(19f, 11f, 42f, 9f, 50f, 8f)
                    curveTo(61f, 10f, 79f, 13f, 73f, 39f); quadTo(60f, 35f, 50f, 27f)
                    quadTo(41f, 36f, 27f, 39f); close()
                }
                line(Color.White.copy(alpha = .40f), 1.8f) {
                    moveTo(30f, 32f); quadTo(49f, 26f, 62f, 16f)
                    moveTo(69f, 32f); quadTo(51f, 26f, 39f, 16f)
                }
            }
            HumanAvatarStyle.SILVER -> shape(hair) {
                moveTo(28f, 43f); lineTo(28f, 28f); quadTo(32f, 12f, 44f, 17f)
                lineTo(40f, 29f); lineTo(34f, 35f); lineTo(34f, 44f); close()
                moveTo(56f, 17f); quadTo(72f, 12f, 72f, 39f); lineTo(66f, 44f)
                lineTo(64f, 33f); lineTo(59f, 29f); close()
            }
            HumanAvatarStyle.AFRO -> {
                for ((x, y) in listOf(32f to 32f, 42f to 25f, 55f to 25f, 67f to 33f)) ellipse(hair, x, y, 9f, 9f)
            }
        }
        if (style == HumanAvatarStyle.TURBAN || style == HumanAvatarStyle.SILVER) {
            shape(hair) {
                moveTo(35f, 54f); quadTo(50f, 62f, 65f, 54f); lineTo(62f, 63f)
                quadTo(50f, 76f, 38f, 63f); close()
            }
            ellipse(skin, 50f, 58f, 5f, 2.5f)
        }
        ellipse(ink, 43f, 47f, 1.2f, 1.5f)
        ellipse(ink, 57f, 47f, 1.2f, 1.5f)
        line(shade, 1.5f) { moveTo(50f, 49f); lineTo(49f, 54f); lineTo(51f, 54f) }
        line(lerp(shade, ink, .35f), 1.4f) { moveTo(45f, 59f); quadTo(50f, 62f, 55f, 59f) }
        if (style == HumanAvatarStyle.GLASSES || style == HumanAvatarStyle.SILVER) {
            line(ink, 1.5f) {
                moveTo(36f, 43f); lineTo(47f, 43f); lineTo(47f, 51f); quadTo(41f, 55f, 36f, 51f); close()
                moveTo(53f, 43f); lineTo(64f, 43f); lineTo(64f, 51f); quadTo(58f, 55f, 53f, 51f); close()
                moveTo(47f, 46f); quadTo(50f, 44f, 53f, 46f)
            }
        }
        // Headphones remain a common music identity across all nine people.
        line(lerp(primary, ink, .55f), 7f) {
            moveTo(27f, 44f); lineTo(27f, 33f); curveTo(27f, 4f, 73f, 4f, 73f, 33f); lineTo(73f, 44f)
        }
        line(primary, 3.8f) {
            moveTo(27f, 40f); lineTo(27f, 33f); curveTo(27f, 6f, 73f, 6f, 73f, 33f); lineTo(73f, 40f)
        }
        for (x in listOf(22f, 66f)) {
            roundedRect(lerp(primary, ink, .5f), x, 38f, 12f, 23f, 5f)
            roundedRect(primary, x + 2f, 40f, 8f, 19f, 3f)
            roundedRect(lerp(secondary, Color.White, .15f), x + 3f, 42f, 2f, 13f, 1f)
        }
    }
    return builder.build()
}

private fun ImageVector.Builder.shape(color: Color, draw: PathBuilder.() -> Unit) = path(fill = SolidColor(color), pathBuilder = draw)
private fun ImageVector.Builder.line(color: Color, width: Float, draw: PathBuilder.() -> Unit) =
    path(fill = null, stroke = SolidColor(color), strokeLineWidth = width, strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)

private fun ImageVector.Builder.ellipse(color: Color, x: Float, y: Float, rx: Float, ry: Float) = shape(color) {
    val k = .5523f
    moveTo(x - rx, y)
    curveTo(x - rx, y - ry * k, x - rx * k, y - ry, x, y - ry)
    curveTo(x + rx * k, y - ry, x + rx, y - ry * k, x + rx, y)
    curveTo(x + rx, y + ry * k, x + rx * k, y + ry, x, y + ry)
    curveTo(x - rx * k, y + ry, x - rx, y + ry * k, x - rx, y); close()
}

private fun ImageVector.Builder.roundedRect(color: Color, x: Float, y: Float, width: Float, height: Float, radius: Float) = shape(color) {
    moveTo(x + radius, y); lineTo(x + width - radius, y); quadTo(x + width, y, x + width, y + radius)
    lineTo(x + width, y + height - radius); quadTo(x + width, y + height, x + width - radius, y + height)
    lineTo(x + radius, y + height); quadTo(x, y + height, x, y + height - radius)
    lineTo(x, y + radius); quadTo(x, y, x + radius, y); close()
}
