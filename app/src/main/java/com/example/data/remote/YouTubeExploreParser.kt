package com.example.data.remote

import com.example.data.cache.RecommendationTrackCodec
import com.example.data.repository.*
import com.example.data.model.MusicTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Metadata-only browse decoding. Opaque category parameters are never reconstructed from labels. */
internal object YouTubeExploreParser {
    private fun string(value: JSONObject, key: String): String =
        value.optString(key).takeUnless { it == "null" }.orEmpty()
    private fun text(value: JSONObject?): String {
        if (value == null) return ""
        val runs = value.optJSONArray("runs")
        return if (runs != null) (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
            .trim() else string(value, "simpleText").ifBlank { string(value, "content") }.trim()
    }
    private fun nullable(value: JSONObject, key: String): String? = string(value, key).takeIf(String::isNotBlank)
    private fun endpoint(value: JSONObject?): JSONObject? = value?.optJSONObject("browseEndpoint")
        ?: value?.optJSONObject("innertubeCommand")?.optJSONObject("browseEndpoint")
    private fun title(value: JSONObject): JSONObject? = value.optJSONObject("title") ?: value.optJSONArray("flexColumns")
        ?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
    private fun runs(value: JSONObject?): List<JSONObject> = value?.optJSONArray("runs")?.let { array ->
        (0 until array.length()).mapNotNull(array::optJSONObject)
    }.orEmpty()

    private fun walk(node: Any?, checkpoint: () -> Unit, visit: (JSONObject) -> Boolean) {
        var count = 0
        fun descend(value: Any?, depth: Int) {
            if (depth > 32 || ++count > 60_000) throw IOException("Music browse response is too large")
            if (count % 128 == 0) checkpoint()
            when (value) {
                is JSONObject -> if (visit(value)) value.keys().forEach { descend(value.opt(it), depth + 1) }
                is JSONArray -> for (index in 0 until value.length()) descend(value.opt(index), depth + 1)
            }
        }
        descend(node, 0)
    }
    private fun validate(root: JSONObject) {
        if (root.has("error") || listOf("contents", "continuationContents", "onResponseReceivedActions",
                "onResponseReceivedCommands", "onResponseReceivedEndpoints").none(root::has))
            throw IOException("Music browse catalogue is unavailable")
    }

    fun isExcludedGenre(title: String): Boolean {
        val lower = title.trim().lowercase()
        val label = lower.replace("&amp;", "&")
        return lower.contains("carnatic") ||
            (label.contains("r&b") && label.contains("soul")) ||
            (lower.contains("country") && lower.contains("americana")) ||
            lower.contains("hindustani") ||
            (lower.contains("indie") && (lower.contains("alternat") || lower.contains("alternate") || lower.contains("alternative"))) ||
            (lower.contains("reggae") && (lower.contains("caribb") || lower.contains("carribb")))
    }

    fun normalizeGenreTitle(title: String): String {
        val trimmed = title.trim()
        return if (trimmed.equals("Dance & Electronic", ignoreCase = true) ||
            trimmed.equals("Dance and Electronic", ignoreCase = true) ||
            trimmed.equals("Dance & electronic", ignoreCase = true)
        ) {
            "Dance"
        } else {
            trimmed
        }
    }

    fun categories(root: JSONObject, checkpoint: () -> Unit = {}): List<ExploreCategory> {
        validate(root)
        val groups = mutableListOf<Pair<String, List<ExploreCategory>>>()
        walk(root.opt("contents"), checkpoint) { node ->
            val grid = node.optJSONObject("gridRenderer") ?: return@walk true
            val heading = text(grid.optJSONObject("header")?.optJSONObject("gridHeaderRenderer")?.optJSONObject("title"))
            val items = grid.optJSONArray("items") ?: return@walk false
            val parsed = (0 until items.length()).mapNotNull { index ->
                val button = items.optJSONObject(index)?.optJSONObject("musicNavigationButtonRenderer") ?: return@mapNotNull null
                val target = endpoint(button.optJSONObject("clickCommand")) ?: endpoint(button.optJSONObject("navigationEndpoint"))
                    ?: return@mapNotNull null
                val id = string(target, "browseId")
                val rawLabel = text(button.optJSONObject("buttonText"))
                if (id.isBlank() || rawLabel.isBlank() || isExcludedGenre(rawLabel)) return@mapNotNull null
                val label = normalizeGenreTitle(rawLabel)
                val kind = when {
                    heading.contains("mood", true) -> ExploreCategoryKind.MOOD
                    heading.contains("genre", true) -> ExploreCategoryKind.GENRE
                    else -> ExploreCategoryKind.OTHER
                }
                ExploreCategory(label, id, nullable(target, "params"), kind, heading)
            }
            if (parsed.isNotEmpty()) groups.add(heading to parsed)
            false
        }
        // Personalised shortcuts duplicate full categories, including on localised pages.
        val categories = groups.filterIndexed { index, group ->
            val laterKeys = groups.drop(index + 1).flatMap { it.second }.mapTo(HashSet()) { it.key }
            !group.first.equals("For you", true) && !group.second.all { it.key in laterKeys }
        }.flatMap { it.second }.distinctBy { it.key }
        if (categories.isEmpty()) throw IOException("No music categories are available")
        return categories
    }

    internal fun thumbnail(value: JSONObject, landscapeOnly: Boolean = false, squareOnly: Boolean = false): String {
        val thumbnails = mutableListOf<JSONObject>()
        walk(value.optJSONObject("thumbnailRenderer") ?: value.optJSONObject("thumbnail") ?: value.optJSONObject("image"), {}) { node ->
            (node.optJSONArray("thumbnails") ?: node.optJSONArray("sources"))?.let { array ->
                for (i in 0 until array.length()) array.optJSONObject(i)?.let(thumbnails::add)
            }
            true
        }
        return thumbnails.filter { (!landscapeOnly || it.optInt("width") > it.optInt("height") * 1.4 && it.optInt("height") > 0) &&
            (!squareOnly || it.optInt("width") > 0 && it.optInt("width") == it.optInt("height")) }
            .maxByOrNull { it.optInt("width") }?.let { string(it, "url") }
            ?.takeIf { it.startsWith("https://") }.orEmpty()
    }
    private fun browseEndpoint(value: JSONObject): JSONObject? = endpoint(value.optJSONObject("navigationEndpoint"))
        ?: runs(title(value)).firstNotNullOfOrNull { endpoint(it.optJSONObject("navigationEndpoint")) }
    private fun pageType(target: JSONObject): String = target.optJSONObject("browseEndpointContextSupportedConfigs")
        ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
    private val durationPattern = Regex("""^\d{1,2}:\d{2}(?::\d{2})?$""")
    private val yearPattern = Regex("""^(?:19|20)\d{2}$""")
    private val countPattern = Regex(
        """(?i)^(?:no\s+(?:views|plays|songs|tracks)|[\d.,]+\s*[KMBT]?\+?(?:\s*(?:plays?|views?|streams?|likes?|songs?|tracks?|episodes?|videos?|listeners?|subscribers?|monthly\s+(?:listeners|audience)|lakh\s+(?:views?|plays?)|crore\s+(?:views?|plays?)))?)$"""
    )
    private val relativeTimePattern = Regex("""(?i)^\d+\s+(?:second|minute|hour|day|week|month|year)s?\s+ago$""")
    private val nonArtistLabels = setOf(
        "song", "songs", "video", "videos", "music video", "album", "single", "ep",
        "playlist", "podcast", "episode", "live", "mix", "station", "radio", "audio",
        "audiobook", "track", "tracks", "explicit", "e", "official", "chart", "charts",
        "artist", "various artists", "unknown", "unknown artist", "youtube", "youtube music"
    )
    private val artistSeparatorPattern = Regex("""^(?:[,&/|+•·\-–—]|\band\b|\bfeat\.?\b|\bft\.?\b|\bx\b)+$""", RegexOption.IGNORE_CASE)
    private val albumTypeLabels = setOf("album", "single", "ep", "audiobook")

    internal fun isUnknownArtist(name: String): Boolean {
        val normalized = name.trim().lowercase()
        return normalized.isBlank() || normalized in setOf("unknown", "unknown artist", "various artists", "youtube music", "youtube artist")
    }

    private fun isNonArtistMetadata(raw: String): Boolean {
        val value = raw.trim()
        if (value.isBlank()) return true
        if (artistSeparatorPattern.matches(value)) return true
        val lower = value.lowercase()
        if (lower in nonArtistLabels) return true
        if (durationPattern.matches(value) || yearPattern.matches(value)) return true
        if (countPattern.matches(value) || relativeTimePattern.matches(value)) return true
        return false
    }

    private fun isNonArtistEndpoint(nav: JSONObject?): Boolean {
        if (nav == null) return false
        if (nav.has("watchEndpoint")) return true
        val target = endpoint(nav) ?: return false
        val id = string(target, "browseId")
        val type = pageType(target)
        if (type == "MUSIC_PAGE_TYPE_ARTIST" || type == "MUSIC_PAGE_TYPE_USER_CHANNEL" || id.startsWith("UC")) return false
        return id.isNotBlank() || type.isNotBlank()
    }

    private fun extractArtistsFromTextObject(textObj: JSONObject?): List<String> {
        if (textObj == null) return emptyList()
        val runList = runs(textObj)
        if (runList.isEmpty()) {
            val plain = text(textObj)
            if (plain.isBlank()) return emptyList()
            for (segment in plain.split('•', '·')) {
                val cleaned = segment.trim()
                if (!isNonArtistMetadata(cleaned)) return listOf(cleaned)
            }
            return emptyList()
        }
        // Split runs into bullet-delimited segments (e.g. "Song • Artist A & Artist B • 3:45").
        val segments = mutableListOf<MutableList<Pair<String, JSONObject?>>>()
        var current = mutableListOf<Pair<String, JSONObject?>>()
        for (run in runList) {
            val rawText = string(run, "text")
            val nav = run.optJSONObject("navigationEndpoint")
            if (rawText.contains('•') || rawText.contains('·')) {
                val parts = rawText.split('•', '·')
                parts.forEachIndexed { idx, part ->
                    if (part.isNotBlank()) current.add(part to nav)
                    if (idx < parts.lastIndex) {
                        if (current.isNotEmpty()) segments.add(current)
                        current = mutableListOf()
                    }
                }
            } else {
                current.add(rawText to nav)
            }
        }
        if (current.isNotEmpty()) segments.add(current)

        for (segment in segments) {
            val fullSegmentText = segment.joinToString("") { it.first }.trim()
            if (isNonArtistMetadata(fullSegmentText)) continue
            val names = mutableListOf<String>()
            val buffer = StringBuilder()
            var sawExcludedEndpointOnly = true
            fun flushBuffer() {
                val candidate = buffer.toString().trim().trim(',', '&', '-', '–', '—', '•', '·')
                buffer.setLength(0)
                if (!isNonArtistMetadata(candidate)) names.add(candidate)
            }
            for ((part, nav) in segment) {
                if (isNonArtistEndpoint(nav)) {
                    flushBuffer()
                    continue
                }
                sawExcludedEndpointOnly = false
                val trimmed = part.trim()
                if (trimmed.isEmpty()) {
                    if (buffer.isNotEmpty() && !buffer.endsWith(" ")) buffer.append(' ')
                } else if (artistSeparatorPattern.matches(trimmed)) {
                    flushBuffer()
                } else {
                    if (buffer.isNotEmpty() && !buffer.endsWith(" ") && !part.startsWith(" ")) buffer.append(' ')
                    buffer.append(trimmed)
                }
            }
            flushBuffer()
            if (!sawExcludedEndpointOnly && names.isNotEmpty()) {
                return names.distinctBy(CatalogNames::normalize)
            }
        }
        return emptyList()
    }

    internal fun fallbackArtistNames(value: JSONObject): List<String> {
        val flexCol1 = value.optJSONArray("flexColumns")?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        extractArtistsFromTextObject(flexCol1).takeIf { it.isNotEmpty() }?.let { return it }
        extractArtistsFromTextObject(value.optJSONObject("subtitle")).takeIf { it.isNotEmpty() }?.let { return it }
        extractArtistsFromTextObject(value.optJSONObject("longBylineText") ?: value.optJSONObject("shortBylineText"))
            .takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    /** Display artist list combining linked artists with unlinked singer runs while excluding metadata/views. */
    internal fun displayArtists(value: JSONObject, linked: List<String> = performingArtists(value)): List<String> {
        val fallback = fallbackArtistNames(value)
        if (linked.isEmpty()) return fallback
        if (fallback.size > linked.size && linked.all { credit -> fallback.any { CatalogNames.same(it, credit) } }) {
            return fallback
        }
        return linked
    }

    // Retain corroborated unlinked performers for listening history without changing linked artist identity.
    internal fun singerCredits(display: List<String>, linked: List<String>): String =
        if (linked.isNotEmpty() && display.size > linked.size) display.joinToString(", ") else ""

    private fun artists(value: JSONObject): List<CatalogArtist> {
        val rows = runs(value.optJSONObject("subtitle")) + value.optJSONArray("flexColumns")?.let { columns ->
            (1 until columns.length()).flatMap { index -> runs(columns.optJSONObject(index)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")) }
        }.orEmpty() + runs(value.optJSONObject("longBylineText") ?: value.optJSONObject("shortBylineText"))
            .takeWhile { !string(it, "text").contains('•') && !string(it, "text").contains('·') }
        return rows.mapNotNull { row ->
            val target = endpoint(row.optJSONObject("navigationEndpoint")) ?: return@mapNotNull null
            val id = string(target, "browseId")
            val name = string(row, "text").trim()
            if (name.isNotBlank() && (id.startsWith("UC") || pageType(target) == "MUSIC_PAGE_TYPE_ARTIST"))
                CatalogArtist(id, name) else null
        }.distinctBy { it.id.ifBlank { it.name } }
    }
    /** Linked music-artist entities only; album links and view-count text never become performers. */
    internal fun performingArtists(value: JSONObject): List<String> = artists(value).map { it.name }
        .distinctBy(CatalogNames::normalize)

    private fun headerLinkedArtists(textObj: JSONObject?): List<CatalogArtist> = runs(textObj).mapNotNull { row ->
        val target = endpoint(row.optJSONObject("navigationEndpoint")) ?: return@mapNotNull null
        val id = string(target, "browseId")
        val name = string(row, "text").trim()
        if (!isUnknownArtist(name) && (id.startsWith("UC") || pageType(target) == "MUSIC_PAGE_TYPE_ARTIST"))
            CatalogArtist(id, name) else null
    }.distinctBy { it.id.ifBlank { CatalogNames.normalize(it.name) } }

    private fun albumHeaderArtists(root: JSONObject, isAlbumCollection: Boolean): List<CatalogArtist> {
        var header: JSONObject? = null
        for (section in listOf(root.opt("header"), root.opt("contents"))) {
            if (header != null) break
            walk(section, {}) { node ->
                if (node.has("musicShelfRenderer") || node.has("musicPlaylistShelfRenderer") || node.has("musicCarouselShelfRenderer")) {
                    return@walk false
                }
                val editable = node.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")?.optJSONObject("header")
                val found = node.optJSONObject("musicResponsiveHeaderRenderer")
                    ?: node.optJSONObject("musicDetailHeaderRenderer")
                    ?: editable?.optJSONObject("musicResponsiveHeaderRenderer")
                    ?: editable?.optJSONObject("musicDetailHeaderRenderer")
                if (found != null && header == null) header = found
                found == null
            }
        }
        val albumHeader = header ?: return emptyList()
        val subtitleObj = albumHeader.optJSONObject("subtitle")
        val subtitleSegments = text(subtitleObj).split('•', '·').map { it.trim().lowercase() }
        val looksLikeAlbum = isAlbumCollection || subtitleSegments.any { it in albumTypeLabels }
        if (!looksLikeAlbum) return emptyList()

        val strapline = albumHeader.optJSONObject("straplineTextOne")
        val straplineLinked = headerLinkedArtists(strapline)
        val straplineFallback = extractArtistsFromTextObject(strapline).filterNot(::isUnknownArtist)
        if (straplineLinked.isNotEmpty()) {
            if (straplineFallback.size > straplineLinked.size &&
                straplineLinked.all { credit -> straplineFallback.any { CatalogNames.same(it, credit.name) } }) {
                return straplineFallback.map { name ->
                    straplineLinked.firstOrNull { CatalogNames.same(it.name, name) } ?: CatalogArtist("", name)
                }
            }
            return straplineLinked
        }
        if (straplineFallback.isNotEmpty()) return straplineFallback.map { CatalogArtist("", it) }

        val subtitleLinked = headerLinkedArtists(subtitleObj)
        if (subtitleLinked.isNotEmpty()) return subtitleLinked
        return extractArtistsFromTextObject(subtitleObj).filterNot(::isUnknownArtist).map { CatalogArtist("", it) }
    }

    internal fun collection(value: JSONObject, country: String, language: String): CatalogDestination? {
        val target = browseEndpoint(value) ?: return null
        val id = string(target, "browseId")
        val name = text(title(value))
        if (id.isBlank() || name.isBlank()) return null
        val type = pageType(target)
        val params = nullable(target, "params")
        return when {
            type == "MUSIC_PAGE_TYPE_ALBUM" || id.startsWith("MPRE") || id.startsWith("OLAK5uy_") -> {
                val linked = artists(value)
                val albumArtists = linked.ifEmpty {
                    fallbackArtistNames(value).filterNot(::isUnknownArtist).map { CatalogArtist("", it) }
                }
                CatalogDestination.Album(CatalogAlbum(id, name, albumArtists, thumbnail(value), "",
                    CatalogProvider.YOUTUBE_MUSIC, params, country, language))
            }
            type == "MUSIC_PAGE_TYPE_PLAYLIST" || id.startsWith("VL") ->
                CatalogDestination.Playlist(CatalogPlaylist(id, name, thumbnail(value),
                    CatalogProvider.YOUTUBE_MUSIC, params, country, language))
            else -> null
        }
    }

    private fun twoRowTrack(value: JSONObject): MusicTrack? {
        val id = value.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")
            ?: value.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")?.optString("videoId") ?: return null
        val name = text(title(value))
        if (!YouTubeAudioStreamService.isVideoId(id) || name.isBlank()) return null
        val credits = artists(value).map { it.name }
        val display = displayArtists(value, credits)
        return MusicTrack("yt_$id", name, display.joinToString(", ").ifBlank { "Unknown" }, "Unknown",
            0, thumbnail(value), "", bitrateKbps = 0, qualityBadge = "", source = "YouTube Music", primaryArtists = credits,
            originalProviderTitle = name, singers = singerCredits(display, credits))
    }
    private fun containerToken(value: JSONObject): String? {
        val continuations = value.optJSONArray("continuations")
        for (index in 0 until (continuations?.length() ?: 0)) {
            val row = continuations?.optJSONObject(index)
            val data = row?.optJSONObject("nextContinuationData") ?: row?.optJSONObject("reloadContinuationData")
            data?.let { nullable(it, "continuation") }?.let { return it }
        }
        val contents = value.optJSONArray("contents") ?: value.optJSONArray("continuationItems")
        for (index in 0 until (contents?.length() ?: 0)) {
            val row = contents?.optJSONObject(index)?.optJSONObject("continuationItemRenderer") ?: continue
            val target = row.optJSONObject("continuationEndpoint")
            (target?.optJSONObject("continuationCommand") ?: target?.optJSONObject("innertubeCommand")
                ?.optJSONObject("continuationCommand"))?.let { nullable(it, "token") }?.let { return it }
        }
        return null
    }

    fun page(root: JSONObject, country: String, language: String, collectionOnly: Boolean = false,
        isAlbumCollection: Boolean = false, checkpoint: () -> Unit = {}): CatalogPage {
        validate(root)
        val headerArtists = if (collectionOnly) albumHeaderArtists(root, isAlbumCollection) else emptyList()
        val headerArtistText = headerArtists.joinToString(", ") { it.name }
        val headerPrimaryArtists = headerArtists.filter { it.id.startsWith("UC") }.map { it.name }
            .distinctBy(CatalogNames::normalize)
        val tracks = linkedMapOf<String, MusicTrack>()
        val albums = linkedMapOf<String, CatalogAlbum>()
        val playlists = linkedMapOf<String, CatalogPlaylist>()
        var continuation: String? = null
        val roots = listOf("contents", "continuationContents", "onResponseReceivedActions",
            "onResponseReceivedCommands", "onResponseReceivedEndpoints")
        roots.forEach { key -> walk(root.opt(key), checkpoint) { node ->
            if (collectionOnly && node.has("musicCarouselShelfRenderer")) return@walk false
            val containers = if (collectionOnly) listOf("musicPlaylistShelfRenderer", "musicPlaylistShelfContinuation",
                "musicShelfRenderer", "musicShelfContinuation", "appendContinuationItemsAction")
                else listOf("sectionListRenderer", "sectionListContinuation", "appendContinuationItemsAction")
            if (continuation == null) for (name in containers) node.optJSONObject(name)?.let {
                continuation = containerToken(it)
            }
            val item = node.optJSONObject("musicTwoRowItemRenderer") ?: node.optJSONObject("musicResponsiveListItemRenderer")
            if (item != null && !collectionOnly) {
                when (val destination = collection(item, country, language)) {
                    is CatalogDestination.Album -> destination.album.let { albums.putIfAbsent("${it.id}|${it.browseParams.orEmpty()}", it) }
                    is CatalogDestination.Playlist -> destination.playlist.let { playlists.putIfAbsent("${it.id}|${it.browseParams.orEmpty()}", it) }
                    else -> Unit
                }
            }
            val parsedSong = YouTubeMusicApiService.parseAnyRenderer(node)
                ?: node.optJSONObject("musicTwoRowItemRenderer")?.let(::twoRowTrack)
            if (parsedSong != null) {
                val resolvedSong = if (headerArtistText.isNotBlank() && isUnknownArtist(parsedSong.artist)) {
                    parsedSong.copy(
                        artist = headerArtistText,
                        primaryArtists = parsedSong.primaryArtists.ifEmpty { headerPrimaryArtists }
                    )
                } else parsedSong
                tracks.putIfAbsent(resolvedSong.id, resolvedSong.copy(audioUrl = "", bitrateKbps = 0, qualityBadge = ""))
            }
            // A renderer's inner metadata can contain the same endpoint again.
            !(node.has("musicResponsiveListItemRenderer") || node.has("musicTwoRowItemRenderer") ||
                node.has("playlistVideoRenderer") || node.has("playlistPanelVideoRenderer"))
        } }
        return CatalogPage(tracks.values.toList(), albums.values.toList(), playlists = playlists.values.toList(),
            browseContinuation = continuation?.let { CatalogBrowseContinuation("", null, it, country, language) })
    }

    /** Artist identity is verified before this response is parsed. Only its own
     * header supplies the portrait/counts; album and song thumbnails cannot win. */
    fun artistProfile(root: JSONObject, selected: CatalogArtist): CatalogArtistProfile {
        validate(root)
        var header: JSONObject? = null
        for (section in listOf(root.opt("header"), root.opt("contents"))) {
            if (header != null) break
            walk(section, {}) { node ->
                val found = node.optJSONObject("musicImmersiveHeaderRenderer")
                    ?: node.optJSONObject("musicVisualHeaderRenderer") ?: node.optJSONObject("musicResponsiveHeaderRenderer")
                if (found != null && header == null) header = found
                found == null
            }
        }
        val artistHeader = header ?: throw IOException("Unsupported artist header")
        val name = text(artistHeader.optJSONObject("title")).ifBlank { selected.name }
        if ((listOf(selected.name) + selected.aliases).none { CatalogNames.same(it, name) })
            throw IOException("Artist header identity mismatch")
        val portrait = artistHeader.optJSONObject("foregroundThumbnail") ?: artistHeader.optJSONObject("thumbnail")
        val image = portrait?.let { thumbnail(JSONObject().put("thumbnail", it)) }.orEmpty().ifBlank { selected.image }
        val artist = selected.copy(name = name, image = image)
        val buttons = mutableListOf<JSONObject>().apply { addAll(listOfNotNull(artistHeader.optJSONObject("subscriptionButton2")?.optJSONObject("subscribeButtonRenderer"),
            artistHeader.optJSONObject("subscriptionButton")?.optJSONObject("subscribeButtonRenderer"))) }
        walk(artistHeader.opt("buttons"), {}) { node ->
            node.optJSONObject("subscribeButtonRenderer")?.let(buttons::add)
            true
        }
        val subscribers = buttons.firstNotNullOfOrNull { button ->
            listOf("longSubscriberCountText", "subscriberCountWithSubscribeText", "shortSubscriberCountText", "subscriberCountText")
                .firstNotNullOfOrNull { field -> text(button.optJSONObject(field)).takeIf(String::isNotBlank) }
        }.orEmpty().ifBlank { text(artistHeader.optJSONObject("subscriberCountText")) }
        var description = text(artistHeader.optJSONObject("description"))
        val songs = linkedMapOf<String, MusicTrack>()
        val releases = mutableListOf<ArtistReleaseSection>()
        val related = linkedMapOf<String, CatalogArtist>()
        var moreSongs: String? = null
        walk(root.opt("contents"), {}) { node ->
            node.optJSONObject("musicDescriptionShelfRenderer")?.let {
                text(it.optJSONObject("description")).takeIf(String::isNotBlank)?.let { body -> description = body }
            }
            node.optJSONObject("musicShelfRenderer")?.let { shelf ->
                if (moreSongs == null) moreSongs = runs(shelf.optJSONObject("title")).firstNotNullOfOrNull {
                    endpoint(it.optJSONObject("navigationEndpoint"))?.let { e -> nullable(e, "browseId") }
                }
                val rows = shelf.optJSONArray("contents")
                for (index in 0 until (rows?.length() ?: 0)) rows?.optJSONObject(index)?.let { row ->
                    YouTubeMusicApiService.parseAnyRenderer(row)?.let { song ->
                        val credits = row.optJSONObject("musicResponsiveListItemRenderer")?.let(::artists).orEmpty()
                        val names = song.primaryArtists.ifEmpty { credits.map { it.name } }.ifEmpty { listOf(artist.name) }
                        songs.putIfAbsent(song.id, song.copy(artist = names.joinToString(", "), primaryArtists = names))
                    }
                }
            }
            (node.optJSONObject("musicCarouselShelfRenderer") ?: node.optJSONObject("gridRenderer"))?.let { shelf ->
                val heading = shelf.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                    ?: shelf.optJSONObject("header")?.optJSONObject("gridHeaderRenderer")
                val label = text(heading?.optJSONObject("title"))
                val target = runs(heading?.optJSONObject("title")).firstNotNullOfOrNull { endpoint(it.optJSONObject("navigationEndpoint")) }
                    ?: heading?.optJSONObject("moreContentButton")?.optJSONObject("buttonRenderer")?.optJSONObject("navigationEndpoint")?.let(::endpoint)
                val contents = shelf.optJSONArray("contents") ?: shelf.optJSONArray("items")
                val albums = (0 until (contents?.length() ?: 0)).mapNotNull { i ->
                    val row = contents?.optJSONObject(i) ?: return@mapNotNull null
                    val item = row.optJSONObject("musicTwoRowItemRenderer") ?: row.optJSONObject("musicResponsiveListItemRenderer")
                        ?: return@mapNotNull null
                    val destination = browseEndpoint(item) ?: return@mapNotNull null
                    val id = string(destination, "browseId")
                    if (id.startsWith("UC") || pageType(destination) == "MUSIC_PAGE_TYPE_ARTIST") {
                        related.putIfAbsent(id, CatalogArtist(id, text(title(item)), image = thumbnail(item)))
                        return@mapNotNull null
                    }
                    if (pageType(destination) != "MUSIC_PAGE_TYPE_ALBUM" && !id.startsWith("MPRE") && !id.startsWith("OLAK5uy_")) return@mapNotNull null
                    CatalogAlbum(id, text(title(item)), listOf(artist), thumbnail(item), text(item.optJSONObject("subtitle")),
                        CatalogProvider.YOUTUBE_MUSIC, nullable(destination, "params"))
                }.distinctBy { it.id }
                if (label.isNotBlank() && albums.isNotEmpty()) releases.add(ArtistReleaseSection(label, albums,
                    target?.let { nullable(it, "browseId") }, target?.let { nullable(it, "params") }))
            }
            true
        }
        val monthly = listOf("monthlyListenerCount", "monthlyListeners", "monthlyAudienceCount")
            .firstNotNullOfOrNull { text(artistHeader.optJSONObject(it)).takeIf(String::isNotBlank) }
            ?: listOf("subtitle", "straplineTextOne", "straplineTextTwo").firstNotNullOfOrNull {
                text(artistHeader.optJSONObject(it)).takeIf { value ->
                    Regex("(?i)monthly (?:listeners|audience)").containsMatchIn(value)
                }
            }.orEmpty()
        return CatalogArtistProfile(artist, description, subscribers, monthly,
            songs.values.toList(), moreSongs, releases, related.values.filter { it.id != artist.id })
    }

    fun artistMatches(root: JSONObject, name: String): List<CatalogArtist> {
        val matches = linkedMapOf<String, CatalogArtist>()
        walk(root, {}) { node ->
            val item = node.optJSONObject("musicResponsiveListItemRenderer") ?: node.optJSONObject("musicTwoRowItemRenderer")
                ?: node.optJSONObject("musicCardShelfRenderer")
            if (item != null) {
                val target = browseEndpoint(item)
                val id = target?.let { string(it, "browseId") }.orEmpty()
                val label = text(title(item))
                if (id.startsWith("UC") && (target?.let(::pageType).isNullOrBlank() || target?.let(::pageType) == "MUSIC_PAGE_TYPE_ARTIST") &&
                    CatalogNames.same(name, label)) matches.putIfAbsent(id, CatalogArtist(id, label, image = thumbnail(item)))
                false
            } else true
        }
        return matches.values.toList()
    }

    /** Prefer a unique exact artist explicitly surfaced as the provider's top result.
     * Ambiguous ordinary rows still cannot choose between unrelated same-name channels. */
    internal fun artistMatch(root: JSONObject, name: String): CatalogArtist? {
        val featured = linkedMapOf<String, CatalogArtist>()
        walk(root, {}) { node ->
            val card = node.optJSONObject("musicCardShelfRenderer")
            if (card != null) {
                artistMatches(JSONObject().put("musicCardShelfRenderer", card), name).forEach { featured[it.id] = it }
                false
            } else true
        }
        return featured.values.singleOrNull() ?: artistMatches(root, name).singleOrNull()
    }

    internal fun encodeArtistIdentity(artist: CatalogArtist): String = JSONObject()
        .put("id", artist.id).put("name", artist.name).put("image", artist.image).toString()

    internal fun decodeArtistIdentity(body: String, selected: CatalogArtist): CatalogArtist {
        val row = JSONObject(body)
        val id = string(row, "id"); val name = string(row, "name")
        if (!id.startsWith("UC") || selected.id.startsWith("UC") && id != selected.id ||
            (listOf(selected.name) + selected.aliases).none { CatalogNames.same(it, name) })
            throw IOException("Cached artist identity mismatch")
        return selected.copy(id = id, name = name, image = string(row, "image"))
    }

    internal fun encodeArtistProfile(profile: CatalogArtistProfile): String = JSONObject().put("profileVersion", 2)
        .put("artist", JSONObject(encodeArtistIdentity(profile.artist)))
        .put("description", profile.description).put("subscribers", profile.subscriberCount)
        .put("listeners", profile.monthlyListeners).put("songs", JSONArray(RecommendationTrackCodec.encode(profile.songs)))
        .put("moreSongs", profile.moreSongsBrowseId)
        .put("releases", JSONArray(profile.releases.map { section -> JSONObject()
            .put("title", section.title).put("moreId", section.moreBrowseId).put("moreParams", section.moreParams)
            .put("page", JSONObject(encodePage(CatalogPage(albums = section.albums)))) }))
        .put("related", JSONArray(profile.relatedArtists.map { JSONObject(encodeArtistIdentity(it)) })).toString()

    internal fun decodeArtistProfile(body: String, selected: CatalogArtist): CatalogArtistProfile {
        val row = JSONObject(body)
        val artist = decodeArtistIdentity(row.getJSONObject("artist").toString(), selected)
        val releases = row.optJSONArray("releases")
        val related = row.optJSONArray("related")
        return CatalogArtistProfile(artist, string(row, "description"), string(row, "subscribers"), string(row, "listeners"),
            RecommendationTrackCodec.decode(row.optJSONArray("songs")?.toString() ?: "[]", maxTracks = 100), nullable(row, "moreSongs"),
            (0 until minOf(releases?.length() ?: 0, 40)).mapNotNull { i -> releases?.optJSONObject(i)?.let {
                ArtistReleaseSection(string(it, "title"), decodePage(it.getJSONObject("page").toString(), "US", "en").albums,
                    nullable(it, "moreId"), nullable(it, "moreParams")) } },
            (0 until minOf(related?.length() ?: 0, 40)).mapNotNull { i -> related?.optJSONObject(i)?.let {
                val id = string(it, "id"); val name = string(it, "name")
                if (id.startsWith("UC") && name.isNotBlank()) CatalogArtist(id, name, image = string(it, "image")) else null } })
    }

    fun encodeCategories(categories: List<ExploreCategory>): String = JSONArray(categories.map { category -> JSONObject()
        .put("title", category.title).put("browseId", category.browseId).put("params", category.params)
        .put("kind", category.kind.name).put("section", category.sectionTitle) }).toString()
    fun decodeCategories(body: String): List<ExploreCategory> = JSONArray(body).let { rows ->
        (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.let { row ->
            val id = string(row, "browseId"); val rawName = string(row, "title")
            if (id.isBlank() || rawName.isBlank() || isExcludedGenre(rawName)) null else ExploreCategory(normalizeGenreTitle(rawName), id, nullable(row, "params"),
                runCatching { ExploreCategoryKind.valueOf(string(row, "kind")) }.getOrDefault(ExploreCategoryKind.OTHER), string(row, "section"))
        } }.also { if (it.isEmpty()) throw IOException("No cached categories") }
    }
    fun encodePage(page: CatalogPage): String = JSONObject()
        .put("tracks", JSONArray(RecommendationTrackCodec.encode(page.tracks)))
        .put("albums", JSONArray(page.albums.map { album -> JSONObject().put("id", album.id).put("title", album.title)
            .put("image", album.image).put("year", album.year).put("params", album.browseParams)
            .put("artists", JSONArray(album.artists.map { JSONObject().put("id", it.id).put("name", it.name) })) }))
        .put("playlists", JSONArray(page.playlists.map { playlist -> JSONObject().put("id", playlist.id)
            .put("title", playlist.title).put("image", playlist.image).put("params", playlist.browseParams) }))
        .put("token", page.browseContinuation?.token).toString()
    fun decodePage(body: String, country: String, language: String): CatalogPage {
        val root = JSONObject(body)
        val albums = root.optJSONArray("albums")
        val playlists = root.optJSONArray("playlists")
        return CatalogPage(tracks = RecommendationTrackCodec.decode(root.optJSONArray("tracks")?.toString() ?: "[]", maxTracks = Int.MAX_VALUE),
            albums = (0 until (albums?.length() ?: 0)).mapNotNull { index -> albums?.optJSONObject(index)?.let { row ->
                val artists = row.optJSONArray("artists")
                CatalogAlbum(string(row, "id"), string(row, "title"),
                    (0 until (artists?.length() ?: 0)).mapNotNull { artists?.optJSONObject(it)?.let { a -> CatalogArtist(string(a, "id"), string(a, "name")) } },
                    string(row, "image"), string(row, "year"), CatalogProvider.YOUTUBE_MUSIC, nullable(row, "params"), country, language)
            } }, playlists = (0 until (playlists?.length() ?: 0)).mapNotNull { index -> playlists?.optJSONObject(index)?.let { row ->
                CatalogPlaylist(string(row, "id"), string(row, "title"), string(row, "image"), CatalogProvider.YOUTUBE_MUSIC,
                    nullable(row, "params"), country, language)
            } }, browseContinuation = nullable(root, "token")?.let { CatalogBrowseContinuation("", null, it, country, language) })
    }
}
