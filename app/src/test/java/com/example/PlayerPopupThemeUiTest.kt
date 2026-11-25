package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LyricsOffsetManager
import com.example.data.model.MusicTrack
import com.example.playback.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.util.TrackPaletteCache
import com.example.ui.util.extractPlayerArtworkColors
import com.example.ui.util.playerAppearanceColors
import com.example.ui.util.playerPreviewPaletteKey
import com.example.ui.util.samplePlayerArtwork
import com.example.ui.viewmodel.PlaylistImportUiState
import com.github.takahirom.roborazzi.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.example.downloads.DownloadRecord
import com.example.downloads.DownloadStatus
import com.example.downloads.OfflineDownloads
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

@OptIn(ExperimentalRoborazziApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class, qualifiers = "w411dp-h891dp-port",
    shadows = [PlayerWindowCopyShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerPopupThemeUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val track = MusicTrack("popup-track", "Test song", "Artist", "Album", 180_000, "", "")
    private var captureSuffix = ""

    @Before fun prepare() {
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT,
            "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        context.getSharedPreferences("lyrics_offset_preferences", 0).edit().clear().commit()
        LyricsOffsetManager.resetOffset(context, track.id, applyToAll = true)
    }

    @After fun cleanup() {
        AudioSignalPath.reset()
        LyricsOffsetManager.resetOffset(context, track.id, applyToAll = true)
        val instance = NetworkQualityMonitor::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (instance.get(null) as? NetworkQualityMonitor)?.let {
            (NetworkQualityMonitor::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(it) as CoroutineScope).cancel()
        }
        instance.set(null, null)
    }

    private fun settle() {
        repeat(3) {
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
    }

    private fun capture(tag: String, useUnmergedTree: Boolean = false): java.awt.image.BufferedImage {
        val file = File("build/reports/player-popup-review/$tag$captureSuffix.png").apply { parentFile?.mkdirs() }
        val node = compose.onNodeWithTag(tag, useUnmergedTree = useUnmergedTree).fetchSemanticsNode()
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            // Draw the node's actual window. The generic multi-window capture can
            // select the Activity behind a Popup or use incorrect bottom-sheet offsets.
            val view = (node.root as ViewRootForTest).view
            val rootView = view.rootView
            val full = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
            rootView.draw(Canvas(full))
            val bounds = node.boundsInRoot
            val location = IntArray(2).also(view::getLocationInWindow)
            val origin = IntArray(2).also(rootView::getLocationInWindow)
            bitmap = Bitmap.createBitmap(full,
                bounds.left.roundToInt() + location[0] - origin[0],
                bounds.top.roundToInt() + location[1] - origin[1],
                bounds.width.roundToInt(), bounds.height.roundToInt())
        }
        bitmap.captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        return ImageIO.read(file)
    }

    private fun assertBackground(tag: String, background: Color) {
        val image = capture(tag)
        val matching = (0 until image.height).sumOf { y ->
            (0 until image.width).count { x -> image.getRGB(x, y) == background.toArgb() }
        }
        val opaque = (0 until image.height).sumOf { y ->
            (0 until image.width).count { x -> image.getRGB(x, y).ushr(24) == 255 }
        }
        // A sheet's semantics bounds can include its transparent offscreen area during native capture.
        assertTrue("$tag must have an untinted $background surface", matching > opaque * .25)
        val visibleRows = (0 until image.height).filter { y -> image.getRGB(image.width / 2, y).ushr(24) == 255 }
        assertTrue("$tag must render an opaque surface", visibleRows.isNotEmpty())
        val middle = (visibleRows.first() + visibleRows.last()) / 2
        // The straight side of the rounded surface must have no contrasting outline.
        assertEquals("$tag outer border", background.toArgb(), image.getRGB(1, middle))
    }

    @Test fun ordinaryPopupsFollowLightModeWhilePlayerGlassStaysDark() = checkPopups(LightAppColors)
    @Test fun playerPopupsUseReadableControlsInDarkMode() = checkPopups(DarkAppColors)
    @Test fun playerGlassUsesUntintedCoverInDarkMode() = checkPopups(DarkAppColors, withGlass = true)
    @Test fun playerGlassUsesUntintedCoverInLightMode() = checkPopups(LightAppColors, withGlass = true)

    private val coverColor = Color(0xFFF2BD73)
    private val coverPixels = IntArray(32 * 32) { coverColor.toArgb() }
    private val artwork = checkNotNull(extractPlayerArtworkColors(coverPixels, 32, 32))

    @Test fun translucentWhiteControlCastsAShadowWithoutDarkeningItsFill() {
        val whiteArtwork = samplePlayerArtwork(IntArray(32 * 32) { Color.White.toArgb() }, 32, 32)
        var ready = false
        compose.setContent {
            val prepared = rememberPlayerPopupArtwork(whiteArtwork, playerVisible = true)
            SideEffect { ready = prepared != null }
            CompositionLocalProvider(LocalDynamicGlassEnabled provides true, LocalPlayerPopupArtwork provides prepared) {
                if (prepared != null) Box(
                    Modifier.size(80.dp).playerPopupGlass(RoundedCornerShape(12.dp)).testTag("white_artwork"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(16.dp).playerPopupControlContrast(translucent = true)
                        .background(Color.White.copy(alpha = .4f), CircleShape))
                }
            }
        }
        compose.waitUntil(5_000) { ready }
        val image = capture("white_artwork")
        val centerX = image.width / 2
        val centerY = image.height / 2
        val belowControl = centerY + (image.height / 10f).roundToInt()
        assertEquals(Color.White.toArgb(), image.getRGB(centerX, centerY))
        assertEquals(Color.White.toArgb(), image.getRGB(centerX, centerY / 2))
        assertEquals(Color.White.toArgb(), image.getRGB(centerX, centerY * 3 / 2))
        assertTrue("The white control must remain visible on white",
            Color(image.getRGB(centerX, belowControl)).luminance() < .85f)
    }

    private fun assertArtworkGlass(tag: String, expected: Color = coverColor): java.awt.image.BufferedImage {
        val image = capture(tag)
        var cover = 0
        for (y in 2 until image.height - 2) for (x in 2 until image.width - 2) {
            val pixel = Color(image.getRGB(x, y))
            if (pixel.alpha > .9f && kotlin.math.abs(pixel.red - expected.red) < .055f &&
                kotlin.math.abs(pixel.green - expected.green) < .055f &&
                kotlin.math.abs(pixel.blue - expected.blue) < .055f) cover++
        }
        assertTrue("$tag should retain the original album-art tone ($cover pixels)",
            cover > image.width * image.height / 4)
        return image
    }

    private fun assertWhitePopupText(tag: String, text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val style = layouts.single().layoutInput.style
        assertEquals("$tag uses white text", Color.White, style.color)
        assertTrue("$tag text has no shadow even over bright artwork",
            style.shadow == null || style.shadow == androidx.compose.ui.graphics.Shadow.None)
    }

    private fun assertContinuousLyricsGlass(tag: String, image: java.awt.image.BufferedImage) {
        // The backdrop should stay continuous
        // across the card's content padding, even when text and controls cover some rows.
        val edge = (image.width * .035f).roundToInt()
        val inset = (image.width * .08f).roundToInt()
        val rows = image.height / 7 until image.height * 6 / 7
        for ((outerX, innerX) in listOf(edge to inset, (image.width - 1 - edge) to (image.width - 1 - inset))) {
            val matching = rows.count { y ->
                val outer = Color(image.getRGB(outerX, y))
                val inner = Color(image.getRGB(innerX, y))
                maxOf(kotlin.math.abs(outer.red - inner.red), kotlin.math.abs(outer.green - inner.green),
                    kotlin.math.abs(outer.blue - inner.blue)) <= .035f
            }
            assertTrue("$tag should show continuous glass across the content padding", matching > rows.count() * .6f)
        }
    }

    private fun assertGlassInset(tag: String) {
        val image = assertArtworkGlass(tag)
        val center = Color(image.getRGB(image.width / 2, image.height * 3 / 4))
        assertTrue("$tag should have a glass fill rather than opaque black",
            center.alpha < .99f || center.luminance() > .012f)
    }

    private fun assertWindowDoesNotDim(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        compose.runOnIdle {
            val window = ((node.root as ViewRootForTest).view.parent as DialogWindowProvider).window
            assertEquals("$tag window dim flag", 0,
                window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            assertEquals("$tag window dim amount", 0f, window.attributes.dimAmount, 0f)
        }
    }

    private fun checkPopups(source: AppThemeColors, withGlass: Boolean = false) {
        captureSuffix = (if (source.isDark) "-dark" else "-light") + if (withGlass) "-glass" else ""
        var popup by mutableIntStateOf(-1)
        var size by mutableIntStateOf(32)
        var enabled by mutableStateOf(true)
        var selectedProvider by mutableStateOf("Auto")
        var dismissed = 0
        // Extreme saved accents previously caused white-on-white switches and selected text.
        val original = source.copy(primaryAccent = if (source.isDark) Color.White else Color.Yellow)
        var glassReady = false
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            val preparedArtwork = rememberPlayerPopupArtwork(artwork.popupSample.takeIf { withGlass }, playerVisible = true)
            SideEffect { glassReady = preparedArtwork != null }
            CompositionLocalProvider(LocalAppColors provides playerAppearanceColors(original),
                LocalPlayerPopupColors provides original, LocalArtworkRequestsActive provides false,
                LocalDynamicGlassEnabled provides withGlass,
                LocalPlayerPopupArtwork provides preparedArtwork,
                LocalVisualEffectsActive provides false) {
                Box(Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(listOf(Color(0xFFFFDD77), Color(0xFF316FB1)))
                )) {
                    when (popup) {
                        0 -> SoundOutputDeviceDialog(
                            listOf(SoundOutputDevice(1, "This phone", "Built-in", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, true)),
                            onSelectDevice = {}, onDismiss = { dismissed++ }
                        )
                        1 -> QueueBottomSheet(listOf(track) + (1..4).map {
                            track.copy(id = "queue-$it", title = "Queued track $it")
                        }, track, {}, { _, _ -> }, { dismissed++ }, isAutoplayEnabled = true)
                        2 -> SleepTimerDialog { dismissed++ }
                        3 -> EqualizerDialog(AudioEffectsState(isEnabled = enabled,
                            availablePresets = listOf("Flat", "Rock & Metal")), { enabled = it }, {}, { _, _ -> },
                            {}, {}, {}, { dismissed++ })
                        4 -> LyricsOffsetBottomSheet(track, { dismissed++ })
                        5 -> AddToPlaylistDialog(track, emptyList(), onPlaylistSelected = {},
                            onCreateNewPlaylist = {}, onDismiss = { dismissed++ })
                        6 -> AudioQualityBadge(track.id, "", Color.White, Modifier.testTag("fixture_pipeline_open"))
                        7 -> LyricsTextSizePopup(size, { size = it }, { dismissed++ })
                        8 -> LyricsProviderSelectionPopup(selectedProvider, { selectedProvider = it },
                            { dismissed++ }, allowImport = true)
                    }
                }
            }
        } }
        if (withGlass) {
            compose.mainClock.autoAdvance = true
            compose.waitUntil(5_000) { glassReady }
            compose.mainClock.autoAdvance = false
        }
        val tags = listOf("sound_output_devices_dialog", "queue_bottom_sheet", "sleep_timer_popup",
            "equalizer_dialog", "lyrics_offset_sheet", "add_to_playlist_popup", "signal_path_popup",
            "lyrics_text_size_popup", "lyrics_provider_popup")
        val background = if (source.isDark) Color.Black else Color.White
        tags.forEachIndexed { index, tag ->
            compose.runOnIdle { popup = index }
            if (index == 0) awaitPopupLayout(tag)
            settle()
            if (index == 6) {
                compose.onNodeWithTag("fixture_pipeline_open").performClick()
                settle()
            }
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val playerGlass = index in listOf(0, 1, 6, 7, 8)
            if (withGlass && playerGlass) {
                assertWindowDoesNotDim(tag)
                val image = assertArtworkGlass(tag)
                if (index == 7 || index == 8) assertContinuousLyricsGlass(tag, image)
                val title = when (index) {
                    0 -> "Audio output"
                    1 -> "Now Playing & Queue"
                    6 -> "Audio Pipeline"
                    7 -> "Lyrics Text Size"
                    else -> "Lyrics Provider"
                }
                assertWhitePopupText(tag, title)
            } else assertBackground(tag, if (playerGlass) Color.Black else background)
            if (withGlass && index == 0) {
                assertGlassInset("device_row_1")
                assertGlassInset("device_output_volume_panel")
                val thumb = capture("device_output_volume_thumb", useUnmergedTree = true)
                assertEquals("The volume thumb stays white on bright artwork", Color.White.toArgb(),
                    thumb.getRGB(thumb.width / 2, thumb.height / 2))
            }
            if (withGlass && index == 6) {
                compose.onNodeWithTag("pipeline_stage_icon_0").assertWidthIsEqualTo(28.dp).assertHeightIsEqualTo(28.dp)
                assertGlassInset("pipeline_stage_icon_0")
            }
            if (index == 1) {
                compose.onNodeWithText("RECOMMENDED NEXT").assertDoesNotExist()
                compose.onNodeWithText("INFINITE AUTOPLAY").assertDoesNotExist()
                compose.onNodeWithText("PLAYING").assertIsDisplayed()
                if (withGlass) {
                    assertGlassInset("queue_track_2")
                    assertGlassInset("queue_track_3")
                }
                val surfaceTop = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
                val titleTop = compose.onNodeWithText("Now Playing & Queue").fetchSemanticsNode().boundsInRoot.top
                assertTrue("The sheet glass starts just above its header", titleTop - surfaceTop in 0f..75f)
            }
            when (index) {
                3 -> {
                    compose.onNodeWithTag("equalizer_master_switch").assertIsOn().performClick()
                    settle()
                    compose.onNodeWithTag("equalizer_master_switch").assertIsOff()
                }
                4 -> {
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNodeWithTag("lyrics_offset_value_text")
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertEquals(playerPopupColors(original).textPrimary, layouts.single().layoutInput.style.color)
                    compose.onNodeWithTag("lyrics_offset_apply_all").assertIsOn().performClick()
                    settle()
                    compose.onNodeWithTag("lyrics_offset_apply_all").assertIsOff()
                    assertFalse(LyricsOffsetManager.usesGlobalOffset(context, track.id))
                }
                7 -> {
                    compose.onNodeWithTag("lyrics_size_slider")
                        .performSemanticsAction(SemanticsActions.SetProgress) { it(37f) }
                    settle()
                    assertEquals(37, size)
                    compose.onNodeWithText("37 sp").assertIsDisplayed()
                }
                8 -> {
                    compose.onNodeWithText("Import Lyrics").assertExists()
                    compose.onNodeWithText("Auto", useUnmergedTree = true).performClick()
                    assertEquals("Auto", selectedProvider)
                    assertEquals(1, dismissed)
                }
            }
        }
    }

    @Test fun actualPlayerPreparesAlbumArtworkBeforeOpeningTheMenu() {
        val coveredTrack = track.copy(coverUrl = "https://lh3.googleusercontent.com/popup-cover")
        TrackPaletteCache.put(playerPreviewPaletteKey(coveredTrack), artwork)
        try {
            captureSuffix = "-actual-glass"
            var prepared = false
            var windowBackdrop: PlayerPopupBackdrop? = null
            compose.setContent { MaterialTheme {
                CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicAlbumGlow = true),
                    LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false) {
                    ExpandedPlayerScreen(PlayerUiState(currentTrack = coveredTrack), {}, {}, {}, {}, {}, {}, {}, {}, {},
                        onAddToPlaylist = {}, queueContent = {
                            val ready = LocalPlayerPopupArtwork.current != null
                            val backdrop = LocalPlayerPopupBackdrop.current
                            SideEffect { prepared = ready; windowBackdrop = backdrop }
                        })
                }
            } }
            settle()
            compose.waitUntil(5_000) { prepared }
            settle()
            awaitBackdrop { windowBackdrop?.hasPreparedFrame == true }
            compose.onNodeWithTag("player_options_button").performClick()
            settle()
            compose.waitUntil(5_000) { windowBackdrop?.frame != null }
            assertArtworkGlass("player_options_popup", Color(PlayerWindowCopyShadow.PLAYER_FRAME_COLOR))
            assertWhitePopupText("player_options_popup", "Sleep Mode")
        } finally {
            TrackPaletteCache.clear()
        }
    }

    @Test fun artworkThatFinishesDuringOpeningAppearsOnTheNextPresentation() {
        var open by mutableStateOf(true)
        var prepared by mutableStateOf<PlayerPopupArtwork?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalDynamicGlassEnabled provides true, LocalPlayerPopupArtwork provides prepared) {
                if (open) Box(Modifier.size(80.dp).background(playerPopupSurfaceColor())
                    .playerPopupGlass(RectangleShape).testTag("popup_artwork_first_frame"))
            }
        }
        fun centerPixel(): Int {
            val image = capture("popup_artwork_first_frame")
            return image.getRGB(image.width / 2, image.height / 2)
        }
        assertEquals(Color.Black.toArgb(), centerPixel())
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(coverColor.toArgb()) }
            prepared = PlayerPopupArtwork(bitmap.asImageBitmap(), needsForegroundContrast = true)
        }
        assertEquals(Color.Black.toArgb(), centerPixel())
        compose.runOnIdle { open = false }
        compose.onNodeWithTag("popup_artwork_first_frame").assertDoesNotExist()
        compose.runOnIdle { open = true }
        assertEquals(coverColor.toArgb(), centerPixel())
    }

    @Test fun popupUsesOneScreenAlignedFrameUntilItCloses() {
        var open by mutableStateOf(false)
        var firstColor by mutableStateOf(Color.Red)
        var horizontalShift by mutableFloatStateOf(0f)
        var sourceWidth by mutableFloatStateOf(1f)
        var captureCount = 0
        lateinit var backdrop: PlayerPopupBackdrop
        compose.setContent {
            val scope = rememberCoroutineScope()
            backdrop = remember(scope) {
                PlayerPopupBackdrop(scope) { source ->
                    captureCount++
                    val still = Bitmap.createBitmap(IntArray(8 * 8) { index ->
                        if (index % 8 < 4) firstColor.toArgb() else Color.Blue.toArgb()
                    }, 8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()
                    PlayerPopupFrame(still, source.localToScreen(Offset.Zero), source.size, needsForegroundContrast = false)
                }
            }
            val scene = remember(firstColor) { Any() }
            SideEffect { backdrop.prepare(scene, canPrepare = true) }
            CompositionLocalProvider(LocalPlayerPopupBackdrop provides backdrop) {
                Box(Modifier.fillMaxWidth(sourceWidth).fillMaxHeight().onGloballyPositioned { backdrop.source = it })
                if (open) AnchoredPlayerPopupDialog(null, { open = false }) { _, _ ->
                    Box(Modifier.fillMaxWidth(.55f).height(72.dp)
                        .graphicsLayer { translationX = horizontalShift }
                        .playerPopupGlass(RectangleShape, onMotion = { horizontalShift })
                        .testTag("screen_aligned_popup"))
                }
            }
        }
        settle()
        awaitBackdrop { backdrop.hasPreparedFrame }
        assertEquals(1, captureCount)
        compose.runOnIdle { open = true }
        settle()

        fun middlePixel(part: Float): Color {
            val image = capture("screen_aligned_popup")
            return Color(image.getRGB((image.width * part).roundToInt(), image.height / 2))
        }
        assertTrue(middlePixel(.25f).red > .95f)
        assertTrue(middlePixel(.74f).blue > .95f)
        compose.runOnIdle { firstColor = Color.Green; horizontalShift = 65f }
        settle()
        assertTrue("The fixed image moves left relative to a popup shifted right", middlePixel(.45f).blue > .85f)
        assertEquals(1, captureCount)

        compose.runOnIdle { open = false; horizontalShift = 0f }
        settle()
        awaitBackdrop { backdrop.hasPreparedFrame }
        compose.runOnIdle { assertNull(backdrop.frame); open = true }
        settle()
        assertTrue(middlePixel(.25f).green > .95f)
        assertEquals(2, captureCount)
        compose.runOnIdle { sourceWidth = .9f }
        settle()
        assertEquals("A visible popup never starts a window capture", 2, captureCount)
        compose.runOnIdle { open = false }
        settle()
        awaitBackdrop { captureCount == 3 }
    }

    private fun awaitBackdrop(ready: () -> Boolean) {
        compose.waitUntil(5_000) {
            // Navigation admission uses Android's elapsed clock alongside Compose's frame clock.
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(32))
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(32)
            ready()
        }
    }

    private fun boundsOnScreen(node: SemanticsNodeInteraction): Rect {
        val semantics = node.fetchSemanticsNode()
        val location = IntArray(2)
        compose.runOnIdle { (semantics.root as ViewRootForTest).view.getLocationOnScreen(location) }
        return semantics.boundsInRoot.translate(Offset(location[0].toFloat(), location[1].toFloat()))
    }

    private fun advancePopup(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    private fun awaitPopupLayout(tag: String) {
        compose.waitUntil(5_000) {
            advancePopup(16)
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun playerDialogsTravelFromTheirOwnPillsAndReturnToThem() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false) {
                ExpandedPlayerScreen(PlayerUiState(currentTrack = track), {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onAddToPlaylist = {})
            }
        } }
        settle()

        fun check(trigger: SemanticsNodeInteraction, panelTag: String, close: () -> Unit) {
            val anchor = boundsOnScreen(trigger)
            val panel = compose.onNodeWithTag(panelTag)
            trigger.performClick()
            awaitPopupLayout(panelTag)
            advancePopup(32)
            val first = boundsOnScreen(panel)
            advancePopup(80)
            val middle = boundsOnScreen(panel)
            advancePopup(350)
            val opened = boundsOnScreen(panel)

            fun distance(bounds: Rect) = (bounds.center - anchor.center).getDistance()
            assertTrue("$panelTag starts by its pill: $first / $opened / $anchor",
                distance(first) < distance(opened) * .6f)
            assertTrue("$panelTag moves monotonically away from the pill", distance(first) < distance(middle) &&
                distance(middle) < distance(opened))
            assertTrue("$panelTag zooms as it moves", first.height < middle.height && middle.height < opened.height)

            close()
            advancePopup(64)
            val closing = boundsOnScreen(panel)
            assertTrue("$panelTag closes back toward the pill", distance(closing) < distance(opened))
            assertTrue(closing.height < opened.height)
            advancePopup(350)
            panel.assertDoesNotExist()
        }

        check(compose.onNodeWithText("Resolving audio"), "signal_path_popup") {
            compose.onNodeWithTag("pipeline_close").performClick()
        }
        check(compose.onNodeWithTag("sound_output_device_button"), "sound_output_devices_dialog") {
            compose.onNodeWithTag("close_output_devices_dialog").performClick()
        }
        compose.onNodeWithTag("player_lyrics_button").performClick()
        settle()
        check(compose.onNodeWithTag("lyrics_provider_pill"), "lyrics_provider_popup") {
            compose.onNode(hasText("Auto") and hasAnyAncestor(hasTestTag("lyrics_provider_popup")),
                useUnmergedTree = true).performClick()
        }
        check(compose.onNodeWithTag("player_lyrics_text_size_button", useUnmergedTree = true), "lyrics_text_size_popup") {
            compose.onNode(isDialog()).performTouchInput { click(Offset(20f, 20f)) }
        }
    }

    @Test fun queueSheetMovesContinuouslyAndCanStillBeSwipedAway() {
        compose.mainClock.autoAdvance = false
        var open by mutableStateOf(false)
        var dismissals = 0
        var queueSize by mutableIntStateOf(1)
        compose.setContent { MaterialTheme {
            val sample = remember { samplePlayerArtwork(coverPixels, 32, 32) }
            val art = rememberPlayerPopupArtwork(sample, true)
            CompositionLocalProvider(LocalPlayerPopupArtwork provides art) {
                Box(Modifier.fillMaxSize()) {
                    if (open) QueueBottomSheet(remember(queueSize) {
                        listOf(track) + List(queueSize - 1) { track.copy(id = "queued-$it") }
                    }, track, {}, { _, _ -> }, {
                        dismissals++
                        open = false
                    })
                }
            }
        } }
        val sheet = compose.onNodeWithTag("queue_bottom_sheet")
        compose.runOnIdle { open = true }
        val frames = List(30) {
            advancePopup(16)
            runCatching { sheet.fetchSemanticsNode().boundsInRoot }.getOrNull()
        }.filterNotNull().filter { it.height > 0f }
        val shown = sheet.fetchSemanticsNode().boundsInRoot
        assertTrue("The queue must slide and widen across visible frames", frames.first().top > shown.top &&
            frames.first().width < shown.width)
        assertTrue("The queue opening should advance without bouncing",
            frames.zipWithNext().all { (a, b) -> b.top <= a.top + 1f })

        compose.onNodeWithContentDescription("Close Queue").performClick()
        advancePopup(96)
        assertTrue(sheet.fetchSemanticsNode().boundsInRoot.top > shown.top)
        advancePopup(350)
        sheet.assertDoesNotExist()
        assertEquals(1, dismissals)

        compose.runOnIdle { queueSize = 120; open = true }
        settle()
        compose.onNodeWithText("120 tracks in queue").assertIsDisplayed()
        assertArtworkGlass("queue_bottom_sheet")
        sheet.performTouchInput {
            swipe(Offset(width * .5f, 12f), Offset(width * .5f, height * .8f))
        }
        advancePopup(420)
        sheet.assertDoesNotExist()
        assertEquals(2, dismissals)
    }

    @Test fun playerMenuKeepsItsRowsThroughTheClosingAnimation() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false) {
                ExpandedPlayerScreen(PlayerUiState(currentTrack = track), {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onAddToPlaylist = {}, isScreenVisible = false)
            }
        } }
        settle()
        compose.onNodeWithTag("player_options_button").performClick()
        advancePopup(48)
        val menu = compose.onNodeWithTag("player_options_popup")
        val opening = menu.fetchSemanticsNode().boundsInRoot
        advancePopup(280)
        val opened = menu.fetchSemanticsNode().boundsInRoot
        assertTrue(opening.width < opened.width)
        compose.onNodeWithText("Add to Queue").performClick()
        advancePopup(64)
        val closing = menu.fetchSemanticsNode().boundsInRoot
        assertTrue(closing.width < opened.width && closing.height > opened.height * .9f)
        compose.onNodeWithText("Upgrade Audio").assertExists()
        advancePopup(240)
        menu.assertDoesNotExist()
    }

    @Test fun actualPlayerMenuStaysClearAndItsChildDialogUsesTheSelectedAppMode() {
        var colors by mutableStateOf(LightAppColors.copy(dynamicGlassEnabled = false))
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors, LocalArtworkRequestsActive provides false,
                LocalVisualEffectsActive provides false) {
                ExpandedPlayerScreen(PlayerUiState(currentTrack = track), {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onAddToPlaylist = {}, isScreenVisible = false)
            }
        } }
        settle()
        compose.onNodeWithTag("player_options_button").performClick()
        settle()
        assertBackground("player_options_popup", Color.Black)
        assertDownloadRow("Download")
        compose.onNodeWithText("Sleep Mode").performClick()
        settle()
        assertBackground("sleep_timer_popup", Color.White)
        compose.onNodeWithText("Close").performClick()
        settle()
        compose.runOnIdle { colors = DarkAppColors.copy(dynamicGlassEnabled = false) }
        settle()
        compose.onNodeWithTag("player_options_button").performClick()
        settle()
        assertBackground("player_options_popup", Color.Black)
        assertDownloadRow("Download")
        val downloads = OfflineDownloads.get(context)
        for ((status, title) in listOf(DownloadStatus.COMPLETE to "Remove download",
            DownloadStatus.PAUSED to "Resume download", DownloadStatus.DOWNLOADING to "Pause download",
            DownloadStatus.QUEUED to "Pause download", DownloadStatus.FAILED to "Resume download")) {
            runBlocking { downloads.put(DownloadRecord(track, status)) }
            settle()
            assertDownloadRow(title)
            val progress = compose.onNodeWithTag("player_download_progress", useUnmergedTree = true)
            if (status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING) {
                assertEquals(ProgressBarRangeInfo.Indeterminate,
                    progress.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
            } else progress.assertDoesNotExist()
        }
        runBlocking { downloads.put(DownloadRecord(track, DownloadStatus.PAUSED)) }
    }

    @Test fun downloadIconProgressFollowsLiveBytesPauseResumeAndCompletion() {
        val progressTrack = track.copy(id = "menu-progress")
        val downloads = OfflineDownloads.get(context)
        runBlocking {
            downloads.load()
            downloads.put(DownloadRecord(progressTrack, DownloadStatus.DOWNLOADING, bytes = 0, total = 1000))
        }
        var colors by mutableStateOf(DarkAppColors.copy(dynamicGlassEnabled = false))
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors, LocalArtworkRequestsActive provides false,
                LocalVisualEffectsActive provides false) {
                ExpandedPlayerScreen(PlayerUiState(currentTrack = progressTrack), {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onAddToPlaylist = {}, isScreenVisible = false)
            }
        } }
        settle()
        compose.onNodeWithTag("player_options_button").performClick()
        settle()
        val ring = compose.onNodeWithTag("player_download_progress", useUnmergedTree = true)
        fun assertProgress(fraction: Float) {
            assertEquals(fraction, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
            val iconBounds = compose.onNodeWithTag("player_download_icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("The progress line surrounds the existing icon", iconBounds, ring.fetchSemanticsNode().boundsInRoot)
        }
        assertProgress(0f)
        for (bytes in listOf(250L, 730L)) {
            runBlocking { downloads.update(progressTrack.id, persist = false) { it.copy(bytes = bytes) } }
            settle()
            assertProgress(bytes / 1000f)
        }
        captureSuffix = "-progress-dark"
        assertBackground("player_options_popup", Color.Black)
        runBlocking { downloads.update(progressTrack.id) { it.copy(status = DownloadStatus.PAUSED) } }
        settle()
        assertDownloadRow("Resume download")
        assertProgress(.73f)
        compose.runOnIdle { colors = LightAppColors.copy(dynamicGlassEnabled = false) }
        settle()
        assertProgress(.73f)
        captureSuffix = "-progress-light"
        assertBackground("player_options_popup", Color.Black)
        runBlocking { downloads.update(progressTrack.id) { it.copy(status = DownloadStatus.DOWNLOADING, bytes = 850) } }
        settle()
        assertDownloadRow("Pause download")
        assertProgress(.85f)
        runBlocking { downloads.update(progressTrack.id) { it.copy(total = -1) } }
        settle()
        assertEquals(ProgressBarRangeInfo.Indeterminate, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        runBlocking { downloads.update(progressTrack.id) { it.copy(status = DownloadStatus.COMPLETE, bytes = 1000, total = 1000) } }
        settle()
        assertDownloadRow("Remove download")
        ring.assertDoesNotExist()
    }

    private fun assertDownloadRow(title: String) {
        val text = compose.onNodeWithText(title, useUnmergedTree = true)
        val share = compose.onNodeWithText("Share Track", useUnmergedTree = true)
        val bounds = text.fetchSemanticsNode().boundsInRoot
        val shareBounds = share.fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag("player_download_icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("Download text uses the same inset as the other options", shareBounds.left, bounds.left, .5f)
        assertEquals(bounds.center.y, icon.center.y, .5f)
        assertEquals(34f * context.resources.displayMetrics.density, icon.width, .5f)
        fun style(node: SemanticsNodeInteraction): androidx.compose.ui.text.TextStyle {
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style
        }
        assertEquals(style(share).fontWeight, style(text).fontWeight)
        assertEquals(style(share).fontSize, style(text).fontSize)
        assertEquals(style(share).color, style(text).color)
    }

    @Test @Config(sdk = [34])
    fun libraryImportSelectionHasReadableInkAndStillChangesPlatform() {
        var colors by mutableStateOf(LightAppColors.copy(dynamicGlassEnabled = false))
        var platform by mutableStateOf("Link")
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors) {
                ImportSetupContent(PlaylistImportUiState(selectedPlatform = platform), false, {},
                    onInputChanged = {}, onPlatformChanged = { platform = it }, onTokenChanged = {},
                    onLoadSample = {}, onPasteFromClipboard = {}, onStartImport = {})
            }
        } }
        settle()
        fun assertLabel() {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(platform, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val ink = layouts.single().layoutInput.style.color
            val surface = colors.primaryAccent.copy(alpha = if (colors.isDark) .35f else .12f)
            val actual = androidx.compose.ui.graphics.lerp(colors.cardBackground, surface.copy(alpha = 1f), surface.alpha)
            val contrast = (maxOf(ink.luminance(), actual.luminance()) + .05f) /
                (minOf(ink.luminance(), actual.luminance()) + .05f)
            assertTrue("Selected import pill text must contrast with its translucent fill", contrast >= 4.5f)
        }
        assertLabel()
        compose.onNodeWithTag("platform_chip_Spotify").performClick()
        settle()
        assertEquals("Spotify", platform)
        assertLabel()
        compose.runOnIdle { colors = DarkAppColors.copy(dynamicGlassEnabled = false) }
        settle()
        assertLabel()
    }
}

// Robolectric's default window copy redraws the View on the caller thread. Model the platform's
// surface-buffer copy so the test can exercise the production request from its worker thread.
@Implements(PixelCopy::class)
class PlayerWindowCopyShadow {
    companion object {
        const val PLAYER_FRAME_COLOR = 0xFF6CA9DD.toInt()

        @JvmStatic
        @Implementation
        @Suppress("UNUSED_PARAMETER")
        fun request(window: Window, bounds: android.graphics.Rect, destination: Bitmap,
            listener: PixelCopy.OnPixelCopyFinishedListener, handler: Handler) {
            destination.eraseColor(PLAYER_FRAME_COLOR)
            handler.post { listener.onPixelCopyFinished(PixelCopy.SUCCESS) }
        }
    }
}
