package com.example

import android.app.Application
import com.example.data.importer.*
import com.example.data.model.MusicTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class YouTubeFirstImportTest {
    private val original = ImportedTrackMeta("Exact Song", "Main Artist", "Album", 180_000)
    private val youtube = MusicTrack("yt_abcdefghijk", "Exact Song", "Main Artist", "Album", 180_000,
        "https://images.example/original.jpg", "", bitrateKbps = 0, source = "YouTube Music")
    private val jio = youtube.copy(id = "online_exact", audioUrl = "https://aac.saavncdn.com/song_320.mp4", source = "JIOSAAVN")

    @Test fun aConfidentYoutubeCatalogMatchVerifiesTheExactAudioBeforeSavingItsId() = runBlocking {
        val verified = mutableListOf<String>()
        val result = PlaylistImportEngine.matchTrack(original, forceRefresh = true,
            findYouTube = { _, _ -> listOf(youtube) }, findJio = { error("A primary catalog match needs no Jio lookup") },
            verifyYouTube = { id -> verified += id; true })
        assertEquals(MatchStatus.EXACT_MATCH, result.matchStatus)
        assertEquals(youtube.id, result.matchedTrack!!.id)
        assertEquals("", result.matchedTrack.audioUrl)
        assertEquals(youtube.coverUrl, result.matchedTrack.coverUrl)
        assertEquals(listOf("abcdefghijk"), verified)
    }

    @Test fun wrongVersionsAndDurationsInYoutubeFallBackToExactJioOnlyAfterYoutubeQueries() = runBlocking {
        val calls = mutableListOf<String>()
        val result = PlaylistImportEngine.matchTrack(original, forceRefresh = true,
            findYouTube = { _, _ -> calls += "youtube"; listOf(youtube.copy(title = "Exact Song (Live)"), youtube.copy(durationMs = 240_000)) },
            findYouTubeVideos = { _, _ -> emptyList() }, findJio = { calls += "jio"; listOf(jio) },
            verifyYouTube = { throw AssertionError("Mismatched recordings must not be probed") })
        assertEquals(MatchStatus.EXACT_MATCH, result.matchStatus)
        assertEquals(jio.id, result.matchedTrack!!.id)
        assertEquals("youtube", calls.first()); assertEquals("jio", calls.last())
    }

    @Test fun successfulCatalogMatchesAreCachedWithoutSignedAudioUrls() = runBlocking {
        val selected = original.copy(originalTitle = "Cached Exact Song")
        val candidate = youtube.copy(title = selected.originalTitle)
        var searches = 0
        PlaylistImportEngine.matchTrack(selected, forceRefresh = true,
            findYouTube = { _, _ -> searches++; listOf(candidate) }, findJio = { error("No fallback needed") }, verifyYouTube = { true })
        val cached = PlaylistImportEngine.matchTrack(selected,
            findYouTube = { _, _ -> error("Use the successful catalog match") }, findJio = { error("No fallback needed") },
            verifyYouTube = { throw AssertionError("Reuse the recent playback verification") })
        assertEquals(1, searches); assertEquals(candidate.id, cached.matchedTrack!!.id)
        assertEquals("", cached.matchedTrack.audioUrl)
    }

    @Test fun aKnownYoutubePlaylistIdAndCoverArePreservedEvenWithMissingArtistCredits() = runBlocking {
        val row = youtube.copy(artist = "Unknown", audioUrl = "https://rr.example/expired-signed-url")
        val result = PlaylistImportEngine.matchTrack(row.toYouTubeImportMetadata(), forceRefresh = true,
            findYouTube = { _, _ -> throw AssertionError("The playlist already supplied the exact ID") },
            findJio = { throw AssertionError("No fallback required") }, verifyYouTube = { it == "abcdefghijk" })
        assertEquals(MatchStatus.EXACT_MATCH, result.matchStatus)
        assertEquals("yt_abcdefghijk", result.matchedTrack!!.id)
        assertEquals(youtube.coverUrl, result.matchedTrack.coverUrl)
        assertEquals("", result.matchedTrack.audioUrl)
    }

    @Test fun unavailablePlaylistUploadsCanFindTheSameCustomVersionInYouTubeVideos() = runBlocking {
        val title = "Exact Song (Custom Reverb)"
        val requested = original.copy(originalTitle = title, youtubeVideoId = "abcdefghijk")
        val alternative = youtube.copy(id = "yt_zyxwvutsrqp", originalProviderTitle = title)
        val checks = mutableListOf<String>()
        val result = PlaylistImportEngine.matchTrack(requested, youtubeOnly = true, forceRefresh = true,
            findYouTube = { _, _ -> listOf(youtube.copy(id = "yt_01234567890")) },
            findYouTubeVideos = { _, _ -> listOf(alternative) },
            verifyYouTube = { id -> checks += id; id == "zyxwvutsrqp" })
        assertEquals(alternative.id, result.matchedTrack?.id)
        assertEquals(title, result.matchedTrack?.fullTitle)
        assertEquals(listOf("abcdefghijk", "zyxwvutsrqp"), checks)
    }
}
