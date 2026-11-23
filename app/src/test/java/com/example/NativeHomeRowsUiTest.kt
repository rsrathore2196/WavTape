package com.example

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.SectionCardLayout
import com.example.ui.screens.HomeShelfTrackRow
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.viewmodel.SectionState
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeHomeRowsUiTest {
    @get:Rule val compose = createComposeRule()
    private val fullTitle = "Artist 1 - Native song 1 (Live Remix) (Official Music Video)"
    private val homeBackground = Color(0xFF324A58)
    private val tracks = (1..20).map { MusicTrack("yt_native$it", if (it == 2) "Native song 2 (Acoustic)" else "Native song $it", "Artist $it", "Album", 120_000, "", "",
        originalProviderTitle = if (it == 1) fullTitle else "") }
    private fun shelfContent(content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                    LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false,
                    LocalArtworkRequestsActive provides false) {
                    Box(Modifier.background(homeBackground)) { content() }
                }
            }
        }
    }
    private fun content(kind: NativeShelfKind, trending: Boolean = false, subtitle: String = "",
        shuffle: ((MusicTrack, List<MusicTrack>) -> Unit)? = null,
        click: (MusicTrack, List<MusicTrack>) -> Unit = { _, _ -> }) {
        val title = if (trending) "Trending" else "Native shelf"
        shelfContent {
            SectionCardLayout(SectionState(title = title, subtitle = subtitle, tracks = tracks, isLoaded = true), tracks[1].id,
                DarkAppColors.cardBackground, DarkAppColors.cardBorder, DarkAppColors.textPrimary, DarkAppColors.textMuted,
                onTrackClick = click, onShuffleQuickPicks = shuffle ?: click, tracksPrepared = true,
                nativeKind = kind, resolveSongCovers = trending,
                shelfItems = tracks.map { ServerHomeItem.Song(it, "MUSIC_VIDEO_TYPE_OMV") })
        }
        compose.onNodeWithText(fullTitle).assertIsDisplayed()
        compose.onNodeWithText(tracks[1].title).assertIsDisplayed()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun standardShelfShowsTheHomeBackgroundAtItsOuterEdge() {
        content(NativeShelfKind.STANDARD)
        val screenshot = File.createTempFile("home-shelf-background-", ".png")
        try {
            compose.onNodeWithTag("home_shelf").captureRoboImage(screenshot.absolutePath,
                RoborazziOptions(taskType = RoborazziTaskType.Record))
            val image = ImageIO.read(screenshot)
            assertEquals(homeBackground.toArgb(), image.getRGB(4, image.height / 2))
            assertEquals(homeBackground.toArgb(), image.getRGB(0, image.height / 2))
        } finally {
            screenshot.delete()
        }
    }

    @Test fun quickPicksStacksFourUnframedTracksPerColumnWithOriginalQueueOrder() {
        var selected: MusicTrack? = null; var queue: List<MusicTrack> = emptyList()
        content(NativeShelfKind.QUICK_PICKS) { track, list -> selected = track; queue = list }
        val frame = compose.onNodeWithTag("native_quick_picks_shelf").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Native shelf").fetchSemanticsNode().boundsInRoot
        val subtitle = compose.onNodeWithText("A mix of songs to try").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(title.top > frame.top && subtitle.top >= title.bottom)
        val bounds = (1..4).map { compose.onNodeWithTag("habit_pill_yt_native$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds.all { it.left > frame.left && it.right < frame.right && it.top > subtitle.bottom && it.bottom <= frame.bottom })
        for ((upper, lower) in bounds.zipWithNext()) {
            assertEquals(upper.left, lower.left, .5f)
            assertEquals(upper.right, lower.right, .5f)
            assertTrue(upper.bottom < lower.top)
        }
        val peek = compose.onNodeWithTag("habit_pill_yt_native5").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(peek.left > bounds.first().right && peek.width < bounds.first().width / 2)
        val playingRow = captureTrack("habit_pill_yt_native2")
        assertEquals(homeBackground.toArgb(), playingRow.getRGB(playingRow.width - 18, playingRow.height / 2))
        compose.onNodeWithTag("habit_pill_yt_native3").performClick()
        compose.runOnIdle { assertEquals(tracks[2], selected); assertEquals(tracks, queue) }
        compose.onNodeWithTag("native_quick_picks_grid").performScrollToIndex(1)
        compose.onNodeWithTag("habit_pill_yt_native5").assertIsDisplayed()
        compose.onNodeWithTag("habit_pill_yt_native8").assertIsDisplayed()
    }

    @Test fun recentRowsHaveTransparentSurfacesAndOnlyOverflowingTitlesScroll() {
        compose.mainClock.autoAdvance = false
        shelfContent {
            CompositionLocalProvider(LocalVisualEffectsActive provides true) {
                Column(Modifier.width(160.dp)) {
                    for (track in listOf(tracks.first(), tracks[1].copy(title = "Short"))) {
                        HomeShelfTrackRow(track, false, DarkAppColors.cardBorder, DarkAppColors.textPrimary,
                            DarkAppColors.textMuted, modifier = Modifier.fillMaxWidth(), onClick = {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        val longBefore = captureTrack("habit_pill_yt_native1")
        val shortBefore = captureTrack("habit_pill_yt_native2")
        assertEquals(homeBackground.toArgb(), shortBefore.getRGB(shortBefore.width - 10, shortBefore.height / 2))
        compose.mainClock.advanceTimeBy(1_800)
        val longAfter = captureTrack("habit_pill_yt_native1")
        val shortAfter = captureTrack("habit_pill_yt_native2")
        fun pixels(image: java.awt.image.BufferedImage) =
            image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        assertFalse(pixels(longBefore).contentEquals(pixels(longAfter)))
        assertArrayEquals(pixels(shortBefore), pixels(shortAfter))
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureTrack(tag: String): java.awt.image.BufferedImage {
        val screenshot = File.createTempFile("home-shelf-track-", ".png")
        return try {
            compose.onNodeWithTag(tag).captureRoboImage(screenshot.absolutePath,
                RoborazziOptions(taskType = RoborazziTaskType.Record))
            ImageIO.read(screenshot)
        } finally {
            screenshot.delete()
        }
    }
    @Test fun verifiedVideoArtworkIsLandscapeAndKeepsPlaybackQueue() {
        var queue: List<MusicTrack> = emptyList()
        content(NativeShelfKind.MUSIC_VIDEOS) { _, list -> queue = list }
        compose.onNodeWithText("Music videos to watch").assertIsDisplayed()
        val video = compose.onNodeWithTag("native_video_art_yt_native1", useUnmergedTree = true)
            .assertWidthIsEqualTo(224.dp).assertHeightIsEqualTo(126.dp)
        val bounds = video.fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("home_shelf").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left > frame.left && bounds.right < frame.right)
        assertTrue(bounds.top > frame.top && bounds.bottom <= frame.bottom)
        compose.onNodeWithText(fullTitle).performClick()
        compose.runOnIdle { assertEquals(tracks, queue) }
    }

    @Test @Config(qualifiers = "en-rUS-w411dp-h891dp-port")
    fun nativeVideoLabelsFollowTheAppLocaleWithoutChangingTheSelectedRecording() {
        val video = tracks.first().copy(title = "दिल से", artist = "ਗੀਤ", originalProviderTitle = "दिल से (Slowed + Reverb)")
        var selected: MusicTrack? = null
        shelfContent {
            SectionCardLayout(SectionState(title = "Music Videos", tracks = listOf(video), isLoaded = true), null,
                DarkAppColors.cardBackground, DarkAppColors.cardBorder, DarkAppColors.textPrimary, DarkAppColors.textMuted,
                onTrackClick = { track, _ -> selected = track }, tracksPrepared = true,
                nativeKind = NativeShelfKind.MUSIC_VIDEOS,
                shelfItems = listOf(ServerHomeItem.Song(video, "MUSIC_VIDEO_TYPE_OMV")))
        }
        val title = "Dil Se (Slowed + Reverb)"
        compose.onNodeWithText("Geet").assertIsDisplayed()
        compose.onNodeWithContentDescription(title, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(title).assertIsDisplayed().performClick()
        compose.runOnIdle { assertSame(video, selected) }
    }
    @Test fun quickPicksKeepsAllEighteenTracksAndThePlaybackQueue() {
        var selected: MusicTrack? = null; var queue: List<MusicTrack> = emptyList()
        content(NativeShelfKind.QUICK_PICKS) { track, list -> selected = track; queue = list }
        compose.onNodeWithTag("native_quick_picks_grid").performScrollToIndex(4)
        compose.onNodeWithTag("native_quick_picks_grid").performTouchInput { swipeLeft(durationMillis = 350) }
        compose.onNodeWithTag("habit_pill_yt_native18").assertIsDisplayed().performClick()
        compose.onNodeWithTag("habit_pill_yt_native19").assertDoesNotExist()
        compose.onNodeWithTag("habit_pill_yt_native20").assertDoesNotExist()
        compose.runOnIdle { assertEquals(tracks[17], selected); assertEquals(tracks, queue) }
    }

    @Test fun quickPicksShuffleStartsAnExplicitQueueFromOnlyTheDisplayedTracks() {
        val original = tracks.take(18)
        val requests = mutableListOf<Pair<MusicTrack, List<MusicTrack>>>()
        content(NativeShelfKind.QUICK_PICKS, shuffle = { track, queue -> requests += track to queue }) { _, _ ->
            fail("Shuffle must not start discovery playback")
        }
        val button = compose.onNodeWithContentDescription("Shuffle Quick Picks").assertIsDisplayed()
        val frame = compose.onNodeWithTag("native_quick_picks_shelf").fetchSemanticsNode().boundsInRoot
        val buttonBounds = button.fetchSemanticsNode().boundsInRoot
        assertTrue(buttonBounds.left > frame.center.x && buttonBounds.right < frame.right)
        assertTrue(buttonBounds.top > frame.top && buttonBounds.bottom < frame.bottom)

        repeat(3) { button.performClick() }
        compose.runOnIdle {
            assertEquals(3, requests.size)
            requests.forEach { (start, queue) ->
                assertEquals(original.size, queue.size)
                assertEquals(original.toSet(), queue.toSet())
                assertEquals(queue.first(), start)
            }
            assertTrue(requests.any { (_, queue) -> queue != original })
        }
    }

    @Test fun standardMusicShelvesKeepTheirExistingSquareArtwork() {
        content(NativeShelfKind.STANDARD)
        compose.onNodeWithText("More to discover on YouTube Music").assertIsDisplayed()
        compose.onNodeWithTag("native_quick_picks_shelf").assertDoesNotExist()
        compose.onNodeWithContentDescription("Shuffle Quick Picks").assertDoesNotExist()
        compose.onNodeWithTag("native_music_video_row").assertDoesNotExist()
        compose.onNodeWithTag("native_quick_picks_grid").assertDoesNotExist()
        compose.onNodeWithContentDescription(fullTitle, useUnmergedTree = true)
            .assertWidthIsEqualTo(163.2.dp).assertHeightIsEqualTo(163.2.dp)
    }
    @Test fun suppliedShelfDescriptionTakesPrecedenceOverFallback() {
        content(NativeShelfKind.STANDARD, subtitle = "Handpicked songs for the weekend")
        compose.onNodeWithText("Handpicked songs for the weekend").assertIsDisplayed()
        compose.onNodeWithText("More to discover on YouTube Music").assertDoesNotExist()
    }
    @Test fun trendingUsesLandscapeArtworkAndKeepsItsPlaybackQueue() {
        var queue: List<MusicTrack> = emptyList()
        content(NativeShelfKind.STANDARD, trending = true) { _, list -> queue = list }
        compose.onNodeWithText("Popular on YouTube Music right now").assertIsDisplayed()
        compose.onNodeWithContentDescription(fullTitle, useUnmergedTree = true)
            .assertWidthIsEqualTo(224.dp).assertHeightIsEqualTo(126.dp)
        compose.onNodeWithText(fullTitle).performClick()
        compose.runOnIdle { assertEquals(tracks, queue) }
    }
}
