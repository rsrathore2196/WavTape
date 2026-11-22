package com.example.data.remote

import com.example.connections.SpotifyConnection
import com.example.data.model.LyricLine
import com.example.data.model.SongIdentity
import com.example.data.model.TrackLyrics
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Catalog lookup and the raw, millisecond-based Spotify lyrics response. */
internal class SpotifyLyricsSource {
    private class AnonymousSession(val token: String, val expiresAt: Long)
    private val sessionLock = Mutex()
    private var anonymousSession: AnonymousSession? = null

    suspend fun lookup(
        identity: SongIdentity,
        query: String,
        connection: SpotifyConnection?,
        matches: (String, String, Double) -> Boolean,
        request: suspend (Request) -> String?
    ): TrackLyrics? {
        val id = identity.trackId?.takeIf { it.startsWith("spotify:track:") }
            ?.removePrefix("spotify:track:")?.takeIf(trackId::matches)
            ?: findTrack(identity, query, connection, matches, request)
            ?: return null

        if (connection != null && connection.bearer.isNotBlank()) {
            val url = "https://spclient.wg.spotify.com/color-lyrics/v2/track/$id".toHttpUrl().newBuilder()
                .addQueryParameter("format", "json").addQueryParameter("market", "from_token").build()
            val authenticated = spotifyRequest(connection.bearer, connection.clientToken).url(url).build()
            parse(identity, request(authenticated))?.let { return it }
        }

        // The public deployment only receives a public recording ID.
        val publicUrl = "https://spotify-lyrics-api-yeah.vercel.app/api/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("trackId", id).build()
        return parse(identity, request(Request.Builder().url(publicUrl).build()))
    }

    private suspend fun findTrack(
        identity: SongIdentity,
        query: String,
        connection: SpotifyConnection?,
        matches: (String, String, Double) -> Boolean,
        request: suspend (Request) -> String?
    ): String? {
        val token = connection?.bearer?.takeIf(String::isNotBlank) ?: publicCatalogToken(request) ?: return null
        val body = JSONObject().put("operationName", "findTracks")
            .put("variables", JSONObject().put("query", query).put("offset", 0).put("limit", 10))
            .put("extensions", JSONObject().put("persistedQuery", JSONObject().put("version", 1)
                .put("sha256Hash", "903df2a65d8121e27d73a2be03c01e88ebe6021bb6d4eb82a389e35d87e51d27")))
        val search = spotifyRequest(token, connection?.clientToken.orEmpty())
            .url("https://api-partner.spotify.com/pathfinder/v2/query")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val results = jsonObject(request(search))?.optJSONObject("data")?.optJSONObject("searchV2")
            ?.optJSONObject("tracksV2")?.optJSONArray("items") ?: return null
        var firstMatch: String? = null
        for (index in 0 until results.length()) {
            val track = results.optJSONObject(index)?.optJSONObject("item")?.optJSONObject("data") ?: continue
            val id = track.optString("id").takeIf(trackId::matches) ?: continue
            if (track.optString("uri") != "spotify:track:$id") continue
            val artists = track.optJSONObject("artists")?.optJSONArray("items") ?: continue
            val credited = (0 until artists.length()).mapNotNull {
                artists.optJSONObject(it)?.optJSONObject("profile")?.optString("name")?.takeIf(String::isNotBlank)
            }.joinToString(", ")
            val seconds = (track.optJSONObject("duration")?.optLong("totalMilliseconds") ?: 0) / 1000.0
            if (credited.isBlank() || !matches(track.optString("name"), credited, seconds)) continue
            val album = track.optJSONObject("albumOfTrack")?.optString("name").orEmpty()
            if (album.isNotBlank() && album.equals(identity.album, ignoreCase = true)) return id
            if (firstMatch == null) firstMatch = id
        }
        return firstMatch
    }

    private suspend fun publicCatalogToken(request: suspend (Request) -> String?): String? = sessionLock.withLock {
        val minimumExpiry = System.currentTimeMillis() + 30_000L
        anonymousSession?.takeIf { it.expiresAt > minimumExpiry }?.let { return@withLock it.token }
        val page = request(Request.Builder().url("https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M").build())
            ?: return@withLock null
        val session = jsonObject(embedData.find(page)?.groupValues?.get(1))?.optJSONObject("props")
            ?.optJSONObject("pageProps")?.optJSONObject("state")?.optJSONObject("settings")
            ?.optJSONObject("session")?.takeIf { it.optBoolean("isAnonymous") } ?: return@withLock null
        val token = session.optString("accessToken").takeIf(String::isNotBlank) ?: return@withLock null
        val expires = session.optLong("accessTokenExpirationTimestampMs")
        if (expires <= minimumExpiry) return@withLock null
        anonymousSession = AnonymousSession(token, expires)
        token
    }

    private fun spotifyRequest(token: String, clientToken: String) = Request.Builder()
        .header("Authorization", "Bearer $token").header("app-platform", "WebPlayer")
        .header("Origin", "https://open.spotify.com").header("Referer", "https://open.spotify.com/")
        .apply { if (clientToken.isNotBlank()) header("Client-Token", clientToken) }

    private fun parse(identity: SongIdentity, body: String?): TrackLyrics? {
        val root = jsonObject(body) ?: return null
        val doc = root.optJSONObject("lyrics") ?: root
        val synced = when (doc.optString("syncType")) {
            "LINE_SYNCED" -> true
            "UNSYNCED" -> false
            else -> return null
        }
        val rows = doc.optJSONArray("lines") ?: return null
        val lines = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val words = (row.opt("words") as? String)?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val timestamp = if (synced) row.optString("startTimeMs").toLongOrNull() ?: return null else 0L
            LyricLine(timestamp, words)
        }
        return LyricsDocuments.build(identity, "PaxSenix Spotify", lines, synced)
    }

    private fun jsonObject(body: String?): JSONObject? = body?.let { runCatching { JSONObject(it) }.getOrNull() }

    private companion object {
        val trackId = Regex("[0-9A-Za-z]{22}")
        val embedData = Regex("""<script\b[^>]*\bid="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    }
}
