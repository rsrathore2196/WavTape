package com.example

import android.app.Application
import com.example.data.remote.InternetArchiveApiService
import com.example.data.remote.ArchiveAudioMetadata
import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import com.example.playback.LosslessDiagnostics.Reason
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArchiveMetadataRegressionTest {
    private val doc = JSONObject("""{"identifier":"album"}""")
    @Test fun collectionFilesUseTheirOwnTitleArtistAndAlternativeDurationTags() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Whole Album","creator":"Various Artists",
            "length":"3600","rights":"public domain"},"files":[
            {"name":"first.flac","format":"Flac","track_title":"First Song","album_artist":"Artist One","duration":"180"},
            {"name":"second.flac","format":"Flac","song_title":"Second Song","artist":"Artist Two","runtime":"3:02"}
        ]}""")
        val result = InternetArchiveApiService.parseLosslessItem(doc, root)
        assertEquals(listOf("First Song", "Second Song"), result.map { it.title })
        assertEquals(listOf("Artist One", "Artist Two"), result.map { it.artist })
        assertEquals(listOf(180000L, 182000L), result.map { it.durationMs })
        assertEquals(2, result.map { it.id }.distinct().size)
        assertTrue(result.all { it.isLossless })
    }
    @Test fun collectionDurationAndDescriptiveFilenamesCannotInventIndividualRecordings() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Album","creator":"Artist","length":"3600",
            "rights":"public domain"},"files":[
            {"name":"Artist - Song.flac","format":"Flac","length":"180"},
            {"name":"another.flac","format":"Flac","title":"Another Song"}
        ]}""")
        val namedFile = InternetArchiveApiService.parseLosslessItem(doc, root, readHeader = { null }).single()
        assertEquals("Another Song", namedFile.title)
        assertEquals(0L, namedFile.durationMs)
    }
    @Test fun licenseTextDoesNotDeterminePlaybackEligibility() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Single Song","creator":"Artist","duration":"181"},
            "files":[{"name":"song.flac","format":"Flac"}]}""")
        assertEquals(181000L, InternetArchiveApiService.parseLosslessItem(doc, root, readHeader = { null }).single().durationMs)
        root.getJSONObject("metadata").put("rights", "all rights reserved")
        root.getJSONObject("metadata").put("licenseurl", "https://example.org/unknown-license")
        root.getJSONArray("files").getJSONObject(0).put("rights", "all rights reserved")
        root.getJSONArray("files").getJSONObject(0).put("licenseurl", "unknown")
        val candidate = InternetArchiveApiService.parseLosslessItem(doc, root, readHeader = { null }).single()
        assertEquals("ia_album:song.flac", candidate.id)
    }

    @Test fun missingCollectionFileTagsAreRecoveredFromTheActualFlacHeader() = runBlocking {
        val request = MusicTrack("online_song", "Song", "Artist", "Original Album", 180000, "", "")
        val root = JSONObject("""{"metadata":{"title":"Original Album","creator":"Various Artists",
            "rights":"public domain"},"files":[{"name":"01.flac","format":"Flac"}]}""")
        var probes = 0
        val recovered = InternetArchiveApiService.parseLosslessItem(doc, root, request) { url ->
            assertEquals("https://archive.org/download/album/01.flac", url)
            probes++
            ArchiveAudioMetadata.Tags("Song", "Artist", "Original Album", durationMs = 183000,
                sampleRate = 96000, bitDepth = 24, channels = 2)
        }.single()
        assertEquals(1, probes)
        assertTrue(StreamResolver.matchesLosslessUpgrade(request, recovered))
        assertEquals(183000L, recovered.durationMs)
        assertEquals("Song", recovered.title)
        assertEquals("Artist", recovered.artist)
        assertEquals("INTERNET_ARCHIVE", recovered.source)
    }
    @Test fun accessFlagsPreventProbesAndFilenamesCannotInventRecordingIdentity() = runBlocking {
        val request = MusicTrack("online_song", "Song", "Artist", "Album", 0, "", "")
        val root = JSONObject("""{"metadata":{"title":"Collection","creator":"Artist"},
            "files":[{"name":"Artist - Song.flac","format":"Flac"},{"name":"other.flac","format":"Flac"}]}""")
        val file = root.getJSONArray("files").getJSONObject(0)
        val key = com.example.data.remote.ArchiveFileKey.create("album", "Artist - Song.flac")!!
        for (place in listOf(root, root.getJSONObject("metadata"), file)) {
            for (flag in listOf("is_dark", "private", "access-restricted-item")) {
                place.put(flag, true)
                assertTrue(InternetArchiveApiService.parseLosslessItem(doc, root, exactFile = key, allowLiteral = true,
                    readHeader = { error("An inaccessible file must not fetch a header") }).isEmpty())
                place.remove(flag)
            }
        }
        assertTrue(InternetArchiveApiService.parseLosslessItem(doc, root, request) { null }.isEmpty())
    }

    @Test fun targetedAndExactMatchesUseEmbeddedDurationEvenWhenTheManifestDisagrees() = runBlocking {
        val request = MusicTrack("online_song", "Song", "Artist", "Album", 0, "", "")
        val root = JSONObject("""{"metadata":{"title":"Album","creator":"Artist"},
            "files":[{"name":"song.flac","title":"Song","format":"Flac"}]}""")
        val key = com.example.data.remote.ArchiveFileKey.create("album", "song.flac")!!
        val named = request.copy(id = key.id, audioUrl = key.url.toString(), durationMs = 330_000L)
        val unknown = InternetArchiveApiService.parseLosslessItem(doc, root, request, readHeader = { null }).single()
        assertEquals(0L, unknown.durationMs)
        // Missing file title forces metadata extraction even if a duration was reported.
        root.getJSONArray("files").getJSONObject(0).remove("title")
        root.getJSONArray("files").getJSONObject(0).put("length", "100")
        val actual = ArchiveAudioMetadata.Tags("Song", "Artist", durationMs = 251_000L)
        val direct = InternetArchiveApiService.parseLosslessItem(doc, root, named, exactFile = key,
            readHeader = { actual }).single()
        assertEquals(251_000L, direct.durationMs)
        assertTrue(InternetArchiveApiService.parseLosslessItem(doc, root, named, exactFile = key,
            readHeader = { actual.copy(title = "Song (Live)") }).isEmpty())
    }

    @Test fun structuredDiscoveryIncludesAlbumCreatorAndIsrcQueriesRatherThanOnlySongAndTerms() {
        val request = MusicTrack("song", "Song (Official Audio)", "Artist", "Original Album", 180000, "", "",
            isrc = "US-ABC-20-00001")
        val plans = InternetArchiveApiService.recordingQueries(request)
        assertTrue(plans.any { it == "creator:\"artist\"" })
        assertTrue(plans.any { it.contains("title:\"original album\"") })
        assertTrue(plans.any { it == "title:\"original album\"" })
        assertTrue(plans.any { it.contains("USABC2000001") })
        assertFalse(plans.any { it.contains("official audio", true) })
    }

    @Test fun oneSharedArtistCreditWorksAcrossTheIndexManifestAndFlacTags() = runBlocking {
        val request = MusicTrack("yt_song", "Tu Juliet Jatt Di", "Diljit Dosanjh, Bunny", "Album", 180_000, "", "",
            primaryArtists = listOf("Diljit Dosanjh", "Bunny"))
        val root = JSONObject("""{"metadata":{"title":"Album","creator":"Various Artists"},"files":[
            {"name":"song.flac","title":"Tu Juliet Jatt Di","artist":["Another Performer","Bunny"],"format":"Flac"}
        ]}""")
        assertTrue(InternetArchiveApiService.recordingQueries(request).contains("creator:(\"diljit dosanjh\" OR \"bunny\")"))

        val header = ArchiveAudioMetadata.Tags(request.title, "BUNNY", durationMs = 180_000)
        val file = InternetArchiveApiService.parseLosslessItem(doc, root, request, readHeader = { header }).single()
        assertEquals("ia_album:song.flac", file.id)
        assertEquals("BUNNY", file.artist)
        val exact = com.example.data.remote.ArchiveFileKey.fromId(file.id)!!
        assertEquals(file, InternetArchiveApiService.parseLosslessItem(doc, root,
            request.copy(id = file.id, audioUrl = file.audioUrl), exactFile = exact, readHeader = { header }).single())
        assertTrue(InternetArchiveApiService.parseLosslessItem(doc, root, request,
            readHeader = { header.copy(artist = "Unrelated Performer") }).isEmpty())
    }

    @Test fun incompleteIndividualTagsAreNotReportedAsAConfirmedEmptyCatalogue() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Collection","creator":"Various Artists","rights":"public domain"},
            "files":[{"name":"track01.flac","format":"Flac"},{"name":"track02.flac","format":"Flac"}]}""")
        val rejected = mutableListOf<Pair<com.example.playback.LosslessDiagnostics.Reason, String>>()
        assertTrue(InternetArchiveApiService.parseLosslessItem(doc, root,
            onRejected = { reason, detail -> rejected.add(reason to detail) }, readHeader = { null }).isEmpty())
        assertEquals(2, rejected.size)
        assertTrue(rejected.all { it.first == com.example.playback.LosslessDiagnostics.Reason.METADATA_INCOMPLETE })
        assertTrue(rejected.first().second.contains("track01.flac"))
    }
    @Test fun archiveAccessDenialMissingFilesAndTimeoutsHaveDifferentDiagnosticOutcomes() {
        for (status in listOf(401, 403, 451))
            assertEquals(Reason.UNAUTHORIZED, InternetArchiveApiService.archiveFailureReason(com.example.data.remote.CatalogHttpException(status, null)))
        assertEquals(Reason.SOURCE_VERIFICATION_FAILED, InternetArchiveApiService.archiveFailureReason(com.example.data.remote.CatalogHttpException(404, null)))
        assertEquals(Reason.NETWORK_TIMEOUT, InternetArchiveApiService.archiveFailureReason(java.net.SocketTimeoutException()))
    }
    @Test fun verifiedFileDoesNotWaitForUnrelatedAlbumHeaders() = runBlocking {
        val request = MusicTrack("yt_song", "Song", "Artist", "Album", 180000, "", "")
        val root = JSONObject("""{"metadata":{"title":"Album","creator":"Artist","rights":"public domain"},"files":[
            {"name":"01 Song.flac","format":"Flac","title":"Song","artist":"Artist","length":"180"},
            {"name":"02.flac","format":"Flac"}
        ]}""")
        val result = kotlinx.coroutines.withTimeoutOrNull(500) {
            InternetArchiveApiService.parseLosslessItem(doc, root, request) { kotlinx.coroutines.awaitCancellation() }
        }
        assertNotNull("An unrelated slow header must not lose an already verified recording", result)
        assertEquals("ia_album:01 Song.flac", result!!.single().id)
    }

    @Test fun explicitFileVerificationStillReachesTheSelectedCopy() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Album","creator":"Artist","rights":"public domain"},"files":[
            {"name":"first.flac","format":"Flac","title":"Song","artist":"Artist","length":"180"},
            {"name":"selected.flac","format":"Flac","title":"Song","artist":"Artist","length":"180"}
        ]}""")
        val request = MusicTrack("ia_album:selected.flac", "Song", "Artist", "Album", 180000, "", "")
        val result = InternetArchiveApiService.parseLosslessItem(doc, root, request)
        assertTrue(result.any { it.id == request.id })
        assertEquals(2, InternetArchiveApiService.parseLosslessItem(doc, root).size)
    }

    @Test fun aRejectedFileDoesNotHideAnotherVerifiedFlacCopy() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Album","creator":"Artist","rights":"public domain"},"files":[
            {"name":"first.flac","format":"Flac","title":"Song","artist":"Artist","length":"180"},
            {"name":"second.flac","format":"Flac","title":"Song","artist":"Artist","length":"180"}
        ]}""")
        val request = MusicTrack("yt_song", "Song", "Artist", "Album", 180000, "", "")
        val rejected = "https://archive.org/download/album/first.flac"
        val result = InternetArchiveApiService.parseLosslessItem(doc, root, request, excludedUrls = setOf(rejected))
        assertEquals("ia_album:second.flac", result.single().id)
    }

    @Test fun maximumResolverReceivesVerifiedFlacBeforeAnUnrelatedHeaderCanTimeout() = runBlocking {
        val request = MusicTrack("yt_song", "Song", "Artist", "Album", 180000, "", "")
        val root = JSONObject("""{"metadata":{"title":"Album","creator":"Artist","rights":"public domain"},"files":[
            {"name":"01 Song.flac","format":"Flac","title":"Song","artist":"Artist","length":"180"},
            {"name":"02.flac","format":"Flac"}
        ]}""")
        val policy = com.example.playback.PlaybackSourcePolicy(com.example.playback.AudioQualityCeiling.MAX,
            sources = listOf(com.example.playback.PlaybackSource.archive, com.example.playback.PlaybackSource.youtube))
        val resolver = StreamResolver(supportsLossless = { true }, resolveYouTube = { _, _, _, _ -> null },
            searchYouTube = { emptyList() }, findLossless = { track, excluded, _ ->
                InternetArchiveApiService.LosslessSearchPage(InternetArchiveApiService.parseLosslessItem(
                    doc, root, track, excludedUrls = excluded) { kotlinx.coroutines.awaitCancellation() })
            })
        try {
            val resolved = kotlinx.coroutines.withTimeout(2_000) {
                resolver.resolve(request, policy.ceiling.audioQuality, sourcePolicy = policy)
            }
            assertEquals(request.id, resolved.id)
            assertEquals("https://archive.org/download/album/01%20Song.flac", resolved.audioUrl)
            assertEquals("INTERNET_ARCHIVE", resolved.source)
            assertTrue(resolved.isLossless)
        } finally { resolver.clear() }
    }

    @Test fun playbackFindsEmbeddedAlbumTrackAndPrefersFlacOverOriginalWav() = runBlocking {
        val request = MusicTrack("yt_backed_off", "Backed Off", "selene XIV", "", 179_970L, "", "")
        val root = JSONObject("""{"metadata":{"title":"selene XIV - Late Surrender","creator":"selene XIV",
            "licenseurl":"https://creativecommons.org/licenses/by/4.0/"},"files":[
            {"name":"catboi-album1.wav","source":"original","format":"WAVE","length":"179.97","title":"Backed Off","artist":"selene XIV"},
            {"name":"catboi-album1.flac","source":"derivative","original":"catboi-album1.wav","format":"Flac","length":"179.97"}]}""")
        val queries = InternetArchiveApiService.recordingQueries(request)
        assertEquals("(\"backed off\" AND \"selene xiv\")", queries.first())
        val checked = mutableListOf<String>()
        val chosen = InternetArchiveApiService.parseLosslessItem(doc, root, request, validateCandidate = { file ->
            checked += file.audioUrl
            true
        }, readHeader = { ArchiveAudioMetadata.Tags(durationMs = 179_970L, sampleRate = 44100, bitDepth = 16) })
        assertEquals("ia_album:catboi-album1.flac", chosen.single().id)
        assertEquals(listOf(chosen.single().audioUrl), checked)
    }

    @Test fun archiveFlacDerivativesUseOnlyTheirExplicitAccessibleOriginal() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Late Surrender","creator":"selene XIV",
            "rights":"all rights reserved"},"files":[
            {"name":"catboi-album3.flac","source":"derivative","original":"catboi-album3.wav","format":"Flac","length":"244.83"},
            {"name":"catboi-album3.wav","source":"original","format":"WAVE","length":"220","title":"Back Counting","artist":"selene XIV","rights":"all rights reserved"},
            {"name":"unlinked.flac","format":"Flac","length":"244.83"}
        ]}""")
        val candidates = InternetArchiveApiService.parseLosslessItem(doc, root, readHeader = { null })
        assertEquals(listOf("ia_album:catboi-album3.flac", "ia_album:catboi-album3.wav"), candidates.map { it.id })
        assertTrue(candidates.all { it.title == "Back Counting" && it.artist == "selene XIV" })
        assertEquals(listOf(244830L, 220000L), candidates.map { it.durationMs })
        root.getJSONArray("files").getJSONObject(1).put("private", "1")
        assertTrue(InternetArchiveApiService.parseLosslessItem(doc, root, readHeader = { null }).isEmpty())
    }

    @Test fun exactBrowseCanShowAFileWithoutMakingItsNameRecordingEvidence() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Collection","creator":"Artist","rights":"public domain"},"files":[
            {"name":"track01.flac","format":"Flac","length":"120"},
            {"name":"track02.flac","format":"Flac","length":"120","title":"Song"}]}""")
        val selected = com.example.data.remote.ArchiveFileKey.create("album", "track01.flac")!!
        val result = InternetArchiveApiService.parseLosslessItem(doc, root, exactFile = selected,
            allowLiteral = true, readHeader = { null }).single()
        assertEquals("track01.flac", result.title)
        assertTrue(com.example.data.remote.ArchiveFileEvidence.isLiteralSelection(result))
        assertEquals(selected.id, result.id)
        assertFalse(StreamResolver.matchesLosslessUpgrade(result.copy(id = "yt_ordinarySong", title = "Song"), result))
        root.put("is_dark", true)
        assertTrue(InternetArchiveApiService.parseLosslessItem(doc, root, exactFile = selected, allowLiteral = true) {
            error("A dark item must be rejected before file probing")
        }.isEmpty())
    }

    @Test fun mismatchedEmbeddedAudioIsRejectedAndTheNextVerifiedCopyCanWin() = runBlocking {
        val request = MusicTrack("online_song", "Song", "Artist", "Album", 180000, "", "")
        val root = JSONObject("""{"metadata":{"title":"Album","rights":"public domain"},"files":[
            {"name":"first.flac","format":"Flac","title":"Song","artist":"Artist","length":"180"},
            {"name":"second.flac","format":"Flac","title":"Song","artist":"Artist","length":"180"}]}""")
        val attempts = mutableListOf<String>()
        val result = InternetArchiveApiService.parseLosslessItem(doc, root, request,
            validateCandidate = { candidate ->
                attempts += candidate.id
                candidate.id == "ia_album:second.flac"
            }).single()
        assertEquals(listOf("ia_album:first.flac", "ia_album:second.flac"), attempts)
        assertEquals("ia_album:second.flac", result.id)
    }


    @Test fun browseSpendsLimitedHeaderProbesOnMatchingFileHintsWithoutTrustingTheFilename() = runBlocking {
        val manifest = JSONObject("""{"metadata":{"title":"Collection","creator":"Various Artists","rights":"public domain"},"files":[
            {"name":"01.flac"},{"name":"02.flac"},{"name":"03.flac"},{"name":"04.flac"},{"name":"Missing Song.flac"}]}""")
        val firstOnly = mutableListOf<String>()
        val result = InternetArchiveApiService.parseLosslessItem(doc, manifest, browseTerms = listOf("missing", "song")) { url ->
            if (firstOnly.isNotEmpty()) null else {
                firstOnly += url
                ArchiveAudioMetadata.Tags("Verified Title", "Verified Artist", durationMs = 182_000)
            }
        }
        assertEquals(listOf("https://archive.org/download/album/Missing%20Song.flac"), firstOnly)
        assertEquals("Verified Title", result.single().title)
        assertTrue(InternetArchiveApiService.parseLosslessItem(doc, manifest,
            browseTerms = listOf("missing", "song")) { null }.isEmpty())
    }

    @Test fun unsupportedAndFalselyLabeledFilesAreNeverPublishedAsPlayableLossless() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Song","creator":"Artist"},"files":[
            {"name":"song.aiff","title":"Song","format":"AIFF"},
            {"name":"song.mp3","title":"Song","format":"FLAC"},
            {"name":"aac-in-disguise.flac","title":"Song","format":"AAC"},
            {"name":"fake.flac","title":"Song","format":"FLAC"}]}""")
        val verified = mutableListOf<String>()
        val tracks = InternetArchiveApiService.parseLosslessItem(doc, root, validateCandidate = {
            verified += it.id
            ArchiveAudioMetadata.flac("not a FLAC file".toByteArray()) != null
        }, readHeader = { null })
        assertTrue(tracks.isEmpty())
        assertEquals(listOf("ia_album:fake.flac"), verified)
    }

    @Test fun genericOriginalM4aAppearsOnlyWhenItsActualCodecIsProven() = runBlocking {
        val manifest = JSONObject("""{"metadata":{"title":"Collection","creator":"Artist","rights":"public domain"},"files":[
            {"name":"track.m4a","title":"Song","source":"original","format":"MPEG4","length":"180"},
            {"name":"other.m4a","title":"Other","source":"original","format":"MPEG4","length":"181"},
            {"name":"known.m4a","title":"AAC","source":"original","format":"AAC","length":"180"},
            {"name":"sample.m4a","title":"Sample","source":"derivative","format":"MPEG4"}]}""")
        val examined = mutableListOf<String>()
        val results = InternetArchiveApiService.parseLosslessItem(doc, manifest, validateUnlabeledAlac = {
            examined += it.title
            it.title == "Song"
        })
        assertEquals(listOf("Song", "Other"), examined)
        assertEquals(listOf("ia_album:track.m4a"), results.map { it.id })
        val direct = com.example.data.remote.ArchiveFileKey.create("album", "sample.m4a")
        assertTrue(InternetArchiveApiService.parseLosslessItem(doc, manifest, exactFile = direct).isEmpty())
        assertEquals("ia_album:sample.m4a", InternetArchiveApiService.parseLosslessItem(doc, manifest,
            exactFile = direct, validateUnlabeledAlac = { true }).single().id)
    }
}
