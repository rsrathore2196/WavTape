package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CatalogDeduplicationRegressionTest {
    private val repository = CatalogRepository()
    private fun song(id: String, title: String, code: String) =
        MusicTrack(id, title, "Singer", "Album", 180_000, "", "", isrc = code)

    @Test fun rejectedDuplicateIdCannotHideAnotherSongsTitleOrRecordingCode() {
        val first = song("first", "First Song", "USABC2400001")
        val rejected = song(first.id, "Second Song", "USABC2400002")
        val distinct = rejected.copy(id = "second")
        assertEquals(listOf(first, distinct), repository.uniqueTracks(listOf(first, rejected, distinct)))
    }

    @Test fun rejectedRecordingAliasCannotHideADifferentRecordingWithItsTitle() {
        val first = song("first", "First Song", "USABC2400001")
        val rejected = song("alias", "Second Song", first.isrc)
        val distinct = song("second", rejected.title, "USABC2400002")
        assertEquals(listOf(first, distinct), repository.uniqueTracks(listOf(first, rejected, distinct)))
    }

    @Test fun rejectedTitleAliasCannotReserveTheIdentityOfALaterDistinctTrack() {
        val first = song("first", "First Song", "USABC2400001")
        val rejected = song("second", first.title, "USABC2400002")
        val distinct = rejected.copy(title = "Second Song")
        assertEquals(listOf(first, distinct), repository.uniqueTracks(listOf(first, rejected, distinct)))
    }

    @Test fun acceptedRowsStillDeduplicateTheirIdsTitlesAndRecordingCodes() {
        val first = song("first", "First Song", "USABC2400001")
        val distinct = song("second", "Second Song", "USABC2400002")
        val rows = listOf(first, first.copy(title = "Changed"), first.copy(id = "title-alias"),
            first.copy(id = "recording-alias", title = "Other upload title"), distinct)
        assertEquals(listOf(first, distinct), repository.uniqueTracks(rows))
    }
}
