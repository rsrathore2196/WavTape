package com.example

import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogAlbum
import com.example.data.repository.CatalogDestination
import com.example.data.repository.CatalogPlaylist
import com.example.data.repository.CatalogProvider
import com.example.data.repository.SearchResultCategory
import com.example.ui.viewmodel.CatalogDetailState
import com.example.ui.viewmodel.buildShuffledGenreQueue
import com.example.ui.viewmodel.collectExploreGenreShuffleTracks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ExploreGenreShuffleTest {

    private fun track(id: String, genre: String = "Jazz") = MusicTrack(
        id = id,
        title = "Title $id",
        artist = "Artist $id",
        album = "Album $id",
        durationMs = 180_000L,
        coverUrl = "",
        audioUrl = "https://example.com/$id.m4a",
        genre = genre
    )

    @Test
    fun directGenreSongsAreShuffledWithoutFetchingCollectionsWhenPoolIsSufficient() = runTest {
        val songs = (1..20).map { track("yt_jazz_$it") }
        val results = SearchResultCategory(matchedGenreOrType = "Jazz", songs = songs)
        var fetchedCollections = 0

        val pool = collectExploreGenreShuffleTracks(
            results = results,
            currentTrackId = songs.first().id,
            random = Random(42),
            loadCollection = {
                fetchedCollections++
                CatalogDetailState(it)
            }
        )

        assertEquals(0, fetchedCollections)
        assertEquals(20, pool.tracks.size)
        assertEquals(songs.map { it.id }.toSet(), pool.tracks.map { it.id }.toSet())
        assertNotEquals(songs.map { it.id }, pool.tracks.map { it.id })
        assertNotEquals(songs.first().id, pool.tracks.first().id)
    }

    @Test
    fun playlistOnlyGenreCollectsAndShufflesTracksFromThatGenresPlaylists() = runTest {
        val playlist1 = CatalogPlaylist("VL_jazz_1", "Jazz Essentials", "", provider = CatalogProvider.YOUTUBE_MUSIC)
        val playlist2 = CatalogPlaylist("VL_jazz_2", "Late Night Jazz", "", provider = CatalogProvider.YOUTUBE_MUSIC)
        val p1Tracks = (1..10).map { track("yt_p1_$it") }
        val p2Tracks = (1..10).map { track("yt_p2_$it") }
        val results = SearchResultCategory(
            matchedGenreOrType = "Jazz",
            songs = emptyList(),
            catalogPlaylists = listOf(playlist1, playlist2)
        )

        val pool = collectExploreGenreShuffleTracks(
            results = results,
            random = Random(7),
            loadCollection = { destination ->
                when ((destination as CatalogDestination.Playlist).playlist.id) {
                    "VL_jazz_1" -> CatalogDetailState(destination, tracks = p1Tracks, nextPage = 2)
                    else -> CatalogDetailState(destination, tracks = p2Tracks)
                }
            }
        )

        assertEquals(20, pool.tracks.size)
        assertEquals((p1Tracks + p2Tracks).map { it.id }.toSet(), pool.tracks.map { it.id }.toSet())
        assertNotNull(pool.continuationSource)
    }

    @Test
    fun albumOnlyGenreAndFailingCollectionStillReturnShuffledGenreQueue() = runTest {
        val brokenPlaylist = CatalogPlaylist("VL_broken", "Broken", "", provider = CatalogProvider.YOUTUBE_MUSIC)
        val album = CatalogAlbum("MPRE_jazz", "Kind of Blue", emptyList(), "", "1959", provider = CatalogProvider.YOUTUBE_MUSIC)
        val albumTracks = (1..6).map { track("yt_album_$it") }
        val results = SearchResultCategory(
            matchedGenreOrType = "Jazz",
            catalogPlaylists = listOf(brokenPlaylist),
            catalogAlbums = listOf(album)
        )

        val pool = collectExploreGenreShuffleTracks(
            results = results,
            random = Random(99),
            loadCollection = { destination ->
                when (destination) {
                    is CatalogDestination.Playlist -> throw java.io.IOException("Temporary network error")
                    is CatalogDestination.Album -> CatalogDetailState(destination, tracks = albumTracks)
                    else -> CatalogDetailState(destination)
                }
            }
        )

        assertEquals(6, pool.tracks.size)
        assertEquals(albumTracks.map { it.id }.toSet(), pool.tracks.map { it.id }.toSet())
    }

    @Test
    fun buildShuffledGenreQueueDeduplicatesAndRotatesAwayFromCurrentTrack() {
        val tracks = listOf(track("a"), track("b"), track("a"), track("c"))
        val shuffled = buildShuffledGenreQueue(tracks, currentTrackId = "a", random = Random(1))

        assertEquals(3, shuffled.size)
        assertTrue(shuffled.first().id != "a")
        assertEquals(setOf("a", "b", "c"), shuffled.map { it.id }.toSet())
    }
}
