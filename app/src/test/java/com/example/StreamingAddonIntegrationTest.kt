package com.example

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.OtherSettingsPreferences
import com.example.data.local.ReactiveSettingsRepository
import com.example.data.model.MusicTrack
import com.example.data.remote.StreamingAddons
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackSourcePolicy
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class StreamingAddonIntegrationTest {
    @Test fun versionedFlacRelayUsesExplicitMimeAndNoDrm() {
        val candidate = song.copy(id = StreamingAddons.catalogTrackId(base, "deezer-654"))
        val body = JSONObject().put("codec", "flac").put("cipher", "NONE").put("format", "flac")
            .put("playback", JSONObject().put("version", 1).put("type", "progressive").put("uri", "$base/_playback/test/media")
                .put("mimeType", "audio/flac").put("codec", "flac")).toString()
        val result = StreamingAddons.parseStream(body, base, song, candidate, maximum)!!
        val item = StreamingAddons.prepareMediaItem(androidx.media3.common.MediaItem.fromUri(result.audioUrl))
        assertEquals(MimeTypes.AUDIO_FLAC, item.localConfiguration!!.mimeType)
        assertNull(item.localConfiguration!!.drmConfiguration)
        assertTrue(result.isLossless)
    }

    @Test fun licensedWidevineDescriptorIsAppliedToTheActualMedia3Item() {
        val candidate = song.copy(id = StreamingAddons.catalogTrackId(base, "tidal-123"))
        val license = "https://backend.example/_playback/test/license"
        val body = JSONObject().put("codec", "flac").put("format", "dash")
            .put("playback", JSONObject().put("version", 1).put("type", "dash").put("uri", "https://cdn.example/full.mpd")
                .put("mimeType", MimeTypes.APPLICATION_MPD).put("codec", "flac").put("requiresLicense", true)
                .put("drm", JSONObject().put("scheme", "widevine").put("licenseUrl", license))).toString()
        val result = StreamingAddons.parseStream(body, base, song, candidate, maximum)!!
        val item = StreamingAddons.prepareMediaItem(androidx.media3.common.MediaItem.fromUri(result.audioUrl))
        assertEquals(MimeTypes.APPLICATION_MPD, item.localConfiguration!!.mimeType)
        val drm = item.localConfiguration!!.drmConfiguration!!
        assertEquals(androidx.media3.common.C.WIDEVINE_UUID, drm.scheme)
        assertEquals(license, drm.licenseUri.toString())
        assertTrue(drm.forceDefaultLicenseUri)
    }

    @Test fun addonManifestsWithoutAMimeTypeKeepScopedSegmentHeadersAndLicensedDash() = runBlocking {
        enable()
        val license = "https://backend.example/_playback/test/license"
        for ((extension, mime) in listOf("mpd" to MimeTypes.APPLICATION_MPD, "m3u8" to MimeTypes.APPLICATION_M3U8)) {
            val manifestFolder = "$base/$extension"
            val manifestUrl = "$manifestFolder/index.$extension?token=signed"
            val transport = JSONObject().put("version", 1).put("uri", manifestUrl)
                .put("headers", JSONObject().put("Authorization", "Bearer addon-$extension"))
            if (extension == "mpd") {
                transport.put("requiresLicense", true)
                    .put("drm", JSONObject().put("scheme", "widevine").put("licenseUrl", license))
            }
            val result = checkNotNull(StreamingAddons.parseStream(JSONObject().put("codec", "flac")
                .put("bitrate", 2_000_000).put("playback", transport).toString(), base, song, song, maximum))
            val item = StreamingAddons.prepareMediaItem(androidx.media3.common.MediaItem.fromUri(result.audioUrl))
            assertEquals(mime, item.localConfiguration!!.mimeType)
            assertEquals(license.takeIf { extension == "mpd" }, item.localConfiguration!!.drmConfiguration?.licenseUri?.toString())

            val segment = "$manifestFolder/segment-1.m4s"
            assertEquals("Bearer addon-$extension", StreamingAddons.headers(segment)["Authorization"])
            assertTrue(StreamingAddons.allowsRegisteredTransport(segment, maximum))
            assertTrue(StreamingAddons.headers("$base/unrelated/segment-1.m4s").isEmpty())
            assertTrue(StreamingAddons.headers("https://another.example/$extension/segment-1.m4s").isEmpty())
            assertEquals("A manifest is not evidence of its currently decoded bitrate", 0,
                StreamingAddons.bitrateForStream(result.audioUrl, "FLAC"))
        }
    }

    @Test fun rawEncryptedDeezerAndUnlicensedDrmAreNotAcceptedAsPlainFlac() {
        val candidate = song.copy(id = StreamingAddons.catalogTrackId(base, "123"))
        assertNull(StreamingAddons.parseStream("""{"url":"https://cdn.example/raw","codec":"flac","cipher":"BF_CBC_STRIPE"}""", base, song, candidate, maximum))
        val body = JSONObject().put("codec", "flac").put("playback", JSONObject().put("version", 1).put("uri", "https://cdn.example/full.mpd")
            .put("mimeType", MimeTypes.APPLICATION_MPD).put("requiresLicense", true)).toString()
        assertNull(StreamingAddons.parseStream(body, base, song, candidate, maximum))
    }

    @Test fun atmosNegotiationAndStereoUseSeparateResolutionCaches() = runBlocking {
        enable()
        stream = {
            if (requests.last().contains("atmos=auto")) """{"url":"$base/immersive.ec3","codec":"eac3-joc","bitrate":768000}"""
            else """{"url":"$base/original.flac","codec":"flac","bitrate":1411200}"""
        }
        val on = maximum.copy(dolbyAtmos = true)
        val immersive = StreamingAddons.resolve(context, song, on)!!
        assertTrue(immersive.audioUrl.endsWith("immersive.ec3")); assertFalse(immersive.isLossless)
        val stereo = StreamingAddons.resolve(context, song, maximum)!!
        assertTrue(stereo.audioUrl.endsWith("original.flac")); assertTrue(stereo.isLossless)
        assertFalse(StreamingAddons.isVerified(immersive.audioUrl, maximum))
        assertTrue(requests.any { it.contains("stream/right") && it.contains("atmos=auto") })
        assertTrue(requests.any { it.contains("stream/right") && it.contains("atmos=none") })
        val before = requests.size
        assertEquals(immersive.audioUrl, StreamingAddons.resolve(context, song, on)!!.audioUrl)
        assertEquals(before, requests.size)
    }

    @Test fun unsupportedAtmosResponseRetriesTheSameRecordingInStereo() = runBlocking {
        enable()
        stream = {
            if (requests.last().contains("atmos=auto")) "{}"
            else """{"url":"$base/safe.flac","codec":"flac","bitrate":1411200}"""
        }
        val resolved = StreamingAddons.resolve(context, song, maximum.copy(dolbyAtmos = true))!!
        assertEquals(song.id, resolved.id)
        assertTrue(resolved.audioUrl.endsWith("safe.flac"))
        assertTrue(requests.any { it.contains("stream/right") && it.contains("atmos=auto") })
        assertTrue(requests.any { it.contains("stream/right") && it.contains("atmos=none") })
    }
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var repository: ReactiveSettingsRepository
    private lateinit var context: Context
    private lateinit var server: HttpServer
    private lateinit var serverExecutor: java.util.concurrent.ExecutorService
    private lateinit var base: String
    private val requests = CopyOnWriteArrayList<String>()
    private var stream: () -> String = { "" }
    private var emptySearch = false
    private var onManifest: () -> Unit = {}
    private var searchResponse: ((String) -> String)? = null
    private var manifest = """{"id":"personal","name":"My Music","resources":["search","stream"]}"""
    private var manifestStatus = 200
    private val song = MusicTrack("online_song", "Song", "Artist", "Album", 180_000, "cover", "https://aac.saavncdn.com/song_320.mp4")
    private val maximum get() = PlaybackSourcePolicy(AudioQualityCeiling.MAX,
        sources = OtherSettingsPreferences.playbackIntegrations(context).sourceList(AudioQualityCeiling.MAX))

    @Before fun setup() = runBlocking {
        withContext(Dispatchers.IO) { app.getSharedPreferences("xtreme_other_settings", 0).edit().clear().commit(); app.getSharedPreferences("xtreme_playback_sources_prefs", 0).edit().clear().commit() }
        repository = ReactiveSettingsRepository(app)
        repository.preload()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = repository.preferences(name)
        }
        serverExecutor = java.util.concurrent.Executors.newCachedThreadPool()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = serverExecutor
            createContext("/a/token/") { exchange ->
                requests.add(exchange.requestURI.toString())
                val response = when {
                    exchange.requestURI.path.endsWith("manifest.json") -> { onManifest(); manifest }
                    exchange.requestURI.path.endsWith("search") -> searchResponse?.invoke(exchange.requestURI.toString())
                        ?: if (emptySearch) """{"tracks":[]}""" else """{"tracks":[
                        {"id":"wrong","title":"Song (Live)","artist":"Artist","album":"Album","duration":180},
                        {"id":"right","title":"Song","artist":"Artist","album":"Album","duration":180},
                        {"id":"missing","title":"Unknown"}]}"""
                    "/stream/" in exchange.requestURI.path -> stream()
                    else -> "{}"
                }.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (exchange.requestURI.path.endsWith("manifest.json")) manifestStatus else 200,
                    response.size.toLong())
                exchange.responseBody.use { it.write(response) }; exchange.close()
            }
            start()
        }
        base = "http://127.0.0.1:${server.address.port}/a/token"
        stream = { """{"stream":{"url":"$base/song.flac","codec":"flac","format":"flac","bitrate":1411200}}""" }
    }
    @After fun cleanup(): Unit = runBlocking {
        repository.awaitPendingWrites(); repository.close(); server.stop(0); serverExecutor.shutdownNow()
    }
    private suspend fun enable() {
        OtherSettingsPreferences.saveStreamingAddon(context, base, "My Music")
        OtherSettingsPreferences.setStreamingAddonEnabled(context, true)
        val sources = listOf(com.example.playback.PlaybackSource(
            com.example.playback.PlaybackSource.addonId(base), com.example.playback.PlaybackSourceKind.ADDON,
            "My Music", baseUrl = base)) + com.example.playback.PlaybackSource.builtIns
        OtherSettingsPreferences.savePlaybackSources(context, sources)
        withTimeout(3_000) {
            while (OtherSettingsPreferences.loadPlaybackSources(context) != sources) delay(10)
        }
    }

    @Test fun multipleSavedServersRetainSeparateIdentitiesAndCanBeDisabledIndividually() = runBlocking {
        val secondBase = base.replace("/a/token", "/b/token")
        server.createContext("/b/token/") { exchange ->
            requests.add(exchange.requestURI.toString())
            val response = when {
                exchange.requestURI.path.endsWith("search") -> """{"tracks":[{"id":"right","title":"Song","artist":"Artist","album":"Album","duration":180}]}"""
                else -> """{"url":"$secondBase/song.flac","codec":"flac","bitrate":1411200}"""
            }.toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }; exchange.close()
        }
        fun custom(url: String, name: String) = com.example.playback.PlaybackSource(
            com.example.playback.PlaybackSource.addonId(url), com.example.playback.PlaybackSourceKind.ADDON, name, baseUrl = url)
        val first = custom(base, "First"); val second = custom(secondBase, "Second")
        val sources = listOf(first, second) + com.example.playback.PlaybackSource.builtIns
        OtherSettingsPreferences.savePlaybackSources(context, sources)
        withTimeout(3000) { while (OtherSettingsPreferences.loadPlaybackSources(context) != sources) delay(10) }
        val policy = maximum.copy(sources = sources)
        val tracks = StreamingAddons.search(context, "Song", policy)
        assertEquals(3, tracks.size)
        assertTrue(tracks.any { StreamingAddons.ownsTrack(first, it.id) })
        val selected = tracks.single { StreamingAddons.ownsTrack(second, it.id) }
        val played = StreamingAddons.resolve(context, selected, policy, source = second)!!
        assertEquals(selected.id, played.id); assertEquals("$secondBase/song.flac", played.audioUrl)
        assertTrue(StreamingAddons.isVerified(played.audioUrl, policy))
        val changed = sources.map { if (it.id == second.id) it.copy(enabled = false) else it }
        OtherSettingsPreferences.savePlaybackSources(context, changed)
        withTimeout(3000) { while (OtherSettingsPreferences.loadPlaybackSources(context) != changed) delay(10) }
        assertFalse(StreamingAddons.isVerified(played.audioUrl, policy.copy(sources = changed)))
        assertNull(StreamingAddons.resolve(context, selected, policy.copy(sources = changed), source = second))
        assertEquals(2, StreamingAddons.search(context, "Song", policy.copy(sources = changed)).size)
    }

    @Test fun manifestConnectionNormalizesUrlAndSavingDoesNotEnableStreaming() = runBlocking {
        val manifest = StreamingAddons.connect("$base/manifest.json")
        assertEquals("My Music", manifest.name); assertEquals(base, manifest.baseUrl)
        assertEquals(listOf("/a/token/manifest.json"), requests.toList())
        OtherSettingsPreferences.saveStreamingAddon(context, manifest.baseUrl, manifest.name)
        assertFalse(OtherSettingsPreferences.playbackIntegrations(context).addonEnabled)
        assertFalse(OtherSettingsPreferences.playbackIntegrations(context).keepLyricsAwake)
        assertTrue(StreamingAddons.search(context, "Song", maximum).isEmpty())
        assertNull(StreamingAddons.resolve(context, song, maximum))
        assertEquals(1, requests.size)
    }

    @Test fun realSearchAndStreamRequestsVerifyIdentityAndDeduplicatePlaybackLookups() = runBlocking {
        enable()
        val results = coroutineScope { (0..7).map { async(Dispatchers.IO) {
            StreamingAddons.resolve(context, song, maximum)
        } }.awaitAll() }
        assertTrue(results.all { it?.audioUrl == "$base/song.flac" })
        assertTrue(results.all { it?.id == song.id && it.coverUrl == song.coverUrl })
        assertTrue(results.all { it?.source == "Streaming Addon: My Music" })
        assertEquals(1, requests.count { it.contains("/search?") })
        assertEquals(1, requests.count { it.contains("/stream/right?") })
        assertFalse(requests.any { it.contains("stream/wrong") })
        assertTrue(requests.filterNot { it.endsWith("manifest.json") }.all {
            ("quality=hi_res" in it || "quality=LOSSLESS" in it) && "atmos=none" in it })
        assertEquals(1, requests.count { it.endsWith("manifest.json") })
        assertNotNull(StreamingAddons.resolve(context, song, maximum))
        assertEquals(3, requests.size)
    }

    @Test fun playbackDoesNotRetryAnUnavailableManifestBetweenSearchAndStream() = runBlocking {
        enable()
        manifestStatus = 503
        val resolved = StreamingAddons.resolve(context, song, maximum)
        assertEquals("$base/song.flac", resolved?.audioUrl)
        assertEquals(1, requests.count { it.endsWith("/manifest.json") })
        assertEquals(1, requests.count { it.contains("/search?") })
        assertEquals(1, requests.count { it.contains("/stream/right?") })
        val direct = song.copy(id = StreamingAddons.catalogTrackId(base, "right"), title = "Another song")
        assertEquals("$base/song.flac", StreamingAddons.resolve(context, direct, maximum)?.audioUrl)
        assertEquals(1, requests.count { it.endsWith("/manifest.json") })
        assertEquals(2, requests.count { it.contains("/stream/right?") })
    }

    @Test fun preferredAddonOverlapsItsFirstSearchWithASlowManifest() = runBlocking {
        enable()
        val searched = CompletableDeferred<Unit>()
        val releaseManifest = CompletableDeferred<Unit>()
        onManifest = { runBlocking { releaseManifest.await() } }
        searchResponse = {
            searched.complete(Unit)
            """{"tracks":[{"id":"right","title":"Song","artist":"Artist","duration":180}]}"""
        }
        try {
            val playback = async(Dispatchers.IO) { StreamingAddons.resolve(context, song, maximum) }
            withTimeout(1_800) { searched.await() }
            assertFalse(requests.any { it.contains("/stream/") })
            releaseManifest.complete(Unit)
            assertEquals("$base/song.flac", withTimeout(3_000) { playback.await() }?.audioUrl)
            assertEquals(1, requests.count { it.contains("/search?") })
        } finally { releaseManifest.complete(Unit) }
    }

    @Test fun preferredAddonDiscardsTheEarlySearchWhenTheManifestChangesItsParameters() = runBlocking {
        enable()
        manifest = """{"name":"My Music","settings":[{"key":"backend","default":"main"}]}"""
        val searched = CompletableDeferred<Unit>()
        val releaseManifest = CompletableDeferred<Unit>()
        onManifest = { runBlocking { releaseManifest.await() } }
        searchResponse = { request ->
            val id = if ("backend=main" in request) "right" else "provisional"
            searched.complete(Unit)
            """{"tracks":[{"id":"$id","title":"Song","artist":"Artist","duration":180}]}"""
        }
        try {
            val playback = async(Dispatchers.IO) { StreamingAddons.resolve(context, song, maximum) }
            withTimeout(1_800) { searched.await() }
            releaseManifest.complete(Unit)
            assertEquals("$base/song.flac", withTimeout(3_000) { playback.await() }?.audioUrl)
            assertEquals(2, requests.count { it.contains("/search?") })
            assertTrue(requests.any { "/stream/right?" in it && "backend=main" in it })
            assertFalse(requests.any { it.contains("/stream/provisional?") })
        } finally { releaseManifest.complete(Unit) }
    }

    @Test fun preferredAddonRefreshesARejectedUrlWithoutSearchingForTheRecordingAgain() = runBlocking {
        enable()
        val original = StreamingAddons.resolve(context, song, maximum)!!
        stream = { """{"url":"$base/new.flac","format":"flac","bitrate":900000}""" }
        val refreshed = StreamingAddons.resolve(context, song, maximum, rejected = setOf(original.audioUrl))
        assertEquals("$base/new.flac", refreshed?.audioUrl)
        assertEquals(1, requests.count { it.contains("/search?") })
        assertEquals(2, requests.count { it.contains("/stream/right?") })
    }

    @Test fun preferredAddonSearchesAgainWhenTheProviderReplacesItsCatalogId() = runBlocking {
        enable()
        val original = StreamingAddons.resolve(context, song, maximum)!!
        searchResponse = {
            """{"tracks":[{"id":"replacement","title":"Song","artist":"Artist","duration":180}]}"""
        }
        stream = {
            if (requests.last().contains("/stream/replacement?")) {
                """{"url":"$base/replacement.flac","format":"flac","bitrate":900000}"""
            } else "{}"
        }
        val refreshed = StreamingAddons.resolve(context, song, maximum, rejected = setOf(original.audioUrl))
        assertEquals("$base/replacement.flac", refreshed?.audioUrl)
        assertEquals(2, requests.count { it.contains("/search?") })
    }

    @Test fun unavailableSearchTierIsNotRequestedAgainUnderAnAlias() = runBlocking {
        enable()
        emptySearch = true
        manifest = """{"name":"My Music","settings":[{"key":"quality","options":[{"value":"LOSSLESS"}]}]}"""
        assertTrue(StreamingAddons.search(context, "Unavailable recording", maximum).isEmpty())
        assertEquals(1, requests.count { it.contains("/search?") })
    }

    @Test fun customSearchResultKeepsItsProviderAndResolvesItsOwnIdWithoutAnotherSearch() = runBlocking {
        enable()
        val tracks = StreamingAddons.search(context, "Song", maximum)
        assertEquals(2, tracks.size)
        val selected = tracks.single { it.title == "Song" }
        assertTrue(selected.id.startsWith("addon_")); assertEquals("Artist", selected.artist)
        val played = StreamingAddons.resolve(context, selected, maximum)!!
        assertEquals(selected.id, played.id)
        assertEquals("$base/song.flac", played.audioUrl)
        assertEquals(1, requests.count { it.contains("/search?") })
    }

    @Test fun restrictedQualitiesDoNotContactAnyCustomServer() = runBlocking {
        enable()
        for (ceiling in listOf(AudioQualityCeiling.DATA_SAVER, AudioQualityCeiling.NORMAL, AudioQualityCeiling.HIGH)) {
            val policy = PlaybackSourcePolicy(ceiling)
            assertNull(StreamingAddons.resolve(context, song, policy))
            assertTrue(StreamingAddons.search(context, "Song", policy).isEmpty())
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun disablingAddonDuringLookupPreventsLateResultsFromBeingActivated() = runBlocking {
        enable()
        val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        stream = { entered.complete(Unit); runBlocking { released.await() }
            """{"url":"$base/late.flac","codec":"flac","format":"flac"}""" }
        try {
            val playback = async(Dispatchers.IO) { StreamingAddons.resolve(context, song, maximum) }
            withTimeout(3000) { entered.await() }
            val disabled = OtherSettingsPreferences.loadPlaybackSources(context).map {
                if (it.baseUrl == base) it.copy(enabled = false) else it
            }
            OtherSettingsPreferences.savePlaybackSources(context, disabled)
            withTimeout(3_000) {
                while (OtherSettingsPreferences.loadPlaybackSources(context) != disabled) delay(10)
            }
            released.complete(Unit)
            assertNull(withTimeout(3000) { playback.await() })
        } finally { released.complete(Unit) }
    }

    @Test fun maximumFallsBackFromUnavailableHiResTierToTheSameLosslessRecording() = runBlocking {
        enable()
        stream = { if (requests.last().contains("quality=hi_res")) "{}" else
            """{"url":"$base/cd.flac","codec":"flac","format":"flac","bitrate":1411200}""" }
        val played = StreamingAddons.resolve(context, song, maximum)!!
        assertEquals("$base/cd.flac", played.audioUrl)
        assertTrue(played.isLossless)
        assertEquals(2, requests.count { it.contains("stream/right") })
        assertTrue(requests.last().contains("quality=LOSSLESS"))
    }

    @Test fun declaredLosslessTierAndFormatOnlyFlacWinBeforeTheAacFallback() = runBlocking {
        manifest = """{"id":"personal","name":"My Music","settings":[
            {"key":"quality","default":"HIGH","options":[{"value":"HIGH"},{"value":"LOSSLESS"}]},
            {"key":"backend","default":"main"}]}"""
        stream = { if (requests.last().contains("quality=LOSSLESS"))
            """{"url":"$base/verified.flac","format":"flac","bitrate":900000}""" else
            """{"url":"$base/fallback.m4a","codec":"aac","bitrate":320000}""" }
        enable()
        val played = StreamingAddons.resolve(context, song, maximum)!!
        assertTrue(played.isLossless); assertEquals("$base/verified.flac", played.audioUrl)
        assertEquals("Streaming Addon: My Music", played.source)
        assertTrue(requests.filter { it.contains("/search?") || it.contains("/stream/") }.all {
            "quality=LOSSLESS" in it && "backend=main" in it && Regex("quality=").findAll(it).count() == 1 })
        assertEquals(1, requests.count { it.endsWith("manifest.json") })
        val count = requests.size
        // A command revision is not a change of recording, server, or quality.
        val again = StreamingAddons.resolve(context, song, maximum.copy(enhancer = true, requestRevision = 7))!!
        assertEquals(played.audioUrl, again.audioUrl); assertEquals(count, requests.size)
    }

    @Test fun aLossyHiResAnswerDoesNotHideTheSameServersRealLosslessTier() = runBlocking {
        enable()
        stream = { if (requests.last().contains("quality=hi_res"))
            """{"url":"$base/lossy.m4a","codec":"aac","bitrate":320000}""" else
            """{"url":"$base/lossless.flac","fileCodec":"flac","bitrate":900000}""" }
        val result = StreamingAddons.resolve(context, song, maximum)!!
        assertTrue(result.isLossless); assertEquals("$base/lossless.flac", result.audioUrl)
        assertEquals(2, requests.count { it.contains("stream/right") })
    }

    @Test fun malformedUnsupportedTierDoesNotSkipAWorkingLosslessResponse() = runBlocking {
        enable()
        stream = { if (requests.last().contains("quality=hi_res")) "<html>Unsupported tier</html>" else
            """{"url":"$base/lossless.flac","format":"flac","bitrate":900000}""" }
        val result = StreamingAddons.resolve(context, song, maximum)!!
        assertTrue(result.isLossless); assertEquals("$base/lossless.flac", result.audioUrl)
        assertEquals(2, requests.count { it.contains("stream/right") })
    }

    @Test fun lossyPreviewIsRejectedAndManualRetryCanResolveTheFullLosslessTrack() = runBlocking {
        enable()
        stream = { """{"url":"$base/preview.mp3","codec":"mp3","format":"mp3","sample":true,"bitrate":128000}""" }
        assertNull(StreamingAddons.resolve(context, song, maximum))
        val previous = requests.size
        stream = { """{"url":"$base/now-available.flac","format":"flac","bitrate":900000}""" }
        val explicit = maximum.copy(enhancer = true, requestTrackId = song.id, requestRevision = 1)
        val upgraded = StreamingAddons.resolve(context, song, explicit)!!
        assertTrue(upgraded.isLossless); assertTrue(requests.size > previous)
        val completed = requests.size
        assertEquals(upgraded.audioUrl, StreamingAddons.resolve(context, song, explicit.copy(requestRevision = 2))!!.audioUrl)
        assertEquals(completed, requests.size)
    }

    @Test fun stereoRequestsRespectTheServersDeclaredOffValue() = runBlocking {
        enable()
        manifest = """{"id":"personal","name":"My Music","settings":[
            {"key":"atmos","default":"1","options":[{"value":"1"},{"value":"0"}]}]}"""
        stream = { if (requests.last().contains("atmos=0"))
            """{"url":"$base/stereo.flac","format":"flac","bitrate":900000}""" else "{}" }
        assertTrue(StreamingAddons.resolve(context, song, maximum)!!.isLossless)
        assertTrue(requests.filter { !it.endsWith("manifest.json") }.all { "atmos=0" in it })
    }

    @Test fun registeredAddonIdentityWinsOverTheOriginalJioCatalogIdAndReportsAdaptiveTransport() = runBlocking {
        enable()
        val candidate = song.copy(source = "Streaming Addon: My Music")
        val result = StreamingAddons.parseStream("""{"url":"$base/audio/play","fileCodec":"flac",
            "format":"FLAC","manifest":"dash","mimeType":"application/dash+xml"}""", base, song, candidate, maximum)!!
        assertTrue(result.isLossless); assertEquals(song.id, result.id)
        assertEquals(MimeTypes.APPLICATION_MPD, StreamingAddons.mime(result.audioUrl))
        assertEquals(result.source, StreamingAddons.providerName(result.audioUrl))
        assertEquals(com.example.playback.PlaybackSource.addonId(base), com.example.playback.SourceTransitionOrigin.sourceId(
            result.id, android.net.Uri.parse(result.audioUrl), maximum))
        val owner = org.robolectric.Robolectric.buildService(com.example.service.MusicService::class.java).get()
        val item = androidx.media3.common.MediaItem.Builder().setMediaId(song.id).setUri(result.audioUrl).build()
        com.example.service.MusicService::class.java.getDeclaredMethod("publishSignalSource", androidx.media3.common.MediaItem::class.java,
            android.net.Uri::class.java, androidx.media3.common.Format::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(owner, item, item.localConfiguration!!.uri,
                androidx.media3.common.Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).setSampleRate(44100).build(), 900)
        assertEquals("Streaming Addon: My Music", com.example.playback.AudioSignalPath.state.value.source)
        com.example.playback.AudioSignalPath.reset()
        assertNull(StreamingAddons.parseStream("""{"url":"$base/expired.flac","format":"flac","expiresAt":1}""",
            base, song, candidate, maximum))
        assertNull(StreamingAddons.parseStream("""{"url":"$base/drm.flac","format":"flac","encrypted":"widevine"}""",
            base, song, candidate, maximum))
    }

    @Test fun adaptiveUrlsTakePrecedenceOverTheDeclaredFlacCodec() {
        val candidate = song.copy(source = "Streaming Addon: My Music")
        val manifests = mapOf("dash/manifest.mpd" to MimeTypes.APPLICATION_MPD,
            "hls/playlist.m3u8" to MimeTypes.APPLICATION_M3U8)
        for ((path, mime) in manifests) {
            val response = JSONObject().put("url", "$base/$path").put("format", "flac")
                .put("mimeType", "audio/flac").put("headers", JSONObject().put("Authorization", "Bearer sample"))
            val resolved = StreamingAddons.parseStream(response.toString(), base, song, candidate, maximum)!!
            assertTrue(resolved.isLossless)
            assertEquals(mime, StreamingAddons.mime(resolved.audioUrl))
            assertNull(StreamingAddons.cacheableLosslessCodec(resolved.audioUrl))
            assertEquals("Bearer sample", StreamingAddons.headers("$base/${path.substringBeforeLast('/')}/chunk.m4s")["Authorization"])
        }
    }

    @Test fun dashManifestCodecUnknownsHeaderScopeAndExpiredUrlsAreHandledAccurately() {
        val candidate = song.copy(source = "Streaming Addon: My Music")
        val body = JSONObject().put("url", "$base/audio/manifest.mpd").put("format", "dash")
            .put("headers", JSONObject().put("Authorization", "Bearer personal-token")).toString()
        val dash = StreamingAddons.parseStream(body, base, song, candidate, maximum)!!
        assertEquals(MimeTypes.APPLICATION_MPD, StreamingAddons.mime(dash.audioUrl))
        assertFalse(dash.isLossless); assertEquals(0, dash.bitrateKbps)
        assertEquals("Bearer personal-token", StreamingAddons.headers("$base/audio/chunk-1.m4s")["Authorization"])
        assertTrue(StreamingAddons.headers("https://another.example/audio/chunk-1.m4s").isEmpty())
        assertTrue(StreamingAddons.headers("$base/private/unrelated").isEmpty())
        // An active segmented song can outlive the lookup cache. Do not strip
        // its headers, while still refusing to reuse an expired URL for new play.
        @Suppress("UNCHECKED_CAST")
        val registry = StreamingAddons::class.java.getDeclaredField("transports").apply { isAccessible = true }
            .get(StreamingAddons) as Map<String, Any>
        val lease = registry.getValue(dash.audioUrl)
        lease.javaClass.getDeclaredField("expires").apply { isAccessible = true }.setLong(lease, 0L)
        assertFalse(StreamingAddons.isVerified(dash.audioUrl, maximum))
        assertEquals("Bearer personal-token", StreamingAddons.headers("$base/audio/chunk-2.m4s")["Authorization"])
        val expired = JSONObject().put("url", "$base/song.flac?expire=1").toString()
        assertNull(StreamingAddons.parseStream(expired, base, song, candidate, maximum))
        assertNull(StreamingAddons.parseStream("""{"encrypted":true,"url":"$base/protected.flac"}""", base, song, candidate, maximum))
        val xml = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><BaseURL>$base/inline/</BaseURL></MPD>"""
        val inline = StreamingAddons.parseStream(JSONObject().put("manifestXml", xml)
            .put("headers", JSONObject().put("Authorization", "Bearer inline")).toString(), base, song, candidate, maximum)!!
        assertTrue(inline.audioUrl.startsWith("data:application/dash+xml;base64,"))
        assertEquals("Bearer inline", StreamingAddons.headers("$base/inline/chunk-1.m4s")["Authorization"])
    }
}
