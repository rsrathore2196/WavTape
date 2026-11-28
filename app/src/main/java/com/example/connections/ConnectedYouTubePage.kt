package com.example.connections

import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeMusicApiService
import org.json.JSONArray
import org.json.JSONObject

internal data class ConnectedYouTubePage(val tracks: List<MusicTrack>, val continuation: String?)

/** Read the selected collection and its continuations for both import entry points. */
internal fun connectedYouTubePage(root: JSONObject): ConnectedYouTubePage {
    fun find(value: Any?, name: String, depth: Int = 0): JSONObject? {
        if (depth > 64) return null
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) find(value.opt(i), name, depth + 1)?.let { return it }
            is JSONObject -> {
                if (value.has("musicCarouselShelfRenderer") || value.has("playlistVideoRenderer") ||
                    value.has("musicResponsiveListItemRenderer")) return null
                value.optJSONObject(name)?.let { section ->
                    if (name != "itemSectionRenderer" || section.optJSONArray("contents")?.let { items ->
                            (0 until items.length()).any { index ->
                                val row = items.optJSONObject(index)
                                row?.has("playlistVideoRenderer") == true || row?.has("lockupViewModel") == true
                            }
                        } == true) return section
                }
                value.optJSONArray("tabs")?.let { tabs ->
                    val candidates = (0 until tabs.length()).mapNotNull { tabs.optJSONObject(it)?.optJSONObject("tabRenderer") }
                    val tab = candidates.firstOrNull { it.optBoolean("selected") } ?: candidates.firstOrNull()
                    find(tab?.opt("content"), name, depth + 1)?.let { return it }
                }
                value.keys().forEach { key ->
                    if (key != "tabs") find(value.opt(key), name, depth + 1)?.let { return it }
                }
            }
        }
        return null
    }
    val continuation = root.optJSONObject("continuationContents")
    val shelf = continuation?.optJSONObject("musicPlaylistShelfContinuation")
        ?: continuation?.optJSONObject("musicShelfContinuation")
        ?: continuation?.optJSONObject("playlistVideoListContinuation")
        ?: find(root.opt("contents"), "musicPlaylistShelfRenderer")
        ?: find(root.opt("contents"), "playlistVideoListRenderer")
        ?: find(root.opt("contents"), "musicShelfRenderer")
        ?: find(root.opt("contents"), "itemSectionRenderer")
        ?: listOf("onResponseReceivedActions", "onResponseReceivedCommands", "onResponseReceivedEndpoints")
            .firstNotNullOfOrNull { name -> find(root.opt(name), "appendContinuationItemsAction")
                ?: find(root.opt(name), "reloadContinuationItemsCommand") }
        ?: return ConnectedYouTubePage(emptyList(), null)
    val items = shelf.optJSONArray("contents") ?: shelf.optJSONArray("continuationItems") ?: JSONArray()
    val tracks = (0 until items.length()).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val row = item.optJSONObject("musicResponsiveListItemRenderer") ?: item.optJSONObject("playlistVideoRenderer")
            ?: item.optJSONObject("playlistPanelVideoRenderer")
        if (row?.optBoolean("isPlayable", true) == false ||
            row?.optString("musicItemRendererDisplayPolicy") == "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT") return@mapNotNull null
        val track = YouTubeMusicApiService.parseAnyRenderer(item) ?: return@mapNotNull null
        track.takeUnless { it.fullTitle.equals("Private video", true) || it.fullTitle.equals("Deleted video", true) }
    }
    val next = shelf.optJSONArray("continuations")?.let { youtubeContinuation(JSONObject().put("continuations", it)) }
        ?: (0 until items.length()).firstNotNullOfOrNull { index ->
            val item = items.optJSONObject(index)
            (item?.optJSONObject("continuationItemRenderer") ?: item?.optJSONObject("continuationItemViewModel"))
                ?.let(::youtubeContinuation)
        }
    return ConnectedYouTubePage(tracks, next)
}
