package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ListeningPeriod
import com.example.data.local.ListeningTotal
import com.example.data.repository.ListeningSummary
import com.example.ui.util.*
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningStatsImageTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun song(index: Int, plays: Long = 2) = ListeningTotal("o", "2026-10", "song", "song-$index",
        "track-$index", "Song $index", "Artist $index", "UC_$index", "", 180_000L, "", 60_000L, plays)

    @Before fun resetProviderRootsForTheFreshTestApplication() {
        // Robolectric creates a new cache directory per test; AndroidX's static roots otherwise outlive it.
        val roots = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (roots.get(null) as MutableMap<*, *>).clear()
    }

    @Test fun exportCountsUseAllDistinctListeningRowsWithoutChangingTopFiveOrQualifiedPlayRules() {
        val songs = (1..8).map { song(it) } + song(1).copy(origin = "backup", listenedMs = 30_000)
        val artists = songs.map { it.copy(kind = "artist", identity = it.artistId) }
        val result = ListeningSummary.from(songs + song(9, plays = 0), artists)
        assertEquals(9, result.songCount)
        assertEquals(8, result.artistCount)
        assertEquals(5, result.songs.size)
        assertEquals(5, result.artists.size)
        assertEquals(18, result.plays)
        assertEquals(570_000, result.millis)
        assertEquals("song-1", result.songs.first().identity)
    }

    @Test fun posterUsesSelectedPeriodActualTotalsAndProfileNameIncludingEmptyAndHours() {
        val request = ListeningStatsImageRequest(ListeningSummary(900_000, 12, emptyList(), emptyList(), 22, 16),
            ListeningPeriod.MONTHLY, Color.CYAN)
        val text = listeningStatsPosterText(request, "  Maya  ")
        assertEquals("Maya's Listening History", text.subtitle)
        assertEquals("15", text.value)
        assertEquals("MINUTES LISTENED", text.unit)
        assertEquals("12 Plays · 22 songs · 16 artists", text.totals)
        assertEquals("Your Listening History", listeningStatsPosterText(request, " ").subtitle)
        assertEquals("HOURS LISTENED", listeningStatsPosterText(request.copy(summary = request.summary.copy(millis = 7_200_000)), "Maya").unit)
    }

    @Test fun highResolutionPosterContainsActualArtworkAndNeverRecyclesSharedCachedBitmaps() {
        val rows = (1..5).map { song(it) }
        val summary = ListeningSummary(900_000, 10, rows, rows.map { it.copy(kind = "artist") }, 22, 16)
        val request = ListeningStatsImageRequest(summary, ListeningPeriod.WEEKLY, Color.rgb(0, 174, 255))
        val songArt = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val artistArt = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        val images = rows.flatMap { listOf("song:" + it.identity to songArt, "artist:" + it.identity to artistArt) }.toMap()
        val poster = ListeningStatsImage.render(context, request, "Maya", images)
        try {
            assertEquals(1080, poster.width); assertEquals(1920, poster.height)
            fun pixel(x: Int, y: Int) = poster.getPixel((x * 1.5).toInt(), (y * 1.5).toInt())
            assertEquals(Color.MAGENTA, pixel(100, 490))
            assertEquals(Color.YELLOW, pixel(445, 499))
            assertNotEquals("Artist portraits are circular, not square", Color.YELLOW, pixel(417, 471))
            assertEquals(Color.MAGENTA, pixel(430, 985))
            assertEquals(Color.YELLOW, pixel(90, 985))
            assertFalse(songArt.isRecycled); assertFalse(artistArt.isRecycled)
            File("/tmp/listening-stats-poster.png").outputStream().use { poster.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { poster.recycle(); songArt.recycle(); artistArt.recycle() }
    }

    @Test fun fullFeaturedNamesWrapWithoutEllipsisAndThePosterRetainsEveryLine() {
        val title = "Phir Aur Kya Chahiye (From Zara Hatke Zara Bachke) — Extended Original Recording"
        val artist = "Arijit Singh, Sachin-Jigar and Amitabh Bhattacharya"
        for (name in listOf(title, artist)) {
            val layout = ListeningStatsImage.wrappedFeatureName(name)
            assertTrue(layout.lineCount > 1)
            assertEquals(name.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
        }
        val row = song(1).copy(title = title, artist = artist)
        val request = ListeningStatsImageRequest(ListeningSummary(60_000, 2, listOf(row), listOf(row.copy(kind = "artist"))),
            ListeningPeriod.WEEKLY, Color.MAGENTA)
        val poster = ListeningStatsImage.render(context, request, "Maya")
        try {
            val expectedName = ListeningStatsImage.wrappedFeatureName(title)
            assertTrue("The complete featured name must fit above the image's bottom margin",
                poster.height >= ((1033 + expectedName.height + 48) * 1.5).toInt())
            File("/tmp/listening-stats-full-names-poster.png").outputStream().use { poster.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { poster.recycle() }
    }

    @Test fun sharedLogoRetainsTheSuppliedWavTapeColorsAndFitsThePosterBrand() {
        val lockup = context.getDrawable(R.drawable.wavtape_lockup)!!
        val px = ListeningStatsImage.brandLockupPixelBounds(lockup.intrinsicWidth, lockup.intrinsicHeight)
        val expected = Bitmap.createBitmap(px.width(), px.height(), Bitmap.Config.ARGB_8888)
        try {
            lockup.setBounds(0, 0, px.width(), px.height())
            lockup.draw(android.graphics.Canvas(expected))
            for (accent in listOf(Color.MAGENTA, Color.rgb(18, 24, 38), Color.WHITE)) {
                val request = ListeningStatsImageRequest(ListeningSummary(0, 0, emptyList(), emptyList()), ListeningPeriod.WEEKLY, accent)
                val poster = ListeningStatsImage.render(context, request, "Maya")
                try {
                    var compared = 0
                    var magentaTextPixels = 0
                    for (y in 1 until px.height() - 1) for (x in 1 until px.width() - 1) {
                        val color = expected.getPixel(x, y)
                        val actual = poster.getPixel(px.left + x, px.top + y)
                        if (accent == Color.MAGENTA && actual == Color.MAGENTA) magentaTextPixels++
                        if (Color.alpha(color) >= 252 && Color.alpha(expected.getPixel(x - 1, y)) >= 252 &&
                            Color.alpha(expected.getPixel(x + 1, y)) >= 252 &&
                            Color.alpha(expected.getPixel(x, y - 1)) >= 252 &&
                            Color.alpha(expected.getPixel(x, y + 1)) >= 252) {
                            assertTrue("The supplied lockup retains its original blue/cyan shading without accent tinting",
                                kotlin.math.abs(Color.red(color) - Color.red(actual)) <= 5 &&
                                    kotlin.math.abs(Color.green(color) - Color.green(actual)) <= 5 &&
                                    kotlin.math.abs(Color.blue(color) - Color.blue(actual)) <= 5)
                            compared++
                        }
                    }
                    assertTrue("The combined logo and wordmark remain crisp at export resolution", compared > 1200)
                    assertEquals("Old accent-tinted brand text is removed", 0, magentaTextPixels)
                    if (accent == Color.MAGENTA) {
                        File("/tmp/listening-stats-updated-brand.png").outputStream().use { poster.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } else if (accent == Color.WHITE) {
                        File("/tmp/listening-stats-light-accent-brand.png").outputStream().use { poster.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                } finally { poster.recycle() }
            }
        } finally { expected.recycle() }
    }

    @Test fun lightThemePosterMatchesStatsCardSurfaceAndAdaptsBrandContrast() {
        val rows = (1..5).map { song(it) }
        val summary = ListeningSummary(900_000, 10, rows, rows.map { it.copy(kind = "artist") }, 22, 16)
        val presets = listOf(
            "tideglass" to com.example.ui.theme.resolveAppThemeColors(false, com.example.data.local.ThemePresets.default(false).toCustomThemeState()),
            "petal" to com.example.ui.theme.resolveAppThemeColors(false, com.example.data.local.ThemePresets.CherryBlossom.toCustomThemeState()),
            "night-ink" to com.example.ui.theme.resolveAppThemeColors(false, com.example.data.local.ThemePresets.MinimalIvory.toCustomThemeState())
        )
        for ((name, colors) in presets) {
            val saved = if (colors.neutralSurfaces) colors.cardBackgroundElevated else colors.cardBackground
            val cardBase = statsCardBase(colors.primaryAccent, saved).toArgb()
            val request = ListeningStatsImageRequest(summary, ListeningPeriod.WEEKLY, colors.primaryAccent.toArgb(), cardBase)
            val poster = ListeningStatsImage.render(context, request, "Maya")
            try {
                // Sample clean background region in the middle-right between subtitle and rankings
                val bgSample = poster.getPixel(480, 500)
                val bgLuminance = androidx.core.graphics.ColorUtils.calculateLuminance(bgSample)
                assertTrue("$name light poster must stay luminous like the 4 stats boxes (lum=$bgLuminance)", bgLuminance >= 0.55)
                File("/tmp/listening-stats-light-poster-$name.png").outputStream().use {
                    poster.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { poster.recycle() }
        }
    }

    @Test fun creationProducesRealPngShareGrantsReadAccessAndSaveCopiesTheSameImage() = runBlocking {
        val request = ListeningStatsImageRequest(ListeningSummary(0, 0, emptyList(), emptyList()), ListeningPeriod.LIFETIME, Color.CYAN)
        val file = ListeningStatsImage.create(context, request, "Maya")
        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            assertEquals(1080, bitmap.width); assertEquals(1920, bitmap.height); bitmap.recycle()
            val share = ListeningStatsImage.shareIntent(context, file)
            assertEquals(Intent.ACTION_SEND, share.action); assertEquals("image/png", share.type)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme); assertEquals(context.packageName + ".provider", uri.authority)
            assertEquals(uri, share.clipData!!.getItemAt(0).uri)
            val output = ByteArrayOutputStream()
            val destination = Uri.parse("content://test/stats-image.png")
            org.robolectric.Shadows.shadowOf(context.contentResolver).registerOutputStream(destination, output)
            ListeningStatsImage.save(context, file, destination)
            assertArrayEquals(file.readBytes(), output.toByteArray())
        } finally { file.delete() }
    }

    @Test fun generationLoadsRealArtworkOffMainAndReusesTheAppsImageCache() {
        val previous = Coil.imageLoader(context)
        val fetches = AtomicInteger()
        val fetchedOnMain = AtomicBoolean()
        val art = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val loader = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<Any> {
                override fun create(data: Any, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
                    fetches.incrementAndGet()
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) fetchedOnMain.set(true)
                    DrawableResult(BitmapDrawable(context.resources, art), false, DataSource.NETWORK)
                }
            })
        }.build()
        Coil.setImageLoader(loader)
        val row = song(1).copy(coverUrl = "https://art.test/the-actual-cover.png")
        val request = ListeningStatsImageRequest(ListeningSummary(60_000, 2, listOf(row), emptyList()),
            ListeningPeriod.WEEKLY, Color.CYAN)
        fun generate(): File {
            // Keep the simulated Android looper available for Coil's lifecycle callbacks.
            val pending = java.util.concurrent.CompletableFuture.supplyAsync {
                runBlocking { ListeningStatsImage.create(context, request, "Maya") }
            }
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!pending.isDone && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue("The explicit export must finish within its bounded artwork budget", pending.isDone)
            return pending.get()
        }
        var first: File? = null; var second: File? = null
        try {
            first = generate()
            val decoded = BitmapFactory.decodeFile(first.absolutePath)
            assertEquals(Color.MAGENTA, decoded.getPixel(150, 735))
            decoded.recycle()
            assertEquals(1, fetches.get())
            second = generate()
            assertEquals("Repeated exports must reuse the existing image cache", 1, fetches.get())
            assertFalse(fetchedOnMain.get())
            assertFalse("The shared cached cover must stay valid", art.isRecycled)
        } finally {
            first?.delete(); second?.delete()
            Coil.setImageLoader(previous); loader.shutdown(); art.recycle()
        }
    }
}
