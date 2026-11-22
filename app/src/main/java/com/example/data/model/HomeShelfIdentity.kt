package com.example.data.model

import java.text.Normalizer
import java.util.Locale
import java.security.MessageDigest

/** Native identities only; never fuzzy-match recordings or rank server results. */
internal object HomeShelfIdentity {
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    fun title(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace("&amp;", "&").replace(punctuation, " ").trim()
    fun isExcluded(value: String): Boolean = title(value) == "listen together"
    fun entries(shelf: ServerHomeShelf): List<ServerHomeItem> = shelf.items.ifEmpty { shelf.tracks.map { ServerHomeItem.Song(it) } }
    fun item(entry: ServerHomeItem): String = when (entry) {
        is ServerHomeItem.Song -> "song:${entry.track.id.removePrefix("yt_")}"
        is ServerHomeItem.Album -> "album:${entry.album.id}"
        is ServerHomeItem.Playlist -> "playlist:${entry.playlist.id.removePrefix("VL")}"
    }
    fun stableId(shelf: ServerHomeShelf): String {
        val destination = shelf.browseId.takeIf(String::isNotBlank)?.let { "$it|${shelf.browseParams}" }
        val native = shelf.providerId.takeIf(String::isNotBlank)
        val identity = destination ?: native ?: entries(shelf).map(::item).distinct().sorted().joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest("${title(shelf.title)}|$identity".toByteArray(Charsets.UTF_8))
        val hash = buildString(24) {
            repeat(12) { index ->
                val byte = digest[index].toInt() and 0xff
                append("0123456789abcdef"[byte ushr 4])
                append("0123456789abcdef"[byte and 0x0f])
            }
        }
        return "server_shelf_$hash"
    }
    private data class Signature(val shelf: ServerHomeShelf, val title: String, val content: Set<String>)
    private fun signature(shelf: ServerHomeShelf) = Signature(shelf, title(shelf.title), entries(shelf).mapTo(hashSetOf(), ::item))
    private fun same(a: Signature, b: Signature): Boolean {
        val first = a.shelf; val second = b.shelf
        if (first.id == second.id) return true
        if (a.title != b.title) return false
        if (first.browseId.isNotBlank() && first.browseId == second.browseId && first.browseParams == second.browseParams) return true
        if (first.providerId.isNotBlank() && first.providerId == second.providerId) return true
        if (a.content == b.content) return true
        val overlap = a.content.count(b.content::contains)
        val smaller = minOf(a.content.size, b.content.size)
        val larger = maxOf(a.content.size, b.content.size)
        // A destination can change between Home and Explore. Content evidence
        // still wins, including two-of-three small shelves, but not one shared item.
        return overlap >= 2 && (smaller <= 4 && overlap * 3 >= smaller * 2 && overlap * 3 >= larger * 2 ||
            overlap >= 3 && overlap * 5 >= smaller * 4 && overlap * 2 >= larger)
    }
    fun equivalent(first: ServerHomeShelf, second: ServerHomeShelf) = same(signature(first), signature(second))
    private fun merge(first: ServerHomeShelf, next: ServerHomeShelf): ServerHomeShelf {
        val entries = entries(first)
        val ids = entries.mapTo(hashSetOf(), ::item)
        val added = entries(next).filter { ids.add(item(it)) }
        val merged = first.copy(subtitle = first.subtitle.ifBlank { next.subtitle },
            tracks = first.tracks + added.filterIsInstance<ServerHomeItem.Song>().map { it.track },
            items = if (first.items.isEmpty() && next.items.isEmpty()) emptyList() else entries + added,
            providerId = first.providerId.ifBlank { next.providerId },
            browseId = first.browseId.ifBlank { next.browseId },
            browseParams = if (first.browseId.isBlank()) next.browseParams else first.browseParams,
            continuationToken = next.continuationToken ?: first.continuationToken)
        return if (merged == first) first else merged
    }
    private fun validated(raw: ServerHomeShelf): Signature? {
        if (raw.title.isBlank() || isExcluded(raw.title)) return null
        val seen = hashSetOf<String>()
        val originalEntries = entries(raw)
        val valid = originalEntries.filter { entry -> when (entry) {
            is ServerHomeItem.Song -> entry.track.id.isNotBlank() && entry.track.title.isNotBlank()
            is ServerHomeItem.Album -> entry.album.id.isNotBlank() && entry.album.title.isNotBlank()
            is ServerHomeItem.Playlist -> entry.playlist.id.isNotBlank() && entry.playlist.title.isNotBlank()
        } && seen.add(item(entry)) }
        if (valid.isEmpty()) return null
        val shelf = if (valid.size == originalEntries.size) raw else raw.copy(
            tracks = valid.filterIsInstance<ServerHomeItem.Song>().map { it.track },
            items = if (raw.items.isEmpty()) emptyList() else valid)
        return Signature(shelf, title(shelf.title), seen)
    }
    private fun normalizedSteps(shelves: List<ServerHomeShelf>,
        prepare: (ServerHomeShelf) -> Signature?): Sequence<List<ServerHomeShelf>?> = sequence {
        val result = mutableListOf<Signature>()
        for (raw in shelves) {
            yield(null)
            val descriptor = prepare(raw) ?: continue
            val index = result.indexOfFirst { same(it, descriptor) }
            if (index >= 0) {
                val old = result[index]
                val merged = merge(old.shelf, descriptor.shelf)
                if (merged !== old.shelf) result[index] = Signature(merged, old.title, old.content + descriptor.content)
            } else result.add(descriptor)
        }
        val normalized = result.map { it.shelf }
        yield(if (normalized == shelves) shelves else normalized)
    }
    fun deduplicate(shelves: List<ServerHomeShelf>): List<ServerHomeShelf> =
        normalizedSteps(shelves, ::validated).filterNotNull().single()

    /** ViewModel-owned bounded signatures, shared by source and aggregate projections. */
    class Index {
        private val signatures = java.util.IdentityHashMap<ServerHomeShelf, Signature>()
        private fun prepared(shelf: ServerHomeShelf): Signature? {
            signatures[shelf]?.let { return it }
            return validated(shelf)?.also { descriptor ->
                if (signatures.size >= 512) signatures.clear()
                signatures[shelf] = descriptor
                signatures[descriptor.shelf] = descriptor
            }
        }
        suspend fun deduplicate(shelves: List<ServerHomeShelf>): List<ServerHomeShelf> {
            val slice = com.example.performance.ProcessingSlice()
            var result = shelves
            for (step in normalizedSteps(shelves, ::prepared)) {
                if (step != null) result = step
                slice.checkpoint()
            }
            return result
        }
        suspend fun refresh(previous: List<ServerHomeShelf>, incoming: List<ServerHomeShelf>): List<ServerHomeShelf> {
            if (incoming.isEmpty()) return incoming
            val slice = com.example.performance.ProcessingSlice()
            val prior = ArrayList<Signature>(previous.size)
            for (shelf in previous) {
                prior.add(signatures[shelf] ?: signature(shelf)); slice.checkpoint()
            }
            val reused = hashSetOf<String>()
            val fresh = ArrayList<ServerHomeShelf>()
            for (shelf in deduplicate(incoming)) {
                val next = checkNotNull(prepared(shelf))
                val old = prior.firstOrNull { it.shelf.id !in reused && same(it, next) }
                val retained = if (old == null) shelf else {
                    reused.add(old.shelf.id)
                    val candidate = if (shelf.id == old.shelf.id) shelf else shelf.copy(id = old.shelf.id)
                    if (candidate == old.shelf) old.shelf else candidate
                }
                if (signatures.size >= 512) signatures.clear()
                signatures[retained] = next.copy(shelf = retained)
                fresh.add(retained); slice.checkpoint()
            }
            return deduplicate(fresh)
        }
    }
    fun refresh(previous: List<ServerHomeShelf>, incoming: List<ServerHomeShelf>): List<ServerHomeShelf> {
        if (incoming.isEmpty()) return incoming
        val prior = previous.map(::signature)
        val reused = hashSetOf<String>()
        val fresh = deduplicate(incoming).map { shelf ->
            val next = signature(shelf)
            prior.firstOrNull { it.shelf.id !in reused && same(it, next) }?.let { old ->
                reused.add(old.shelf.id)
                val retained = if (shelf.id == old.shelf.id) shelf else shelf.copy(id = old.shelf.id)
                if (retained == old.shelf) old.shelf else retained
            } ?: shelf
        }
        // A successful source refresh replaces that source's old generation.
        // Other browse sources are retained independently by the feed owner.
        return deduplicate(fresh)
    }
}
