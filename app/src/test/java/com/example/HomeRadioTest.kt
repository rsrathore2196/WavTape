package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeMusicApiService.SongSearchPage
import com.example.data.remote.YouTubeRadioRequest
import com.example.data.remote.loadYouTubeRadioQueue
import com.example.playback.ArtistRadio
import com.example.playback.ArtistRadioCursor
import com.example.playback.loadSongs
import com.example.ui.viewmodel.MostListenedArtist
import com.example.ui.viewmodel.findArtistRadioSeed
import com.example.ui.viewmodel.findColdRadioSeed
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeRadioTest {
    private val seed = MusicTrack("yt_abcdefghijk", "Seed song", "Seed artist", "Album", 180_000, "", "")
    private val second = seed.copy(id = "yt_lmnopqrstuv", title = "Another song", artist = "Another singer")

    @Test fun emptyInstallationFallsBackToANativeSearchWithoutWaitingForRadio() = runBlocking {
        val candidate = findColdRadioSeed(
            cached = { listOf(seed.copy(id = "local_only")) },
            home = { throw java.io.IOException("Home unavailable") },
            search = { listOf(seed) }
        )
        assertEquals(seed, candidate)
        assertEquals(seed, findColdRadioSeed({ listOf(seed) }, { error("Home is already cached") }, { error("Unnecessary search") }))
    }

    @Test fun artistRadioUsesTheListenedRecordingForAnyCreditedSinger() = runBlocking {
        val collaboration = second.copy(primaryArtists = listOf(second.artist, "Guest Singer"))
        val artist = MostListenedArtist("Guest Singer", 5, listOf(seed, collaboration))
        assertEquals(collaboration, findArtistRadioSeed(artist) { error("The listened recording is sufficient") })
    }

    @Test fun artistRadioFindsAVerifiedSeedForLocalHistoryAndRejectsOtherArtists() = runBlocking {
        val local = seed.copy(id = "local_seed", audioUrl = "file:///music/seed.flac")
        val artist = MostListenedArtist(seed.artist, 5, listOf(local))
        val sameTitle = second.copy(title = seed.title)
        assertEquals(seed, findArtistRadioSeed(artist) { name ->
            assertEquals(seed.artist, name)
            SongSearchPage(listOf(sameTitle, seed), null)
        })
        assertNull(findArtistRadioSeed(artist) { SongSearchPage(listOf(sameTitle), null) })
    }

    @Test fun artistRefillsStayWithTheOriginalArtistAfterACollaboration() = runBlocking {
        val station = ArtistRadio(seed.artist)
        val collaboration = second.copy(primaryArtists = listOf(second.artist, seed.artist))
        val otherSong = second.copy(id = "yt_01234567890", title = "Featuring ${seed.artist}")
        val samePrefix = second.copy(id = "yt_11111111111", artist = "${seed.artist} Jr")
        val nextSong = seed.copy(id = "yt_22222222222", title = "Another recording")
        val finalSong = seed.copy(id = "yt_33333333333", title = "Later recording")
        val visited = mutableListOf<String?>()
        val search: suspend (String, String?) -> SongSearchPage = { name, token ->
            assertEquals(seed.artist, name)
            visited += token
            if (token == null) {
                SongSearchPage(listOf(seed, otherSong, collaboration, collaboration, nextSong), "page-2")
            } else {
                SongSearchPage(listOf(samePrefix, finalSong), null)
            }
        }
        val first = station.loadSongs(ArtistRadioCursor(), setOf(seed.id), 1, search)
        assertEquals(listOf(collaboration), first.tracks)
        val refill = station.loadSongs(first.cursor, setOf(seed.id, collaboration.id), 4, search)
        assertEquals(listOf(nextSong, finalSong), refill.tracks)
        assertEquals(listOf(null, "page-2"), visited)
        assertTrue(refill.cursor.exhausted)
        assertTrue(station.loadSongs(refill.cursor, emptySet(), 4) { _, _ ->
            error("An exhausted artist catalogue must not start a mix")
        }.tracks.isEmpty())
    }

    @Test fun artistRadioRejectsAnApparentMatchContradictedByProviderCredits() = runBlocking {
        val credits = mapOf(seed.id to listOf(second.artist))
        val page = SongSearchPage(listOf(seed), null, credits)
        val artist = MostListenedArtist(seed.artist, 1, emptyList())
        assertNull(findArtistRadioSeed(artist) { page })
        assertTrue(ArtistRadio(artist.name).loadSongs(ArtistRadioCursor(), emptySet(), 10) { _, _ ->
            page
        }.tracks.isEmpty())
    }

    @Test fun seedOnlyRadioFollowsTheAutomixAndKeepsOnlyPlayableNewTracksInOrder() = runBlocking {
        val automix = JSONObject().put("automixPreviewVideoRenderer", JSONObject().put("content", JSONObject()
            .put("automixPlaylistVideoRenderer", JSONObject().put("navigationEndpoint", JSONObject()
                .put("watchPlaylistEndpoint", JSONObject().put("playlistId", "RDAMVMmix_001"))))))
        val visited = mutableListOf<YouTubeRadioRequest>()
        val result = loadYouTubeRadioQueue("abcdefghijk", setOf("yt_01234567890"), 2) { request ->
            visited += request
            if (request.videoId != null) panel(row("abcdefghijk"), automix)
            else panel(row("abcdefghijk"), row("01234567890"), row("00000000001", playable = false), row("lmnopqrstuv"),
                JSONObject().put("playlistPanelVideoWrapperRenderer", JSONObject().put("primaryRenderer", row("12345678901"))))
        }
        assertEquals(listOf(YouTubeRadioRequest("abcdefghijk", "RDAMVMabcdefghijk"),
            YouTubeRadioRequest(null, "RDAMVMmix_001")), visited)
        assertEquals(listOf("yt_lmnopqrstuv", "yt_12345678901"), result.map { it.id })
    }

    @Test fun seedOnlyRadioUsesAnAvailableContinuationAndStopsOnRepeatedTokens() = runBlocking {
        val visited = mutableListOf<YouTubeRadioRequest>()
        val result = loadYouTubeRadioQueue("abcdefghijk", emptySet(), 10) { request ->
            visited += request
            panel(row("abcdefghijk"), continuation = "next-page")
        }
        assertTrue(result.isEmpty())
        assertEquals(listOf(null, "next-page"), visited.map { it.continuation })
    }

    private fun row(id: String, playable: Boolean = true) = JSONObject().put("playlistPanelVideoRenderer",
        JSONObject().put("videoId", id).put("isPlayable", playable)
            .put("title", JSONObject().put("simpleText", "Song $id")))

    private fun panel(vararg rows: JSONObject, continuation: String? = null): String {
        val panel = JSONObject().put("contents", JSONArray(rows.toList()))
        if (continuation != null) panel.put("continuations", JSONArray().put(
            JSONObject().put("nextRadioContinuationData", JSONObject().put("continuation", continuation))))
        return JSONObject().put("playlistPanelRenderer", panel).toString()
    }
}
