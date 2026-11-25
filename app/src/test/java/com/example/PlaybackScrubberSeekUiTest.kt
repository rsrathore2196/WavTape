package com.example

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.model.MusicTrack
import com.example.playback.PlayerUiState
import com.example.ui.components.ExpandedPlayerScreen
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.WavyScrubberBar
import com.example.ui.util.LocalArtworkRequestsActive
import com.github.takahirom.roborazzi.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackScrubberSeekUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun doubleTapsSeekWithinTheTrackInPortrait() = verifyTransportTaps()

    @Test @Config(qualifiers = "w891dp-h411dp-land")
    fun doubleTapsSeekWithinTheTrackInLandscape() = verifyTransportTaps()

    private fun verifyTransportTaps() {
        val track = MusicTrack("skip", "Skip test", "Artist", "Album", 35_000L, "", "")
        var position = 15_000L
        var nextTracks = 0
        var previousTracks = 0
        val seeks = mutableListOf<Long>()
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalArtworkRequestsActive provides false, LocalDynamicGlassEnabled provides false) {
                ExpandedPlayerScreen(PlayerUiState(currentTrack = track), onMinimize = {}, onPlayPause = {},
                    onSeekTo = { position = it; seeks += it }, onSkipNext = { nextTracks++ },
                    onSkipPrevious = { previousTracks++ }, onToggleShuffle = {}, onToggleRepeat = {},
                    onToggleFavorite = {}, onOpenQueue = {}, onAddToPlaylist = {},
                    currentPositionProvider = { position })
            }
        } }
        val next = compose.onNodeWithTag("expanded_player_next")
        val previous = compose.onNodeWithTag("expanded_player_previous")
        repeat(2) { next.performTouchInput { doubleClick(delayMillis = 80L) } }
        repeat(4) { previous.performTouchInput { doubleClick(delayMillis = 80L) } }
        compose.runOnIdle {
            assertEquals(listOf(25_000L, 35_000L, 25_000L, 15_000L, 5_000L, 0L), seeks)
            assertEquals(0, nextTracks)
            assertEquals(0, previousTracks)
        }
        next.performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400L)
        compose.waitUntil(5_000L) { nextTracks == 1 }
        previous.performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400L)
        compose.waitUntil(5_000L) { previousTracks == 1 }
        compose.runOnIdle {
            assertEquals(1, nextTracks)
            assertEquals(1, previousTracks)
            assertEquals(6, seeks.size)
        }
    }

    @Test fun audioQualityPillStaysCenteredAsElapsedTimeGetsWider() {
        val track = MusicTrack("timer", "Timer test", "Artist", "Album", 720_000L, "", "")
        val elapsed = MutableStateFlow(539_000L)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalArtworkRequestsActive provides false, LocalDynamicGlassEnabled provides false) {
                ExpandedPlayerScreen(PlayerUiState(currentTrack = track), {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onAddToPlaylist = {}, playbackPositionFlow = elapsed, currentPositionProvider = { elapsed.value })
            }
        } }
        val pill = compose.onNode(hasClickAction() and hasText("Resolving audio"))
        val timeline = compose.onNodeWithTag("player_timeline_section", useUnmergedTree = true)
        compose.onNodeWithText("8:59").assertExists()
        val center = pill.fetchSemanticsNode().boundsInRoot.center.x
        assertEquals(timeline.fetchSemanticsNode().boundsInRoot.center.x, center, 1f)
        compose.runOnIdle { elapsed.value = 600_000L }
        compose.onNodeWithText("10:00").assertExists()
        assertEquals(center, pill.fetchSemanticsNode().boundsInRoot.center.x, 1f)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun bothEndpointsKeepTheWholeThumbAndRoundTrackCapsInsideTheCanvas() {
        var progress by mutableFloatStateOf(0f)
        val seeks = mutableListOf<Float>()
        compose.setContent { MaterialTheme {
            WavyScrubberBar(progress, false, {}, {}, seeks::add, isDark = true,
                modifier = Modifier.width(300.dp).background(Color.Black))
        } }
        val scrubber = compose.onNodeWithTag("wavy_scrubber_bar")
        for (fraction in listOf(0f, 1f)) {
            compose.runOnIdle { progress = fraction }
            val file = java.io.File.createTempFile("scrubber-endpoint-", ".png")
            try {
                scrubber.captureRoboImage(file.absolutePath, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                val image = javax.imageio.ImageIO.read(file)
                var left = image.width
                var right = -1
                var bright = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val pixel = image.getRGB(x, y)
                    val ink = ((pixel ushr 16) and 255) * ((pixel ushr 24) and 255) / 255
                    if (ink > 5) { left = minOf(left, x); right = maxOf(right, x) }
                    if (ink > 230) bright++
                }
                assertTrue("The thumb is visible even at zero progress", bright > 30)
                assertTrue("The left cap/halo must not be cut off", left > 1)
                assertTrue("The right cap/halo must not be cut off", right < image.width - 2)
                if (fraction == 1f) {
                    val strokeRows = (0 until image.height).filter { y ->
                        ((image.getRGB(image.width / 2, y) ushr 16) and 255) > 127
                    }
                    val (stroke, lift) = with(compose.density) { 3.dp.toPx() to 2.dp.toPx() }
                    assertEquals(stroke, strokeRows.size.toFloat(), 1f)
                    assertEquals(image.height / 2f - lift, strokeRows.average().toFloat() + .5f, 1f)
                }
            } finally { file.delete() }
        }
        scrubber.performTouchInput { click(Offset(0f, centerY)) }
        scrubber.performTouchInput { click(Offset(width.toFloat(), centerY)) }
        compose.runOnIdle { assertEquals(listOf(0f, 1f), seeks) }
    }

    @Test fun tapAndDragUseTheLoadedDurationAndNewTrackCallbacksWithoutRecreatingTheScrubber() {
        var duration by mutableLongStateOf(0L)
        var track by mutableStateOf("first")
        val seeks = mutableListOf<Pair<String, Long>>()
        val started = mutableListOf<String>()
        val moved = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            val loadedDuration = duration
            val currentTrack = track
            WavyScrubberBar(0f, false, onSeekStarted = { started.add(currentTrack) },
                onSeekProgress = { moved.add(currentTrack) }, onSeekFinished = { fraction -> seeks.add(currentTrack to (fraction * loadedDuration).toLong()) },
                isDark = true, modifier = Modifier.width(300.dp))
        } }
        compose.runOnIdle { duration = 180_000L }
        val scrubber = compose.onNodeWithTag("wavy_scrubber_bar")
        scrubber.performTouchInput { click(Offset(width * .5f, centerY)) }
        compose.runOnIdle {
            assertEquals("first", seeks.single().first)
            assertTrue("Loaded duration must replace zero-duration callbacks", seeks.single().second in 89_500..90_500)
            track = "second"; duration = 240_000L
        }
        val inset = with(compose.density) { 16.dp.toPx() }
        scrubber.performTouchInput { swipe(Offset(inset + (width - inset * 2) * .2f, centerY),
            Offset(inset + (width - inset * 2) * .75f, centerY), 400L) }
        compose.runOnIdle {
            assertEquals("second", seeks.last().first)
            assertTrue(seeks.last().second in 179_000..181_000)
            assertEquals("second", started.last())
            assertEquals("second", moved.last())
        }
    }

    @Test fun cancellingEitherAPressOrADragDiscardsThePreviewWithoutSeeking() {
        var cancelled = 0
        val seeks = mutableListOf<Float>()
        compose.setContent { MaterialTheme {
            WavyScrubberBar(0f, false, {}, {}, seeks::add, isDark = true,
                modifier = Modifier.width(300.dp), onSeekCancelled = { cancelled++ })
        } }
        val scrubber = compose.onNodeWithTag("wavy_scrubber_bar")
        scrubber.performTouchInput { down(center); cancel() }
        compose.runOnIdle { assertEquals(1, cancelled) }
        scrubber.performTouchInput {
            down(center)
            moveBy(Offset(50f, 0f))
            cancel()
        }
        compose.runOnIdle {
            assertEquals(2, cancelled)
            assertTrue(seeks.isEmpty())
        }
    }

    @Test fun releasingAnOldDragCannotSeekTheNextTrack() {
        var track by mutableStateOf("first")
        val seeks = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            val currentTrack = track
            WavyScrubberBar(0f, false, {}, {}, { seeks += currentTrack }, isDark = true,
                modifier = Modifier.width(300.dp), gestureKey = currentTrack)
        } }
        val scrubber = compose.onNodeWithTag("wavy_scrubber_bar")
        scrubber.performTouchInput { down(center); moveBy(Offset(50f, 0f)) }
        compose.runOnIdle { track = "second" }
        compose.waitForIdle()
        scrubber.performTouchInput { up() }
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }

        scrubber.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf("second"), seeks) }
    }
}
