package com.example.service

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.example.data.remote.YouTubeAudioStreamService
import com.example.playback.NetworkQualityMonitor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One optional writer. It never falls through to uncached network reads on cache contention. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object StreamedAudioPrefetch {
    const val OPENING_BYTES = 1024L * 1024L
    const val CHUNK_BYTES = 2L * 1024L * 1024L
    private const val MAX_FULL_BYTES = 64L * 1024L * 1024L

    suspend fun fetch(context: Context, uri: Uri, full: Boolean, allowed: () -> Boolean) {
        val coroutine = currentCoroutineContext()
        val epoch = MusicCache.prefetchEpoch.value
        var checkedAt = 0L
        var resources = true
        fun admitted(): Boolean {
            coroutine.ensureActive()
            val now = android.os.SystemClock.elapsedRealtime()
            if (checkedAt == 0L || now - checkedAt >= 1_000L) {
                checkedAt = now
                resources = NextPrefetchPolicy.resourcesAvailable(context) &&
                    NetworkQualityMonitor.getInstance(context).sourcePolicySnapshot().online
            }
            return allowed() && resources && MusicCache.prefetchEpoch.value == epoch
        }
        if (!admitted() || MusicCache.isLocal(uri) || uri.scheme == MusicCache.OFFLINE_SCHEME) return
        // Segmented/serialized transports require their own demux/session ownership.
        // Media3's imminent queue preparation remains their only speculative reader.
        if (uri.scheme == "xtreme-sabr" || uri.path.orEmpty().endsWith(".m3u8", true) ||
            uri.path.orEmpty().endsWith(".mpd", true)) return
        var address = uri
        var key = StreamedAudioRepresentations.keyFor(uri)
        var total: Long? = null
        var headers = com.example.data.remote.StreamingAddons.headers(uri.toString())
        if (uri.scheme == "xtreme-youtube") {
            val video = YouTubeAudioStreamService.videoIdFromUri(uri) ?: return
            val cap = minOf(NetworkQualityMonitor.getInstance(context).snapshot().effectiveCeiling.maxBitrateBps,
                YouTubeAudioStreamService.bitrateCapFromUri(uri) ?: Int.MAX_VALUE)
            val audio = YouTubeAudioStreamService.resolve(video,
                com.example.data.local.AudioQualityPreferences.getSelectedQuality(context), maxBitrateBps = cap,
                requireOpus = uri.getQueryParameter("codec") == "opus")
            if (!admitted() || audio.isSabr || audio.isHls || audio.contentLength == null) return
            address = Uri.parse(audio.url); key = audio.cacheKey; total = audio.contentLength; headers = audio.headers
            MusicCache.rememberRepresentation(context, audio)
        }
        if (address.scheme !in setOf("http", "https")) return
        val cacheKey = key ?: return // No verified recording/representation identity, no optional download.
        val cache = MusicCache.getSimpleCache(context)
        StreamedAudioRepresentations.persistBinding(cache, cacheKey)
        val length = total ?: androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
            .takeIf { it > 0 }
        if (MusicCache.fullyCached(context, cacheKey, length)) return
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(5_000).setReadTimeoutMs(5_000)
        val upstream = DataSource.Factory { YouTubeMediaDataSource(context, DefaultDataSource.Factory(context, http), http) }
        val budget = if (full && length != null && length <= MAX_FULL_BYTES) length else minOf(length ?: OPENING_BYTES, OPENING_BYTES)
        val spec = DataSpec.Builder().setUri(address).setKey(cacheKey).setHttpRequestHeaders(headers).build()
        try {
            StrictAudioCacheWriter.fetch(cache, upstream, spec, length, budget) {
                admitted()
            }
            val discovered = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
            if (full && length == null && discovered in (OPENING_BYTES + 1)..MAX_FULL_BYTES && admitted()) {
                StrictAudioCacheWriter.fetch(cache, upstream, spec, discovered, discovered) { admitted() }
            }
        } finally { MusicCache.scheduleUsageRefresh(context) }
    }
    fun preempt(key: String) = StrictAudioCacheWriter.preempt(key)
    fun preemptAll() = StrictAudioCacheWriter.preemptAll()
}
