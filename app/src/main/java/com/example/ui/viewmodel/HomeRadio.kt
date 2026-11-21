package com.example.ui.viewmodel

import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.remote.YouTubeMusicApiService
import com.example.playback.ArtistRadio
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
    search: suspend (String) -> YouTubeMusicApiService.SongSearchPage
): MusicTrack? {
    val station = ArtistRadio(artist.name)
    fun matches(track: MusicTrack): Boolean = isNativeRadioSeed(track) && station.includes(track)

    return artist.tracks.firstOrNull(::matches)
        ?: withTimeoutOrNull(5_000L) {
            val page = search(artist.name)
            page.tracks.firstOrNull {
                isNativeRadioSeed(it) && station.includes(it, page.artistCredits[it.id].orEmpty())
            }
        }
}
