package com.example

import android.app.Application
import android.content.Context
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AudioQualityPreferences
import com.example.data.local.PlaybackSourcesPreferences
import com.example.data.model.MusicTrack
import com.example.data.remote.StreamingAddons
import com.example.playback.*
import com.example.service.FastStartManifests
import com.example.service.MusicCache
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.*

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@Suppress("DEPRECATION")
class LosslessPrebufferRegressionTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: HttpServer
    private lateinit var source: PlaybackSource

    @Before fun prepare() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        com.example.performance.BackgroundWorkCoordinator.resetForTesting()
        AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.MAX)
        val manager = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        manager.clearAllNetworks()
        val network = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).apply {
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }
        manager.addNetwork(network, info); manager.setActiveNetworkInfo(info)
        manager.setDefaultNetworkActive(true); manager.setNetworkCapabilities(network, caps)
        val capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createAudioFormat(MimeTypes.AUDIO_FLAC, 44100, 1))
            .setIsEncoder(false).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("OMX.google.flac.decoder")
            .setIsEncoder(false).setCapabilities(capabilities).build())
        MediaCodecUtil.clearDecoderInfoCache()
        val bytes = javaClass.getResourceAsStream("/audio/unindexed-silence.flac")!!.use { it.readBytes() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/song.flac") { exchange ->
                exchange.responseHeaders.add("Content-Type", "audio/flac")
                exchange.sendResponseHeaders(200, 0) // Real chunked response: no length or seek table.
                exchange.responseBody.use { it.write(bytes) }; exchange.close()
            }
            createContext("/fake.flac") { exchange ->
                val invalid = "This is not an audio container".toByteArray()
                exchange.sendResponseHeaders(200, invalid.size.toLong())
                exchange.responseBody.use { it.write(invalid) }; exchange.close()
            }
            createContext("/unknown-duration.flac") { exchange ->
                val unknown = bytes.copyOf().apply {
                    // A valid STREAMINFO may leave its 36-bit total-sample count unknown.
                    this[21] = (this[21].toInt() and 0xf0).toByte()
                    for (i in 22..25) this[i] = 0
                }
                exchange.responseHeaders.add("Content-Type", "audio/flac")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(unknown) }; exchange.close()
            }
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}"
        source = PlaybackSource(PlaybackSource.addonId(base), PlaybackSourceKind.ADDON, "Local fixture", baseUrl = base)
        val sources = listOf(source) + PlaybackSource.builtIns
        PlaybackSourcesPreferences.save(app, sources)
        withTimeout(3_000) { while (PlaybackSourcesPreferences.load(app) != sources) delay(10) }
    }
    @After fun cleanup() = runBlocking {
        server.stop(0)
        withContext(Dispatchers.IO) {
            MusicCache::class.java.getDeclaredField("simpleCacheInstance").apply { isAccessible = true }.let {
                (it.get(null) as? SimpleCache)?.release(); it.set(null, null)
            }
        }
        val instance = NetworkQualityMonitor::class.java.getDeclaredField("instance").apply { isAccessible = true }
        instance.get(null)?.let { monitor ->
            val scope = NetworkQualityMonitor::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(monitor) as CoroutineScope
            scope.coroutineContext[Job]?.cancelAndJoin()
            instance.set(null, null)
        }
        com.example.performance.BackgroundWorkCoordinator.resetForTesting()
        Dispatchers.resetMain()
    }
    private fun item(path: String): MediaItem {
        val url = "${source.baseUrl}/$path"
        val track = MusicTrack(path, "Song", "Artist", "Album", 24_000, "cover", url,
            source = "Streaming Addon: Local fixture")
        // Prebuffer tests start after provider verification. Register this local
        // transport through the real parser so source-policy checks stay active.
        checkNotNull(StreamingAddons.parseStream(JSONObject().put("url", url).put("codec", "flac").toString(),
            source.baseUrl, track, track, PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = listOf(source))))
        return MediaItem.Builder().setMediaId(path).setUri(url).build()
    }

    @Test fun realUnindexedChunkedFlacIsPreparedAtThePlayingPositionWithoutAnotherPlayer() = runBlocking {
        val item = item("song.flac")
        val prepared = withTimeout(10_000) { LosslessStreamPrebuffer.prepare(app, item,
            expectedDurationMs = 24_000, currentPosition = { 6_000 }, stillCurrent = { true }) }
        assertNotNull("${LosslessDiagnostics.last(item.mediaId)}", prepared)
        assertTrue(prepared!!.covers(6_000))
        assertEquals("FLAC", prepared.audio.codec)
        assertEquals(44100, prepared.audio.sampleRate)
        assertEquals(16, prepared.audio.bitDepth)
        assertTrue(withContext(Dispatchers.IO) { MusicCache.fullyCachedResource(app, item.localConfiguration!!.uri) })
        // The cache now supplies a real length. Exercise Media3's ordinary FLAC
        // binary seeker again at the target, rather than assuming cache fill suffices.
        val indexed = withTimeout(10_000) { LosslessStreamPrebuffer.prepare(app, item,
            expectedDurationMs = 20_000, currentPosition = { 6_000 }, stillCurrent = { true }) }
        assertNotNull("Native seeking must work after preparing the unindexed source", indexed)
        assertTrue(indexed!!.covers(6_000))
    }
    @Test fun actualDurationAndActualContainerRejectMismatchesAndFalseFlacExtensions() = runBlocking {
        val real = item("song.flac")
        assertNull(LosslessStreamPrebuffer.prepare(app, real, 40_000, { 6_000 }, { true }))
        assertEquals(LosslessDiagnostics.Reason.RECORDING_MISMATCH, LosslessDiagnostics.last(real.mediaId)?.reason)
        val fake = item("fake.flac")
        assertNull(LosslessStreamPrebuffer.prepare(app, fake, 20_000, { 6_000 }, { true }))
        assertEquals(LosslessDiagnostics.Reason.INVALID_FORMAT, LosslessDiagnostics.last(fake.mediaId)?.reason)
    }

    @Test fun fastStartChecksTheReplacementBytesAtThePlayingPosition() = runBlocking {
        val item = item("song.flac")
        val current = MusicTrack(item.mediaId, "Song", "Artist", "Album", 24_000L, "",
            "xtreme-youtube://audio/abcdefghijk/stream.audio")
        val replacement = current.copy(audioUrl = item.localConfiguration!!.uri.toString(), isLossless = true)
        val prepared = prepareFastStartReplacement(app, item, current, replacement,
            position = { 6_000L }, stillCurrent = { true }, automatic = false)
        assertTrue(prepared?.covers(6_000L) == true)
        assertFalse(prepared?.covers(19_000L) == true)
        assertNull(prepareFastStartReplacement(app, item, current, replacement,
            position = { 6_000L }, stillCurrent = { false }, automatic = false))
    }

    @Test fun fastStartPreparesADashAddonWithoutAnExplicitMimeType() = runBlocking {
        val manifest = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT24S">
              <Period duration="PT24S">
                <AdaptationSet mimeType="audio/mp4" codecs="fLaC">
                  <Representation id="hires" bandwidth="1500000">
                    <SegmentTemplate timescale="1" duration="4" startNumber="1" initialization="init.mp4" media="segment-${'$'}Number${'$'}.m4s"/>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent().toByteArray()
        val requests = java.util.concurrent.ConcurrentLinkedQueue<String>()
        server.createContext("/adaptive/") { exchange ->
            val resource = exchange.requestURI.path.substringAfterLast('/')
            requests.add(resource)
            // The preparation reads manifest metadata and warms the byte ranges. The active
            // player, rather than this probe, is responsible for consuming the sample data.
            val content = if (resource == "audio.mpd") manifest else byteArrayOf(1, 2, 3, 4)
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
            exchange.close()
        }
        val current = MusicTrack("adaptive-fast-start", "Song", "Artist", "Album", 24_000L, "",
            "xtreme-youtube://audio/abcdefghijk/stream.audio")
        val url = "${source.baseUrl}/adaptive/audio.mpd"
        val replacement = checkNotNull(StreamingAddons.parseStream(
            JSONObject().put("url", url).put("codec", "flac").toString(),
            source.baseUrl, current, current, PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = listOf(source))))
        assertEquals(MimeTypes.APPLICATION_MPD, StreamingAddons.mime(url))
        val media = MediaItem.Builder().setMediaId(current.id).setUri(url).build()
        val ready = withTimeout(10_000L) {
            prepareFastStartReplacement(app, media, current, replacement,
                position = { 6_000L }, stillCurrent = { true }, automatic = true)
        }
        assertNotNull("${LosslessDiagnostics.last(current.id)}, requested $requests", ready)
        assertTrue(ready!!.covers(6_000L))
        assertFalse("A seek beyond the warmed window cannot switch sources", ready.covers(14_000L))
        assertTrue(requests.containsAll(listOf("init.mp4", "segment-2.m4s", "segment-3.m4s")))
        assertNull("Ordinary source creation must use its own manifest", FastStartManifests.takeFor(media))
        val handoff = ready.forHandoff(media)
        assertNull(FastStartManifests.takeFor(handoff.buildUpon().setMediaId("another-track").build()))
        val prepared = checkNotNull(FastStartManifests.takeFor(handoff))
        assertNull("The manifest handoff is single-use", FastStartManifests.takeFor(handoff))
        val networkCalls = requests.toList()
        withContext(Dispatchers.IO) {
            val factory = prepared.replaying(MusicCache.createCacheDataSourceFactory(app))
            val dash = DashUtil.loadManifest(factory.createDataSource(), media.localConfiguration!!.uri)
            val representation = dash.getPeriod(0).adaptationSets.first().representations.first()
            val index = checkNotNull(representation.index)
            val segment = index.getSegmentNum(8_000_000L, dash.getPeriodDurationUs(0))
            val spec: DataSpec = DashUtil.buildDataSpec(representation, index.getSegmentUrl(segment), 0)
            val dataSource = factory.createDataSource()
            try {
                dataSource.open(spec)
                val bytes = ByteArray(4)
                assertEquals(4, dataSource.read(bytes, 0, bytes.size))
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes)
            } finally { dataSource.close() }
        }
        assertEquals("Playback uses the prepared manifest and the same media cache keys", networkCalls, requests.toList())
    }
    @Test fun nonMaximumProfileRejectsPreparationBeforeItTouchesPlayerOrNetwork() = runBlocking {
        AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.HIGH)
        assertNull(LosslessStreamPrebuffer.prepare(app, item("song.flac"), 20_000,
            { error("Capped profile must not read the player") }, { error("Capped profile must not reach Main") }))
    }
    @Test fun unknownFlacDurationCanPlayFromTheBeginningWithoutInventingSeekEvidence() = runBlocking {
        val item = item("unknown-duration.flac")
        val prepared = withTimeout(10_000) { LosslessStreamPrebuffer.prepare(app, item, 24_000, { 0L }, { true }) }
        assertNotNull("${LosslessDiagnostics.last(item.mediaId)}", prepared)
        assertEquals("FLAC", prepared!!.audio.codec)
        assertTrue(prepared.covers(0L))
        assertNull(LosslessStreamPrebuffer.prepare(app, item, 24_000, { 6_000L }, { true }))
        assertEquals(LosslessDiagnostics.Reason.SEEK_UNAVAILABLE, LosslessDiagnostics.last(item.mediaId)?.reason)
    }
    @Test fun disabledSourceRejectsEvenAnAlreadyRegisteredAndCachedTransport() = runBlocking {
        val candidate = item("song.flac")
        assertNotNull("${LosslessDiagnostics.last(candidate.mediaId)}", LosslessStreamPrebuffer.prepare(app, candidate, 24_000, { 6_000 }, { true }))
        assertTrue(withContext(Dispatchers.IO) { MusicCache.fullyCachedResource(app, candidate.localConfiguration!!.uri) })
        val sources = listOf(source.copy(enabled = false)) + PlaybackSource.builtIns
        PlaybackSourcesPreferences.save(app, sources)
        withTimeout(3_000) { while (PlaybackSourcesPreferences.load(app) != sources) delay(10) }
        assertNull(LosslessStreamPrebuffer.prepare(app, candidate, 24_000,
            { error("Disabled source must not read the player") }, { error("Disabled source must not reach Main") }))
        assertEquals(LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED, LosslessDiagnostics.last(candidate.mediaId)?.reason)
    }
    @Test fun optionalPreparationReleasesItsWorkerDuringNavigationWithoutRejectingTheFile() = runBlocking {
        val coordinator = com.example.performance.BackgroundWorkCoordinator
        coordinator.firstFrameRendered()
        coordinator.foreground(true); coordinator.frame(2, 1)
        ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(16))
        // Both initial admission and navigation resumption use elapsed time. Keep
        // it progressing while asynchronous work records completion timestamps.
        val clock = launch {
            while (isActive) {
                delay(25)
                ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(25))
            }
        }
        val navigation = CompletableDeferred<AutoCloseable>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val bytes = javaClass.getResourceAsStream("/audio/unindexed-silence.flac")!!.use { it.readBytes() }
        server.createContext("/paused.flac") { exchange ->
            if (reads.incrementAndGet() == 1) navigation.complete(coordinator.navigationBusy())
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(bytes) }; exchange.close()
        }
        val candidate = item("paused.flac")
        val optional = async { LosslessStreamPrebuffer.prepare(app, candidate, 24_000,
            { 6_000 }, { true }, automatic = true) }
        var lease: AutoCloseable? = null
        try {
            lease = withTimeout(5_000) { navigation.await() }
            // Required preparation is independent of optional idle admission and
            // cannot be held behind a paused worker/cache lock.
            val explicit = withTimeout(10_000) { LosslessStreamPrebuffer.prepare(app, item("song.flac"),
                24_000, { 6_000 }, { true }) }
            assertNotNull(explicit)
            assertFalse(optional.isCompleted)
            assertNull(LosslessDiagnostics.last(candidate.mediaId))
            lease.close(); lease = null
            coordinator.foreground(false)
            ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(16))
            val resumed = withTimeout(10_000) { optional.await() }
            assertNotNull(resumed)
            assertTrue(resumed!!.covers(6_000))
        } finally {
            lease?.close(); optional.cancelAndJoin(); clock.cancelAndJoin(); coordinator.foreground(false)
        }
    }

}
