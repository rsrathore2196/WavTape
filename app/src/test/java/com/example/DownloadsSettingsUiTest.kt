package com.example

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.model.MusicTrack
import com.example.downloads.*
import com.example.ui.screens.settings.DownloadsPage
import com.example.ui.theme.*
import com.github.takahirom.roborazzi.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadsSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val gib = 1_073_741_824L
    private lateinit var store: OfflineDownloads
    private val track = MusicTrack("storage", "Saved song", "Artist", "Album", 120_000, "", "")
    private val plan = OfflinePlan("https://audio.example/song", null, "fixture", true)

    @Before fun prepare() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        store = OfflineDownloads(context)
        runBlocking { store.load() }
        storage(16)
    }

    private fun storage(availableGiB: Int) {
        // Android's StatFs test shadow uses 4096-byte blocks. The app must use
        // available blocks, which can differ from all free blocks on the volume.
        ShadowStatFs.registerStats(context.noBackupFilesDir.absolutePath, 16_777_216, 8_388_608, availableGiB * 262_144)
    }

    @Test fun storageSitsBelowQualityAndItsBarAndLabelsAdaptToThemes() {
        runBlocking { store.put(DownloadRecord(track, DownloadStatus.COMPLETE, bytes = 16 * gib, plan = plan)) }
        var colors by mutableStateOf(DarkAppColors)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAppColors provides colors) {
            Box(Modifier.fillMaxSize().background(colors.scaffoldBackground)) { DownloadsPage(store = store) }
        } } }
        for ((name, theme) in listOf("dark" to DarkAppColors, "light" to LightAppColors,
            "custom" to DarkAppColors.copy(primaryAccent = Color(0xFF63D5C4)))) {
            compose.runOnIdle { colors = theme }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("64.0 GB total").fetchSemanticsNodes().isNotEmpty() }
            val summary = compose.onNodeWithTag("download_storage_summary").fetchSemanticsNode().boundsInRoot
            val quality = compose.onNodeWithText("Download quality").fetchSemanticsNode().boundsInRoot
            assertTrue("Storage is directly below quality", summary.top > quality.bottom)
            compose.onAllNodesWithText("16.0 GB", useUnmergedTree = true).assertCountEquals(2) // downloads and available
            compose.onNodeWithText("32.0 GB", useUnmergedTree = true).assertExists() // other used
            compose.onNodeWithText("Available").assertExists()
            compose.onNodeWithText("1 track saved on this device").assertDoesNotExist()
            val counts = compose.onNodeWithTag("downloaded_songs_summary").fetchSemanticsNode().boundsInRoot
            assertTrue("Song counts follow device storage", counts.top > summary.bottom)
            compose.onNodeWithTag("downloaded_songs_total").assertTextEquals("1")
            compose.onNodeWithTag("downloaded_songs_high").assert(hasAnyDescendant(hasText("1")))
            val tiles = listOf("lossless", "high", "standard").map { tier ->
                compose.onNodeWithTag("downloaded_songs_$tier").fetchSemanticsNode().boundsInRoot
            }
            tiles.drop(1).forEach { tile ->
                assertEquals(tiles.first().height, tile.height, 0f)
                assertEquals(tiles.first().width, tile.width, 1f) // Whole-pixel rounding in an equal-weight row.
            }
            compose.onNodeWithText("Find and play your saved tracks in Library → Downloads.").assertDoesNotExist()
            val bar = compose.onNodeWithTag("download_storage_bar")
            assertEquals(.75f, bar.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .0001f)
            val file = File("build/reports/download-settings-review/$name-bar.png").apply { parentFile?.mkdirs() }
            bar.captureRoboImage(file.absolutePath, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            val bitmap = ImageIO.read(file)
            assertEquals("Offline audio uses the theme accent", theme.primaryAccent.toArgb(), bitmap.getRGB(bitmap.width / 8, bitmap.height / 2))
            compose.onNodeWithTag("downloads_page").captureRoboImage("build/reports/download-settings-review/$name.png",
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        }
    }

    @Test fun storageRefreshesWithoutScrollingWhenDownloadsChangeOrTheAppResumes() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides LightAppColors, LocalLifecycleOwner provides owner) {
                DownloadsPage(store = store)
            }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("64.0 GB total").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("0 tracks saved on this device").assertDoesNotExist()
        compose.onNodeWithTag("downloaded_songs_total").assertTextEquals("0")
        storage(24)
        runBlocking { store.put(DownloadRecord(track, DownloadStatus.PAUSED, bytes = 2 * gib)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("24.0 GB").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("2.0 GB").assertExists()
        compose.onNodeWithText("Includes in-progress audio").assertExists()
        compose.onNodeWithTag("downloaded_songs_total").assertTextEquals("0")
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        storage(8)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("8.0 GB").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("54.0 GB").assertExists()
    }

    @Test fun countsUseEachSavedFileQualityAndUpdateAsDownloadsChange() {
        val standard = DownloadRecord(track.copy(bitrateKbps = 128), DownloadStatus.COMPLETE, bytes = 100, plan = plan)
        val lossless = standard.copy(track = standard.track.copy(id = "lossless", isLossless = true))
        val high = standard.copy(track = standard.track.copy(id = "high", bitrateKbps = 256))
        runBlocking {
            store.put(standard)
            store.put(lossless)
            store.put(high)
            store.put(standard.copy(track = track.copy(id = "partial"), status = DownloadStatus.PAUSED))
            store.put(standard.copy(track = track.copy(id = "missing"), plan = null))
        }
        compose.setContent { MaterialTheme { DownloadsPage(store = store) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("downloaded_songs_total") and hasText("3")).fetchSemanticsNodes().isNotEmpty() }
        for (tier in listOf("lossless", "high", "standard")) {
            compose.onNodeWithTag("downloaded_songs_$tier").assert(hasAnyDescendant(hasText("1")))
        }
        runBlocking { store.remove(lossless.track.id) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("downloaded_songs_total") and hasText("2")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("downloaded_songs_lossless").assert(hasAnyDescendant(hasText("0")))
    }
}
