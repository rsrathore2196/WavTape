package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.request.SuccessResult
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.repository.ArtistPhotoRepository
import com.example.data.repository.CatalogArtist
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.components.ArtistPortrait
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.util.LocalArtworkRequestsActive
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtistPortraitLifecycleUiTest {
    @get:Rule val compose = createComposeRule()
    @Before fun prepare() {
        ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("artist_images_v2", 0).edit().clear().commit()
        (ArtistPhotoRepository::class.java.getDeclaredField("memory").apply { isAccessible = true }.get(null) as LruCache<*, *>).evictAll()
        BackgroundWorkCoordinator.resetForTesting()
        BackgroundWorkCoordinator.foreground(true); BackgroundWorkCoordinator.firstFrameRendered(); BackgroundWorkCoordinator.scrolling(false)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(16))
    }
    @After fun reset() { ArtistPhotoRepository.identityRequest = YouTubeMusicApiService::artistIdentity; BackgroundWorkCoordinator.foreground(false) }

    @Test fun suppliedPortraitLoadsWhenArtistDetailsArriveForTheSameIdentity() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val artist = mutableStateOf(CatalogArtist("UCdetail", "Artist Detail"))
        val supplied = "https://covers.example/artist.jpg"
        val loaded = AtomicReference<String>()
        val previous = Coil.imageLoader(context)
        val loader = ImageLoader.Builder(context).components {
            add(coil.intercept.Interceptor { chain ->
                val url = chain.request.data.toString()
                if (url != supplied) return@Interceptor chain.proceed(chain.request)
                loaded.set(url)
                val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
                SuccessResult(BitmapDrawable(context.resources, bitmap), chain.request, DataSource.MEMORY_CACHE)
            })
        }.networkObserverEnabled(false).build()
        Coil.setImageLoader(loader)
        try {
            compose.setContent { ArtistPortrait(artist.value, Modifier.size(120.dp), enrichmentAllowed = false) }
            compose.runOnIdle { artist.value = artist.value.copy(image = supplied) }
            compose.waitUntil(5_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                loaded.get() == supplied
            }
        } finally {
            Coil.setImageLoader(previous)
            loader.shutdown()
        }
    }

    @Test fun interactionDoesNotRestartAVisibleLookupButLeavingTheViewportCancelsIt() {
        val calls = AtomicInteger(); val cancellations = AtomicInteger()
        val allowed = mutableStateOf(false); val active = mutableStateOf(true)
        ArtistPhotoRepository.identityRequest = { _, _ ->
            calls.incrementAndGet()
            try { awaitCancellation() } finally { cancellations.incrementAndGet() }
        }
        compose.setContent {
            CompositionLocalProvider(LocalArtworkRequestsActive provides active.value, LocalVisualEffectsActive provides false) {
                ArtistPortrait(CatalogArtist("UCvisible", "Visible Artist"), Modifier.size(124.8.dp),
                    deferEnrichment = true, enrichmentAllowed = allowed.value)
            }
        }
        compose.runOnIdle { assertEquals(0, calls.get()); allowed.value = true }
        compose.waitForIdle()
        compose.waitUntil(3_000) { calls.get() == 1 }
        compose.runOnIdle { allowed.value = false }
        compose.waitForIdle()
        compose.runOnIdle { allowed.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, calls.get()); assertEquals(0, cancellations.get()); active.value = false }
        compose.waitForIdle()
        compose.waitUntil(3_000) { cancellations.get() == 1 }
    }
}
