package com.example.data.remote

import android.os.SystemClock
import android.util.Base64
import com.example.data.model.SongIdentity
import com.example.data.model.TrackLyrics
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Musixmatch's web search and lyrics routes share a short-lived, in-memory signing key. */
internal class MusixmatchWebLyrics(
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val today: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) }
) {
    private class WebKey(val value: ByteArray, val expiresAt: Long)
    private val keyLock = Mutex()
    private var cachedKey: WebKey? = null

    suspend fun lookup(
        identity: SongIdentity,
        query: String,
        matches: (String, String, Double) -> Boolean,
        request: suspend (Request) -> String?
    ): TrackLyrics? {
        val key = webKey(request) ?: return null
        suspend fun api(method: String, vararg parameters: Pair<String, String>): JSONObject? {
            val address = signedUrl(method, key.value, today(), parameters)
            val raw = request(browserRequest(address).build()) ?: return null
            val envelope = runCatching { JSONObject(raw) }.getOrNull()?.optJSONObject("message") ?: return null
            val code = envelope.optJSONObject("header")?.optInt("status_code") ?: return null
            if (code == 401 || code == 403) keyLock.withLock {
                if (cachedKey === key) cachedKey = null
            }
            return envelope.optJSONObject("body").takeIf { code == 200 }
        }

        val tracks = api("track.search", "q" to query, "f_has_lyrics" to "true", "page_size" to "10", "page" to "1")
            ?.optJSONArray("track_list") ?: return null
        val expectedIsrc = identity.isrc.orEmpty().replace("-", "")
        val candidates = (0 until tracks.length()).mapNotNull { tracks.optJSONObject(it)?.optJSONObject("track") }
            .filter { row ->
                val isrc = row.optString("track_isrc").replace("-", "")
                row.optLong("track_id") > 0 && row.optInt("restricted") == 0 &&
                    (expectedIsrc.isBlank() || isrc.isBlank() || isrc.equals(expectedIsrc, ignoreCase = true)) &&
                    matches(row.optString("track_name"), row.optString("artist_name"), row.optDouble("track_length", 0.0))
            }
        val track = candidates.firstOrNull {
            expectedIsrc.isNotBlank() && it.optString("track_isrc").replace("-", "").equals(expectedIsrc, ignoreCase = true)
        } ?: candidates.firstOrNull() ?: return null
        val id = "track_id" to track.optLong("track_id").toString()
        fun parse(body: String?): TrackLyrics? = body?.let { LyricsDocuments.parse(identity, it, "Musixmatch") }
            ?.takeIf { LyricsDocuments.verified(identity, it) }
        if (track.optInt("has_richsync", 1) == 1) {
            parse(api("track.richsync.get", id)?.optJSONObject("richsync")?.optString("richsync_body"))
                ?.takeIf { it.isSynced }?.let { return it }
        }
        if (track.optInt("has_subtitles", 1) == 1) {
            parse(api("track.subtitle.get", id, "subtitle_format" to "lrc")
                ?.optJSONObject("subtitle")?.optString("subtitle_body"))
                ?.takeIf { it.isSynced }?.let { return it }
        }
        val plain = api("track.lyrics.get", id)?.optJSONObject("lyrics")?.takeIf { it.optInt("restricted") == 0 }
            ?.optString("lyrics_body")?.substringBefore("\n*******")?.trimEnd()
        return parse(plain)
    }

    private suspend fun webKey(request: suspend (Request) -> String?): WebKey? = keyLock.withLock {
        cachedKey?.takeIf { it.expiresAt > now() }?.let { return@withLock it }
        val page = request(browserRequest(searchPage).header("Cookie", "mxm_bab=AB").build()) ?: return@withLock null
        val script = appScript.findAll(page).mapNotNull { match ->
            searchPage.resolve(match.groupValues[1])?.takeIf {
                it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
                    it.host in setOf("www.musixmatch.com", "s.mxmcdn.net")
            }
        }.lastOrNull() ?: return@withLock null
        val js = request(browserRequest(script).build()) ?: return@withLock null
        val encoded = encodedKey.find(js)?.groupValues?.get(1) ?: return@withLock null
        val key = runCatching { Base64.decode(encoded.reversed(), Base64.DEFAULT) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return@withLock null
        WebKey(key, now() + 60 * 60_000L).also { cachedKey = it }
    }

    private fun browserRequest(address: HttpUrl) = Request.Builder().url(address)
        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")

    private fun signedUrl(method: String, key: ByteArray, date: LocalDate, parameters: Array<out Pair<String, String>>): HttpUrl {
        val unsigned = "https://www.musixmatch.com/ws/1.1/$method".toHttpUrl().newBuilder()
            .addQueryParameter("app_id", "web-desktop-app-v1.0").addQueryParameter("format", "json").apply {
                parameters.forEach { (name, value) -> addQueryParameter(name, value) }
            }.build().toString().replace("%20", "+")
        val signer = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val signature = signer.doFinal((unsigned + date.format(DateTimeFormatter.BASIC_ISO_DATE)).toByteArray(Charsets.UTF_8))
        return unsigned.toHttpUrl().newBuilder()
            .addQueryParameter("signature", Base64.encodeToString(signature, Base64.NO_WRAP))
            .addQueryParameter("signature_protocol", "sha256").build()
    }

    private companion object {
        val searchPage = "https://www.musixmatch.com/search".toHttpUrl()
        val appScript = Regex("""\bsrc=["']([^"']*/_next/static/chunks/pages/_app-[^"']+\.js)["']""")
        val encodedKey = Regex("""\bfrom\(\s*["']([A-Za-z0-9+/=]{8,512})["']\s*\.split""")
    }
}
