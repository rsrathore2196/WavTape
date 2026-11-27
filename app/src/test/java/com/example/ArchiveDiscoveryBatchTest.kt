package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.remote.InternetArchiveApiService
import com.example.data.remote.ArchiveFileTransport
import com.example.playback.LosslessDiagnostics.Reason
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArchiveDiscoveryBatchTest {
    private val selected = MusicTrack("online_song", "Song", "Artist", "Album", 180000, "", "")
    private val recording = selected.copy(id = "ia_album:song.flac", source = "INTERNET_ARCHIVE",
        audioUrl = "https://archive.org/download/album/song.flac", isLossless = true)
    private val documents = listOf(JSONObject("""{"identifier":"verified"}"""), JSONObject("""{"identifier":"slow"}"""))

    @Test fun aVerifiedFileDoesNotWaitForASlowUnrelatedCollectionAndCancelsItsRequest() = runTest {
        val slowEntered = CompletableDeferred<Unit>()
        var cancelled = false
        val result = InternetArchiveApiService.collectRecordingFiles(selected, documents) { doc ->
            if (doc.getString("identifier") == "verified") {
                slowEntered.await()
                listOf(recording)
            } else {
                slowEntered.complete(Unit)
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        assertEquals(listOf(recording), result.filterNotNull().flatten())
        assertTrue(cancelled)
    }
    @Test fun aRejectedRepresentationCannotStopTheSearchBeforeAnotherVerifiedCopyArrives() = runTest {
        val result = InternetArchiveApiService.collectRecordingFiles(selected, documents,
            excludedUrls = setOf(recording.audioUrl)) { doc ->
            if (doc.getString("identifier") == "verified") listOf(recording)
            else { delay(50); listOf(recording.copy(audioUrl = "https://archive.org/download/other/song.flac")) }
        }
        assertEquals(2, result.filterNotNull().flatten().size)
        assertTrue(result.filterNotNull().flatten().any { it.audioUrl != recording.audioUrl })
    }

    private fun index(total: Int, vararg identifiers: String) = JSONObject().put("response", JSONObject().apply {
        put("numFound", total)
        put("docs", org.json.JSONArray(identifiers.map { JSONObject().put("identifier", it) }))
    })

    @Test fun browseMergesBothBoundedIndexesAndReadsManifestsWithoutAnItemFormat() = runTest {
        val expressions = mutableListOf<String>()
        val page = InternetArchiveApiService.discoverItemPage("\"song\"", targeted = false, page = 1, rows = 2) {
            expressions += it
            if ("format:" in it) index(1, "shared")
            else index(3, "shared", "manifest-only", "beyond-budget")
        }
        assertEquals(setOf(true, false), expressions.map { "format:" in it }.toSet())
        assertEquals(listOf("shared", "manifest-only"), page.items.map { it.getString("identifier") })
        assertEquals(2, page.pageAfter(1))
        val files = InternetArchiveApiService.collectRecordingFiles(null, page.items) { doc ->
            val manifest = JSONObject("""{"metadata":{"title":"Song","creator":"Artist","rights":"public domain"},
                "files":[{"name":"01.flac","title":"Song","artist":"Artist","duration":"180"}]}""")
            InternetArchiveApiService.parseLosslessItem(doc, manifest)
        }.filterNotNull().flatten()
        assertEquals(listOf("ia_shared:01.flac", "ia_manifest-only:01.flac"), files.map { it.id })
        val second = InternetArchiveApiService.discoverItemPage("\"song\"", targeted = false, page = 2, rows = 2) {
            if ("format:" in it) index(1) else index(3, "last")
        }
        assertEquals(listOf("last"), second.items.map { it.getString("identifier") })
        assertNull(second.pageAfter(2))
    }

    @Test fun rateLimitedLaneKeepsSuccessAndRetriesTheSamePage() = runTest {
        val page = InternetArchiveApiService.discoverItemPage("song", targeted = false, page = 2, rows = 2) {
            if ("format:" in it) index(6, "indexed") else throw ArchiveFileTransport.Failure("rate_limited", 429)
        }
        assertNotNull(page.failure)
        assertEquals("indexed", page.items.single().getString("identifier"))
        assertEquals(2, page.pageAfter(2))
    }

    @Test fun targetedSearchKeepsSingleLaneAndCancellationStopsBothBrowseLanes() = runTest {
        var targetedCalls = 0
        val malformed = InternetArchiveApiService.discoverItemPage("creator:\"artist\"", targeted = true, page = 1, rows = 4) {
            targetedCalls++
            assertFalse("format:" in it)
            JSONObject()
        }
        assertEquals(1, targetedCalls)
        assertEquals(Reason.METADATA_INCOMPLETE, malformed.failure)
        var entered = 0
        var cancelled = 0
        val ready = CompletableDeferred<Unit>()
        val browse = launch {
            InternetArchiveApiService.discoverItemPage("song", targeted = false, page = 1, rows = 2) {
                if (++entered == 2) ready.complete(Unit)
                try { awaitCancellation() } finally { cancelled++ }
            }
        }
        ready.await()
        browse.cancelAndJoin()
        assertEquals(2, cancelled)
    }
}
