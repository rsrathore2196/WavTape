package com.example.data.importer

import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogNames
import com.example.data.repository.RecordingIdentity
import java.text.Normalizer
import java.util.Locale

data class SavedPlaylistImport(val id: Long, val added: Int)

internal fun importedPlaylistName(raw: String): String = Normalizer.normalize(CatalogNames.clean(raw), Normalizer.Form.NFKC)
    .replace(Regex("[\\s\\u00a0]+"), " ").trim().lowercase(Locale.ROOT)

/** Skip an equivalent recording while retaining distinct remixes, reverbs and other edits. */
internal fun newImportedTracks(existing: List<MusicTrack>, incoming: List<MusicTrack>): List<MusicTrack> {
    val ids = existing.mapTo(mutableSetOf()) { it.id }
    val titles = mutableMapOf<String, MutableList<RecordingIdentity.Signature>>()
    val codes = mutableMapOf<String, MutableList<RecordingIdentity.Signature>>()
    fun remember(signature: RecordingIdentity.Signature) {
        titles.getOrPut(signature.title.replace(" ", "")) { mutableListOf() }.add(signature)
        signature.isrc?.let { codes.getOrPut(it) { mutableListOf() }.add(signature) }
    }
    existing.forEach { remember(RecordingIdentity.signature(it)) }
    return incoming.filter { track ->
        if (!ids.add(track.id)) return@filter false
        val signature = RecordingIdentity.signature(track)
        val title = signature.title.replace(" ", "")
        val candidates = titles[title].orEmpty() + signature.isrc?.let { codes[it] }.orEmpty()
        val duplicate = candidates.any { known ->
            val sameRelease = signature.album != null && signature.album == known.album
            val sameCode = signature.isrc != null && signature.isrc == known.isrc
            val bothTimed = signature.durationMs > 0 && known.durationMs > 0
            // A shared title/artist without a duration needs album or ISRC evidence.
            // For custom edits, a title or duration alone cannot identify an upload.
            val evidence = sameCode || (signature.versions.isEmpty() && (bothTimed || sameRelease))
            evidence && RecordingIdentity.same(signature.copy(title = title), known.copy(title = known.title.replace(" ", "")))
        }
        if (!duplicate) remember(signature)
        !duplicate
    }
}
