package com.example.ui.viewmodel

import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.remote.YouTubeExploreParser
import com.example.recommendation.HomeRecommendationPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal fun isNativeRadioSeed(track: MusicTrack): Boolean = track.title.isNotBlank() &&
    YouTubeAudioStreamService.videoIdFromReference(track.id, track.audioUrl) != null

/** Use a real catalog recording on an empty installation, even when Home has not returned a shelf. */
internal suspend fun findColdRadioSeed(
    cached: suspend () -> List<MusicTrack>,
    home: suspend () -> List<MusicTrack>,
    search: suspend () -> List<MusicTrack>
): MusicTrack? {
    suspend fun first(timeoutMs: Long, load: suspend () -> List<MusicTrack>): MusicTrack? =
        try { withTimeoutOrNull(timeoutMs) { load().firstOrNull(::isNativeRadioSeed) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    return first(500, cached) ?: first(4_000, home) ?: first(5_000, search)
}

internal data class ArtistRadioStation(val artist: String, val seed: MusicTrack)

/** Catalog and listening signals already present on Home are enough to build the shelf. */
internal fun artistRadioStations(
    listened: List<MostListenedArtist>,
    recent: List<MusicTrack>,
    recommended: Sequence<MusicTrack>
): List<ArtistRadioStation> {
    val stations = linkedMapOf<String, ArtistRadioStation>()
    fun add(name: String, track: MusicTrack) {
        if (!YouTubeExploreParser.isUnknownArtist(name)) {
            val key = HomeRecommendationPolicy.mainArtistKey(name)
            if (key.isNotBlank()) stations.putIfAbsent(key, ArtistRadioStation(name, track))
        }
    }
    for (artist in listened) {
        val seed = artist.tracks.firstOrNull(::isNativeRadioSeed) ?: continue
        add(artist.name, seed)
        if (stations.size >= 8) return stations.values.toList()
    }
    for (track in (recent.asSequence() + recommended).filter(::isNativeRadioSeed).distinctBy { it.id }.take(60)) {
        for (artist in HomeRecommendationPolicy.listeningArtists(track, track)) {
            add(artist, track)
            if (stations.size >= 8) return stations.values.toList()
        }
    }
    return stations.values.toList()
}
