package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WavTapeBrandAssetsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun render(id: Int): Bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also { bitmap ->
        requireNotNull(context.getDrawable(id)).apply { setBounds(0, 0, 512, 512); draw(Canvas(bitmap)) }
    }

    @Test @Config(sdk = [24])
    fun androidSevenResolvesTheLegacyIconsAndHighQualityLogoResources() {
        // Robolectric's native Canvas renderer starts at API 26. Verify API 24
        // resource resolution here; the pixel/mask checks below use native graphics.
        assertEquals("WavTape", context.getString(R.string.app_name))
        for (id in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round,
            R.drawable.wavtape_logo, R.drawable.wavtape_wordmark, R.drawable.ic_launcher_foreground)) {
            val drawable = requireNotNull(context.getDrawable(id))
            assertTrue(drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
        }
    }

    @Test fun suppliedBrandHasTransparentSurroundAndReelHolesWithOriginalBlueCyanColors() {
        assertEquals("WavTape", context.getString(R.string.app_name))
        for (asset in listOf(R.drawable.wavtape_logo, R.drawable.wavtape_logo_small)) {
            val bitmap = render(asset)
            try {
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                assertEquals(0, Color.alpha(bitmap.getPixel(511, 511)))
                assertTrue("Left reel hole stays transparent", Color.alpha(bitmap.getPixel(104, 220)) < 5)
                assertTrue("Right reel hole stays transparent", Color.alpha(bitmap.getPixel(407, 220)) < 5)
                var blue = 0; var cyan = 0
                for (y in 0 until 512 step 4) for (x in 0 until 512 step 4) {
                    val c = bitmap.getPixel(x, y)
                    if (Color.alpha(c) > 200) {
                        if (Color.blue(c) > Color.red(c) + 50 && Color.blue(c) > Color.green(c) + 30) blue++
                        if (Color.blue(c) > Color.red(c) + 50 && Color.green(c) > Color.red(c) + 50) cyan++
                    }
                }
                assertTrue(blue > 100 && cyan > 100)
            } finally { bitmap.recycle() }
        }
        val wordmark = Bitmap.createBitmap(616, 135, Bitmap.Config.ARGB_8888).also { bitmap ->
            requireNotNull(context.getDrawable(R.drawable.wavtape_wordmark)).apply {
                setBounds(0, 0, 616, 135); draw(Canvas(bitmap))
            }
        }
        try {
            assertEquals("Wordmark surround stays transparent", 0, Color.alpha(wordmark.getPixel(0, 0)))
            assertEquals("Wordmark bottom corner stays transparent", 0, Color.alpha(wordmark.getPixel(615, 134)))
            var blue = 0; var cyan = 0
            for (y in 0 until 135 step 2) for (x in 0 until 616 step 2) {
                val c = wordmark.getPixel(x, y)
                if (Color.alpha(c) > 200) {
                    if (Color.blue(c) > Color.red(c) + 50 && Color.blue(c) > Color.green(c) + 30) blue++
                    if (Color.blue(c) > Color.red(c) + 50 && Color.green(c) > Color.red(c) + 50) cyan++
                }
            }
            assertTrue("Wordmark preserves cyan/blue gradient", blue > 100 && cyan > 100)
        } finally { wordmark.recycle() }
    }

    @Test fun legacyAndAdaptiveLaunchersUseTheNewMarkAndSafeTransparentForeground() {
        val icon = requireNotNull(context.getDrawable(R.mipmap.ic_launcher))
        if (Build.VERSION.SDK_INT >= 26) assertTrue(icon is AdaptiveIconDrawable)
        val foreground = render(R.drawable.ic_launcher_foreground)
        val round = render(R.mipmap.ic_launcher_round)
        try {
            assertEquals("Adaptive foreground must not contain a background tile", 0, Color.alpha(foreground.getPixel(0, 0)))
            val bounds = (0 until 512).flatMap { y -> (0 until 512).mapNotNull { x ->
                if (Color.alpha(foreground.getPixel(x, y)) > 128) x to y else null
            } }
            assertTrue(bounds.isNotEmpty())
            assertTrue("The wide W remains inside Android's safe mask area", bounds.minOf { it.first } > 80 && bounds.maxOf { it.first } < 432)
            if (Build.VERSION.SDK_INT < 26) assertEquals("Legacy round icon is actually round", 0, Color.alpha(round.getPixel(0, 0)))
        } finally { foreground.recycle(); round.recycle() }
    }
}
