package com.example.connections

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal object ConnectionHosts {
    fun allowed(url: String, provider: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) return@runCatching false
        val host = uri.host?.lowercase() ?: return@runCatching false
        val domains = if (provider == "youtube") listOf("youtube.com", "google.com", "gstatic.com", "googleusercontent.com")
            else listOf("spotify.com", "scdn.co", "google.com", "gstatic.com", "facebook.com", "apple.com")
        domains.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)
}

/** A separate window avoids recording a WebView inside a glass backdrop layer. */
class ConnectionLoginActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val provider = intent.getStringExtra("provider")?.takeIf { it == "youtube" || it == "spotify" } ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!ConnectionBrowserProfile.isSupported()) {
            setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("Update Android System WebView to connect an account.", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview"))) }) { Text("Update WebView") }
                    TextButton(onClick = { finish() }) { Text("Close") }
                }
            } } }
            return
        }
        val manageYouTube = provider == "youtube" && intent.getBooleanExtra("manage", false)
        setContent {
            MaterialTheme {
                var view by remember { mutableStateOf<WebView?>(null) }
                var status by remember { mutableStateOf("Sign in on the service’s secure website.") }
                var saving by remember { mutableStateOf(false) }
                var pendingSpotify by remember { mutableStateOf<SpotifyConnection?>(null) }
                var pageUrl by remember { mutableStateOf("") }
                var youtubeAttempt by remember { mutableIntStateOf(0) }
                var youtubeLoadFailed by remember { mutableStateOf(false) }
                var browserStopped by remember { mutableStateOf(false) }
                val onYouTubeMusic = provider == "youtube" && isYouTubeMusicPage(pageUrl)
                LaunchedEffect(view, onYouTubeMusic, youtubeAttempt, youtubeLoadFailed) {
                    val browser = view ?: return@LaunchedEffect
                    if (!onYouTubeMusic || youtubeLoadFailed || manageYouTube && youtubeAttempt == 0) return@LaunchedEffect
                    saving = true
                    status = "Connecting YouTube Music…"
                    try {
                        val session = awaitYouTubeWebSession(
                            currentUrl = { browser.url },
                            readIdentity = { browser.readYouTubeIdentity() },
                            readCookie = { ConnectionBrowserProfile.cookies().getCookie(YOUTUBE_MUSIC_HOME).orEmpty() }
                        )
                        if (session == null) {
                            status = "Finish signing in on YouTube Music, then tap Use account."
                        } else if (view === browser && !isFinishing && isYouTubeMusicPage(browser.url)) {
                            val store = ConnectionStore.get(this@ConnectionLoginActivity)
                            val saved = store.addYouTube(session.name, session.cookie, session.visitor, session.profile, session.user)
                            store.refreshYouTubeProfileName(saved)
                            finish()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status = "The secure connection could not be saved. Try again." }
                    finally { saving = false }
                }
                fun saveSpotify(session: SpotifyConnection) {
                    pendingSpotify = session
                    if (saving || isFinishing) return
                    saving = true
                    lifecycleScope.launch {
                        try {
                            delay(1500) // Allow the first-party player to supply its companion client token.
                            val captured = pendingSpotify ?: session
                            ConnectionStore.get(this@ConnectionLoginActivity).change { it.copy(spotify = captured) }; finish()
                        }
                        catch (_: Exception) { saving = false; status = "The secure connection could not be saved. Try again." }
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { finish() }) { Text("Close") }
                            Text(if (provider == "youtube") "YouTube Music" else "Spotify", modifier = Modifier.padding(top = 14.dp))
                            if (provider == "youtube") TextButton(
                                enabled = view != null && !saving && (onYouTubeMusic || youtubeLoadFailed),
                                onClick = {
                                    if (youtubeLoadFailed) view?.reload() else youtubeAttempt++
                                }
                            ) { Text(if (youtubeLoadFailed) "Retry" else "Use account") }
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                        if (!browserStopped) AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                            sessionWebView(context).apply {
                                webViewClient = object : SpotifySessionClient({ session -> runOnUiThread { saveSpotify(session) } }) {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !ConnectionHosts.allowed(request.url.toString(), provider)
                                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        if (provider == "youtube") {
                                            youtubeLoadFailed = false
                                            pageUrl = url.orEmpty()
                                            if (!isYouTubeMusicPage(url)) {
                                                youtubeAttempt = 0
                                                status = "Sign in on the service’s secure website."
                                            }
                                        }
                                    }
                                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                        if (provider == "youtube") pageUrl = (view.url ?: url).orEmpty()
                                    }
                                    override fun onPageFinished(view: WebView, url: String?) {
                                        if (provider == "youtube") pageUrl = (view.url ?: url).orEmpty()
                                        if (provider == "spotify" && Uri.parse(url).host == "open.spotify.com") {
                                            status = "Completing your Spotify connection…"
                                            view.evaluateJavascript("document.querySelectorAll('audio,video').forEach(function(m){m.pause();});", null)
                                        }
                                    }
                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                        if (provider == "youtube" && request.isForMainFrame) {
                                            youtubeLoadFailed = true
                                            status = "YouTube Music couldn’t load. Tap Retry to try again."
                                        }
                                    }
                                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                                        if (provider == "youtube" && request.isForMainFrame) {
                                            youtubeLoadFailed = true
                                            status = "YouTube Music couldn’t load. Tap Retry to try again."
                                        }
                                    }
                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                        if (provider == "spotify") super.shouldInterceptRequest(view, request) else null
                                    override fun onRenderProcessGone(deadView: WebView, detail: RenderProcessGoneDetail): Boolean {
                                        if (view === deadView) view = null
                                        status = "The browser process stopped unexpectedly. Close and try again."
                                        browserStopped = true
                                        return true
                                    }
                                }
                                view = this
                                loadUrl(if (manageYouTube) YOUTUBE_MUSIC_HOME
                                    else if (provider == "youtube") "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F"
                                    else "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                            }
                        }, onRelease = {
                            if (view === it) view = null
                            if (!browserStopped) it.stopLoading()
                            it.destroy()
                        })
                    }
                }
                BackHandler { if (view?.canGoBack() == true) view?.goBack() else finish() }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun sessionWebView(context: Context) = WebView(context).apply {
    layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    ConnectionBrowserProfile.attach(this)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mediaPlaybackRequiresUserGesture = true
    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.userAgentString = settings.userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
    ConnectionBrowserProfile.cookies().setAcceptCookie(true)
    ConnectionBrowserProfile.cookies().setAcceptThirdPartyCookies(this, true)
}

