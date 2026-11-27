package com.example

import com.example.data.model.MusicTrack
import com.example.data.remote.ArchiveFileEvidence
import com.example.data.remote.ArchiveRecordingIdentity
import com.example.data.repository.RecordingIdentity
import com.example.data.repository.StreamResolver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArchiveRecordingIdentityTest {
    private val selected = MusicTrack("online_song", "Song (Official Audio)", "Artist", "Album", 180000, "", "")
    private val flac = selected.copy(id = "ia_album:song.flac", title = "Song", source = "INTERNET_ARCHIVE",
        audioUrl = "https://archive.org/download/album/song.flac", isLossless = true)
    @Test fun archiveRequiresTitleAndArtistButDoesNotRequireEitherDuration() {
        for (requested in listOf(selected, selected.copy(durationMs = 0))) {
            for (file in listOf(flac.copy(durationMs = 0), flac.copy(durationMs = 244_000))) {
                assertNotNull(ArchiveRecordingIdentity.confidence(requested, file))
            }
        }
        assertNull(RecordingIdentity.losslessConfidence(selected, flac.copy(durationMs = 244_000)))
        assertNull(RecordingIdentity.fallbackConfidence(selected, flac.copy(durationMs = 244_000)))
        assertNull(ArchiveRecordingIdentity.confidence(selected, flac.copy(artist = "Unknown Artist")))
        assertNull(ArchiveRecordingIdentity.confidence(selected, flac.copy(title = "Other Recording")))
    }

    @Test fun normalizedCreditsAndSmallSpellingVariationsStillMatch() {
        val request = selected.copy(title = "The Beautiful Song", artist = "A. COOPER", durationMs = 0)
        val tagged = flac.copy(title = "  The Beautifull Song ", artist = "A Cooper", durationMs = 0)
        assertNotNull(ArchiveRecordingIdentity.confidence(request, tagged))
        assertNull(ArchiveRecordingIdentity.confidence(request, tagged.copy(artist = "Other Artist")))
        assertNull(ArchiveRecordingIdentity.confidence(request.copy(title = "Song Part 1"), tagged.copy(title = "Song Part 2")))
    }

    @Test fun oneSharedCreditIsEnoughRegardlessOfArtistOrderOnlyForArchive() {
        val request = selected.copy(title = "Tu Juliet Jatt Di", artist = "Diljit Dosanjh, Bunny",
            primaryArtists = listOf("Diljit Dosanjh", "Bunny"))
        val file = flac.copy(title = request.title, artist = "DILJIT DOSANJH")
        assertTrue(StreamResolver.matchesInitialLossless(request, file))
        assertTrue(StreamResolver.matchesLosslessUpgrade(request, file.copy(artist = "Bunny")))

        val oneSharedCredit = file.copy(artist = "Another Performer, Bunny")
        assertTrue(StreamResolver.matchesLosslessUpgrade(request, oneSharedCredit))
        assertNull(RecordingIdentity.providerConfidence(request, oneSharedCredit))
        assertFalse(StreamResolver.matchesLosslessUpgrade(request, file.copy(artist = "Another Performer")))
        assertFalse(StreamResolver.matchesLosslessUpgrade(request, oneSharedCredit.copy(title = "Tu Juliet Jatt Di (Live)")))
    }

    @Test fun catalogTitleCreditsDoNotPreventMatchingTheArchiveRecording() {
        val request = selected.copy(title = "Song", artist = "Artist, Another Performer",
            originalProviderTitle = "Artist - Song (From ‘Album’)")
        assertNotNull(ArchiveRecordingIdentity.confidence(request, flac))
        assertNull(RecordingIdentity.providerConfidence(request, flac))
        assertNull(ArchiveRecordingIdentity.confidence(request, flac.copy(title = "Song (Live)")))
        assertNull(ArchiveRecordingIdentity.confidence(request.copy(originalProviderTitle = "Different Artist - Song"), flac))
        assertNull(ArchiveRecordingIdentity.confidence(request, flac.copy(artist = "Different Artist")))
    }

    @Test fun verifiedIsrcSupportsButCannotReplaceOrContradictNamedRecordings() {
        val request = selected.copy(isrc = "USABC2000001")
        assertNotNull(ArchiveRecordingIdentity.confidence(request, flac.copy(isrc = request.isrc, durationMs = 0)))
        assertNull(ArchiveRecordingIdentity.confidence(request, flac.copy(isrc = "USABC2000002")))
        assertNull(ArchiveRecordingIdentity.confidence(request, flac.copy(isrc = request.isrc, artist = "Other Artist")))
        assertNull(ArchiveRecordingIdentity.confidence(request, flac.copy(isrc = request.isrc, artist = "")))
        assertNull(ArchiveRecordingIdentity.confidence(request, flac.copy(isrc = request.isrc, title = "Song (Live)")))
    }

    @Test fun directSelectionAndVerifiedCacheUseTheSameDurationIndependentIdentity() {
        val exact = flac.copy(durationMs = 0)
        val actual = flac.copy(durationMs = 241_000)
        assertTrue(ArchiveFileEvidence.matchesSelection(exact, actual))
        assertFalse(ArchiveFileEvidence.matchesSelection(exact, actual.copy(title = "Song (Live)")))
        assertFalse(ArchiveFileEvidence.matchesSelection(exact, actual.copy(artist = "Different Artist")))
        assertFalse(ArchiveFileEvidence.matchesSelection(exact, actual.copy(audioUrl = "https://archive.org/download/album/other.flac")))
        try {
            StreamResolver.cacheVerifiedLosslessMatch(selected, actual)
            assertEquals(actual, StreamResolver.getCachedLosslessMatch(selected.copy(durationMs = 0)))
            assertEquals(actual, StreamResolver.getCachedLosslessMatch(selected.copy(durationMs = 123_000)))
            assertNull(StreamResolver.getCachedLosslessMatch(selected.copy(artist = "Other Artist")))
        } finally {
            StreamResolver.invalidateLosslessMatch(selected)
        }
    }

    @Test fun unsupportedVersionsSourcesAndContainersAreRejected() {
        for (version in listOf("Live", "Remix", "Cover", "Karaoke", "Instrumental", "Remastered"))
            assertFalse(StreamResolver.matchesLosslessUpgrade(selected, flac.copy(title = "Song ($version)")))
        assertFalse(StreamResolver.matchesLosslessUpgrade(selected, flac.copy(album = "Album (Remastered)")))
        assertFalse(StreamResolver.matchesLosslessUpgrade(selected, flac.copy(audioUrl = "https://other.example/song.flac")))
        assertFalse(StreamResolver.matchesLosslessUpgrade(selected, flac.copy(audioUrl = "https://archive.org/download/album/song.mp3")))
    }
}
