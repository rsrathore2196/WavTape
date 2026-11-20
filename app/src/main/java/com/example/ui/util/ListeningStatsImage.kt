package com.example.ui.util

import android.content.ClipData
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import android.text.Layout
import android.text.StaticLayout
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.R
import com.example.data.local.ListeningPeriod
import com.example.data.local.ListeningTotal
import com.example.data.repository.ArtistPhotoRepository
import com.example.data.repository.ListeningSummary
import com.example.performance.AppDispatchers
import com.example.ui.screens.listeningTime
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Frozen at the explicit share action; later listening/period updates cannot change the image. */
internal data class ListeningStatsImageRequest(
    val summary: ListeningSummary,
    val period: ListeningPeriod,
    val accent: Int,
    val surface: Int = ListeningStatsImage.DARK_POSTER_SURFACE
)

internal data class ListeningStatsPosterText(val subtitle: String, val value: String, val unit: String, val totals: String)

internal fun listeningStatsPosterText(request: ListeningStatsImageRequest, name: String): ListeningStatsPosterText {
    val summary = request.summary
    val time = listeningTime(summary.millis)
    val subtitle = name.trim().takeIf(String::isNotEmpty)?.let { "$it's Listening History" } ?: "Your Listening History"
    return ListeningStatsPosterText(subtitle, time.first,
        if (summary.millis >= 3_600_000L) "HOURS LISTENED" else "MINUTES LISTENED",
        summary.plays.toString() + " Plays · " + summary.songCount + " songs · " + summary.artistCount + " artists")
}

/** Explicit, bounded export work only. No startup work, catalog searches or extra image cache. */
internal object ListeningStatsImage {
    private val regular by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
    private val bold by lazy { Typeface.create("sans-serif", Typeface.BOLD) }
    internal const val DARK_POSTER_SURFACE = 0xFF020A18.toInt()
    private const val NAVY = DARK_POSTER_SURFACE
    private const val SECONDARY = 0xFFB9C5D9.toInt()
    private const val LIGHT_PRIMARY_TEXT = 0xFF0F172A.toInt()
    private const val LIGHT_SECONDARY_TEXT = 0xFF3E4A5E.toInt()

    private val darkTideglassColors by lazy {
        com.example.ui.theme.resolveAppThemeColors(
            darkTheme = true,
            custom = com.example.data.local.ThemePresets.default(dark = true).toCustomThemeState()
        )
    }
    private val lightTideglassColors by lazy {
        com.example.ui.theme.resolveAppThemeColors(
            darkTheme = false,
            custom = com.example.data.local.ThemePresets.default(dark = false).toCustomThemeState()
        )
    }

    /** Exported listening stats always use Tideglass colors, adapting only between dark and light mode. */
    internal fun tideglassPalette(isDark: Boolean): Pair<Int, Int> {
        val tideglass = if (isDark) darkTideglassColors else lightTideglassColors
        val surface = if (isDark) DARK_POSTER_SURFACE else {
            val saved = if (tideglass.neutralSurfaces) tideglass.cardBackgroundElevated else tideglass.cardBackground
            statsCardBase(tideglass.primaryAccent, saved).toArgb()
        }
        return tideglass.primaryAccent.toArgb() to surface
    }

    internal fun requestForMode(
        summary: ListeningSummary,
        period: ListeningPeriod,
        isDark: Boolean
    ): ListeningStatsImageRequest {
        val (accent, surface) = tideglassPalette(isDark)
        return ListeningStatsImageRequest(summary, period, accent, surface)
    }

