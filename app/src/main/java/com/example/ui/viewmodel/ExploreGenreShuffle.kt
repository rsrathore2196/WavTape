package com.example.ui.viewmodel

import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogDestination
import com.example.data.repository.SearchResultCategory
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

internal data class ExploreGenreShufflePool(
    val tracks: List<MusicTrack>,
    val continuationSource: CatalogDetailState? = null
)

internal fun buildShuffledGenreQueue(
    tracks: List<MusicTrack>,
    currentTrackId: String? = null,
    random: Random = Random.Default
): List<MusicTrack> {
    val unique = tracks
        .filter { it.id.isNotBlank() }
        .distinctBy { it.id }
    if (unique.size <= 1) return unique
    val shuffled = unique.shuffled(random).toMutableList()
    if (currentTrackId != null && shuffled.first().id == currentTrackId) {
        val swapIndex = shuffled.indexOfFirst { it.id != currentTrackId }
        if (swapIndex > 0) {
            val first = shuffled[0]
            shuffled[0] = shuffled[swapIndex]
            shuffled[swapIndex] = first
        }
    }
    return shuffled
}

internal suspend fun collectExploreGenreShuffleTracks(
    results: SearchResultCategory,
    currentTrackId: String? = null,
    random: Random = Random.Default,
    targetMinQueueSize: Int = 15,
    maxCollectionsToSample: Int = 2,
    loadCollection: suspend (CatalogDestination) -> CatalogDetailState
): ExploreGenreShufflePool {
    val gathered = LinkedHashMap<String, MusicTrack>()
    results.songs.forEach { track ->
        if (track.id.isNotBlank()) {
            gathered.putIfAbsent(track.id, track)
        }
    }

    var continuationSource: CatalogDetailState? = null
    if (gathered.size < targetMinQueueSize) {
        val destinations = buildList {
            results.catalogPlaylists.shuffled(random).forEach { playlist ->
                add(CatalogDestination.Playlist(playlist))
            }
            results.catalogAlbums.shuffled(random).forEach { album ->
                add(CatalogDestination.Album(album))
            }
        }
        var loadedCollections = 0
        var attempts = 0
        val maxAttempts = maxCollectionsToSample + 2
        for (destination in destinations) {
            if (gathered.size >= targetMinQueueSize || loadedCollections >= maxCollectionsToSample || attempts >= maxAttempts) {
                break
            }
            attempts++
            try {
                val state = loadCollection(destination)
                if (state.tracks.isNotEmpty()) {
                    loadedCollections++
                    state.tracks.forEach { track ->
                        if (track.id.isNotBlank()) {
                            gathered.putIfAbsent(track.id, track)
                        }
                    }
                    if (continuationSource == null && state.nextPage != null) {
                        continuationSource = state
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Skip an individual failing collection and try the next one in this genre.
            }
        }
    }

    val shuffled = buildShuffledGenreQueue(
        tracks = gathered.values.toList(),
        currentTrackId = currentTrackId,
        random = random
    )
    return ExploreGenreShufflePool(
        tracks = shuffled,
        continuationSource = continuationSource
    )
}
