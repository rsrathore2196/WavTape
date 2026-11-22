package com.example.data.repository

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Immutable
import com.example.data.model.MusicTrack
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.remote.YouTubeExploreParser
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.remote.YouTubePlayerArtworkResolver
import com.example.data.remote.cachedNativeSongCover
import com.example.data.remote.catalogGet
import com.example.data.remote.catalogRequest
import com.example.data.remote.resolveYouTubePlayerCover
import com.example.performance.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

@Immutable
data class ResolvedTrackMetadata(
    val track: MusicTrack,
    val title: String,
    val coverUrl: String,
    val artists: List<CatalogArtist>,
    val album: CatalogAlbum?,
    val composers: List<String>,
    val lyricists: List<String> = emptyList(),
    val genre: String? = null,
    val language: String? = null,
    val year: String? = null,
    val label: String? = null,
    val isrc: String? = null,
    val durationMs: Long = track.durationMs,
    val isEnriched: Boolean = false,
    val hasVerifiedSquareCover: Boolean = false
) {
    val primaryArtistName: String
        get() = artists.firstOrNull()?.name ?: track.artist
}

/**
 * Resolves and caches accurate track metadata (individual artists with IDs, real album
 * never substituted with a playlist name, composer, lyricist, specific genre, language,
 * year, label, and ISRC) for both the Track Info page and the player Credits panel.
 */
object TrackMetadataResolver {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val cache = object : LruCache<String, ResolvedTrackMetadata>(80) {}
    private val locks = Array(8) { Mutex() }

    private val placeholderAlbums = setOf(
        "",
        "unknown",
        "unknown album",
        "youtube music",
        "youtube playlist",
        "youtube",
        "jiosaavn",
        "saavn",
        "original single",
        "single",
        "untitled",
        "playlist",
        "liked songs",
        "downloads",
        "custom playlist"
    )

    private val placeholderArtists = setOf(
        "",
        "unknown",
        "unknown artist",
        "various artists",
        "youtube artist",
        "youtube music"
    )

    private val placeholderValues = setOf(
        "",
        "unknown",
        "null",
        "none",
        "n/a",
        "original composer",
        "original single"
    )

    internal fun sanitizeAlbumName(raw: String?, sourcePlaylistTitle: String? = null): String? {
        val cleaned = CatalogNames.clean(raw.orEmpty()).trim()
        val normalized = CatalogNames.normalize(cleaned)
        if (normalized in placeholderAlbums) return null
        if (!sourcePlaylistTitle.isNullOrBlank()) {
            val playlistNorm = CatalogNames.normalize(sourcePlaylistTitle)
            if (playlistNorm.isNotBlank() && normalized == playlistNorm) return null
        }
        return cleaned.takeIf { it.isNotBlank() }
    }

    internal fun sanitizeField(raw: String?): String? {
        val cleaned = CatalogNames.clean(raw.orEmpty()).trim()
        val normalized = cleaned.lowercase(Locale.ROOT)
        if (normalized in placeholderValues) return null
        return cleaned.takeIf { it.isNotBlank() }
    }

    private val creditSeparators = Regex("\\s*(?:,|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|;|\\|)\\s*",
        RegexOption.IGNORE_CASE)

    internal fun splitNames(raw: String?): List<String> {
        val cleaned = CatalogNames.clean(raw.orEmpty()).trim()
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split(creditSeparators)
            .map { it.trim() }
            .filter { it.isNotBlank() && CatalogNames.normalize(it) !in placeholderArtists }
            .distinctBy { CatalogNames.normalize(it) }
    }

    private fun performers(track: MusicTrack): List<String> {
        val primary = track.primaryArtists.map(CatalogNames::clean)
            .filter { CatalogNames.normalize(it) !in placeholderArtists }
        if (primary.isEmpty()) return splitNames(track.singers).ifEmpty { splitNames(track.artist) }
        // The flat singer label can repeat a whole structured entity. Remove
        // those repetitions before legacy splitting so band names stay intact.
        val extras = primary.sortedByDescending(String::length).fold(CatalogNames.clean(track.singers)) { label, name ->
            val wholeCredit = Regex("""(?:^|(?<=[,;|]))\s*${Regex.escape(name)}\s*(?=$|[,;|])""",
                RegexOption.IGNORE_CASE)
            wholeCredit.replace(label, "")
        }
        return (primary + splitNames(extras)).distinctBy(CatalogNames::normalize)
    }

