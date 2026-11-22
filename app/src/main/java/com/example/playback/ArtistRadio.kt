package com.example.playback

import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.remote.YouTubeMusicApiService
import com.example.recommendation.HomeRecommendationPolicy
import kotlinx.coroutines.CancellationException

internal const val ARTIST_RADIO_EXTRA = "queue_artist_radio"

internal data class ArtistRadio(val name: String, val shuffle: Boolean = false) {
    private val artistKey = HomeRecommendationPolicy.mainArtistKey(name)

    fun includes(track: MusicTrack, credits: List<String> = emptyList()): Boolean =
        artistKey.isNotBlank() &&
            credits.ifEmpty { HomeRecommendationPolicy.listeningArtists(track, track) }
                .any { HomeRecommendationPolicy.mainArtistKey(it) == artistKey }
}

internal data class ArtistRadioCursor(
    val continuation: String? = null,
    val pending: List<MusicTrack> = emptyList(),
    val exhausted: Boolean = false
)

internal data class ArtistRadioBatch(val tracks: List<MusicTrack>, val cursor: ArtistRadioCursor)

/** Browse the selected artist's songs without opening the similar-artist mix. */
internal suspend fun ArtistRadio.loadSongs(
    cursor: ArtistRadioCursor,
    excludedIds: Set<String>,
    limit: Int,
    search: suspend (String, String?) -> YouTubeMusicApiService.SongSearchPage
): ArtistRadioBatch {
    val seen = excludedIds.toMutableSet()
    val tracks = mutableListOf<MusicTrack>()
    var next = cursor
    val visited = mutableSetOf<String>().apply { cursor.continuation?.let(::add) }
    var pages = 0
    while (tracks.size < limit) {
        val eligible = next.pending.filter { it.id !in seen }.distinctBy { it.id }
        val admitted = eligible.take(limit - tracks.size)
        tracks += admitted
        admitted.forEach { seen.add(it.id) }
        next = next.copy(pending = eligible.drop(admitted.size))
        if (tracks.size == limit || next.exhausted || pages == 3) break

        pages++
        val page = try {
            search(name, next.continuation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (tracks.isEmpty()) throw failure
            break
        }
        val token = page.continuation?.takeIf(visited::add)
        val matched = page.tracks.filter { track ->
            YouTubeAudioStreamService.videoIdFromReference(track.id, track.audioUrl) != null &&
                includes(track, page.artistCredits[track.id].orEmpty())
        }
        next = ArtistRadioCursor(
            continuation = token,
            pending = if (shuffle) matched.shuffled() else matched,
            exhausted = token == null
        )
    }
    return ArtistRadioBatch(tracks, next)
}
