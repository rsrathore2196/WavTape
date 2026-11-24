package com.example

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.example.ui.util.ExtractedTrackColors
import com.example.ui.util.TrackPaletteCache
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ArtworkPaletteCacheTest {
    @Test fun startupRestoresObserversAndRetainsPalettesAcrossAnEarlyWrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.filesDir, "track_palettes_v2.json")
        val restored = Color(0xff2350c0)
        val fresh = Color(0xffd98f28)
        file.writeText(JSONObject()
            .put("previous-cover", JSONArray(listOf(restored.toArgb(), restored.toArgb(), restored.toArgb())))
            .put("new-cover", JSONArray(listOf(restored.toArgb(), restored.toArgb(), restored.toArgb())))
            .toString())
        val palette = async(start = CoroutineStart.UNDISPATCHED) {
            TrackPaletteCache.observeArtwork("previous-cover").filterNotNull().first()
        }
        TrackPaletteCache.put("new-cover", ExtractedTrackColors(fresh, fresh))
        TrackPaletteCache.initialize(context)
        assertEquals(restored, withTimeout(5_000) { palette.await() }.dominantColor)
        withTimeout(5_000) {
            while (runCatching {
                    JSONObject(file.readText()).getJSONArray("new-cover").getInt(0) != fresh.toArgb()
                }.getOrDefault(true)) delay(10)
        }
        assertEquals(restored.toArgb(), JSONObject(file.readText()).getJSONArray("previous-cover").getInt(0))
    }
}
