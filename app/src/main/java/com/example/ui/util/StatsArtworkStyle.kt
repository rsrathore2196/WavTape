package com.example.ui.util

import android.graphics.Canvas
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.example.ui.screens.listeningTime

internal fun statsShortTime(millis: Long): String = listeningTime(millis).let {
    it.first + if (millis >= 3_600_000L) " hr" else " min"
}

internal fun isLightStatsSurface(surface: Int): Boolean = ColorUtils.calculateLuminance(surface) >= 0.35

/** Moderates decorative accent chroma/tone so ribbons and borders stay balanced on light and dark cards. */
internal fun statsCardDecorAccent(accent: Color, surface: Color): Color {
    val accentHct = com.example.ui.theme.colorutilities.hct.Hct.fromInt(accent.toArgb())
    return if (isLightStatsSurface(surface.toArgb())) {
        val chroma = if (accentHct.chroma < 6.0) 0.0 else accentHct.chroma.coerceIn(24.0, 52.0)
        val tone = if (accentHct.chroma < 6.0) 48.0 else accentHct.tone.coerceIn(44.0, 62.0)
        Color(com.example.ui.theme.colorutilities.hct.Hct.from(accentHct.hue, chroma, tone).toInt())
    } else {
        if (accentHct.chroma < 6.0) accent
        else Color(com.example.ui.theme.colorutilities.hct.Hct.from(
            accentHct.hue, accentHct.chroma.coerceIn(28.0, 58.0), accentHct.tone.coerceIn(58.0, 78.0)
        ).toInt())
    }
}

/** Theme-adaptive tinted surface for stats cards: soft luminous pastel in light themes, rich velvet ink in dark themes. */
internal fun statsCardBase(accent: Color, surface: Color): Color {
    val surfaceArgb = surface.toArgb()
    val accentHct = com.example.ui.theme.colorutilities.hct.Hct.fromInt(accent.toArgb())
    val surfaceHct = com.example.ui.theme.colorutilities.hct.Hct.fromInt(surfaceArgb)
    val achromatic = accentHct.chroma < 6.0
    return if (isLightStatsSurface(surfaceArgb)) {
        val target = if (achromatic) {
            com.example.ui.theme.colorutilities.hct.Hct.from(
                surfaceHct.hue, surfaceHct.chroma.coerceAtMost(4.0), (surfaceHct.tone - 4.0).coerceIn(88.0, 93.0)
            ).toInt()
        } else {
            com.example.ui.theme.colorutilities.hct.Hct.from(
                accentHct.hue, (accentHct.chroma * 0.45).coerceIn(12.0, 24.0), 92.5
            ).toInt()
        }
        Color(ColorUtils.blendARGB(surfaceArgb, target, .86f))
    } else {
        val target = if (achromatic) {
            com.example.ui.theme.colorutilities.hct.Hct.from(
                surfaceHct.hue, surfaceHct.chroma.coerceAtMost(4.0), surfaceHct.tone.coerceIn(10.0, 15.0)
            ).toInt()
        } else {
            com.example.ui.theme.colorutilities.hct.Hct.from(
                accentHct.hue, (accentHct.chroma * 0.58).coerceIn(14.0, 32.0), (surfaceHct.tone * 0.45 + 9.0).coerceIn(12.0, 17.5)
            ).toInt()
        }
        Color(ColorUtils.blendARGB(surfaceArgb, target, .84f))
    }
}

internal fun statsCardHighlight(accent: Color, surface: Color): Color {
    val surfaceArgb = surface.toArgb()
    val light = isLightStatsSurface(surfaceArgb)
    val decor = statsCardDecorAccent(accent, surface).toArgb()
    val ink = if (light) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    var amount = if (light) .24f else .44f
    val floor = if (light) .08f else .14f
    while (amount > floor && ColorUtils.calculateContrast(ink,
            ColorUtils.blendARGB(surfaceArgb, decor, amount)) < 7.0) amount -= .04f
    return Color(ColorUtils.blendARGB(surfaceArgb, decor, amount))
}

