package com.example.service

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.local.AudioQualityPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object MusicCache {
    const val DEFAULT_CACHE_SIZE = com.example.data.cache.SmartCacheManager.MIN_CACHE_BYTES -
        com.example.data.cache.SmartCacheManager.ARTWORK_CACHE_MAX_BYTES
    private var audioEvictor: HeadAwareAudioCacheEvictor? = null
    private val cacheEpoch = kotlinx.coroutines.flow.MutableStateFlow(0L)
    internal val prefetchEpoch: kotlinx.coroutines.flow.StateFlow<Long> = cacheEpoch
    private val usageScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val usagePending = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var ownerDirectory: File? = null
    const val OFFLINE_SCHEME = "xtreme-cached"
    internal fun isLocal(uri: android.net.Uri): Boolean = uri.scheme?.lowercase(java.util.Locale.ROOT) in
        setOf(null, "file", "content", "asset", "rawresource", "android.resource", "data")
    private val saavnRate = Regex("_(320|160|96|48)\\.(?:mp4|m4a)(?:$|[?#])")

    internal fun fullyCached(context: Context, key: String, knownLength: Long? = null): Boolean = runCatching {
        val cache = getSimpleCache(context)
        val indexed = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(key))
        val length = knownLength?.takeIf { it > 0 } ?: indexed
        length > 0 && (indexed <= 0 || indexed == length) && cache.isCached(key, 0L, length) &&
            cache.getCachedSpans(key).all { span -> span.file?.let { it.isFile && it.length() == span.length } == true }
    }.getOrDefault(false)

    /** Match the streaming loader's key, including bound addon representations. */
    internal fun fullyCachedResource(context: Context, uri: android.net.Uri): Boolean =
        fullyCached(context, StreamedAudioRepresentations.keyFor(uri) ?: safeTransportKey(uri))

    internal fun offlineAudio(context: Context, video: String, cap: Int, sabr: Boolean,
        opusOnly: Boolean = false): YouTubeAudioStreamService.AudioStream? {
        val cached = (YouTubeAudioStreamService.cachedStreams(video) + persistedRepresentations(context, video)).distinctBy { it.cacheKey }.filter {
            it.isSabr == sabr && !it.isHls && (!opusOnly || it.codec == "Opus") &&
                fullyCached(context, it.cacheKey, it.contentLength)
        }
        return cached.filter { com.example.data.remote.YouTubeAudioFormatPolicy.acceptsBitrate(it.bitrateKbps * 1000, cap) }
            .maxByOrNull { it.bitrateKbps }?.also(YouTubeAudioStreamService::rememberOfflineRepresentation)
    }

    private const val REPRESENTATION = "xtreme_audio_representation_v1"

    /** Stored in SimpleCache's own journal/index; no signed URL, cookie or account data. */
    internal fun rememberRepresentation(context: Context, stream: YouTubeAudioStreamService.AudioStream) {
        if (stream.isHls || !YouTubeAudioStreamService.isVideoId(stream.videoId) || stream.bitrateKbps <= 0) return
        val cache = getSimpleCache(context)
        val encoded = org.json.JSONObject().put("video", stream.videoId).put("codec", stream.codec)
            .put("itag", stream.itag).put("bitrate", stream.bitrateKbps).put("sabr", stream.isSabr)
            .put("length", stream.contentLength ?: 0L).put("cap", stream.selectionCapBps).toString()
        if (cache.getContentMetadata(stream.cacheKey).get(REPRESENTATION, "") != encoded)
            cache.applyContentMetadataMutations(stream.cacheKey,
                androidx.media3.datasource.cache.ContentMetadataMutations().set(REPRESENTATION, encoded).apply {
                    if (stream.contentLength?.let { it > 0 } == true &&
                        androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(stream.cacheKey)) <= 0)
                        set("exo_len", requireNotNull(stream.contentLength))
                })
    }

    private fun persistedRepresentations(context: Context, video: String): List<YouTubeAudioStreamService.AudioStream> {
        val cache = getSimpleCache(context)
        return cache.keys.asSequence().filter {
            it.startsWith("youtube:$video:") || it.startsWith("youtube-sabr:$video:")
        }.take(512).mapNotNull { key ->
            val encoded = cache.getContentMetadata(key).get(REPRESENTATION, "").orEmpty()
            if (encoded.isBlank() || encoded.length > 2_048) return@mapNotNull null
            runCatching {
                val meta = org.json.JSONObject(encoded)
                if (meta.getString("video") != video) return@runCatching null
                val codec = meta.getString("codec")
                val bitrate = meta.getInt("bitrate")
                val itag = meta.getInt("itag")
                if (codec !in setOf("AAC", "Opus") || bitrate !in 1..1_000 || itag <= 0) return@runCatching null
                YouTubeAudioStreamService.AudioStream(
                    url = "https://www.youtube.com/videoplayback?video_id=$video", bitrateKbps = bitrate, isHls = false,
                    cacheKey = key, expiresAt = 0L, headers = emptyMap(), codec = codec, itag = itag,
                    contentLength = meta.optLong("length").takeIf { it > 0 }, videoId = video,
                    useRangeChunks = false, rangeChunkSize = 0L, clientId = "", selectionCapBps = meta.getInt("cap"),
                    cachedSabr = meta.optBoolean("sabr"))
            }.getOrNull()
        }.toList()
    }

    @Volatile
    private var simpleCacheInstance: SimpleCache? = null

    /** Read an existing owner's index without opening/scanning a cache at startup. */
    fun peekCacheUsage(): Pair<Long, Int>? = runCatching {
        simpleCacheInstance?.let { it.cacheSpace to it.keys.size }
    }.getOrNull()

    fun getSimpleCache(context: Context): SimpleCache {
        com.example.performance.MaintenanceScheduler.onCacheUsed(context)
        simpleCacheInstance?.let { return it }
        return synchronized(this) { simpleCacheInstance ?: run {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Audio cache must open on IO" }
            val cacheDir = PersistentAudioCacheMigration.directory(context)
            val evictor = HeadAwareAudioCacheEvictor(com.example.data.cache.SmartCacheManager.audioBudget(
                com.example.data.cache.SmartCacheManager.configuredQuota(context)))
            val databaseProvider = StandaloneDatabaseProvider(context)
            val cache = SimpleCache(cacheDir, evictor, databaseProvider)
            ownerDirectory = cacheDir
            audioEvictor = evictor
            simpleCacheInstance = cache
            cache
        } }
    }

    /** Change the existing cache owner in place; do not recreate it or interrupt playback. IO only. */
    internal fun applyConfiguredLimit(context: Context) {
        val cache = simpleCacheInstance ?: return
        synchronized(cache) {
            audioEvictor?.setMaximumBytes(cache, com.example.data.cache.SmartCacheManager.audioBudget(
                com.example.data.cache.SmartCacheManager.configuredQuota(context)))
        }
        peekCacheUsage()?.let { (bytes, count) ->
            com.example.data.cache.SmartCacheManager.cacheUsageChanged(audioBytes = bytes, audioTrackCount = count)
        }
        scheduleUsageRefresh(context)
    }

    internal fun isManifestRequest(spec: androidx.media3.datasource.DataSpec): Boolean =
        spec.isFlagSet(androidx.media3.datasource.DataSpec.FLAG_ALLOW_GZIP) ||
            spec.uri.path.orEmpty().endsWith(".m3u8", true) || spec.uri.path.orEmpty().endsWith(".mpd", true) ||
            YouTubeAudioStreamService.cachedForUrl(spec.uri.toString())?.isHls == true

    fun createCacheDataSourceFactory(context: Context, connectTimeoutMs: Int = 15000,
        readTimeoutMs: Int = 15000): DataSource.Factory {
        val app = context.applicationContext
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(connectTimeoutMs)
            .setReadTimeoutMs(readTimeoutMs)

        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val rangedUpstream = DataSource.Factory {
            val source = YouTubeMediaDataSource(app, upstreamFactory, httpDataSourceFactory)
            object : DataSource by source {
                private var requested: android.net.Uri? = null
                override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                    requested = spec.uri
                    return source.open(spec)
                }
                // CacheDataSource must not journal a signed CDN redirect URI.
                override fun getUri() = requested
                override fun close() { try { source.close() } finally { requested = null } }
            }
        }

        // Creating a Media3 factory happens on Main. Cache recovery happens only
        // when its data source opens on Media3's loader thread.
        val cached = DataSource.Factory { object : DataSource {
            private var delegate: DataSource? = null
            private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
            override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {
                listeners.add(listener); delegate?.addTransferListener(listener)
            }
            override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                // Local audio already owns its bytes. Bypass both cache initialization
                // and network-policy reads, including SAF and Android resources.
                if (isLocal(spec.uri)) {
                    val source = upstreamFactory.createDataSource()
                    listeners.forEach(source::addTransferListener)
                    delegate = source
                    return source.open(spec)
                }
                val online = com.example.playback.NetworkQualityMonitor.getInstance(app).sourcePolicySnapshot().online
                if (isManifestRequest(spec)) {
                    if (!online) throw IOException("An adaptive stream needs an online manifest or an explicit download")
                    // Signed segment URLs belong to the live tracker. Never persist
                    // its playlist/MPD body in streamed-audio storage.
                    val source = YouTubeMediaDataSource(app, upstreamFactory, httpDataSourceFactory)
                    listeners.forEach(source::addTransferListener)
                    delegate = source
                    return try { source.open(spec) } catch (error: Exception) { close(); throw error }
                }
                val selectedKey = spec.key ?: StreamedAudioRepresentations.keyFor(spec.uri) ?: safeTransportKey(spec.uri)
                StreamedAudioPrefetch.preempt(selectedKey)
                val source: DataSource = try { CacheDataSource.Factory().setCache(getSimpleCache(app))
                    .setCacheKeyFactory { request -> request.key ?: StreamedAudioRepresentations.keyFor(request.uri)
                        ?: safeTransportKey(request.uri) }
                    .setUpstreamDataSourceFactory(rangedUpstream.takeIf { online })
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).createDataSource()
                } catch (failure: Exception) {
                    if (!online) throw IOException("Cached audio is unavailable offline", failure)
                    rangedUpstream.createDataSource()
                }
                listeners.forEach(source::addTransferListener)
                delegate = source
                return try { source.open(spec) } catch (failure: Cache.CacheException) {
                    close()
                    if (!online) throw failure
                    val upstream = rangedUpstream.createDataSource()
                    listeners.forEach(upstream::addTransferListener)
                    delegate = upstream
                    try { upstream.open(spec) } catch (error: Exception) { close(); throw error }
                } catch (error: Exception) { close(); throw error }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int) =
                checkNotNull(delegate).read(buffer, offset, length)
            override fun getUri() = delegate?.uri
            override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
            override fun close() {
                try { delegate?.close() } finally {
                    delegate = null
                    scheduleUsageRefresh(app)
                    peekCacheUsage()?.let { (bytes, count) ->
                        com.example.data.cache.SmartCacheManager.cacheUsageChanged(audioBytes = bytes, audioTrackCount = count)
                    }
                }
            }
        } }
        // Media3 calls this on its loader thread. Refresh signed video URLs at load time,
        // including restored queues, without holding the UI thread or storing stale URLs.
        return DataSource.Factory {
            // Per MediaPeriod/DataSource, not a global URL map: a new track uses
            // today's policy, while routine close/reopen/seek retains its format.
            var pinnedCeiling: Int? = null
            var offline = false
            var boundAudio: YouTubeAudioStreamService.AudioStream? = null
            var boundJioUri: android.net.Uri? = null
            ResolvingDataSource(cached.createDataSource()) { spec ->
                val host = spec.uri.host.orEmpty()
                val saavn = host == "saavncdn.com" || host.endsWith(".saavncdn.com")
                val youtube = spec.uri.scheme in setOf("xtreme-sabr", "xtreme-youtube") ||
                    YouTubeAudioStreamService.isMediaUrl(spec.uri.toString())
                val archive = host == "archive.org" || host.endsWith(".archive.org")
                val addon = com.example.data.remote.StreamingAddons.hasRegisteredTransport(spec.uri.toString())
                if (youtube || saavn || archive || addon) {
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                        throw IOException("Audio admission must run on the Media3 loader thread")
                    runBlocking(Dispatchers.IO) { com.example.data.local.OtherSettingsPreferences.loadPlaybackSources(app) }
                    val active = com.example.playback.NetworkQualityMonitor.getInstance(app).sourcePolicySnapshot()
                    val allowed = when {
                        addon -> com.example.data.remote.StreamingAddons.allowsRegisteredTransport(spec.uri.toString(), active)
                        youtube -> active.allows(com.example.playback.PlaybackSourceKind.YOUTUBE)
                        saavn -> active.allows(com.example.playback.PlaybackSourceKind.JIOSAAVN)
                        else -> active.maximum && active.allows(com.example.playback.PlaybackSourceKind.INTERNET_ARCHIVE)
                    }
                    if (!allowed) throw com.example.playback.PlaybackDiagnostics.Failure(
                        com.example.playback.PlaybackDiagnostics.Reason.POLICY, com.example.playback.PlaybackDiagnostics.Stage.LOADING)
                    // Keep byte offsets pinned to the representation, but never let
                    // its next renewal exceed a newly lowered active-network cap.
                    pinnedCeiling = pinnedCeiling?.let { minOf(it, active.ceiling.maxBitrateBps) }
                    offline = !active.online
                }
                if (spec.uri.scheme in setOf("xtreme-sabr", "xtreme-youtube") || saavn) {
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                        throw IOException("Audio resolution must run on the Media3 loader thread")
                    if (pinnedCeiling == null) {
                        val policy = com.example.playback.NetworkQualityMonitor.getInstance(app).snapshot()
                        val referenceCap = YouTubeAudioStreamService.bitrateCapFromUri(spec.uri)
                        pinnedCeiling = minOf(policy.effectiveCeiling.maxBitrateBps, referenceCap ?: Int.MAX_VALUE)
                        offline = policy.connectionType == com.example.playback.NetworkConnectionType.OFFLINE
                    }
                }
                if (saavn) {
                    val target = boundJioUri ?: run {
                        val quality = AudioQualityPreferences.getSelectedQuality(app)
                        val current = saavnRate.find(spec.uri.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 320
                        val rate = com.example.playback.AudioQualityCeiling.saavnKbps(quality.kbps, current,
                            requireNotNull(pinnedCeiling))
                        val requested = com.example.data.remote.OnlineMusicApiService.formatUrlForQuality(spec.uri.toString(), rate.toString())
                        val cached = if (offline) listOf(48, 96, 160, 320).map { value ->
                            value to com.example.data.remote.OnlineMusicApiService.formatUrlForQuality(spec.uri.toString(), value.toString())
                        }.filter { fullyCached(app, it.second) } else emptyList()
                        val available = cached.filter { it.first <= rate }.maxByOrNull { it.first }
                        if (offline && available == null) throw IOException("No cached audio fits the active quality profile")
                        android.net.Uri.parse(available?.second ?: requested)
                    }.also { boundJioUri = it }
                    spec.buildUpon().setUri(target).setKey(if (target == spec.uri) spec.key else StreamedAudioRepresentations.keyFor(target) ?: safeTransportKey(target)).build()
                } else if (spec.uri.scheme == "xtreme-sabr") {
                    val video = YouTubeAudioStreamService.videoIdFromUri(spec.uri)
                        ?: throw IOException("Invalid SABR recording")
                    val audio = (if (offline) offlineAudio(app, video, requireNotNull(pinnedCeiling), sabr = true,
                        opusOnly = spec.uri.getQueryParameter("codec") == "opus") else null) ?:
                        if (offline) throw IOException("No compatible cached SABR audio") else try { runBlocking(Dispatchers.IO) {
                        YouTubeAudioStreamService.resolveSabr(video, AudioQualityPreferences.getSelectedQuality(app),
                            maxBitrateBps = pinnedCeiling, requireOpus = spec.uri.getQueryParameter("codec") == "opus")
                    } } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt(); throw IOException("Audio resolution interrupted", e)
                    } catch (e: Exception) { throw IOException("Couldn't resolve SABR audio", e) }
                    val expected = boundAudio?.cacheKey ?: SabrSessionRegistry.cacheKeyFromUri(spec.uri)
                    if ((boundAudio != null || spec.position > 0) && expected != audio.cacheKey) {
                        SabrSessionRegistry.recordReplacement(spec.uri.toString(), audio)
                        throw SabrStreamChangedException(audio)
                    }
                    boundAudio = audio
                    rememberRepresentation(app, audio)
                    spec.buildUpon().setUri(android.net.Uri.parse(audio.url)).setKey(audio.cacheKey).build()
                } else if (spec.uri.scheme == "xtreme-youtube") {
                    val videoId = spec.uri.pathSegments.firstOrNull().orEmpty()
                    val hls = spec.uri.lastPathSegment?.endsWith(".m3u8") == true
                    val audio = (if (offline && !hls) offlineAudio(app, videoId, requireNotNull(pinnedCeiling), sabr = false,
                        opusOnly = spec.uri.getQueryParameter("codec") == "opus") else null) ?:
                        if (offline) throw IOException("No compatible cached YouTube audio") else try {
                        runBlocking(Dispatchers.IO) {
                            YouTubeAudioStreamService.resolve(videoId, AudioQualityPreferences.getSelectedQuality(app), hlsOnly = hls,
                                maxBitrateBps = pinnedCeiling, requireOpus = spec.uri.getQueryParameter("codec") == "opus")
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Audio resolution interrupted", e)
                    } catch (e: Exception) { throw IOException("Couldn't resolve YouTube audio", e) }
                    if (audio.isHls && !hls || audio.isSabr || boundAudio?.cacheKey?.let { it != audio.cacheKey } == true) {
                        SabrSessionRegistry.recordReplacement(spec.uri.toString(), audio)
                        throw SabrStreamChangedException(audio)
                    }
                    if (!hls && (boundAudio != null || spec.position > 0)) {
                        val expectedItag = boundAudio?.itag ?: spec.uri.getQueryParameter("itag")?.toIntOrNull()
                        val expectedLength = boundAudio?.contentLength ?: spec.uri.getQueryParameter("length")?.toLongOrNull()
                        if (expectedItag != null && expectedItag != audio.itag ||
                            expectedLength != null && audio.contentLength != null && expectedLength != audio.contentLength) {
                            SabrSessionRegistry.recordReplacement(spec.uri.toString(), audio)
                            throw SabrStreamChangedException(audio)
                        }
                    }
                    boundAudio = audio
                    rememberRepresentation(app, audio)
                    spec.buildUpon().setUri(audio.url).setKey(if (hls) spec.key else audio.cacheKey)
                        .setHttpRequestHeaders(spec.httpRequestHeaders + audio.headers).build()
                } else if (YouTubeAudioStreamService.isMediaUrl(spec.uri.toString())) {
                    val audio = YouTubeAudioStreamService.cachedForUrl(spec.uri.toString())
                    spec.buildUpon().setKey(audio?.takeUnless { it.isHls }?.cacheKey ?: spec.key)
                        .setHttpRequestHeaders(spec.httpRequestHeaders + YouTubeAudioStreamService.headersFor(spec.uri.toString())).build()
                } else spec.buildUpon().setHttpRequestHeaders(spec.httpRequestHeaders +
                    com.example.data.remote.StreamingAddons.headers(spec.uri.toString())).build()
            }
        }
    }

    internal fun scheduleUsageRefresh(context: Context) {
        if (!usagePending.compareAndSet(false, true)) return
        usageScope.launch {
            try {
                kotlinx.coroutines.delay(500L)
                val cache = simpleCacheInstance ?: return@launch
                com.example.data.cache.SmartCacheManager.cacheUsageChanged(audioBytes = cache.cacheSpace,
                    audioTrackCount = cache.keys.size, completeAudioTrackCount = getCompleteCachedTrackCount(context))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { android.util.Log.d("MusicCache", "Audio usage refresh deferred", failure) }
            finally { usagePending.set(false) }
        }
    }

    internal fun safeTransportKey(uri: android.net.Uri): String {
        val host = uri.host.orEmpty()
        if ((host == "saavncdn.com" || host.endsWith(".saavncdn.com")) && uri.query == null)
            return uri.toString()
        return "transport:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun getCompleteCachedTrackCount(context: Context): Int {
        val cache = getSimpleCache(context)
        val adopted = StreamedAudioRepresentations.adoptedYoutubeVideos(cache)
        val videos = cache.keys.mapNotNull { key ->
            val metadata = cache.getContentMetadata(key).get(REPRESENTATION, "").orEmpty()
            if (metadata.isBlank()) null else runCatching {
                val json = org.json.JSONObject(metadata)
                val length = json.optLong("length").takeIf { it > 0 }
                if (fullyCached(context, key, length)) json.getString("video") else null
            }.getOrNull()
        }.toSet().minus(adopted).size
        return videos + StreamedAudioRepresentations.completeSongCount(cache)
    }

    /** Cache-only source retains the session item; no resolver or HTTP fallback is constructed. */
    internal fun cachedSource(context: Context, item: androidx.media3.common.MediaItem): androidx.media3.exoplayer.source.MediaSource {
        val uri = requireNotNull(item.localConfiguration).uri
        val key = uri.pathSegments.firstOrNull() ?: throw IOException("Invalid cached recording")
        val data = DataSource.Factory {
            object : DataSource {
                private var delegate: DataSource? = null
                private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
                override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) { listeners += listener }
                override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                    val policy = com.example.playback.NetworkQualityMonitor.getInstance(context).sourcePolicySnapshot()
                    if (uri.getQueryParameter("track") != item.mediaId || !StreamedAudioRepresentations.validateCached(context, uri, policy) || !fullyCached(context, key))
                        throw IOException("Cached audio is incomplete or incompatible with the active profile")
                    val source = CacheDataSource.Factory().setCache(getSimpleCache(context))
                        .setCacheKeyFactory { key }.setUpstreamDataSourceFactory(null)
                        .setCacheWriteDataSinkFactory(null).createDataSource()
                    delegate = source
                    listeners.forEach(source::addTransferListener)
                    return try { source.open(spec.buildUpon().setKey(key).build()) }
                        catch (error: Exception) { close(); throw error }
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(delegate).read(buffer, offset, length)
                override fun getUri() = delegate?.uri
                override fun close() { try { delegate?.close() } finally { delegate = null } }
            }
        }
        val mime = uri.getQueryParameter("mime") ?: item.localConfiguration?.mimeType
        return androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(data)
            .createMediaSource(item.buildUpon().setMimeType(mime).setCustomCacheKey(key).build())
    }

    /** Only a decoder/parser-confirmed corrupt representation is discarded. Network failures keep audio. */
    internal fun invalidateCorruptRepresentation(context: Context, uri: android.net.Uri) {
        if (uri.scheme != OFFLINE_SCHEME) return
        val key = uri.pathSegments.singleOrNull() ?: return
        if (!key.startsWith("stream:v1:") && !key.startsWith("youtube:") && !key.startsWith("youtube-sabr:")) return
        StreamedAudioPrefetch.preempt(key)
        getSimpleCache(context).removeResource(key)
        scheduleUsageRefresh(context)
    }

    internal suspend fun prefetch(context: Context, uri: android.net.Uri, full: Boolean, allowed: () -> Boolean) =
        StreamedAudioPrefetch.fetch(context, uri, full, allowed)

    fun getCacheSize(context: Context): Long {
        return try {
            getSimpleCache(context).cacheSpace
        } catch (e: Exception) {
            val cacheDir = ownerDirectory ?: File(context.noBackupFilesDir, "xtreme_streamed_audio_cache")
            if (cacheDir.exists()) getFolderSize(cacheDir) else 0L
        }
    }

    fun getCachedTrackCount(context: Context): Int {
        return try {
            getSimpleCache(context).keys.size
        } catch (e: Exception) {
            val cacheDir = ownerDirectory ?: File(context.noBackupFilesDir, "xtreme_streamed_audio_cache")
            if (cacheDir.exists()) (cacheDir.listFiles()?.count { it.isFile && it.name.endsWith(".exo") } ?: 0) else 0
        }
    }

    fun clearAudioCache(context: Context): Long {
        cacheEpoch.value += 1L
        StreamedAudioPrefetch.preemptAll()
        val removed = trimAudioCache(context, 0L)
        val legacyBytes = PersistentAudioCacheMigration.clearLegacy(context,
            ownerDirectory ?: File(context.noBackupFilesDir, "xtreme_streamed_audio_cache"))
        scheduleUsageRefresh(context)
        return removed + legacyBytes
    }

    /** Remove committed spans through Media3 so its index and active writers stay consistent. */
    fun trimAudioCache(context: Context, targetBytes: Long): Long {
        return try {
            val cache = getSimpleCache(context)
            val before = cache.cacheSpace
            val spans = cache.keys.flatMap { cache.getCachedSpans(it).toList() }
                .sortedBy { it.lastTouchTimestamp }
            for (span in spans) {
                if (cache.cacheSpace <= targetBytes.coerceAtLeast(0L)) break
                cache.removeSpan(span)
            }
            com.example.data.cache.SmartCacheManager.cacheUsageChanged(audioBytes = cache.cacheSpace,
                audioTrackCount = cache.keys.size, completeAudioTrackCount = getCompleteCachedTrackCount(context))
            (before - cache.cacheSpace).coerceAtLeast(0L)
        } catch (e: Exception) {
            android.util.Log.w("MusicCache", "Cache trim error: ${e.message}")
            0L
        }
    }

    private fun getFolderSize(file: File): Long {
        var size = 0L
        val children = file.listFiles() ?: return 0L
        for (child in children) {
            size += if (child.isDirectory) getFolderSize(child) else child.length()
        }
        return size
    }
}

/** Source compatibility for existing callers; the persistent owner uses the same dynamic evictor. */
internal typealias AdjustableAudioCacheEvictor = HeadAwareAudioCacheEvictor