    suspend fun create(context: Context, request: ListeningStatsImageRequest, name: String): File = withContext(AppDispatchers.Io) {
        val images = ConcurrentHashMap<String, Bitmap>()
        val sources = buildList {
            request.summary.songs.take(5).forEach { add("song:" + it.identity to it.coverUrl) }
            request.summary.artists.take(5).forEach { row ->
                val cached = ArtistPhotoRepository.cachedArtist(row.artist)
                val artist = if (cached.id.isBlank() && row.artistId.startsWith("UC")) cached.copy(id = row.artistId) else cached
                ArtistPhotoRepository.initialPhoto(artist)?.let { add("artist:" + row.identity to it) }
            }
        }.filter { it.second.isNotBlank() }.distinctBy { it.first }
        val permits = Semaphore(2)
        // Reuse the existing provider URLs and Coil caches. A missing portrait never blocks sharing.
        withTimeoutOrNull(2_500L) {
            coroutineScope {
                sources.forEach { (key, url) -> launch {
                    permits.withPermit {
                        try {
                            val result = context.imageLoader.execute(ImageRequest.Builder(context)
                                .data(providerArtworkUrl(url, 200)).size(200).allowHardware(false)
                                .interceptorDispatcher(AppDispatchers.Io).fetcherDispatcher(AppDispatchers.Io)
                                .decoderDispatcher(AppDispatchers.CpuBounded).build())
                            ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap?.let { images[key] = it }
                        } catch (cancelled: CancellationException) { throw cancelled }
                          catch (_: Exception) { /* Keep actual names/initials when artwork is unavailable. */ }
                    }
                } }
            }
        }
        ensureActive()
        var bitmap: Bitmap? = null
        var file: File? = null
        try {
            // Retain ownership even if cancellation arrives while the CPU dispatcher returns the bitmap.
            withContext(AppDispatchers.CpuBounded) { bitmap = render(context, request, name, images) }
            val directory = File(context.cacheDir, "listening-stats").apply { check(isDirectory || mkdirs()) }
            val expiry = System.currentTimeMillis() - 86_400_000L
            directory.listFiles()?.filter { it.isFile && it.lastModified() < expiry }?.forEach { it.delete() }
            file = File.createTempFile("WavTape-" + request.period.label + "-", ".png", directory)
            file.outputStream().use { check(checkNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, it)) { "Stats image could not be saved" } }
            ensureActive()
            file
        } catch (error: Exception) { file?.delete(); throw error }
        finally { bitmap?.recycle() } // Artwork bitmaps belong to Coil and are never recycled here.
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Listening Stats", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun save(context: Context, file: File, destination: Uri) = withContext(AppDispatchers.Io) {
        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IOException("The selected location could not be opened")
    }

    suspend fun saveToGallery(context: Context, file: File, displayName: String): Uri = withContext(AppDispatchers.Io) {
        ensureActive()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WavTape")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val destination = resolver.insert(collection, values) ?: throw IOException("Gallery could not create the image")
        var complete = false
        try {
            save(context, file, destination)
            ensureActive()
            if (Build.VERSION.SDK_INT >= 29 && resolver.update(destination,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) <= 0)
                throw IOException("Gallery could not publish the image")
            complete = true
            destination
        } finally {
            if (!complete) withContext(kotlinx.coroutines.NonCancellable + AppDispatchers.Io) {
                resolver.delete(destination, null, null)
            }
        }
    }

    /** Pure fixed-ratio poster renderer: never captures or alters any app screen. */
    internal fun render(context: Context, request: ListeningStatsImageRequest, name: String,
        images: Map<String, Bitmap> = emptyMap()): Bitmap {
        val lightPoster = isLightStatsSurface(request.surface)
        val primaryText = if (lightPoster) LIGHT_PRIMARY_TEXT else Color.WHITE
        val secondaryText = if (lightPoster) LIGHT_SECONDARY_TEXT else SECONDARY
        val artistFeature = featureLayout(request.summary.artists.firstOrNull(), true, primaryText, secondaryText)
        val songFeature = featureLayout(request.summary.songs.firstOrNull(), false, primaryText, secondaryText)
        val featureHeight = maxOf(artistFeature.height, songFeature.height)
        // Normal posters retain their dimensions; unusually long names get the
        // space they need below the rankings rather than being cut off.
        val logicalHeight = maxOf(1280, 893 + featureHeight + 48)
        val output = Bitmap.createBitmap(1080, (logicalHeight * 3 + 1) / 2, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            canvas.scale(1.5f, 1.5f)
            val text = listeningStatsPosterText(request, name)
            val (resolvedAccent, posterSurface) = tideglassPalette(isDark = !lightPoster)
            // Keep the Tideglass hue readable on either the light stats surface or the dark canvas.
            var accent = if (lightPoster) {
                statsCardDecorAccent(androidx.compose.ui.graphics.Color(resolvedAccent),
                    androidx.compose.ui.graphics.Color(posterSurface)).toArgb()
            } else resolvedAccent
            val minContrast = if (lightPoster) 4.5 else 3.0
            val blendTarget = if (lightPoster) LIGHT_PRIMARY_TEXT else Color.WHITE
            if (ColorUtils.calculateContrast(accent, posterSurface) < minContrast) {
                val baseAccent = accent
                for (step in 1..12) {
                    accent = ColorUtils.blendARGB(baseAccent, blendTarget, step / 12f)
                    if (ColorUtils.calculateContrast(accent, posterSurface) >= minContrast) break
                }
            }
            val frameAccent = if (lightPoster) resolvedAccent else accent
            val featureCardSurface = if (lightPoster) ColorUtils.blendARGB(posterSurface, Color.WHITE, .28f) else NAVY
            val coverPlaceholderBg = if (lightPoster) ColorUtils.blendARGB(posterSurface, Color.WHITE, .68f) else Color.rgb(15, 29, 48)

            drawStatsPosterBackground(canvas, frameAccent, posterSurface)
            canvas.label("Listening Stats", 46f, 132f, 36f, 370f, primaryText, bold = true)
            canvas.label(text.subtitle, 47f, 167f, 20f, 380f, secondaryText)
            canvas.label(request.period.label.uppercase(Locale.getDefault()), 47f, 202f, 13f, 380f, accent, tracking = .15f)
            drawBrandLockup(canvas, context, lightPoster)
            val number = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 104f; typeface = bold
                shader = LinearGradient(0f, 235f, 0f, 320f, primaryText, accent, Shader.TileMode.CLAMP)
            }
            val fitting = TextUtils.ellipsize(text.value, number, 620f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(fitting, 44f, 312f, number)
            canvas.label(text.unit, 47f, 349f, 20f, 625f, secondaryText, tracking = .15f)
            canvas.label(text.totals, 47f, 389f, 19f, 630f, secondaryText)
            canvas.label("TOP SONGS", 47f, 441f, 18f, 290f, secondaryText, true, .18f)
            canvas.label("TOP ARTISTS", 378f, 441f, 18f, 300f, secondaryText, true, .18f)
            val summary = request.summary
            summary.songs.take(5).forEachIndexed { index, row ->
                val y = 470f + index * 79f
                canvas.label((index + 1).toString(), 47f, y + 38f, 23f, 25f, accent, true)
                canvas.cover(images["song:" + row.identity], row.title, 78f, y, 59f, false, accent, coverPlaceholderBg, secondaryText)
                canvas.label(row.title, 149f, y + 23f, 20f, 192f, primaryText, bold = true)
                canvas.label(row.artist + " · " + statsShortTime(row.listenedMs), 149f, y + 49f, 15f, 194f, secondaryText)
            }
            summary.artists.take(5).forEachIndexed { index, row ->
                val y = 470f + index * 79f
                canvas.label((index + 1).toString(), 378f, y + 38f, 23f, 25f, accent, true)
                canvas.cover(images["artist:" + row.identity], row.artist, 416f, y, 59f, true, accent, coverPlaceholderBg, secondaryText)
                canvas.label(row.artist, 496f, y + 23f, 20f, 181f, primaryText, bold = true)
                canvas.label(statsShortTime(row.listenedMs), 496f, y + 49f, 16f, 181f, secondaryText)
            }
            if (summary.songs.isEmpty()) canvas.label("No qualified plays yet", 47f, 493f, 16f, 285f, secondaryText)
            if (summary.artists.isEmpty()) canvas.label("No listening activity yet", 378f, 493f, 16f, 300f, secondaryText)
            canvas.featureCard(artistFeature, 29f, frameAccent, featureCardSurface, secondaryText, coverPlaceholderBg, images, featureHeight)
            canvas.featureCard(songFeature, 369f, frameAccent, featureCardSurface, secondaryText, coverPlaceholderBg, images, featureHeight)
            return output
        } catch (error: Exception) { output.recycle(); throw error }
    }

    internal const val BRAND_LOCKUP_RIGHT = 676f
    internal const val BRAND_LOCKUP_WIDTH = 214f
    internal const val BRAND_LOCKUP_CENTER_Y = 132f

    private val lightLockupColorFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.12f, 0f, 0f, 0f, 0f,
                0f, 0.56f, 0f, 0f, 0f,
                0f, 0f, 0.78f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    internal fun brandLockupPixelBounds(intrinsicWidth: Int, intrinsicHeight: Int): Rect {
        val aspect = intrinsicWidth.toFloat() / intrinsicHeight.coerceAtLeast(1)
        val bmpW = kotlin.math.round(BRAND_LOCKUP_WIDTH * 1.5f).toInt().coerceAtLeast(1)
        val bmpH = kotlin.math.round(bmpW / aspect.coerceAtLeast(.01f)).toInt().coerceAtLeast(1)
        val dstLeftPx = kotlin.math.round(BRAND_LOCKUP_RIGHT * 1.5f).toInt() - bmpW
        val dstTopPx = kotlin.math.round(BRAND_LOCKUP_CENTER_Y * 1.5f).toInt() - bmpH / 2
        return Rect(dstLeftPx, dstTopPx, dstLeftPx + bmpW, dstTopPx + bmpH)
    }

    private fun drawBrandLockup(canvas: Canvas, context: Context, lightPoster: Boolean) {
        // Draw the supplied transparent WavTape logo+wordmark lockup at top-right,
        // adapting contrast for light surfaces while preserving its cyan/blue identity.
        val lockup = context.getDrawable(R.drawable.wavtape_lockup) ?: return
        val px = brandLockupPixelBounds(lockup.intrinsicWidth, lockup.intrinsicHeight)
        val dst = RectF(px.left / 1.5f, px.top / 1.5f, px.right / 1.5f, px.bottom / 1.5f)

        val rendered = Bitmap.createBitmap(px.width(), px.height(), Bitmap.Config.ARGB_8888)
        try {
            lockup.setBounds(0, 0, px.width(), px.height())
            lockup.draw(Canvas(rendered))

            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(
                    ColorUtils.setAlphaComponent(if (lightPoster) 0xFF041430.toInt() else NAVY, if (lightPoster) 55 else 165),
                    PorterDuff.Mode.SRC_IN
                )
                maskFilter = BlurMaskFilter(if (lightPoster) 3.5f else 5f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawBitmap(rendered, null, RectF(dst.left, dst.top + 1.5f, dst.right, dst.bottom + 1.5f), shadowPaint)

            val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                if (lightPoster) colorFilter = lightLockupColorFilter
            }
            canvas.drawBitmap(rendered, null, dst, brandPaint)
        } finally {
            rendered.recycle()
        }
    }

    internal fun wrappedFeatureName(value: String): StaticLayout = wrappedText(value, 22f, Color.WHITE, true)

    @android.annotation.SuppressLint("WrongConstant") // Layout's API 23 constant is identical to LineBreaker's API 29 constant (1).
    private fun wrappedText(value: String, size: Float, color: Int, heavy: Boolean = false): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; typeface = if (heavy) bold else regular; this.color = color
        }
        return StaticLayout.Builder.obtain(value, 0, value.length, paint, 272)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).build()
    }

    private data class FeatureLayout(val row: ListeningTotal?, val artist: Boolean,
        val name: StaticLayout?, val subtitle: StaticLayout?, val time: StaticLayout?, val height: Int)

    private fun featureLayout(row: ListeningTotal?, artist: Boolean, primaryText: Int = Color.WHITE,
        secondaryText: Int = SECONDARY): FeatureLayout {
        if (row == null) return FeatureLayout(null, artist, null, null, null, 204)
        val name = wrappedText(if (artist) row.artist else row.title, 22f, primaryText, true)
        val subtitle = if (artist) null else wrappedText(row.artist, 17f, secondaryText)
        val time = wrappedText(statsShortTime(row.listenedMs), 17f, secondaryText)
        val height = 140 + name.height + (subtitle?.let { 4 + it.height } ?: 0) + 6 + time.height + 20
        return FeatureLayout(row, artist, name, subtitle, time, height)
    }

    private fun Canvas.featureCard(feature: FeatureLayout, x: Float, accent: Int, cardSurface: Int,
        secondaryText: Int, coverPlaceholderBg: Int, images: Map<String, Bitmap>, height: Int) {
        drawStatsCardFrame(this, RectF(x, 893f, x + 324f, 893f + height), accent, cardSurface, 28f)
        label(if (feature.artist) "TOP ARTIST" else "MOST PLAYED SONG", x + 26f, 932f, 15f, 276f, secondaryText, false, .16f)
        val row = feature.row
        if (row == null) {
            label(if (feature.artist) "No listening activity yet" else "No qualified plays yet", x + 26f, 1015f, 18f, 276f, secondaryText)
            return
        }
        cover(images[(if (feature.artist) "artist:" else "song:") + row.identity], if (feature.artist) row.artist else row.title,
            x + 26f, 949f, 72f, feature.artist, accent, coverPlaceholderBg, secondaryText)
        var y = 1033f
        fun drawText(layout: StaticLayout) {
            save(); translate(x + 26f, y); layout.draw(this); restore()
            y += layout.height
        }
        drawText(checkNotNull(feature.name))
        feature.subtitle?.let { y += 4f; drawText(it) }
        y += 6f
        drawText(checkNotNull(feature.time))
    }

    private fun Canvas.label(value: String, x: Float, baseline: Float, size: Float, width: Float,
        color: Int = Color.WHITE, bold: Boolean = false, tracking: Float = 0f) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; typeface = if (bold) ListeningStatsImage.bold else regular
            this.color = color; letterSpacing = tracking
        }
        drawText(TextUtils.ellipsize(value, paint, width, TextUtils.TruncateAt.END).toString(), x, baseline, paint)
    }

    private fun Canvas.cover(bitmap: Bitmap?, name: String, x: Float, y: Float, size: Float, circle: Boolean, accent: Int,
        placeholderBg: Int = Color.rgb(15, 29, 48), placeholderText: Int = SECONDARY) {
        val rect = RectF(x, y, x + size, y + size)
        val path = Path().apply {
            if (circle) addOval(rect, Path.Direction.CW) else addRoundRect(rect, 8f, 8f, Path.Direction.CW)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        save()
        clipPath(path)
        drawColor(placeholderBg)
        if (bitmap != null && !bitmap.isRecycled) {
            val side = minOf(bitmap.width, bitmap.height)
            val left = (bitmap.width - side) / 2; val top = (bitmap.height - side) / 2
            drawBitmap(bitmap, Rect(left, top, left + side, top + side), rect, paint)
        } else {
            val initial = name.trim().take(1).uppercase(Locale.getDefault())
            label(initial, x + size * .32f, y + size * .66f, size * .45f, size * .5f, placeholderText, true)
        }
        restore()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        paint.color = ColorUtils.setAlphaComponent(accent, 100)
        drawPath(path, paint)
    }
}
