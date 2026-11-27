package com.example.data.remote

import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogNames
import com.example.data.repository.RecordingIdentity

/** Archive manifests often omit durations. Title, artist and version identify the recording. */
internal object ArchiveRecordingIdentity {
    private val soundtrackCredit = Regex("""(?i)\s*[\[(]\s*from\s+(?:"[^"]+"|'[^']+'|“[^”]+”|‘[^’]+’)\s*[\])]\s*$""")

    private fun recordingTrack(track: MusicTrack): MusicTrack {
        var title = track.fullTitle
        val prefix = title.substringBefore(" - ", "")
        // Video titles can include any one performer before the song name.
        if (prefix.isNotBlank() && RecordingIdentity.artistNames(track).any { CatalogNames.same(prefix, it) })
            title = title.substringAfter(" - ")
        title = title.replace(soundtrackCredit, "").trim()
        return if (title == track.fullTitle) track else track.copy(title = title, originalProviderTitle = "")
    }

    fun title(track: MusicTrack): String = RecordingIdentity.title(recordingTrack(track))

    fun confidence(requested: MusicTrack, candidate: MusicTrack): Int? {
        if (ArchiveFileEvidence.isLiteralSelection(requested) || ArchiveFileEvidence.isLiteralSelection(candidate)) return null
        val a = RecordingIdentity.signature(recordingTrack(requested))
        val b = RecordingIdentity.signature(recordingTrack(candidate))
        if (!hasCredits(a) || !hasCredits(b) || a.versions != b.versions || isrcConflict(a.isrc, b.isrc)) return null
        if (a.language !in unknownLanguages && b.language !in unknownLanguages && a.language != b.language) return null

        val exactTitle = a.title.replace(" ", "") == b.title.replace(" ", "")
        val exactArtist = a.artists.any { it in b.artists }
        if (!exactTitle && !CatalogNames.spellingEquivalent(a.title, b.title)) return null
        if (!exactArtist && !artistsAgree(a.artists, b.artists)) return null
        val sameCode = a.isrc != null && a.isrc == b.isrc
        if (!exactTitle && !exactArtist && !sameCode) return null

        // Some Archive collections advertise a different version only in their album title.
        val album = b.album.orEmpty()
        if (("live" !in a.versions && (album.startsWith("live at ") || album.endsWith(" live"))) ||
            ("remaster" !in a.versions && "remaster" in album)) return null
        val sameAlbum = a.album != null && a.album == b.album
        return (if (sameCode) 150 else 0) + (if (exactTitle) 50 else 25) +
            (if (exactArtist) 40 else 25) + (if (sameAlbum) 10 else 0)
    }

    fun hasCredits(track: MusicTrack): Boolean = hasCredits(RecordingIdentity.signature(track))

    private fun hasCredits(signature: RecordingIdentity.Signature): Boolean =
        signature.title.isNotBlank() && signature.artists.isNotEmpty()

    // Archive and the catalog may list different contributors for the same recording.
    fun artistsAgree(first: List<String>, second: List<String>): Boolean =
        first.any { a -> second.any { b -> CatalogNames.spellingEquivalent(a, b) } }

    fun artistCredits(value: String): List<String> = if (value.isBlank()) emptyList() else
        RecordingIdentity.artistNames(MusicTrack(id = "", title = "", artist = value,
            album = "", durationMs = 0L, coverUrl = "", audioUrl = ""))

    fun artistsConflict(first: List<String>, second: List<String>): Boolean =
        first.isNotEmpty() && second.isNotEmpty() && !artistsAgree(first, second)

    fun isrcConflict(first: String?, second: String?): Boolean = first != null && second != null && first != second

    fun key(track: MusicTrack): String = RecordingIdentity.signature(track).let { identity ->
        listOf(identity.title, identity.artists.joinToString("|"), identity.versions.sorted().joinToString("|"),
            identity.album.orEmpty(), identity.isrc.orEmpty(), identity.language).joinToString("\u0000")
    }

    private val unknownLanguages = setOf("", "unknown", "und")
}