/** Let the first-party web page form its token request; no client secrets or token algorithms are embedded. */
private open class SpotifySessionClient(private val onSession: (SpotifyConnection) -> Unit) : WebViewClient() {
    @Volatile private var bearer = ""
    @Volatile private var expiry = 0L
    @Volatile private var clientToken = ""
    @Volatile private var firstPartyActive = false
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        firstPartyActive = url?.let(Uri::parse)?.let { it.scheme == "https" && it.host == "open.spotify.com" } == true
    }
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.scheme != "https" || !firstPartyActive) return null
        if (url.host == "api-partner.spotify.com") {
            val authorization = request.requestHeaders.entries.firstOrNull { it.key.equals("authorization", true) }?.value.orEmpty()
            if (authorization.startsWith("Bearer ") && bearer != authorization.removePrefix("Bearer ")) {
                bearer = authorization.removePrefix("Bearer "); expiry = System.currentTimeMillis() + 15 * 60_000L
            }
            val token = request.requestHeaders.entries.firstOrNull { it.key.equals("client-token", true) }?.value
            if (!token.isNullOrBlank()) { clientToken = token; publish() }
        }
        if (url.host != "open.spotify.com" || url.path != "/api/token" || request.method != "GET") return null
        return runCatching {
            val cookie = ConnectionBrowserProfile.cookies().getCookie("https://open.spotify.com").orEmpty()
            val forwarded = Request.Builder().url(url.toString()).header("Cookie", cookie).apply {
                request.requestHeaders.filterKeys { !it.equals("cookie", true) && !it.equals("host", true) }
                    .forEach { (key, value) -> header(key, value) }
            }.build()
            http.newCall(forwarded).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val source = response.body.source()
                if (source.request(512 * 1024L + 1)) return@use null
                val bytes = source.readByteArray()
                val body = JSONObject(String(bytes, Charsets.UTF_8))
                if (!body.optBoolean("isAnonymous", true) && body.optString("accessToken").isNotBlank()) {
                    bearer = body.getString("accessToken")
                    expiry = body.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis() + 30 * 60_000L)
                    publish()
                }
                WebResourceResponse("application/json", "UTF-8", response.code, response.message.ifBlank { "OK" },
                    mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes))
            }
        }.getOrNull()
    }
    private fun publish() {
        if (bearer.isBlank() || clientToken.isBlank()) return
        val cookie = ConnectionStore.cookieValue(ConnectionBrowserProfile.cookies().getCookie("https://open.spotify.com").orEmpty(), "sp_dc")
        if (cookie.isNotBlank()) onSession(SpotifyConnection(cookie, bearer, expiry, clientToken = clientToken))
    }
    companion object {
        private val http by lazy { OkHttpClient.Builder().followRedirects(false).connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build() }
    }
}