/** Recorded once per size/palette, not rebuilt while scrolling or on every frame. */
internal fun Modifier.statsCardDecoration(accent: Color, surface: Color): Modifier = drawWithCache {
    val picture = Picture()
    val canvas = picture.beginRecording(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
    drawStatsCardFrame(canvas, RectF(0f, 0f, size.width, size.height), accent.toArgb(), surface.toArgb(), 24.dp.toPx())
    picture.endRecording()
    onDrawBehind { drawIntoCanvas { it.nativeCanvas.drawPicture(picture) } }
}

private fun alpha(color: Int, amount: Float) = ColorUtils.setAlphaComponent(color, (amount * 255).toInt().coerceIn(0, 255))

/** Same static edge lighting and ribbon detail for the cards and exported artwork, adapted for light and dark surfaces. */
internal fun drawStatsCardFrame(canvas: Canvas, bounds: RectF, accent: Int, surface: Int, radius: Float) {
    val light = isLightStatsSurface(surface)
    val decor = statsCardDecorAccent(Color(accent), Color(surface)).toArgb()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val outline = Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) }
    canvas.save()
    canvas.clipPath(outline)
    paint.color = surface
    canvas.drawRect(bounds, paint)
    val highlight = statsCardHighlight(Color(accent), Color(surface)).toArgb()
    paint.shader = LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
        intArrayOf(highlight, surface, ColorUtils.blendARGB(surface, decor, if (light) .10f else .14f)),
        floatArrayOf(0f, if (light) .52f else .48f, 1f), Shader.TileMode.CLAMP)
    canvas.drawRect(bounds, paint)
    paint.shader = RadialGradient(bounds.right, bounds.top, bounds.width() * .55f,
        intArrayOf(alpha(decor, if (light) .14f else .22f), alpha(decor, 0f)), null, Shader.TileMode.CLAMP)
    canvas.drawRect(bounds, paint)
    val wave = Path().apply {
        moveTo(bounds.left - bounds.width() * .1f, bounds.bottom)
        cubicTo(bounds.left + bounds.width() * .2f, bounds.bottom + bounds.height() * .12f,
            bounds.left + bounds.width() * .55f, bounds.top + bounds.height() * .57f,
            bounds.right + bounds.width() * .1f, bounds.top + bounds.height() * .72f)
        lineTo(bounds.right, bounds.bottom)
        close()
    }
    paint.shader = LinearGradient(bounds.left, bounds.bottom, bounds.right, bounds.top + bounds.height() * .65f,
        intArrayOf(alpha(decor, if (light) .03f else .02f), alpha(decor, if (light) .22f else .28f), alpha(decor, .07f)),
        null, Shader.TileMode.CLAMP)
    canvas.drawPath(wave, paint)
    // Multiple softly shaded ribbons give the whole interior depth, not just an outlined edge.
    paint.style = Paint.Style.STROKE
    for (offset in listOf(-.04f, .035f, .09f)) {
        val ribbon = Path().apply {
            moveTo(bounds.left - bounds.width() * .1f, bounds.bottom + bounds.height() * offset)
            cubicTo(bounds.left + bounds.width() * .3f, bounds.bottom + bounds.height() * (.08f + offset),
                bounds.left + bounds.width() * .65f, bounds.top + bounds.height() * (.64f + offset),
                bounds.right + bounds.width() * .1f, bounds.top + bounds.height() * (.69f + offset))
        }
        paint.shader = LinearGradient(bounds.left, bounds.bottom, bounds.right, bounds.top + bounds.height() * .7f,
            intArrayOf(alpha(decor, 0f), alpha(decor, if (light) .07f else .08f), alpha(decor, if (light) .22f else .24f), alpha(decor, .04f)),
            floatArrayOf(0f, .45f, .75f, 1f), Shader.TileMode.CLAMP)
        paint.strokeWidth = bounds.width() * .045f
        canvas.drawPath(ribbon, paint)
        paint.strokeWidth = (bounds.width() * .003f).coerceAtLeast(.8f)
        canvas.drawPath(ribbon, paint)
    }
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = (bounds.width() * .004f).coerceAtLeast(1f)
    paint.shader = LinearGradient(bounds.left, bounds.bottom, bounds.right, bounds.top + bounds.height() * .6f,
        intArrayOf(alpha(decor, 0f), alpha(decor, if (light) .40f else .52f), alpha(decor, 0f)), null, Shader.TileMode.CLAMP)
    canvas.drawPath(wave, paint)
    canvas.restore()
    paint.shader = null
    paint.style = Paint.Style.STROKE
    val inset = bounds.width() * .004f
    val rim = RectF(bounds).apply { inset(inset, inset) }
    paint.strokeWidth = (bounds.width() * .014f).coerceAtLeast(2f)
    paint.color = alpha(decor, if (light) .09f else .12f)
    canvas.drawRoundRect(rim, radius, radius, paint)
    paint.strokeWidth = (bounds.width() * .004f).coerceIn(1f, 1.8f)
    paint.shader = LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
        if (light) intArrayOf(alpha(decor, .58f), alpha(decor, .24f), alpha(decor, .52f))
        else intArrayOf(alpha(decor, .78f), alpha(decor, .28f), alpha(decor, .72f)), null, Shader.TileMode.CLAMP)
    canvas.drawRoundRect(rim, radius, radius, paint)
}

internal fun drawStatsPosterBackground(canvas: Canvas, accent: Int, surface: Int = android.graphics.Color.rgb(2, 10, 24)) {
    val light = isLightStatsSurface(surface)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val decor = if (light) statsCardDecorAccent(Color(accent), Color(surface)).toArgb() else accent
    canvas.drawColor(if (light) surface else android.graphics.Color.rgb(2, 10, 24))
    if (light) {
        val highlight = statsCardHighlight(Color(accent), Color(surface)).toArgb()
        paint.shader = LinearGradient(0f, 0f, 720f, 1280f,
            intArrayOf(highlight, surface, ColorUtils.blendARGB(surface, decor, .10f)),
            floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 720f, 1280f, paint)
    }
    paint.shader = RadialGradient(620f, 130f, 610f,
        intArrayOf(alpha(decor, if (light) .14f else .19f), alpha(decor, 0f)), null, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, 720f, 1280f, paint)
    val ribbons = listOf(
        Path().apply { moveTo(480f, -45f); cubicTo(535f, 75f, 625f, 99f, 765f, 142f) },
        Path().apply { moveTo(700f, 182f); cubicTo(500f, 256f, 664f, 343f, 790f, 492f) },
        Path().apply { moveTo(-80f, 1194f); cubicTo(160f, 1268f, 356f, 1031f, 794f, 1211f) },
        Path().apply { moveTo(-80f, 1105f); cubicTo(196f, 1190f, 395f, 1248f, 788f, 1218f) }
    )
    for (path in ribbons) {
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
        paint.strokeWidth = 28f
        paint.color = alpha(decor, if (light) .26f else .65f)
        canvas.drawPath(path, paint)
        paint.maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
        paint.strokeWidth = 10f
        paint.color = alpha(decor, if (light) .38f else .8f)
        canvas.drawPath(path, paint)
        paint.maskFilter = null
        paint.strokeWidth = 1.4f
        paint.color = if (light) alpha(decor, .72f) else ColorUtils.blendARGB(accent, android.graphics.Color.WHITE, .45f)
        canvas.drawPath(path, paint)
    }
}
