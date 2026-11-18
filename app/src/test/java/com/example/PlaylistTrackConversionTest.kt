package com.example

import android.app.Application
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaylistTrackConversionTest {
    private val playlist = PlaylistEntity(playlistId = 7L, title = "Repeated songs")
    private val first = TrackEntity("first", "First Song", "Singer", "Album", 180_000, "cover", "audio",
        isLiked = true, isCached = true, writer = "Writer", source = "YouTube Music",
        primaryArtists = "[\"Singer\",\"Guest\"]")
    private val second = first.copy(id = "second", title = "Second Song", primaryArtists = "[\"Other Singer\"]")

    @Test fun repeatedEntriesKeepTheirOrderAndIdentityWhileReusingPreparedMetadata() {
        val refs = listOf(
            PlaylistTrackCrossRef(7, first.id, 2, 25),
            PlaylistTrackCrossRef(7, second.id, 1, 20),
            PlaylistTrackCrossRef(7, first.id, 0, 10),
            PlaylistTrackCrossRef(7, first.id, 2, 24),
            PlaylistTrackCrossRef(7, "missing", 3, 30)
        )
        val actual = PlaylistWithTracks(playlist, listOf(first, second), refs).musicTracks
        assertEquals(listOf(first.id, second.id, first.id, first.id), actual.map { it.id })
        assertEquals(listOf(10L, 20L, 24L, 25L), actual.map { it.playlistEntryId })
        assertEquals(listOf(first, second, first, first).mapIndexed { index, row ->
            row.toMusicTrack().copy(playlistEntryId = listOf(10L, 20L, 24L, 25L)[index])
        }, actual)
        assertSame("Repeated metadata is parsed once, while entry-specific track copies stay independent",
            actual[0].primaryArtists, actual[2].primaryArtists)
        assertSame(actual[2].primaryArtists, actual[3].primaryArtists)
        assertNotSame(actual[0], actual[2])
    }

    @Test fun conflictingRowsWithOneIdStillUseTheLastCompleteRowWithoutMixingMetadata() {
        val updated = first.copy(title = "Updated", writer = "New writer", isLiked = false,
            primaryArtists = "[\"Updated singer\"]")
        val refs = listOf(PlaylistTrackCrossRef(7, first.id, 0, 10), PlaylistTrackCrossRef(7, first.id, 1, 11))
        val actual = PlaylistWithTracks(playlist, listOf(first, updated), refs).musicTracks
        assertEquals(listOf(updated.toMusicTrack().copy(playlistEntryId = 10), updated.toMusicTrack().copy(playlistEntryId = 11)), actual)
        assertSame(actual[0].primaryArtists, actual[1].primaryArtists)
    }

    @Test fun olderCollectionsWithoutEntryRowsKeepTheirOriginalTrackOrderAndMetadata() {
        val content = PlaylistWithTracks(playlist, listOf(second, first, first.copy(title = "Alternate metadata")))
        assertEquals(content.tracks.map { it.toMusicTrack() }, content.musicTracks)
        assertSame(content.musicTracks, content.musicTracks)
    }
}
