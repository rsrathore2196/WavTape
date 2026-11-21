package com.example.data.remote

import com.example.data.model.MusicTrack
import com.example.performance.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class YouTubeRadioRequest(
    val videoId: String?,
    val playlistId: String,
    val continuation: String? = null
)

/** A seed-only panel can hand back a provider playlist or a continuation. Keep these hops bounded. */
internal suspend fun loadYouTubeRadioQueue(
    seedVideoId: String,
    excludedTrackIds: Set<String>,
    limit: Int,
    fetch: suspend (YouTubeRadioRequest) -> String
): List<MusicTrack> {
    val initial = YouTubeRadioRequest(seedVideoId, "RDAMVM$seedVideoId")
    val excluded = excludedTrackIds + "yt_$seedVideoId"
    val pending = ArrayDeque<YouTubeRadioRequest>().apply { add(initial) }
    val seenPlaylists = mutableSetOf(initial.playlistId)
    val seenContinuations = mutableSetOf<String>()
    repeat(3) {
        val request = pending.removeFirstOrNull() ?: return emptyList()
        val panel = try {
            val body = fetch(request)
            withContext(AppDispatchers.CpuBounded) { parseRadioPanel(JSONObject(body), excluded, limit) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@repeat
        }
        if (panel.tracks.isNotEmpty()) return panel.tracks
        panel.automix?.takeIf(seenPlaylists::add)?.let { pending.add(YouTubeRadioRequest(null, it)) }
        panel.continuation?.takeIf(seenContinuations::add)?.let {
            pending.add(YouTubeRadioRequest(null, request.playlistId, it))
        }
    }
    return emptyList()
}

private data class RadioPanel(val tracks: List<MusicTrack>, val automix: String?, val continuation: String?)

private val radioPlaylistId = Regex("RD[A-Za-z0-9_-]{1,255}")

private fun parseRadioPanel(root: JSONObject, excluded: Set<String>, limit: Int): RadioPanel {
    val tracks = linkedMapOf<String, MusicTrack>()
    var automix: String? = null
    var continuation: String? = null
    fun visit(value: Any?, depth: Int = 0) {
        if (depth > 24 || tracks.size >= limit) return
        when (value) {
            is JSONObject -> {
                val primary = value.optJSONObject("playlistPanelVideoWrapperRenderer")?.optJSONObject("primaryRenderer")
                val renderer = (primary ?: value).optJSONObject("playlistPanelVideoRenderer")
                if (renderer != null) {
                    if (!renderer.has("unplayableText") && renderer.optBoolean("isPlayable", true)) {
                        val id = renderer.optString("videoId").ifBlank {
                            renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                                ?.optString("videoId").orEmpty()
                        }
                        if ("yt_$id" !in excluded && "yt_$id" !in tracks && YouTubeAudioStreamService.isVideoId(id)) {
                            val item = if (renderer.optString("videoId").isNotBlank()) primary ?: value else
                                JSONObject().put("playlistPanelVideoRenderer", JSONObject(renderer.toString()).put("videoId", id))
                            YouTubeMusicApiService.parseAnyRenderer(item)?.let { tracks[it.id] = it }
                        }
                    }
                    return
                }
                if (automix == null) {
                    automix = value.optJSONObject("automixPreviewVideoRenderer")?.optJSONObject("content")
                        ?.optJSONObject("automixPlaylistVideoRenderer")?.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId")?.takeIf(radioPlaylistId::matches)
                }
                val panel = value.optJSONObject("playlistPanelRenderer") ?: value.optJSONObject("playlistPanelContinuation")
                if (continuation == null && panel != null) {
                    val tokens = panel.optJSONArray("continuations")
                    for (index in 0 until (tokens?.length() ?: 0)) {
                        val item = tokens?.optJSONObject(index)
                        continuation = (item?.optJSONObject("nextRadioContinuationData") ?: item?.optJSONObject("nextContinuationData"))
                            ?.optString("continuation")?.takeIf(String::isNotBlank)
                        if (continuation != null) break
                    }
                }
                value.keys().forEach { visit(value.opt(it), depth + 1) }
            }
            is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index), depth + 1)
        }
    }
    visit(root)
    return RadioPanel(tracks.values.toList(), automix, continuation)
}