    private val googleWidthPattern = Regex("(?:=|-)w(\\d+)")
    private val googleHeightPattern = Regex("(?:=|-)h(\\d+)")
    private val googleSquareSizePattern = Regex("=s[1-9][0-9]*(?:-|$)")

    internal fun isVideoThumbnailUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = try { java.net.URI(url) } catch (_: Exception) { return false }
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (host == "img.youtube.com" || host.endsWith(".ytimg.com")) return true
        if (host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com")) {
            val w = googleWidthPattern.find(url)?.groupValues?.get(1)?.toIntOrNull()
            val h = googleHeightPattern.find(url)?.groupValues?.get(1)?.toIntOrNull()
            if (w != null && h != null && w > 0 && h > 0 && w != h) return true
        }
        return false
    }

    internal fun isLikelySquareAudioCoverUrl(url: String): Boolean {
        if (url.isBlank() || isVideoThumbnailUrl(url)) return false
        val uri = try { java.net.URI(url) } catch (_: Exception) { return false }
        if (uri.scheme !in setOf("https", "http")) return false
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com")) {
            val w = googleWidthPattern.find(url)?.groupValues?.get(1)?.toIntOrNull()
            val h = googleHeightPattern.find(url)?.groupValues?.get(1)?.toIntOrNull()
            return (w != null && w > 0 && w == h) || googleSquareSizePattern.containsMatchIn(url)
        }
        return host.endsWith(".saavncdn.com") || host.endsWith(".mzstatic.com")
    }

    internal fun cached(trackId: String): ResolvedTrackMetadata? =
        synchronized(cache) { cache.get(trackId) }

    internal fun clearCache() {
        synchronized(cache) { cache.evictAll() }
    }

    /** Immediate synchronous baseline from track fields with placeholders stripped. */
    fun initial(
        track: MusicTrack,
        sourcePlaylistTitle: String? = null,
        cachedSquareCover: String? = null
    ): ResolvedTrackMetadata {
        val instantSquare = cachedSquareCover?.takeIf { it.isNotBlank() && !isVideoThumbnailUrl(it) }
            ?: cachedNativeSongCover(track)?.takeIf { it.isNotBlank() && !isVideoThumbnailUrl(it) }
        val cachedEntry = cached(track.id)
        if (cachedEntry != null) {
            val safeAlbum = cachedEntry.album?.takeIf {
                sanitizeAlbumName(it.title, sourcePlaylistTitle) != null
            }
            val upgradedCover = if (instantSquare != null && !cachedEntry.hasVerifiedSquareCover) {
                instantSquare
            } else {
                cachedEntry.coverUrl
            }
            val upgradedVerified = cachedEntry.hasVerifiedSquareCover || instantSquare != null
            val upgradedAlbum = safeAlbum?.let { album ->
                if (upgradedCover.isNotBlank() && (album.image.isBlank() || isVideoThumbnailUrl(album.image))) {
                    album.copy(image = upgradedCover)
                } else album
            }
            return cachedEntry.copy(
                track = track,
                coverUrl = upgradedCover,
                album = upgradedAlbum,
                hasVerifiedSquareCover = upgradedVerified
            )
        }
        val artists = performers(track).map { CatalogArtist(id = "", name = it) }
        val initialCover = instantSquare ?: track.coverUrl
        val albumTitle = sanitizeAlbumName(track.album, sourcePlaylistTitle)
        val album = albumTitle?.let {
            CatalogAlbum(
                id = "",
                title = it,
                artists = artists,
                image = initialCover,
                year = sanitizeField(track.year).orEmpty()
            )
        }
        val composers = splitNames(sanitizeField(track.writer))
        val genre = sanitizeField(track.genre)?.takeUnless { it.equals("unknown", ignoreCase = true) }
        val language = sanitizeField(track.language)?.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
        val year = sanitizeField(track.year)?.takeIf { it.matches(Regex("(?:19|20)[0-9]{2}")) }
        val isrc = sanitizeField(track.isrc)
        return ResolvedTrackMetadata(
            track = track,
            title = track.cleanTitle.ifBlank { track.title },
            coverUrl = initialCover,
            artists = artists,
            album = album,
            composers = composers,
            genre = genre,
            language = language,
            year = year,
            isrc = isrc,
            durationMs = track.durationMs,
            isEnriched = false,
            hasVerifiedSquareCover = instantSquare != null || (!YouTubePlayerArtworkResolver.needsSongCover(track) && isLikelySquareAudioCoverUrl(initialCover))
        )
    }

    /** Enriches track metadata using JioSaavn, YouTube Music, and iTunes Search with caching. */
    suspend fun resolve(
        context: Context,
        track: MusicTrack,
        sourcePlaylistTitle: String? = null,
        resolveSquareCover: suspend (MusicTrack) -> String? = ::resolveYouTubePlayerCover
    ): ResolvedTrackMetadata = withContext(AppDispatchers.Io) {
        val cacheKey = track.id
        locks[(cacheKey.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            cached(cacheKey)?.takeIf { it.isEnriched }?.let { hit ->
                val safeAlbum = hit.album?.takeIf { sanitizeAlbumName(it.title, sourcePlaylistTitle) != null }
                return@withLock hit.copy(track = track, album = safeAlbum)
            }

            var current = initial(track, sourcePlaylistTitle)

            // 0. Always prefer the original square audio cover for YouTube tracks (including shelf videos).
            if (YouTubePlayerArtworkResolver.needsSongCover(track) && !current.hasVerifiedSquareCover) {
                val squareAudioCover = try {
                    withTimeoutOrNull(5_000L) { resolveSquareCover(track) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }?.takeIf { it.isNotBlank() && !isVideoThumbnailUrl(it) }
                if (squareAudioCover != null) {
                    current = merge(
                        current,
                        PartialMetadata(coverUrl = squareAudioCover, isSquareAudioCover = true),
                        preferIncomingAlbum = false,
                        preferIncomingCover = true
                    )
                }
            }

            // 1. Direct provider detail lookup
            if (track.id.startsWith("online_")) {
                val songId = track.id.removePrefix("online_")
                val jio = withTimeoutOrNull(6_000L) { fetchJioSaavnSongMetadata(songId, sourcePlaylistTitle) }
                if (jio != null) current = merge(current, jio, preferIncomingAlbum = true, preferIncomingCover = jio.isSquareAudioCover)
            } else if (track.id.startsWith("yt_")) {
                val videoId = track.id.removePrefix("yt_")
                val yt = withTimeoutOrNull(6_000L) { fetchYouTubeNextMetadata(videoId, track, sourcePlaylistTitle) }
                if (yt != null) {
                    current = merge(
                        current,
                        yt,
                        preferIncomingAlbum = true,
                        preferIncomingCover = !current.hasVerifiedSquareCover && yt.isSquareAudioCover
                    )
                }
            }

            // 2. If album ID, composer, language, ISRC, or square cover are missing, verify a JioSaavn match
            val needsJioEnrichment = !track.id.startsWith("online_") &&
                (current.composers.isEmpty() || current.album == null || current.language == null ||
                    current.isrc == null || !current.hasVerifiedSquareCover)
            if (needsJioEnrichment) {
                val jioMatch = withTimeoutOrNull(6_000L) {
                    findVerifiedJioSaavnMatch(track, current, sourcePlaylistTitle)
                }
                if (jioMatch != null) {
                    current = merge(
                        current,
                        jioMatch,
                        preferIncomingAlbum = current.album?.id.isNullOrBlank(),
                        preferIncomingCover = !current.hasVerifiedSquareCover && jioMatch.isSquareAudioCover
                    )
                }
            }

            // 3. If specific genre, real album, year, or square cover is still missing, verify an iTunes match
            if (current.genre == null || current.album == null || current.year == null || !current.hasVerifiedSquareCover) {
                val itunes = withTimeoutOrNull(5_000L) {
                    findVerifiedItunesMetadata(track, current, sourcePlaylistTitle)
                }
                if (itunes != null) {
                    current = merge(
                        current,
                        itunes,
                        preferIncomingAlbum = current.album == null,
                        preferIncomingCover = !current.hasVerifiedSquareCover && itunes.isSquareAudioCover
                    )
                }
            }

            val enriched = current.copy(isEnriched = true)
            synchronized(cache) { cache.put(cacheKey, enriched) }
            enriched
        }
    }

    internal fun selectPreferredCover(
        baseCover: String,
        baseVerifiedSquare: Boolean,
        incomingCover: String?,
        incomingSquareAudio: Boolean,
        preferIncomingCover: Boolean
    ): Pair<String, Boolean> {
        val candidate = incomingCover?.trim().orEmpty()
        if (candidate.isBlank()) return baseCover to baseVerifiedSquare
        val candidateIsSquareAudio = incomingSquareAudio && !isVideoThumbnailUrl(candidate)
        if (preferIncomingCover && candidateIsSquareAudio) {
            return candidate to true
        }
        if (baseCover.isBlank()) {
            return candidate to candidateIsSquareAudio
        }
        if (!baseVerifiedSquare && candidateIsSquareAudio && (isVideoThumbnailUrl(baseCover) || !isLikelySquareAudioCoverUrl(baseCover))) {
            return candidate to true
        }
        return baseCover to baseVerifiedSquare
    }

    private fun merge(
        base: ResolvedTrackMetadata,
        incoming: PartialMetadata,
        preferIncomingAlbum: Boolean,
        preferIncomingCover: Boolean = false
    ): ResolvedTrackMetadata {
        val mergedArtists = mergeArtists(base.artists, incoming.artists)
        val (resolvedCover, verifiedSquare) = selectPreferredCover(
            baseCover = base.coverUrl,
            baseVerifiedSquare = base.hasVerifiedSquareCover,
            incomingCover = incoming.coverUrl,
            incomingSquareAudio = incoming.isSquareAudioCover,
            preferIncomingCover = preferIncomingCover
        )
        val rawAlbum = when {
            incoming.album != null && (preferIncomingAlbum || base.album == null || (base.album.id.isBlank() && incoming.album.id.isNotBlank())) ->
                incoming.album.copy(
                    image = incoming.album.image.ifBlank { base.album?.image.orEmpty().ifBlank { resolvedCover } },
                    artists = incoming.album.artists.ifEmpty { mergedArtists }
                )
            else -> base.album
        }
        val album = rawAlbum?.let { currentAlbum ->
            if (verifiedSquare && resolvedCover.isNotBlank() &&
                (currentAlbum.image.isBlank() || isVideoThumbnailUrl(currentAlbum.image) || currentAlbum.image == base.track.coverUrl)) {
                currentAlbum.copy(image = resolvedCover)
            } else {
                currentAlbum
            }
        }
        return base.copy(
            coverUrl = resolvedCover,
            hasVerifiedSquareCover = verifiedSquare,
            artists = mergedArtists,
            album = album,
            composers = incoming.composers.ifEmpty { base.composers },
            lyricists = incoming.lyricists.ifEmpty { base.lyricists },
            genre = incoming.genre ?: base.genre,
            language = incoming.language ?: base.language,
            year = incoming.year ?: base.year,
            label = incoming.label ?: base.label,
            isrc = incoming.isrc ?: base.isrc,
            durationMs = if (base.durationMs > 0L) base.durationMs else incoming.durationMs
        )
    }

    private fun mergeArtists(base: List<CatalogArtist>, incoming: List<CatalogArtist>): List<CatalogArtist> {
        if (incoming.isEmpty()) return base
        if (base.isEmpty()) return incoming
        val byNorm = LinkedHashMap<String, CatalogArtist>()
        // Prefer incoming artists that carry real provider IDs and photos.
        val primaryList = if (incoming.any { it.id.isNotBlank() }) incoming else base
        val secondaryList = if (primaryList === incoming) base else incoming
        for (artist in primaryList + secondaryList) {
            val key = CatalogNames.normalize(artist.name)
            if (key.isBlank() || key in placeholderArtists) continue
            val existing = byNorm[key]
            if (existing == null) {
                byNorm[key] = artist
            } else {
                byNorm[key] = existing.copy(
                    id = existing.id.ifBlank { artist.id },
                    image = existing.image.ifBlank { artist.image }
                )
            }
        }
        return byNorm.values.toList()
    }

    private data class PartialMetadata(
        val coverUrl: String? = null,
        val isSquareAudioCover: Boolean = false,
        val artists: List<CatalogArtist> = emptyList(),
        val album: CatalogAlbum? = null,
        val composers: List<String> = emptyList(),
        val lyricists: List<String> = emptyList(),
        val genre: String? = null,
        val language: String? = null,
        val year: String? = null,
        val label: String? = null,
        val isrc: String? = null,
        val durationMs: Long = 0L
    )

    private suspend fun fetchJioSaavnSongMetadata(
        songId: String,
        sourcePlaylistTitle: String?
    ): PartialMetadata? {
        if (songId.isBlank()) return null
        return try {
            val url = "https://www.jiosaavn.com/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("__call", "song.getDetails")
                .addQueryParameter("pids", songId)
                .addQueryParameter("api_version", "4")
                .addQueryParameter("ctx", "web6dot0")
                .addQueryParameter("_format", "json")
                .addQueryParameter("_marker", "0")
                .build()
            val body = client.catalogGet(url.toString())
            val root = JSONObject(body)
            val songs = root.optJSONArray("songs")
            val item = root.optJSONObject(songId)
                ?: (0 until (songs?.length() ?: 0)).mapNotNull { songs?.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == songId }
                ?: root.takeIf { it.optString("id") == songId }
                ?: return null
            parseJioSaavnSongObject(item, sourcePlaylistTitle)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun parseJioSaavnSongObject(item: JSONObject, sourcePlaylistTitle: String?): PartialMetadata {
        fun value(obj: JSONObject?, key: String): String =
            obj?.optString(key)?.takeUnless { it == "null" }?.let(CatalogNames::clean).orEmpty().trim()
        val info = item.optJSONObject("more_info")
        fun field(key: String) = value(info, key).ifBlank { value(item, key) }

        val coverUrl = field("image")
            .replace("150x150", "500x500")
            .replace("50x50", "500x500")
            .replaceFirst("http://", "https://")
            .takeIf { it.isNotBlank() }

        val artistMap = info?.optJSONObject("artistMap")
        fun parseArtistsArray(key: String, roleFilter: Set<String>? = null): List<CatalogArtist> {
            val arr = artistMap?.optJSONArray(key) ?: return emptyList()
            val list = mutableListOf<CatalogArtist>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val role = value(obj, "role").lowercase(Locale.ROOT)
                if (roleFilter != null && role !in roleFilter) continue
                val name = value(obj, "name").ifBlank { value(obj, "title") }
                if (name.isBlank() || CatalogNames.normalize(name) in placeholderArtists) continue
                val id = value(obj, "id")
                val img = value(obj, "image")
                    .replace("150x150", "500x500")
                    .replace("50x50", "500x500")
                    .replaceFirst("http://", "https://")
                list.add(CatalogArtist(id = id, name = name, image = img))
            }
            return list
        }

        val performers = (
            parseArtistsArray("primary_artists") +
                parseArtistsArray("featured_artists") +
                parseArtistsArray("artists", setOf("singer", "vocalist", "primary_artists", "featured_artists"))
            ).distinctBy { CatalogNames.normalize(it.name) }

        val composers = (
            parseArtistsArray("artists", setOf("music", "composer", "music director")).map { it.name } +
                splitNames(sanitizeField(field("music")))
            ).distinctBy { CatalogNames.normalize(it) }

        val lyricists = parseArtistsArray("artists", setOf("lyricist", "writer"))
            .map { it.name }
            .distinctBy { CatalogNames.normalize(it) }

        val year = sanitizeField(field("year"))?.takeIf { it.matches(Regex("(?:19|20)[0-9]{2}")) }
        val albumTitle = sanitizeAlbumName(field("album"), sourcePlaylistTitle)
        val albumId = field("album_id")
        val album = albumTitle?.let {
            CatalogAlbum(
                id = albumId,
                title = it,
                artists = performers,
                image = coverUrl.orEmpty(),
                year = year.orEmpty(),
                provider = CatalogProvider.JIOSAAVN
            )
        }
        val language = sanitizeField(field("language"))?.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
        val genre = sanitizeField(field("genre"))
        val label = sanitizeField(field("label"))
        val isrc = sanitizeField(field("isrc"))
        val durationMs = (field("duration").toLongOrNull() ?: 0L).coerceAtLeast(0L) * 1000L

        return PartialMetadata(
            coverUrl = coverUrl,
            isSquareAudioCover = !coverUrl.isNullOrBlank() && !isVideoThumbnailUrl(coverUrl),
            artists = performers,
            album = album,
            composers = composers,
            lyricists = lyricists,
            genre = genre,
            language = language,
            year = year,
            label = label,
            isrc = isrc,
            durationMs = durationMs
        )
    }

    private suspend fun fetchYouTubeNextMetadata(
        videoId: String,
        track: MusicTrack,
        sourcePlaylistTitle: String?
    ): PartialMetadata? {
        if (!YouTubeAudioStreamService.isVideoId(videoId)) return null
        return try {
            val payload = JSONObject().apply {
                put(
                    "context",
                    JSONObject().put(
                        "client",
                        JSONObject()
                            .put("clientName", "WEB_REMIX")
                            .put("clientVersion", "1.20231204.01.00")
                            .put("hl", "en")
                            .put("gl", "US")
                    )
                )
                put("videoId", videoId)
                put("isAudioOnly", true)
            }
            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/next")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://music.youtube.com/")
                .build()
            val body = client.catalogRequest(request)
            val root = JSONObject(body)
            var matchedRenderer: JSONObject? = null
            fun findPanel(value: Any?, depth: Int = 0) {
                if (depth > 24 || matchedRenderer != null) return
                when (value) {
                    is JSONObject -> {
                        val panel = value.optJSONObject("playlistPanelVideoRenderer")
                        if (panel != null && panel.optString("videoId") == videoId) {
                            matchedRenderer = panel
                            return
                        }
                        value.keys().forEach { findPanel(value.opt(it), depth + 1) }
                    }
                    is JSONArray -> for (i in 0 until value.length()) findPanel(value.opt(i), depth + 1)
                }
            }
            findPanel(root)
            val panel = matchedRenderer ?: return null
            val runs = panel.optJSONObject("longBylineText")?.optJSONArray("runs") ?: return null
            val artists = mutableListOf<CatalogArtist>()
            var albumId = ""
            var albumName: String? = null
            var year: String? = null
            for (i in 0 until runs.length()) {
                val run = runs.optJSONObject(i) ?: continue
                val text = CatalogNames.clean(run.optString("text")).trim()
                if (text.isBlank()) continue
                val endpoint = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                val browseId = endpoint?.optString("browseId").orEmpty()
                val pageType = endpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType").orEmpty()
                when {
                    pageType == "MUSIC_PAGE_TYPE_ARTIST" || browseId.startsWith("UC") -> {
                        if (CatalogNames.normalize(text) !in placeholderArtists) {
                            artists.add(CatalogArtist(id = browseId, name = text))
                        }
                    }
                    pageType == "MUSIC_PAGE_TYPE_ALBUM" || browseId.startsWith("MPRE") -> {
                        albumId = browseId
                        albumName = sanitizeAlbumName(text, sourcePlaylistTitle)
                    }
                    text.matches(Regex("(?:19|20)[0-9]{2}")) -> year = text
                }
            }
            val squareCover = YouTubePlayerArtworkResolver.counterpartCover(root, videoId, track)
            val thumb = squareCover
                ?: YouTubeExploreParser.thumbnail(panel).ifBlank { track.coverUrl }
            val album = albumName?.let {
                CatalogAlbum(
                    id = albumId,
                    title = it,
                    artists = artists.distinctBy { a -> CatalogNames.normalize(a.name) },
                    image = thumb,
                    year = year.orEmpty(),
                    provider = CatalogProvider.YOUTUBE_MUSIC
                )
            }
            PartialMetadata(
                coverUrl = thumb.takeIf { it.isNotBlank() },
                isSquareAudioCover = squareCover != null,
                artists = artists.distinctBy { CatalogNames.normalize(it.name) },
                album = album,
                year = year
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun findVerifiedJioSaavnMatch(
        track: MusicTrack,
        current: ResolvedTrackMetadata,
        sourcePlaylistTitle: String?
    ): PartialMetadata? {
        val cleanTitle = track.cleanTitle.ifBlank { track.title }.trim()
        val primaryArtist = current.primaryArtistName.trim()
        if (cleanTitle.isBlank()) return null
        val query = if (primaryArtist.isNotBlank() && CatalogNames.normalize(primaryArtist) !in placeholderArtists) {
            "$cleanTitle $primaryArtist"
        } else cleanTitle
        return try {
            val url = "https://www.jiosaavn.com/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("__call", "search.getResults")
                .addQueryParameter("q", query)
                .addQueryParameter("p", "1")
                .addQueryParameter("n", "6")
                .addQueryParameter("api_version", "4")
                .addQueryParameter("ctx", "web6dot0")
                .addQueryParameter("_format", "json")
                .addQueryParameter("_marker", "0")
                .build()
            val root = JSONObject(client.catalogGet(url.toString()))
            val results = root.optJSONArray("results") ?: return null
            var bestId: String? = null
            var bestScore = 0
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isBlank()) continue
                val parsed = OnlineMusicApiService.parseSong(obj, allowMissingStream = true) ?: continue
                val score = metadataMatchScore(track, current.artists, parsed) ?: continue
                if (score > bestScore) {
                    bestScore = score
                    bestId = id
                }
            }
            bestId?.let { fetchJioSaavnSongMetadata(it, sourcePlaylistTitle) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun findVerifiedItunesMetadata(
        track: MusicTrack,
        current: ResolvedTrackMetadata,
        sourcePlaylistTitle: String?
    ): PartialMetadata? {
        val cleanTitle = track.cleanTitle.ifBlank { track.title }.trim()
        val primaryArtist = current.primaryArtistName.trim()
        if (cleanTitle.isBlank()) return null
        val term = if (primaryArtist.isNotBlank() && CatalogNames.normalize(primaryArtist) !in placeholderArtists) {
            "$cleanTitle $primaryArtist"
        } else cleanTitle
        return try {
            val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", term)
                .addQueryParameter("entity", "song")
                .addQueryParameter("limit", "6")
                .build()
            val root = JSONObject(client.catalogGet(url.toString()))
            val results = root.optJSONArray("results") ?: return null
            var best: JSONObject? = null
            var bestScore = 0
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                val candidate = MusicTrack(id = "", title = obj.optString("trackName").trim(),
                    artist = obj.optString("artistName"), album = obj.optString("collectionName"),
                    durationMs = obj.optLong("trackTimeMillis"), coverUrl = "", audioUrl = "")
                val score = metadataMatchScore(track, current.artists, candidate) ?: continue
                if (score > bestScore) {
                    bestScore = score
                    best = obj
                }
            }
            val match = best ?: return null
            val genre = sanitizeField(match.optString("primaryGenreName"))
            val rawRelease = match.optString("releaseDate")
            val year = Regex("((?:19|20)[0-9]{2})").find(rawRelease)?.groupValues?.getOrNull(1)
            val rawCollection = match.optString("collectionName")
                .replace(Regex("(?i)\\s*-\\s*Single\\s*$"), "")
                .trim()
            val albumName = sanitizeAlbumName(rawCollection, sourcePlaylistTitle)
            val art = match.optString("artworkUrl100")
                .replace("100x100bb", "600x600bb")
                .takeIf { it.isNotBlank() }
            val album = albumName?.let {
                CatalogAlbum(
                    id = "",
                    title = it,
                    artists = current.artists,
                    image = art.orEmpty().ifBlank { current.coverUrl },
                    year = year.orEmpty()
                )
            }
            PartialMetadata(
                coverUrl = art,
                isSquareAudioCover = !art.isNullOrBlank() && !isVideoThumbnailUrl(art),
                album = album,
                genre = genre,
                year = year
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    internal fun metadataMatchScore(track: MusicTrack, artists: List<CatalogArtist>, candidate: MusicTrack): Int? {
        val requested = if (track.primaryArtists.isEmpty() && artists.isNotEmpty()) {
            track.copy(primaryArtists = artists.map { it.name })
        } else track
        return RecordingIdentity.providerConfidence(requested, candidate)
    }

    /** Resolves an album name (+ optional artist) into a real official CatalogAlbum with full tracklist. */
    suspend fun resolveAlbum(
        catalogRepository: CatalogRepository,
        albumName: String,
        artistName: String = "",
        fallbackImage: String = "",
        fallbackYear: String = ""
    ): CatalogAlbum = withContext(AppDispatchers.Io) {
        val cleanName = CatalogNames.clean(albumName).trim()
        val cleanArtist = CatalogNames.clean(artistName).trim()
        val normAlbum = CatalogNames.normalize(cleanName)
        val normArtist = CatalogNames.normalize(cleanArtist)

        fun scoreAlbum(candidate: CatalogAlbum): Int {
            val candNorm = CatalogNames.normalize(candidate.title)
            var score = when {
                candNorm == normAlbum -> 100
                CatalogNames.spellingEquivalent(candNorm, normAlbum) -> 80
                candNorm.contains(normAlbum) || normAlbum.contains(candNorm) -> 45
                else -> 0
            }
            if (score > 0 && normArtist.isNotBlank()) {
                val artistHit = candidate.artists.any { a ->
                    val n = CatalogNames.normalize(a.name)
                    n == normArtist || CatalogNames.spellingEquivalent(n, normArtist) || n.contains(normArtist)
                }
                if (artistHit) score += 40
            }
            return score
        }

        val candidates = mutableListOf<CatalogAlbum>()
        val queries = listOfNotNull(
            if (cleanArtist.isNotBlank() && normArtist !in placeholderArtists) "$cleanName $cleanArtist" else null,
            cleanName
        ).distinct()

        for (q in queries) {
            try {
                candidates += catalogRepository.search(q, "Albums", 1).albums
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            if (candidates.any { scoreAlbum(it) >= 120 }) break
        }

        val best = candidates.maxByOrNull(::scoreAlbum)?.takeIf { scoreAlbum(it) >= 45 }
        best ?: CatalogAlbum(
            id = "album_${cleanName.hashCode()}",
            title = cleanName,
            artists = splitNames(cleanArtist).map { CatalogArtist(id = "", name = it) },
            image = fallbackImage,
            year = fallbackYear
        )
    }

    private val genreAliases = mapOf(
        "hip hop rap" to listOf("hip-hop", "hip hop", "rap"),
        "hip-hop/rap" to listOf("hip-hop", "hip hop", "rap"),
        "r b soul" to listOf("r&b", "r&b & soul", "soul"),
        "r&b/soul" to listOf("r&b", "r&b & soul", "soul"),
        "bollywood" to listOf("hindi", "indian pop", "bollywood"),
        "indian pop" to listOf("indian pop", "hindi", "pop"),
        "filmi" to listOf("hindi", "indian pop"),
        "electronic" to listOf("dance & electronic", "electronic", "dance"),
        "dance" to listOf("dance & electronic", "dance", "party"),
        "alternative" to listOf("indie & alternative", "alternative", "indie", "rock"),
        "indie" to listOf("indie & alternative", "indie", "pop"),
        "indie pop" to listOf("indie & alternative", "pop", "indian pop"),
        "singer songwriter" to listOf("folk & acoustic", "indie & alternative", "pop"),
        "soundtrack" to listOf("hindi", "soundtrack", "classical"),
        "worldwide" to listOf("pop", "hindi", "punjabi")
    )

    /** Loads real songs, albums, and playlists for any specific genre name. */
    suspend fun loadGenrePage(
        catalogRepository: CatalogRepository,
        genreName: String,
        countryCode: String = "US",
        languageCode: String = "en"
    ): CatalogPage = withContext(AppDispatchers.Io) {
        val clean = CatalogNames.clean(genreName).trim()
        val norm = CatalogNames.normalize(clean)
        val aliasTargets = (listOf(norm, clean.lowercase(Locale.ROOT)) +
            genreAliases[norm].orEmpty() +
            genreAliases[clean.lowercase(Locale.ROOT)].orEmpty() +
            clean.split('/', '&', ',').map { CatalogNames.normalize(it) })
            .filter { it.isNotBlank() }
            .distinct()

        // 1. Try matching an official YouTube Music Explore Genre category first.
        val categories = try {
            catalogRepository.cachedExploreCategories(countryCode, languageCode).ifEmpty {
                withTimeoutOrNull(6_000L) { catalogRepository.exploreCategories(countryCode, languageCode) }.orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        val matchedCategory = categories.firstOrNull { cat ->
            val catNorm = CatalogNames.normalize(cat.title)
            aliasTargets.any { target ->
                catNorm == target || catNorm.contains(target) || target.contains(catNorm)
            }
        }

        if (matchedCategory != null) {
            try {
                val page = catalogRepository.exploreCategory(matchedCategory, countryCode, languageCode)
                if (page.tracks.isNotEmpty() || page.albums.isNotEmpty() || page.playlists.isNotEmpty()) {
                    return@withContext page
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }

        // 2. Fallback: fetch real genre songs and albums/playlists for the exact genre name.
        val songs = try {
            OnlineMusicApiService.getSongsByGenre(clean, limit = 25)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }.ifEmpty {
            try {
                YouTubeMusicApiService.searchSongs("$clean songs", limit = 20)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
        val catalog = try {
            catalogRepository.search(clean, "All")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CatalogPage()
        }
        val mergedTracks = catalogRepository.uniqueTracks(songs + catalog.tracks)
        CatalogPage(
            tracks = mergedTracks,
            albums = catalog.albums,
            playlists = catalog.playlists
        )
    }
}
