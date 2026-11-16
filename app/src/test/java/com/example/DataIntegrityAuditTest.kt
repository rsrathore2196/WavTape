package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.RecommendationTrackCodec
import com.example.data.local.CustomLyricsManager
import com.example.data.model.MusicTrack
import com.example.data.repository.RecordingIdentity
import com.example.downloads.OfflineJson
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DataIntegrityAuditTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val song = MusicTrack("audit-song", "Song", "Artist", "Album", 180_000, "", "")

    @Test fun oneDamagedRecommendationDoesNotDiscardTheOtherCachedSongs() {
        val rows = JSONArray(RecommendationTrackCodec.encode(listOf(song)))
        rows.put(JSONObject().put("title", "Missing identity"))
        rows.put(JSONArray(RecommendationTrackCodec.encode(listOf(song.copy(id = "second")))).get(0))
        assertEquals(listOf(song.id, "second"), RecommendationTrackCodec.decode(rows.toString()).map { it.id })
    }

    @Test fun directHomeJsonContainersRetainMetadataAndTheYouTubeUrlPolicy() {
        val original = song.copy(id = "yt_abcdefghijk", audioUrl = "https://video.example/expired?signature=private",
            writer = "Writer", primaryArtists = listOf("Artist", "Guest"), originalProviderTitle = "Song (Club Mix)")
        val rows = RecommendationTrackCodec.encodeArray(listOf(original))
        assertFalse(rows.toString().contains("signature"))
        val restored = RecommendationTrackCodec.decode(rows).single()
        assertEquals(original.originalProviderTitle, restored.fullTitle)
        assertEquals(original.primaryArtists, restored.primaryArtists)
        assertEquals(original.writer, restored.writer)
        assertTrue(restored.audioUrl.isEmpty())
        assertTrue(RecommendationTrackCodec.decode(rows, 0).isEmpty())
    }

    @Test fun savedRecordingVersionsRemainDistinctWhenTheDisplayTitleWasShortened() {
        val slowed = song.copy(originalProviderTitle = "Song (Slowed + Reverb)")
        assertFalse(RecordingIdentity.same(song, slowed))
        assertNull(RecordingIdentity.providerConfidence(song, slowed))
        assertNull(RecordingIdentity.losslessConfidence(slowed, song))
        assertNotEquals(RecordingIdentity.key(song), RecordingIdentity.key(slowed))
    }

    @Test fun catalogDeduplicationKeepsDifferentVersionsEvenWithAReusedIsrc() {
        val original = song.copy(isrc = "USABC2400001")
        val slowed = original.copy(id = "slowed", originalProviderTitle = "Song (Slowed + Reverb)")
        val mix = original.copy(id = "mix", originalProviderTitle = "Song (Club Mix)")
        val tracks = listOf(original, slowed, mix, slowed.copy(id = "same-slowed-recording"))
        assertEquals(listOf(original.id, slowed.id, mix.id),
            com.example.data.repository.CatalogRepository().uniqueTracks(tracks).map { it.id })
    }

    @Test fun offlineMetadataRetainsWriterAndMotionArtworkAfterRestart() {
        val original = song.copy(writer = "Songwriter", motionArtworkUrl = "https://covers.example/motion.mp4")
        val restored = OfflineJson.track(OfflineJson.track(original))
        assertEquals(original.writer, restored.writer)
        assertEquals(original.motionArtworkUrl, restored.motionArtworkUrl)
    }

    @Test fun disablingImportedLyricsAlsoDisablesTheirFallbackForAnotherProvider() = runBlocking {
        assertTrue(CustomLyricsManager.saveCustomLyrics(app, song, "First line\nSecond line"))
        CustomLyricsManager.setCustomLyricsActive(app, song, false)
        val alias = song.copy(id = "another-provider")
        assertTrue(CustomLyricsManager.hasCustomLyrics(app, alias))
        assertFalse(CustomLyricsManager.isCustomLyricsActive(app, alias))
    }

    @Test fun importedLyricsRecoverTheirAtomicBackupAfterAnInterruptedWrite() = runBlocking {
        assertTrue(CustomLyricsManager.saveCustomLyrics(app, song, "Saved line\nAnother line"))
        val directory = File(app.filesDir, "custom_lyrics")
        val primary = File(directory, CustomLyricsManager.primaryKey(song.id, song.title, song.artist) + ".lrc")
        val fallback = File(directory, CustomLyricsManager.titleArtistKey(song.title, song.artist) + ".lrc")
        assertTrue(primary.renameTo(File(primary.path + ".bak")))
        assertTrue(fallback.delete())
        assertTrue(CustomLyricsManager.hasCustomLyrics(app, song))
        assertEquals("Saved line", CustomLyricsManager.getCustomLyrics(app, song)?.lines?.first()?.text)
    }

    @Test fun deletingImportedLyricsRemovesAtomicSidecarsAsWell() = runBlocking {
        assertTrue(CustomLyricsManager.saveCustomLyrics(app, song, "Saved line\nAnother line"))
        val directory = File(app.filesDir, "custom_lyrics")
        val primary = File(directory, CustomLyricsManager.primaryKey(song.id, song.title, song.artist) + ".lrc")
        File(primary.path + ".bak").writeText("Old saved line")
        File(primary.path + ".new").writeText("Uncommitted line")
        assertTrue(CustomLyricsManager.deleteCustomLyrics(app, song))
        assertFalse(File(primary.path + ".bak").exists())
        assertFalse(File(primary.path + ".new").exists())
        assertFalse(CustomLyricsManager.hasCustomLyrics(app, song))
    }

    @Test fun readingDuringAnImportNeverObservesAnIncompleteDocument() = runBlocking<Unit> {
        val first = "First complete document\n" + (1..2_000).joinToString("\n") { "Saved line $it" }
        val replacement = "Replacement complete document\n" + (1..2_000).joinToString("\n") { "New line $it" }
        assertTrue(CustomLyricsManager.saveCustomLyrics(app, song, first))
        coroutineScope {
            val writer = async(Dispatchers.IO) { repeat(4) {
                assertTrue(CustomLyricsManager.saveCustomLyrics(app, song, replacement))
            } }
            val reader = async(Dispatchers.IO) { repeat(12) {
                val lyrics = CustomLyricsManager.getCustomLyrics(app, song)
                assertNotNull(lyrics)
                assertEquals(2_001, lyrics!!.lines.size)
                assertTrue(lyrics.lines.first().text in setOf("First complete document", "Replacement complete document"))
            } }
            awaitAll(writer, reader)
        }
    }
}
