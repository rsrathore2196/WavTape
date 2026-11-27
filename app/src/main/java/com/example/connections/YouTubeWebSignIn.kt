package com.example.connections

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

internal fun isYouTubeMusicPage(url: String?): Boolean = url != null && runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host.equals("music.youtube.com", ignoreCase = true) &&
        uri.rawUserInfo == null && uri.port in setOf(-1, 443)
}.getOrDefault(false)

internal data class YouTubeWebSession(val name: String, val cookie: String, val visitor: String, val profile: String, val user: String) {
    override fun toString() = "YouTubeWebSession(ready)"
}

/** Navigation can finish before the web app initializes its identity or WebView commits its cookies. */
internal suspend fun awaitYouTubeWebSession(
    currentUrl: () -> String?, readIdentity: suspend () -> String?, readCookie: () -> String,
    timeoutMs: Long = 20_000L
): YouTubeWebSession? = withTimeoutOrNull(timeoutMs) {
    var session: YouTubeWebSession? = null
    while (session == null) {
        if (isYouTubeMusicPage(currentUrl())) {
            // A navigation can also drop an individual JavaScript callback.
            val identity = withTimeoutOrNull(2_000L) { readIdentity() }?.let {
                try { JSONObject(it) } catch (_: JSONException) { null }
            }
            if (identity?.opt("loggedIn") == true && isYouTubeMusicPage(currentUrl())) {
                val cookie = readCookie()
                if (ConnectionStore.youTubeSignInCookie(cookie).isNotBlank()) {
                    session = YouTubeWebSession(identity.optString("name"), cookie, identity.optString("visitor"),
                        identity.optString("page").ifBlank { identity.optString("sync").substringBefore("||") },
                        identity.optString("user").toIntOrNull()?.takeIf { it >= 0 }?.toString() ?: "0")
                }
            }
        }
        if (session == null) delay(350L)
    }
    session
}
