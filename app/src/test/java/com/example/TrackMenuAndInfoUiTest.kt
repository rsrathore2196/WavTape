package com.example

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogAlbum
import com.example.data.repository.CatalogArtist
import com.example.data.repository.CatalogDestination
import com.example.data.repository.CatalogPlaylist
import com.example.data.repository.TrackMetadataResolver
import com.example.downloads.DownloadRecord
import com.example.downloads.DownloadStatus
import com.example.downloads.OfflineDownloads
import com.example.downloads.OfflinePlan
import com.example.downloads.OfflineTransfer
import com.example.playback.PlayerUiState
import com.example.ui.components.ExpandedPlayerScreen
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalOpenTrackInfo
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.CatalogDetailScreen
import com.example.ui.screens.LibraryDownloadsScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.PlaylistDetailScreen
import com.example.ui.screens.TrackInfoScreen
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.viewmodel.CatalogDetailState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrackMenuAndInfoUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: OfflineDownloads
    private val savedIds = mutableListOf<String>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        store = OfflineDownloads(context)
        runBlocking { store.load() }
        TrackMetadataResolver.clearCache()
    }

    @After
    fun tearDown() {
        savedIds.forEach(store::releaseCache)
        TrackMetadataResolver.clearCache()
    }

    private fun saveOfflineTrack(track: MusicTrack) = runBlocking(Dispatchers.IO) {
        val plan = OfflinePlan(track.audioUrl.ifBlank { "https://example.test/${track.id}" }, "audio/mpeg", "test", true)
        OfflineTransfer.save(store, track.id, plan, DataSource.Factory { ByteArrayDataSource(ByteArray(1024) { 1 }) })
        store.put(DownloadRecord(track, DownloadStatus.COMPLETE, bytes = 1024, total = 1024, plan = plan, addedAt = 100L))
        savedIds += track.id
    }

    @Test
    fun albumPageTrackHasOverflowMenuWithoutHeartAndSwitchesToRemoveDownloadWhenSaved() {
        val track = MusicTrack("alb-1", "Midnight Echo", "Aria", "Starlight", 210_000, "", "https://example.test/alb-1")
        val album = CatalogAlbum("album-1", "Starlight", listOf(CatalogArtist("a1", "Aria")), "", "2024")
        var liked: MusicTrack? = null
        var openedInfo: Pair<MusicTrack, String?>? = null

        saveOfflineTrack(track)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                    LocalDynamicGlassEnabled provides false,
                    LocalVisualEffectsActive provides false,
                    LocalArtworkRequestsActive provides false,
                    LocalOpenTrackInfo provides { t, pl -> openedInfo = t to pl }
                ) {
                    CatalogDetailScreen(
                        state = CatalogDetailState(
                            destination = CatalogDestination.Album(album),
                            tracks = listOf(track),
                            initialized = true
                        ),
                        player = PlayerUiState(),
                        onBack = {},
                        onMore = {},
                        onPlay = { _, _ -> },
                        onFavorite = { liked = it },
                        onScroll = { _, _ -> },
                        downloadStore = store
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add to favorites").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove from favorites").assertDoesNotExist()
        compose.onNodeWithTag("track_menu_${track.id}").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("track_menu_like").assertIsDisplayed()
        compose.onNodeWithText("Like").assertIsDisplayed()
        compose.onNodeWithTag("track_menu_download").assertIsDisplayed()
        // Wait for OfflineDownloads state and verify auto-switched label
        compose.onNodeWithText("Remove download").assertIsDisplayed()
        compose.onNodeWithTag("track_menu_remove").assertDoesNotExist()
        compose.onNodeWithTag("track_menu_info").assertIsDisplayed().performClick()
        assertEquals(track.id, openedInfo?.first?.id)
        assertNull(openedInfo?.second)
        assertNull(liked)
    }

    @Test
    fun publicPlaylistTrackHasStandardThreeOptionPopupMenu() {
        val track = MusicTrack("pub-1", "Ocean Drift", "Nora", "Blue Horizon", 195_000, "", "https://example.test/pub-1")
        val playlist = CatalogPlaylist("pl-1", "Summer Hits", "")
        var openedInfo: Pair<MusicTrack, String?>? = null

        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                    LocalDynamicGlassEnabled provides false,
                    LocalVisualEffectsActive provides false,
                    LocalArtworkRequestsActive provides false,
                    LocalOpenTrackInfo provides { t, pl -> openedInfo = t to pl }
                ) {
                    CatalogDetailScreen(
                        state = CatalogDetailState(
                            destination = CatalogDestination.Playlist(playlist),
                            tracks = listOf(track),
                            playlistContent = PlaylistWithTracks(
                                PlaylistEntity(1L, playlist.title, ""),
                                listOf(TrackEntity.fromMusicTrack(track))
                            ),
                            initialized = true
                        ),
                        player = PlayerUiState(),
                        onBack = {},
                        onMore = {},
                        onPlay = { _, _ -> },
                        onFavorite = {},
                        onScroll = { _, _ -> },
                        downloadStore = store
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add to favorites").assertDoesNotExist()
        compose.onNodeWithTag("track_menu_${track.id}").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("track_menu_like").assertIsDisplayed()
        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithTag("track_menu_remove").assertDoesNotExist()
        compose.onNodeWithTag("track_menu_info").assertIsDisplayed().performClick()
        assertEquals(track.id, openedInfo?.first?.id)
        assertEquals("Summer Hits", openedInfo?.second)
    }

    @Test
    fun customPlaylistReplacesHeartAndDeleteWithSingleOverflowMenuContainingAllFourOptions() {
        val track = MusicTrack("cust-1", "Night Drive", "Kai", "Neon City", 200_000, "", "https://example.test/cust-1")
        val custom = PlaylistWithTracks(
            PlaylistEntity(5L, "Late Night Mix", ""),
            listOf(TrackEntity.fromMusicTrack(track))
        )
        var removedId: String? = null

        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                    LocalDynamicGlassEnabled provides false,
                    LocalVisualEffectsActive provides false,
                    LocalArtworkRequestsActive provides false
                ) {
                    PlaylistDetailScreen(
                        playlistWithTracks = custom,
                        onBackClick = {},
                        onPlayTrack = { _, _ -> },
                        onToggleFavorite = {},
                        onDeletePlaylist = {},
                        onRemoveTrack = { removedId = it },
                        readOnly = false,
                        downloadStore = store
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add to favorites").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove from playlist").assertDoesNotExist()

        compose.onNodeWithTag("track_menu_${track.id}").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("track_menu_like").assertIsDisplayed()
        compose.onNodeWithTag("track_menu_download").assertIsDisplayed()
        compose.onNodeWithTag("track_menu_remove").assertIsDisplayed()
        compose.onNodeWithTag("track_menu_info").assertIsDisplayed()

        compose.onNodeWithTag("track_menu_remove").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Remove from Playlist").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(track.id, removedId)
    }

    @Test
    fun downloadsPageReplacesHeartAndDeleteWithOverflowMenuWithoutSeparateDownloadOption() {
        val track = MusicTrack("dl-1", "Offline Melody", "Liam", "Acoustic Tape", 185_000, "", "https://example.test/dl-1")
        saveOfflineTrack(track)

        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                    LocalDynamicGlassEnabled provides false,
                    LocalVisualEffectsActive provides false,
                    LocalArtworkRequestsActive provides false
                ) {
                    LibraryDownloadsScreen(
                        onBackClick = {},
                        onPlayTrack = { _, _ -> false },
                        onToggleFavorite = {},
                        store = store
                    )
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Offline Melody").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Add to favorites").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove from playlist").assertDoesNotExist()

        compose.onNodeWithTag("track_menu_${track.id}").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("track_menu_like").assertIsDisplayed()
        compose.onNodeWithTag("track_menu_download").assertDoesNotExist()
        compose.onNodeWithTag("track_menu_remove").assertIsDisplayed()
        compose.onNodeWithTag("track_menu_info").assertIsDisplayed()
    }

    @Test
    fun likedSongsReplacesHeartWithOverflowMenuContainingLikeDownloadAndTrackInfo() {
        val liked = MusicTrack(
            "lk-1", "Favorite Song", "Maya", "Golden Hour", 190_000, "", "https://example.test/lk-1",
            isLiked = true
        )
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                    LocalDynamicGlassEnabled provides false,
                    LocalVisualEffectsActive provides false,
                    LocalArtworkRequestsActive provides false
                ) {
                    LibraryScreen(
                        favoriteTracks = listOf(liked),
                        playlists = emptyList(),
                        onTrackClick = { _, _ -> },
                        onToggleFavorite = {},
                        onOpenPlaylist = {},
                        onCreatePlaylistClick = {},
                        downloadStore = store
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("open_liked_songs").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Favorite Song").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Remove from favorites").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add to favorites").assertDoesNotExist()

        compose.onNodeWithTag("track_menu_${liked.id}").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("track_menu_like").assertIsDisplayed()
        compose.onNodeWithTag("track_menu_download").assertIsDisplayed()
        compose.onNodeWithTag("track_menu_remove").assertDoesNotExist()
        compose.onNodeWithTag("track_menu_info").assertIsDisplayed()
    }

    @Test
    fun trackInfoScreenShowsRoundCardsForArtistsAlbumComposerGenreAndRejectsPlaylistNameAsAlbum() {
        val track = MusicTrack(
            id = "ti-1",
            title = "Skyline Lights",
            artist = "Arijit Singh, Shreya Ghoshal",
            album = "My Road Trip Playlist",
            durationMs = 225_000,
            coverUrl = "",
            audioUrl = "",
            singers = "Arijit Singh, Shreya Ghoshal",
            writer = "Pritam",
            genre = "Indie Pop",
            language = "Hindi",
            year = "2024"
        )
        var clickedArtist: CatalogArtist? = null
        var clickedGenre: String? = null

        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                    LocalDynamicGlassEnabled provides false,
                    LocalVisualEffectsActive provides false,
                    LocalArtworkRequestsActive provides false
                ) {
                    TrackInfoScreen(
                        track = track,
                        sourcePlaylistTitle = "My Road Trip Playlist",
                        onOpenArtist = { clickedArtist = it },
                        onOpenGenre = { clickedGenre = it },
                        resolveRemoteMetadata = false
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("track_info_artwork").assertExists()
        compose.onNodeWithTag("track_info_title").assertIsDisplayed()
        compose.onNodeWithTag("track_info_screen").performScrollToNode(hasTestTag("track_info_artists_heading"))
        compose.onNodeWithText("Artist Names").assertIsDisplayed()
        // Album matches the source playlist name, so it MUST NOT be shown as Album Name.
        compose.onNodeWithTag("track_info_album_heading").assertDoesNotExist()
        compose.onNodeWithTag("track_info_artist_0").performClick()
        assertEquals("Arijit Singh", clickedArtist?.name)

        compose.onNodeWithTag("track_info_screen").performScrollToNode(hasTestTag("track_info_composer_heading"))
        compose.onNodeWithText("Composer").assertIsDisplayed()
        compose.onNodeWithText("Pritam").assertIsDisplayed()

        compose.onNodeWithTag("track_info_screen").performScrollToNode(hasTestTag("track_info_genre_card"))
        compose.onNodeWithText("Genre").assertIsDisplayed()
        compose.onNodeWithTag("track_info_genre_card").performClick()
        assertEquals("Indie Pop", clickedGenre)
    }

    @Test
    fun trackInfoScreenShowsRealAlbumCardAndHidesComposerWhenUnavailable() {
        val track = MusicTrack(
            id = "ti-2",
            title = "Echoes",
            artist = "The Midnight",
            album = "Endless Summer",
            durationMs = 240_000,
            coverUrl = "",
            audioUrl = "",
            writer = "",
            genre = "Synthwave"
        )
        var clickedAlbum: Pair<String, String>? = null

        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                    LocalDynamicGlassEnabled provides false,
                    LocalVisualEffectsActive provides false,
                    LocalArtworkRequestsActive provides false
                ) {
                    TrackInfoScreen(
                        track = track,
                        sourcePlaylistTitle = "Workout Mix",
                        onOpenAlbumByName = { album, artist -> clickedAlbum = album to artist },
                        resolveRemoteMetadata = false
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Album Name").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("track_info_composer_heading").assertDoesNotExist()
        compose.onNodeWithTag("track_info_album_card").performScrollTo().performClick()
        assertEquals("Endless Summer" to "The Midnight", clickedAlbum)
    }

    @Test
    fun playerCreditsBoxIsAccurateAndMakesArtistAlbumAndGenreTouchable() {
        val song = MusicTrack(
            id = "credits-nav-1",
            title = "Solar Flare",
            artist = "Nova, Orion",
            album = "Galactic Horizon",
            durationMs = 205_000,
            coverUrl = "",
            audioUrl = "",
            singers = "Nova, Orion",
            writer = "",
            genre = "Electronic",
            language = "English",
            year = "2025"
        )
        var openedAlbum: Pair<String, String>? = null
        var openedGenre: String? = null
        var openedArtist: CatalogArtist? = null

        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false, dynamicAlbumGlow = false),
                    LocalDynamicGlassEnabled provides false
                ) {
                    ExpandedPlayerScreen(
                        uiState = PlayerUiState(currentTrack = song),
                        onMinimize = {},
                        onPlayPause = {},
                        onSeekTo = {},
                        onSkipNext = {},
                        onSkipPrevious = {},
                        onToggleShuffle = {},
                        onToggleRepeat = {},
                        onToggleFavorite = {},
                        onOpenQueue = {},
                        onAddToPlaylist = {},
                        isScreenVisible = false,
                        onOpenArtist = { openedArtist = it },
                        onOpenAlbumByName = { a, ar -> openedAlbum = a to ar },
                        onOpenGenre = { openedGenre = it }
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("flip_album_art_card").performClick()
        compose.mainClock.advanceTimeBy(1200)

        // Placeholder strings must never appear
        compose.onNodeWithText("Original Composer").assertDoesNotExist()
        compose.onNodeWithText("Original Single").assertDoesNotExist()
        compose.onAllNodesWithText("Hindi").assertCountEquals(0)

        // Tap Album
        compose.onNodeWithTag("credits_album_value", useUnmergedTree = true).performClick()
        assertEquals("Galactic Horizon" to "Nova", openedAlbum)

        // Tap Genre
        compose.onNodeWithTag("credits_genre_value", useUnmergedTree = true).performClick()
        assertEquals("Electronic", openedGenre)

        // Tap first artist name inside the touchable artists text
        compose.onNodeWithTag("credits_artists_value", useUnmergedTree = true).performTouchInput {
            click(percentOffset(0.15f, 0.5f))
        }
        assertEquals("Nova", openedArtist?.name)
    }

    @Test
    fun trackInfoMetadataPrefersSquareOriginalAudioCoverOverVideoThumbnailWithVideoFallback() {
        val videoThumb = "https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg"
        val rectShelfThumb = "https://lh3.googleusercontent.com/shelfvideo=w544-h306-l90-rj"
        val squareAtvCover = "https://lh3.googleusercontent.com/officialaudio=w544-h544-l90-rj"
        val squareSaavnCover = "https://c.saavncdn.com/123/Song-500x500.jpg"

        assertTrue(TrackMetadataResolver.isVideoThumbnailUrl(videoThumb))
        assertTrue(TrackMetadataResolver.isVideoThumbnailUrl(rectShelfThumb))
        assertFalse(TrackMetadataResolver.isVideoThumbnailUrl(squareAtvCover))
        assertTrue(TrackMetadataResolver.isLikelySquareAudioCoverUrl(squareAtvCover))
        assertTrue(TrackMetadataResolver.isLikelySquareAudioCoverUrl(squareSaavnCover))
        assertFalse(TrackMetadataResolver.isLikelySquareAudioCoverUrl(videoThumb))
        assertFalse(TrackMetadataResolver.isLikelySquareAudioCoverUrl(rectShelfThumb))

        // Cached square original audio cover replaces rectangular video cover immediately on initial load.
        val shelfTrack = MusicTrack(
            id = "yt_abcdefghijk",
            title = "Shelf Song",
            artist = "Arijit Singh",
            album = "Midnight Release",
            durationMs = 210_000,
            coverUrl = rectShelfThumb,
            audioUrl = ""
        )
        val initialWithSquare = TrackMetadataResolver.initial(
            track = shelfTrack,
            cachedSquareCover = squareAtvCover
        )
        assertEquals(squareAtvCover, initialWithSquare.coverUrl)
        assertTrue(initialWithSquare.hasVerifiedSquareCover)
        assertEquals(squareAtvCover, initialWithSquare.album?.image)

        // When no square cover exists, video thumbnail remains as fallback.
        val initialFallbackOnly = TrackMetadataResolver.initial(track = shelfTrack)
        assertEquals(rectShelfThumb, initialFallbackOnly.coverUrl)
        assertFalse(initialFallbackOnly.hasVerifiedSquareCover)

        // Resolved square audio cover upgrades rectangular video thumbnail; another video URL never replaces a verified square cover.
        val (upgraded, upgradedVerified) = TrackMetadataResolver.selectPreferredCover(
            baseCover = videoThumb,
            baseVerifiedSquare = false,
            incomingCover = squareAtvCover,
            incomingSquareAudio = true,
            preferIncomingCover = true
        )
        assertEquals(squareAtvCover, upgraded)
        assertTrue(upgradedVerified)

        val (keptSquare, keptVerified) = TrackMetadataResolver.selectPreferredCover(
            baseCover = squareAtvCover,
            baseVerifiedSquare = true,
            incomingCover = videoThumb,
            incomingSquareAudio = false,
            preferIncomingCover = true
        )
        assertEquals(squareAtvCover, keptSquare)
        assertTrue(keptVerified)

        val (keptFallback, fallbackVerified) = TrackMetadataResolver.selectPreferredCover(
            baseCover = videoThumb,
            baseVerifiedSquare = false,
            incomingCover = null,
            incomingSquareAudio = false,
            preferIncomingCover = false
        )
        assertEquals(videoThumb, keptFallback)
        assertFalse(fallbackVerified)
    }
}