internal suspend fun refreshSpotifySession(context: Context, saved: SpotifyConnection): SpotifyConnection = withContext(Dispatchers.Main) {
    val result = CompletableDeferred<Unit>()
    var latest: SpotifyConnection? = null
    val cookies = ConnectionBrowserProfile.cookies()
    val cookieReady = CompletableDeferred<Boolean>()
    cookies.setCookie("https://open.spotify.com", "sp_dc=${saved.cookie}; Domain=.spotify.com; Path=/; Secure; HttpOnly") {
        cookieReady.complete(it)
    }
    check(withTimeout(10_000) { cookieReady.await() }) { "Spotify session cookie could not be restored" }
    val view = sessionWebView(context).apply {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching { setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) }
        }
        runCatching { setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null) }
    }
    var destroyedByCrash = false
    try {
        view.webViewClient = object : SpotifySessionClient({ session -> view.post {
            // Ignore the cached pair that triggered recovery; let the page finish renewing it.
            if (session.bearer != saved.bearer || session.clientToken != saved.clientToken) {
                latest = session
                result.complete(Unit)
            }
        } }) {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !ConnectionHosts.allowed(request.url.toString(), "spotify")
            override fun onRenderProcessGone(deadView: WebView, detail: RenderProcessGoneDetail): Boolean {
                destroyedByCrash = true
                runCatching { deadView.destroy() }
                result.completeExceptionally(java.io.IOException("Spotify refresh renderer stopped"))
                return true
            }
        }
        // A detached view still needs a viewport for the web player to initialize its library requests.
        val viewportWidth = (360 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val viewportHeight = (640 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(viewportWidth, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(viewportHeight, android.view.View.MeasureSpec.EXACTLY))
        view.layout(0, 0, viewportWidth, viewportHeight)
        // The web player performs its own challenge and token refresh with the stored session.
        view.loadUrl("https://open.spotify.com/?connection_refresh=${System.currentTimeMillis()}")
        withTimeout(45_000) { result.await(); checkNotNull(latest) }
    } finally {
        if (!destroyedByCrash) {
            runCatching { view.stopLoading() }
            runCatching { view.destroy() }
        }
    }
}

internal suspend fun clearConnectionBrowserData(provider: String) = withContext(Dispatchers.Main) {
    if (!ConnectionBrowserProfile.isSupported()) return@withContext
    val profile = ConnectionBrowserProfile.profile()
    val sites = if (provider == "youtube") listOf("youtube.com", "google.com") else listOf("spotify.com")
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
        val removed = withTimeoutOrNull(5_000L) {
            sites.forEach { site ->
                suspendCancellableCoroutine { continuation ->
                    WebStorageCompat.deleteBrowsingDataForSite(profile.webStorage, "https://$site/") {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
            true
        }
        if (removed == true) return@withContext
    }
    val origins = if (provider == "youtube") listOf(YOUTUBE_MUSIC_HOME, "https://www.youtube.com/", "https://accounts.google.com/", "https://google.com/")
        else listOf("https://open.spotify.com/", "https://accounts.spotify.com/", "https://spotify.com/")
    val cookies = profile.cookieManager
    origins.forEach { origin ->
        profile.webStorage.deleteOrigin(origin.removeSuffix("/"))
        val host = Uri.parse(origin).host.orEmpty()
        val domain = sites.firstOrNull { host == it || host.endsWith(".$it") } ?: return@forEach
        cookies.getCookie(origin).orEmpty().split(';').map { it.substringBefore('=').trim() }.filter(String::isNotBlank).forEach { name ->
            cookies.setCookie(origin, "$name=; Max-Age=0; Path=/; Secure")
            cookies.setCookie(origin, "$name=; Max-Age=0; Path=/; Domain=.$domain; Secure")
        }
    }
    withContext(Dispatchers.IO) { cookies.flush() }
}

private suspend fun WebView.readYouTubeIdentity(): String? = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(YOUTUBE_IDENTITY) { if (continuation.isActive) continuation.resume(it) }
}

private const val YOUTUBE_MUSIC_HOME = "https://music.youtube.com/"

private const val YOUTUBE_IDENTITY = """(function(){
  if(location.origin !== 'https://music.youtube.com' || !window.ytcfg || !ytcfg.get) return null;
  return {loggedIn:ytcfg.get('LOGGED_IN') === true, name:ytcfg.get('ACCOUNT_NAME')||ytcfg.get('CHANNEL_NAME')||'YouTube Music',
    visitor:ytcfg.get('VISITOR_DATA')||'', page:ytcfg.get('DELEGATED_SESSION_ID')||'', sync:ytcfg.get('DATASYNC_ID')||'', user:String(ytcfg.get('SESSION_INDEX')||'0')};
})()
"""
