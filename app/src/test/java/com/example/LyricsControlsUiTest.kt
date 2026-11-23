package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.playback.*
import com.example.ui.components.*
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsControlsUiTest {
    @get:Rule val compose = createComposeRule()
    @Before @After fun resetMonitor() {
        AudioSignalPath.reset()
        val field = NetworkQualityMonitor::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? NetworkQualityMonitor)?.let {
            (NetworkQualityMonitor::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(it) as CoroutineScope).cancel()
        }
        field.set(null, null)
    }
    private val lyrics = TrackLyrics("lyrics-controls", "Title", "Artist", true,
        listOf(LyricLine(1000, "दिल मेरा", 3000, translation = "My heart", romanization = "Dil mera"),
            LyricLine(5000, "तेरे लिए", 3000, translation = "For you", romanization = "Tere liye")), provider = "BetterLyrics Portato")

    @Test fun portraitControlsFitAndRemainAvailableWhenMaximized() = checkPlayerControls()
    @Test @Config(qualifiers = "w320dp-h740dp-port")
    fun narrowPortraitControlsDoNotPushTranslateOrMaximizeOffscreen() = checkPlayerControls()
    @Test @Config(qualifiers = "w891dp-h411dp-land")
    fun landscapeControlsFitAndRemainAvailableWhenMaximized() = checkPlayerControls()

    private fun checkPlayerControls() {
        val song = MusicTrack(lyrics.trackId, lyrics.title, lyrics.artist, "Album", 180000, "", "")
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false,
                dynamicAlbumGlow = false), LocalDynamicGlassEnabled provides false) {
                ExpandedPlayerScreen(PlayerUiState(currentTrack = song), onMinimize = {}, onPlayPause = {}, onSeekTo = {},
                    onSkipNext = {}, onSkipPrevious = {}, onToggleShuffle = {}, onToggleRepeat = {},
                    onToggleFavorite = {}, onOpenQueue = {}, onAddToPlaylist = {}, lyrics = lyrics, isScreenVisible = false)
            }
        } }
        compose.mainClock.advanceTimeBy(800)
        checkOutputPillSpacing()
        compose.onNodeWithTag("lyrics_romanize_button").assertDoesNotExist()
        compose.onNodeWithTag("player_lyrics_button").performClick()
        compose.mainClock.advanceTimeBy(1000)
        checkOrder()
        compose.onNodeWithTag("player_lyrics_expand_button").performClick()
        compose.mainClock.advanceTimeBy(1000)
        checkOrder()
        compose.onNodeWithTag("player_lyrics_expand_button").performClick()
        compose.mainClock.advanceTimeBy(1000)
        checkOrder()
        // Source-supplied supplemental fields are hidden until explicitly enabled.
        compose.onNodeWithText("My heart").assertDoesNotExist()
        compose.onNodeWithText("Dil mera").assertDoesNotExist()
    }

    private fun checkOutputPillSpacing() {
        val output = compose.onNodeWithTag("sound_output_device_button").assertIsDisplayed().assertHasClickAction()
            .fetchSemanticsNode().boundsInRoot
        val lyrics = compose.onNodeWithTag("player_lyrics_button").fetchSemanticsNode().boundsInRoot
        val queue = compose.onNodeWithTag("player_queue_button").fetchSemanticsNode().boundsInRoot
        val leftGap = output.left - lyrics.right
        val rightGap = queue.left - output.right
        val minimumGap = with(compose.density) { 16.dp.toPx() } - 1f
        assertTrue("Output pill needs room on both sides", leftGap >= minimumGap && rightGap >= minimumGap)
        assertEquals("Output pill is centered between lyrics and queue", leftGap, rightGap, 1f)
    }

    private fun checkOrder() {
        val pill = compose.onNodeWithTag("lyrics_provider_pill").assertTextContains("BetterLyrics")
            .assertTouchHeightIsEqualTo(48.dp).assertHasClickAction()
        val pillCenter = pill.fetchSemanticsNode().boundsInRoot.center
        val face = compose.onAllNodesWithTag("lyrics_provider_pill_face", useUnmergedTree = true)
            .filterToOne(SemanticsMatcher("face at the active provider pill") { it.boundsInRoot.contains(pillCenter) })
            .getUnclippedBoundsInRoot()
        val height = face.bottom - face.top
        assertTrue("The provider pill should have a slightly taller face, measured $height", height in 27.dp..30.dp)
        assertTrue("The provider pill should stay narrow", face.right - face.left <= 160.dp)
        compose.onNodeWithText("BetterLyrics Portato").assertDoesNotExist()
        val tags = listOf("player_lyrics_text_size_button", "lyrics_romanize_button", "lyrics_provider_pill",
            "lyrics_translate_button", "player_lyrics_expand_button")
        val bounds = tags.map { tag -> compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
            .fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (left, right) -> assertTrue("Controls must not overlap", left.right <= right.left + 1f) }
        val minimumGap = with(compose.density) { 6.dp.toPx() } - 1f
        val leftGap = bounds[2].left - bounds[1].right
        val rightGap = bounds[3].left - bounds[2].right
        assertTrue("The provider pill needs room on both sides", leftGap >= minimumGap && rightGap >= minimumGap)
        assertEquals("The provider pill is centered between its neighbors", leftGap, rightGap, 1f)
        compose.onNodeWithTag("lyrics_romanize_button").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("lyrics_translate_button").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test fun compactProviderNamesKeepOnlyTheFirstWordWithoutChangingTheProviderIdentity() {
        assertEquals("BetterLyrics", lyricsProviderDisplayName("BetterLyrics Portato"))
        assertEquals("Import", lyricsProviderDisplayName("  Import\tLyrics  "))
        assertEquals("LRCLIB", lyricsProviderDisplayName("LRCLIB"))
        assertEquals("Auto", lyricsProviderDisplayName(" \n "))
        assertEquals("BetterLyrics Portato", lyrics.provider)
    }

    @Test fun translatedAndRomanizedSubtitlesRetainOriginalLinesAndSeekTimestamps() {
        var seek = -1L
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            SyncedLyricsView(lyrics, currentPositionMs = 1000, isPlaying = false, onSeekTo = { seek = it })
        } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("दिल मेरा").assertExists()
        compose.onNodeWithText("Dil mera").assertExists()
        compose.onNodeWithText("My heart").assertExists()
        compose.onNodeWithText("My heart").performClick()
        assertEquals(1000L, seek)
    }
}
