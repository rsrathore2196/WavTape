package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogArtist
import com.example.data.repository.TrackMetadataResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrackMetadataRecordingTest {
    private val recording = MusicTrack("yt_abcdefghijk", "City Lights", "Main Singer", "City Lights",
        180_000L, "", "")
    private val credits = listOf(CatalogArtist("", "Main Singer"))

    @Test fun differentRecordingVersionsCannotSupplyArtworkOrCredits() {
        val modified = recording.copy(originalProviderTitle = "City Lights (Slowed + Reverb)")
        assertNull(TrackMetadataResolver.metadataMatchScore(modified, credits, recording))
        assertNull(TrackMetadataResolver.metadataMatchScore(recording, credits, modified))
        assertNotNull(TrackMetadataResolver.metadataMatchScore(recording, credits, recording.copy(id = "other")))
    }

    @Test fun guessesWithoutArtistOrDurationCannotSupplyMetadata() {
        val unknown = recording.copy(artist = "Unknown", durationMs = 0)
        assertNull(TrackMetadataResolver.metadataMatchScore(unknown, emptyList(), recording))
    }

    @Test fun initialCreditsPreserveWholeArtistEntitiesAndRetainOtherSingers() {
        val track = recording.copy(primaryArtists = listOf("Earth, Wind & Fire"),
            artist = "Earth, Wind & Fire, Guest Singer",
            singers = "Earth, Wind & Fire, Guest Singer")
        assertEquals(listOf("Earth, Wind & Fire", "Guest Singer"),
            TrackMetadataResolver.initial(track).artists.map { it.name })
        assertEquals(listOf("Earth, Wind & Fire"),
            TrackMetadataResolver.initial(track.copy(singers = "")).artists.map { it.name })
    }
}
