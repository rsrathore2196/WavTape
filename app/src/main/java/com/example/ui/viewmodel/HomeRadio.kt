package com.example.ui.viewmodel

import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeAudioStreamService
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

internal suspend fun findArtistRadioSeed(
    artist: MostListenedArtist,
    search: suspend (String) -> List<MusicTrack>
): MusicTrack? {
    val artistKey = HomeRecommendationPolicy.mainArtistKey(artist.name)
    if (artistKey.isBlank()) return null
    fun matches(track: MusicTrack): Boolean = isNativeRadioSeed(track) &&
        HomeRecommendationPolicy.listeningArtists(track, track).any {
            HomeRecommendationPolicy.mainArtistKey(it) == artistKey
        }

    return artist.tracks.firstOrNull(::matches)
        ?: withTimeoutOrNull(5_000L) { search(artist.name).firstOrNull(::matches) }
}
