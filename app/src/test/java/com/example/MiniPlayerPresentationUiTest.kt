package com.example

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.model.MusicTrack
import com.example.playback.PlayerUiState
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.util.LocalArtworkRequestsActive
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiniPlayerPresentationUiTest {
    @get:Rule val compose = createComposeRule()
    private val song = MusicTrack("mini", "Song", "Artist", "Album", 180_000, "", "")

    private fun settle() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private fun capture(tag: String): BufferedImage {
        val file = File.createTempFile("mini-presentation-", ".png")
        try {
            compose.onNodeWithTag(tag, useUnmergedTree = true).captureRoboImage(file.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            return ImageIO.read(file)
        } finally { file.delete() }
    }

    private fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    @Test fun playbackButtonsMatchTheSelectedDockPillAndKeepTheirClickActions() {
        var colors by mutableStateOf(DarkAppColors)
        var playing by mutableStateOf(false)
        var playTaps = 0
        var skips = 0
        var opens = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors,
                LocalDynamicGlassEnabled provides colors.dynamicGlassEnabled,
                LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false) {
                Column(Modifier.fillMaxSize().background(colors.scaffoldBackground).padding(16.dp)) {
                    MiniPlayer(PlayerUiState(currentTrack = song, isPlaying = playing),
                        { playTaps++; playing = !playing }, { skips++ }, {}, { opens++ })
                    FloatingPillBottomBar(0, {})
                }
            }
        } }

        fun near(first: Color, second: Color, tolerance: Float = .025f): Boolean =
            first.alpha > .95f && second.alpha > .95f &&
                kotlin.math.abs(first.red - second.red) <= tolerance &&
                kotlin.math.abs(first.green - second.green) <= tolerance &&
                kotlin.math.abs(first.blue - second.blue) <= tolerance

        val palettes = listOf(Color(0xff9ce4ff) to Color(0xffc1ef8f), Color(0xff293d68) to Color(0xff5c245d))
        for (base in listOf(DarkAppColors, LightAppColors)) for (glass in listOf(true, false)) {
            for ((index, palette) in palettes.withIndex()) {
                compose.runOnIdle {
                    colors = base.copy(primaryAccent = palette.first, secondaryAccent = palette.second,
                        dynamicGlassEnabled = glass)
                    playing = index != 0
                }
                settle()
                val dockPill = capture("nav_tab_home_active")
                val foreground = colors.onAccentGradient
                for (tag in listOf("mini_player_play_pause", "mini_player_next")) {
                    val button = capture(tag)
                    for (fraction in listOf(.3f, .7f)) {
                        val fill = Color(button.getRGB((button.width * fraction).toInt(), button.height * 4 / 5))
                        val dockFill = Color(dockPill.getRGB((dockPill.width * fraction).toInt(), dockPill.height * 4 / 5))
                        assertTrue("$tag fill at $fraction should match the navigation pill, got $fill vs $dockFill",
                            near(fill, dockFill))
                    }
                    val sheen = Color(button.getRGB(button.width / 2, 7))
                    val dockSheen = Color(dockPill.getRGB(dockPill.width / 2, 7))
                    assertTrue("$tag sheen should match the navigation pill, got $sheen vs $dockSheen", near(sheen, dockSheen))
                    assertTrue("$tag uses the accent's contrasting foreground",
                        pixels(button).count { near(Color(it), foreground, .012f) } > 12)
                }
            }
        }
        compose.onNodeWithTag("mini_player_play_pause").performClick()
        compose.onNodeWithTag("mini_player_next").performClick()
        compose.runOnIdle { assertEquals(1, playTaps); assertEquals(1, skips); assertEquals(0, opens) }
    }

    @Test fun bothMiniPlayerLabelsUseHighContrastBoldTextWithoutBlackOutlineGlyphs() {
        var colors by mutableStateOf(DarkAppColors)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            val backdrop = rememberLiquidGlassBackdrop()
            CompositionLocalProvider(LocalAppColors provides colors,
                LocalLiquidGlassBackdrop provides backdrop,
                LocalDynamicGlassEnabled provides true, LocalArtworkRequestsActive provides false) {
                Box(Modifier.fillMaxSize().testTag("mini_contrast_scene")) {
                    Box(Modifier.fillMaxSize().xtremeSharedBackdropSource()
                        .background(colors.scaffoldBackground))
                    MiniPlayer(PlayerUiState(currentTrack = song), {}, {}, {}, {},
                        modifier = Modifier.padding(16.dp))
                }
            }
        } }
        for (theme in listOf(DarkAppColors, LightAppColors)) {
            compose.runOnIdle { colors = theme }
            settle()
            capture("mini_contrast_scene")
            compose.mainClock.advanceTimeByFrame()
            val scene = capture("mini_contrast_scene")
            for (tag in listOf("mini_player_track_title", "mini_player_track_artist")) {
                val node = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
                val layout = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
                val style = layout.single().layoutInput.style
                assertEquals("$tag must use bold weight", androidx.compose.ui.text.font.FontWeight.Bold, style.fontWeight)
                val bounds = node.fetchSemanticsNode().boundsInRoot
                val background = Color(scene.getRGB((bounds.right + 12).toInt(), bounds.center.y.toInt()))
                val text = style.color.luminance()
                val surface = background.luminance()
                val contrast = (maxOf(text, surface) + .05f) / (minOf(text, surface) + .05f)
                assertTrue("$tag must have high contrast on themed surface in dark=${theme.isDark}: $contrast", contrast >= 4.5f)
            }
        }
    }

    @Test fun longTitlesScrollAutomaticallyWhileShortTitlesStayStill() {
        var track by mutableStateOf(song.copy(title = "Northern lights across the ocean with a very long unforgettable song title"))
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors,
                LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false) {
                Box(Modifier.fillMaxSize().padding(16.dp)) { MiniPlayer(PlayerUiState(currentTrack = track), {}, {}, {}, {}) }
            }
        } }
        compose.mainClock.advanceTimeBy(400)
        val start = pixels(capture("mini_player_track_title"))
        compose.mainClock.advanceTimeBy(1_500)
        assertFalse("An overflowing title moves without focus or user input", start.contentEquals(pixels(capture("mini_player_track_title"))))
        compose.runOnIdle { track = song }
        settle()
        val short = pixels(capture("mini_player_track_title"))
        compose.mainClock.advanceTimeBy(1_500)
        assertArrayEquals("A fitting title must remain still", short, pixels(capture("mini_player_track_title")))
    }
}
