package com.example.data.remote

import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** Shared by add-on discovery, media, and DRM. No release or debug override permits HTTP. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object AddonHttps {
    const val REQUIRED = "Add-ons require HTTPS. Update the server URL to use HTTPS."
    class Required : IOException(REQUIRED)

    fun isAllowed(url: HttpUrl): Boolean = url.isHttps && url.username.isEmpty() && url.password.isEmpty()
    fun isAllowed(url: String): Boolean = url.toHttpUrlOrNull()?.let(::isAllowed) == true

    fun client(builder: OkHttpClient.Builder): OkHttpClient = builder
        .followSslRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            if (!isAllowed(request.url)) throw Required()
            val response = chain.proceed(request)
            if (response.isRedirect) {
                val target = response.header("Location")?.let(response.request.url::resolve)
                if (target != null && !isAllowed(target)) {
                    response.close()
                    throw Required()
                }
            }
            response
        }
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (!isAllowed(request.url)) throw Required()
            val origin = chain.call().request().url
            if (origin.host == request.url.host && origin.port == request.url.port) chain.proceed(request)
            else {
                // Add-ons may supply arbitrary auth header names. Redirects to
                // another origin get only the headers needed for media transport.
                val redirected = request.newBuilder()
                request.headers.names().filterNot { name ->
                    name.lowercase(java.util.Locale.ROOT) in setOf("host", "connection", "accept-encoding",
                        "range", "content-type", "content-length", "transfer-encoding")
                }.forEach(redirected::removeHeader)
                chain.proceed(redirected.build())
            }
        }.build()

    fun requireMediaUri(uri: android.net.Uri) {
        val url = uri.toString()
        if (!isAllowed(url) && !url.startsWith("data:application/dash+xml;base64,")) throw Required()
    }

    private val media by lazy { client(OkHttpClient.Builder()) }
    fun mediaFactory(connectTimeoutMs: Int, readTimeoutMs: Int): HttpDataSource.Factory =
        OkHttpDataSource.Factory((StreamingAddons.testClient ?: media).newBuilder()
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS).build())
}
