package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.Options
import com.example.ui.theme.rememberTonalArtworkBitmap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TonalArtworkLoaderUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun artworkLoadsOnlyWhenEnabledSharesTheCacheAndFallsBackCleanlyOnFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previous = Coil.imageLoader(context)
        val fetches = AtomicInteger()
        val source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        val loader = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<Any> {
                override fun create(data: Any, options: Options, imageLoader: ImageLoader) = Fetcher {
                    fetches.incrementAndGet()
                    if (data.toString().contains("missing")) throw java.io.IOException("Cover unavailable")
                    DrawableResult(BitmapDrawable(context.resources, source), false, DataSource.MEMORY_CACHE)
                }
            })
        }.build()
        Coil.setImageLoader(loader)
        var enabled by mutableStateOf(false)
        var url by mutableStateOf<String?>("https://example.test/cover.jpg")
        var latest: Bitmap? = null
        try {
            compose.setContent {
                val image = rememberTonalArtworkBitmap(url, enabled)
                SideEffect { latest = image }
            }
            compose.waitForIdle()
            assertEquals(0, fetches.get()); assertNull(latest)
            compose.runOnIdle { enabled = true }
            compose.waitUntil(5_000) { compose.waitForIdle(); latest != null }
            assertEquals(1, fetches.get()); assertFalse(latest!!.isRecycled)
            compose.runOnIdle { enabled = false }
            compose.waitForIdle(); assertNull(latest); assertFalse(source.isRecycled)
            compose.runOnIdle { enabled = true }
            compose.waitUntil(5_000) { compose.waitForIdle(); latest != null }
            assertEquals("Reopening the editor shares the cached small software cover", 1, fetches.get())
            compose.runOnIdle { url = "https://example.test/missing.jpg" }
            compose.waitUntil(5_000) { compose.waitForIdle(); fetches.get() == 2 && latest == null }
            compose.runOnIdle { url = null }
            compose.waitForIdle(); assertNull(latest)
        } finally { Coil.setImageLoader(previous); loader.shutdown() }
    }
}
