package com.example

import android.app.Application
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.repository.*
import com.example.downloads.OfflineDownloads
import com.example.playback.PlayerUiState
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.viewmodel.CatalogDetailState
import com.github.takahirom.roborazzi.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionHeaderUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: OfflineDownloads
    private val tracks = (1..2).map { MusicTrack("header-$it", "Song $it", "Artist", "Album", 120_000, "", "https://example.test/$it") }
    private val playlist = PlaylistEntity(10, "Night Drive (Deluxe) [Official Playlist]", "<b>Late nights &amp; long drives</b>")

    @Before fun prepare() {
        OfflineDownloads::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        store = OfflineDownloads.get(context)
        runBlocking { store.load() }
    }

    private fun showPlaylist(icon: Boolean = false, readOnly: Boolean = false,
        onRename: (String) -> Unit = {}, onDelete: () -> Unit = {}) {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false,
                LocalArtworkRequestsActive provides false) {
                PlaylistDetailScreen(PlaylistWithTracks(playlist, tracks.map(TrackEntity::fromMusicTrack)),
                    onBackClick = {}, onPlayTrack = { _, _ -> }, onToggleFavorite = {}, onDeletePlaylist = onDelete, onRemoveTrack = {},
                    onRenamePlaylist = onRename, readOnly = readOnly, downloadStore = store, artworkIcon = if (icon) Icons.Default.Download else null)
            }
        } }
    }

    @Test fun playlistUsesACleanTitleAndOneCenteredCircularActionGroup() {
        showPlaylist()
        compose.onNodeWithText("Night Drive").assertExists()
        compose.onNodeWithText("Late nights & long drives").assertExists()
        compose.onNodeWithText("2 tracks • 4 min").assertExists()
        assertTitleAction("playlist_profile_name", "playlist_profile_header")
        assertPlaylistTitleActions()
        assertArtAtTop("playlist_profile_artwork", "playlist_profile_header")
        capture("playlist_detail_screen", "playlist")
        assertEquals("Saved title stays intact", "Night Drive (Deluxe) [Official Playlist]", playlist.title)
    }

    @Test fun albumFiltersEditionLabelsAndSeparatesCleanArtistYearAndTrackMetadata() {
        val album = CatalogAlbum("album", "Blue &amp; Gold (Remastered) [Official Audio]",
            listOf(CatalogArtist("a", "Artist - Topic"), CatalogArtist("b", "Artist")), "", "2024-11-13")
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides LightAppColors.copy(dynamicGlassEnabled = false),
                LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false,
                LocalArtworkRequestsActive provides false) {
                CatalogDetailScreen(CatalogDetailState(CatalogDestination.Album(album), tracks = tracks), PlayerUiState(),
                    onBack = {}, onMore = {}, onPlay = { _, _ -> }, onFavorite = {}, onScroll = { _, _ -> })
            }
        } }
        compose.onNodeWithText("Blue & Gold").assertExists()
        compose.onNodeWithText("Artist • 2024").assertExists()
        compose.onNodeWithText("2 tracks • 4 min").assertExists()
        assertTitleAction("album_profile_name", "album_profile_header")
        assertArtAtTop("album_profile_artwork", "album_profile_header")
        capture("catalog_detail_list", "album-light")
    }

    @Test fun albumPlayAllAndShuffleSubmitPlayableQueuesWithoutABackButton() {
        var played: MusicTrack? = null
        var queue = emptyList<MusicTrack>()
        val album = CatalogAlbum("actions", "Album", emptyList(), "", "2026")
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors, LocalArtworkRequestsActive provides false,
                LocalVisualEffectsActive provides false, LocalDynamicGlassEnabled provides false) {
                CatalogDetailScreen(CatalogDetailState(CatalogDestination.Album(album), tracks = tracks), PlayerUiState(),
                    onBack = {}, onMore = {}, onPlay = { song, songs -> played = song; queue = songs },
                    onFavorite = {}, onScroll = { _, _ -> })
            }
        } }
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithTag("album_play").performScrollTo().assertIsEnabled().performClick()
        assertEquals(tracks.first(), played)
        assertEquals(tracks, queue)
        compose.runOnIdle { played = null; queue = emptyList() }
        compose.onNodeWithTag("album_shuffle").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { played == queue.firstOrNull() && queue.isNotEmpty() }
        assertEquals(tracks.toSet(), queue.toSet())
    }

    @Test @Config(qualifiers = "w740dp-h400dp-land")
    fun landscapePlaylistCentersTheDownloadWithWorkingPlaybackActions() {
        showPlaylist()
        assertTitleAction("playlist_profile_name", "playlist_detail_screen")
        assertPlaylistTitleActions()
        compose.onNodeWithContentDescription("Play all").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Shuffle").assertIsDisplayed().performClick()
        capture("playlist_detail_screen", "playlist-landscape")
    }

    @Test fun normalPlaylistArtAlsoStartsAtTheTopAndKeepsItsExistingEdgeBlends() {
        showPlaylist(icon = true)
        assertArtAtTop("playlist_profile_artwork", "playlist_profile_header")
        val file = capture("playlist_profile_artwork", "normal-playlist-blend")
        val image = ImageIO.read(file)
        assertEquals(DarkAppColors.scaffoldBackground.toArgb(), image.getRGB(image.width / 2, 1))
        assertTrue(java.awt.Color(image.getRGB(image.width / 2, image.height / 3)).blue > 100)
        assertEquals(DarkAppColors.scaffoldBackground.toArgb(), image.getRGB(image.width / 2, image.height - 1))
    }

    @Test fun movedTitleActionsKeepTheirRenameAndDeleteBehavior() {
        var renamed: String? = null
        var deleted = false
        showPlaylist(onRename = { renamed = it }, onDelete = { deleted = true })
        compose.onNodeWithTag("edit_playlist_name_button").performScrollTo()
        // The host's wrap-content dialog window otherwise repeatedly measures
        // its text field; give that window a deterministic test viewport.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("edit_playlist_name_button").performClick()
        settleDialog()
        compose.onNode(hasSetTextAction()).performTextReplacement("Renamed playlist")
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Save").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { assertEquals("Renamed playlist", renamed); assertFalse(deleted) }
        compose.onNodeWithTag("delete_playlist_button").performClick()
        settleDialog()
        compose.onNodeWithText("Delete").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { assertTrue(deleted) }
    }

    private fun settleDialog() {
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread {
            val density = context.resources.displayMetrics.density
            org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!
                .setLayout((360 * density).toInt(), (400 * density).toInt())
        }
        compose.mainClock.advanceTimeBy(300)
    }

    @Test fun readOnlyCollectionsDoNotExposeCustomPlaylistManagement() {
        showPlaylist(readOnly = true)
        compose.onNodeWithTag("playlist_profile_name").performScrollTo()
        compose.onNodeWithTag("edit_playlist_name_button").assertDoesNotExist()
        compose.onNodeWithTag("delete_playlist_button").assertDoesNotExist()
        compose.onNodeWithTag("download_collection").performScrollTo().assertIsDisplayed()
    }

    private fun assertPlaylistTitleActions() {
        compose.onNodeWithTag("playlist_profile_name").performScrollTo()
        compose.onAllNodesWithTag("edit_playlist_name_button").assertCountEquals(1)
        compose.onAllNodesWithTag("delete_playlist_button").assertCountEquals(1)
        val title = compose.onNodeWithTag("playlist_profile_name").fetchSemanticsNode().boundsInRoot
        val edit = compose.onNodeWithTag("edit_playlist_name_button").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val delete = compose.onNodeWithTag("delete_playlist_button").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val page = compose.onNodeWithTag("playlist_detail_screen").fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        assertTrue(edit.left >= title.right)
        assertEquals(title.center.y, edit.center.y, 1f)
        assertEquals(edit.center.y, delete.center.y, 1f)
        assertEquals(38f * density, edit.width, 1f)
        assertEquals(edit.width, delete.width, .5f)
        assertEquals(edit.width, edit.height, .5f)
        assertEquals(delete.width, delete.height, .5f)
        assertEquals(10f * density, delete.left - edit.right, 1f)
        assertEquals("Management buttons use the title row's right margin", 24f * density, page.right - delete.right, 1f)
    }

    private fun assertTitleAction(titleTag: String, screenTag: String) {
        val prefix = if (titleTag.startsWith("album")) "album" else "playlist"
        compose.onNodeWithTag("${prefix}_play").performScrollTo()
        compose.onNodeWithTag("download_collection").assertIsDisplayed()
        compose.onAllNodesWithTag("download_collection").assertCountEquals(1)
        compose.onNodeWithText("Download").assertDoesNotExist()
        val action = compose.onNodeWithTag("download_collection").fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithTag("${prefix}_play").fetchSemanticsNode().boundsInRoot
        val shuffle = compose.onNodeWithTag("${prefix}_shuffle").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag(titleTag).fetchSemanticsNode().boundsInRoot
        val screen = compose.onNodeWithTag(screenTag).fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        assertTrue("The controls follow the collection details", play.top >= title.bottom)
        assertTrue("Shuffle, play and download retain their requested order", shuffle.right < play.left && play.right < action.left)
        assertEquals(57.6f * density, action.width, 1f)
        assertEquals(action.width, shuffle.width, 1f)
        assertEquals(66.24f * density, play.width, 1f)
        assertEquals(action.width, action.height, .5f)
        assertEquals(play.width, play.height, .5f)
        assertEquals(shuffle.width, shuffle.height, .5f)
        assertEquals(play.center.y, action.center.y, 1f)
        assertEquals(play.center.y, shuffle.center.y, 1f)
        assertEquals("The complete group has equal page margins", screen.center.x, (shuffle.left + action.right) / 2f, 1f)
        assertEquals(12f * density, play.left - shuffle.right, 1f)
        assertEquals(12f * density, action.left - play.right, 1f)
        compose.onNodeWithText("PLAY ALL").assertDoesNotExist()
        compose.onNodeWithText("SHUFFLE").assertDoesNotExist()
    }

    private fun assertArtAtTop(artTag: String, headerTag: String) {
        val art = compose.onNodeWithTag(artTag).fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag(headerTag).fetchSemanticsNode().boundsInRoot
        assertEquals(0f, header.top, .5f)
        assertEquals(header.top, art.top, .5f)
    }

    private fun capture(tag: String, name: String): File {
        val file = File("build/reports/collection-header-review/$name.png").apply { parentFile?.mkdirs() }
        compose.onNodeWithTag(tag).captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        return file
    }
}
