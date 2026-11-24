package com.example

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AudioQualityPreferences
import com.example.data.local.MusicDao
import com.example.data.local.PlaybackSourcesPreferences
import com.example.data.model.MusicTrack
import com.example.data.model.toMediaItem
import com.example.data.remote.InternetArchiveApiService.LosslessSearchPage
import com.example.data.remote.StreamingAddons
import com.example.data.repository.MusicRepository
import com.example.data.repository.StreamResolver
import com.example.playback.*
import com.example.service.MusicService
import com.example.service.MusicCache
import com.example.service.PolicyResolvingMediaSource
import com.example.service.StreamedAudioRepresentations
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers

/** Real service -> repository -> resolver boundary; only remote providers are fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Suppress("DEPRECATION")
class MusicServiceQueueOwnershipTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val selected = MusicTrack("online_song", "Song", "Artist", "Album", 180_000, "",
        "https://aac.saavncdn.com/song_320.mp4", source = "JIOSAAVN", bitrateKbps = 320)
    private val archive = selected.copy(id = "ia_album:song.flac", source = "INTERNET_ARCHIVE", isLossless = true,
        audioUrl = "https://archive.org/download/album/song.flac", qualityBadge = "Lossless")
    private lateinit var lookupScope: CoroutineScope
    private lateinit var resolver: StreamResolver
    private lateinit var repository: MusicRepository
    private lateinit var service: MusicService
    private lateinit var monitor: NetworkQualityMonitor
    private val entered = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    private val cancelled = CompletableDeferred<Unit>()
    private val requests = AtomicInteger()
    private val sources = mutableListOf<Pair<MediaSource, MediaSource.MediaSourceCaller>>()
    private var standardAvailable = true
    private var youtubeAvailable = false
    private val youtubeRequests = AtomicInteger()
    private var standardGate: CompletableDeferred<Unit>? = null
    private var archiveDelayMs: Long? = null
    private val addonAddress = "https://files.example/fast-start.flac"
    private var addonGate: CompletableDeferred<Unit>? = null
    private val addonRequests = AtomicInteger()

    @Before fun setup() = runBlocking {
        StreamResolver.clearVerifiedLosslessCache()
        AudioQualityPreferences.setPlaybackMode(app, PlaybackMode.BEST_QUALITY)
        PlaybackSourcesPreferences.save(app, PlaybackSource.builtIns)
        AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.MAX)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val network = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        val capabilities = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }
        shadowOf(connectivity).apply {
            clearAllNetworks()
            val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI, 0, true, true)
            addNetwork(network, info); setActiveNetworkInfo(info); setDefaultNetworkActive(true)
            setNetworkCapabilities(network, capabilities)
        }
        monitor = NetworkQualityMonitor.getInstance(app)
        withTimeout(3_000) {
            while (PlaybackSourcesPreferences.load(app) != PlaybackSource.builtIns) delay(5)
            // Finish the monitor's initial preference readers before a following
            // case can replace Robolectric's application/shared-preference clock.
            while (!monitor.sourcePolicy.value.ready || monitor.sourcePolicy.value.key != monitor.sourcePolicySnapshot().key) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(5)
            }
        }
        assertTrue(monitor.sourcePolicySnapshot().allowsArchive)
        lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        resolver = StreamResolver(lookupScope = lookupScope, supportsLossless = { true },
            searchStandard = { _, _ -> standardGate?.await(); if (standardAvailable) listOf(selected) else emptyList() },
            fetchById = { _, _ -> standardGate?.await(); selected.takeIf { standardAvailable } },
            searchYouTube = { if (youtubeAvailable) listOf(selected.copy(id = "yt_abcdefghijk", audioUrl = "")) else emptyList() },
            resolveYouTube = { request, _, _, _ ->
                youtubeRequests.incrementAndGet()
                if (youtubeAvailable) request.copy(
                    audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=320000",
                    bitrateKbps = 128, qualityBadge = "Opus • 128 kbps") else null
            },
            resolveAddon = { source, track, policy, _ ->
                addonRequests.incrementAndGet()
                addonGate?.await()
                StreamingAddons.parseStream("""{"url":"$addonAddress","codec":"flac","bitrate":900000}""",
                    source.baseUrl, track, track, policy, source.id)
            },
            archiveAllowed = { monitor.sourcePolicySnapshot().key == it.key },
            findLossless = { _, _, allowed ->
                assertNotEquals("Archive work must stay off Main", Looper.getMainLooper(), Looper.myLooper())
                requests.incrementAndGet(); entered.complete(Unit)
                try {
                    archiveDelayMs?.let { delay(it) } ?: released.await()
                    assertTrue("A surviving owner must still allow the recording", allowed())
                    LosslessSearchPage(listOf(archive))
                } catch (error: CancellationException) { cancelled.complete(Unit); throw error }
            })
        val dao = Proxy.newProxyInstance(MusicDao::class.java.classLoader, arrayOf(MusicDao::class.java)) { _, method, _ ->
            error("Queue preparation must not query Room: ${method.name}")
        } as MusicDao
        repository = MusicRepository(dao, app)
        ReflectionHelpers.setField(repository, "streamResolver", resolver)
        service = Robolectric.buildService(MusicService::class.java).get()
        // Use the actual service method without running unrelated Application startup jobs.
        val application = XtremeMusicApp()
        ReflectionHelpers.setField(application, "repository\$delegate", lazyOf(repository))
        ReflectionHelpers.setField(service, "mApplication", application)
    }

    @After fun cleanup() = runBlocking {
        sources.forEach { (source, caller) -> source.releaseSource(caller) }
        if (::resolver.isInitialized) resolver.clear()
        if (::lookupScope.isInitialized) lookupScope.coroutineContext[Job]?.cancelAndJoin()
        if (::repository.isInitialized) ReflectionHelpers.getField<CoroutineScope>(repository, "initialCatalogScope").coroutineContext[Job]?.cancelAndJoin()
        if (::service.isInitialized) ReflectionHelpers.getField<CoroutineScope>(service, "serviceScope").coroutineContext[Job]?.cancelAndJoin()
        if (::monitor.isInitialized) ReflectionHelpers.getField<CoroutineScope>(monitor, "scope").coroutineContext[Job]?.cancelAndJoin()
        ReflectionHelpers.setStaticField(NetworkQualityMonitor::class.java, "instance", null)
        StreamResolver.clearVerifiedLosslessCache()
    }

    private suspend fun resolveQueued(item: MediaItem): MediaItem = suspendCoroutineUninterceptedOrReturn { continuation ->
        try {
            MusicService::class.java.getDeclaredMethod("resolveQueuedSource", MediaItem::class.java, Continuation::class.java)
                .apply { isAccessible = true }.invoke(service, item, continuation)
        } catch (error: InvocationTargetException) { throw error.targetException }
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(4_000) {
        while (!condition()) { shadowOf(Looper.getMainLooper()).idle(); delay(5) }
    }

    @Test fun serviceQueueItemsRetainTheRecordingMetadataRequiredForArchiveEligibility() {
        val item = selected.copy(isrc = "USABC2400001").toMediaItem()
        assertEquals(selected.durationMs, item.mediaMetadata.extras!!.getLong("duration"))
        assertEquals("USABC2400001", item.mediaMetadata.extras!!.getString("isrc"))
    }

    @Test fun fastStartQueuesYouTubeWithoutWaitingForAnyPreferredProvider() = runBlocking {
        AudioQualityPreferences.setPlaybackMode(app, PlaybackMode.FAST_START)
        youtubeAvailable = true
        val policy = monitor.sourcePolicySnapshot()
        var timeline: Timeline? = null
        val transport = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(app)
        val source = PolicyResolvingMediaSource(selected.toMediaItem(), transport, lookupScope,
            resolvedIsCurrent = { it.mediaMetadata.extras?.getString("source_policy_key") == monitor.sourcePolicySnapshot().key },
            resolve = ::resolveQueued)
        val caller = MediaSource.MediaSourceCaller { _, value -> timeline = value }
        source.prepareSource(caller, null); sources.add(source to caller)
        until { source.maybeThrowSourceInfoRefreshError(); timeline != null }
        assertEquals(selected.id, source.mediaItem.mediaId)
        assertEquals(policy.key, source.mediaItem.mediaMetadata.extras!!.getString("source_policy_key"))
        assertEquals(PlaybackMode.FAST_START, source.mediaItem.playbackMode)
        assertEquals("xtreme-youtube", source.mediaItem.localConfiguration?.uri?.scheme)
        assertEquals(0, requests.get())
        source.releaseSource(caller); sources.clear()
        assertFalse(resolver.hasPendingLosslessDiscovery(selected.id, policy.key))
        released.complete(Unit)
        val upgraded = repository.resolveFastStartUpgrade(
            selected.copy(audioUrl = source.mediaItem.localConfiguration!!.uri.toString()), policy)
        assertEquals(archive.audioUrl, upgraded?.audioUrl)
        assertEquals(selected.id, upgraded?.id)
        assertEquals(1, requests.get())
    }

    @Test fun automaticFastStartKeepsPlayingWhenNoHigherPrioritySourceCanResume() = runBlocking {
        val base = "https://addons.example/fast-start"
        val addon = PlaybackSource(PlaybackSource.addonId(base), PlaybackSourceKind.ADDON, "Fixture", baseUrl = base)
        val sources = listOf(addon, PlaybackSource.jio, PlaybackSource.youtube)
        PlaybackSourcesPreferences.save(app, sources)
        until { monitor.sourcePolicy.value.upgradeSources == sources }
        val playing = selected.copy(audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio",
            source = "YouTube Music", bitrateKbps = 128)
        val checked = mutableListOf<String>()
        val automatic = repository.resolveFastStartUpgrade(playing, monitor.sourcePolicy.value) {
            checked += it.audioUrl
            false
        }
        assertNull(automatic)
        assertEquals(listOf(addonAddress, selected.audioUrl), checked)

        monitor.setAudioEnhancer(true, trackId = playing.id)
        until { monitor.sourcePolicy.value.enhancer }
        checked.clear()
        val manual = repository.resolveFastStartUpgrade(playing, monitor.sourcePolicy.value, manual = true) {
            checked += it.audioUrl
            it.audioUrl != addonAddress
        }
        assertEquals(listOf(addonAddress, selected.audioUrl), checked)
        assertEquals(selected.audioUrl, manual?.audioUrl)
        assertEquals(playing.id, manual?.id)
    }

    @Test fun fastStartReturnsYoutubeThenWaitsForTheBestQualityAddon() = runBlocking {
        val base = "https://addons.example/preferred"
        val addon = PlaybackSource(PlaybackSource.addonId(base), PlaybackSourceKind.ADDON, "Preferred", baseUrl = base)
        val sources = listOf(addon) + PlaybackSource.builtIns
        val providerReady = CompletableDeferred<Unit>()
        addonGate = providerReady
        PlaybackSourcesPreferences.save(app, sources)
        until { monitor.sourcePolicy.value.upgradeSources == sources }
        AudioQualityPreferences.setPlaybackMode(app, PlaybackMode.FAST_START)
        youtubeAvailable = true

        val initial = withTimeout(3_000L) { repository.resolvePlaybackStart(selected) }
        assertEquals(PlaybackMode.FAST_START, initial.mode)
        assertEquals(PlaybackSource.youtube.id, SourceTransitionOrigin.sourceId(
            initial.track.id, Uri.parse(initial.track.audioUrl), monitor.sourcePolicy.value))
        assertEquals(0, addonRequests.get())
        assertEquals(0, requests.get())
        assertEquals(1, youtubeRequests.get())

        val replacement = async {
            repository.resolveFastStartUpgrade(initial.track, monitor.sourcePolicy.value) { candidate ->
                assertEquals(selected.id, candidate.id)
                assertEquals(selected.title, candidate.title)
                assertTrue(candidate.isLossless)
                true
            }
        }
        withTimeout(4_000L) { entered.await() }
        assertEquals(1, addonRequests.get())
        assertFalse(replacement.isCompleted)
        released.complete(Unit)
        providerReady.complete(Unit)
        assertEquals(addonAddress, withTimeout(4_000L) { replacement.await() }?.audioUrl)
        assertEquals("Best Quality can reuse the audible YouTube fallback", 1, youtubeRequests.get())
    }

    @Test fun fastStartUsesTheSameBestQualityResult() = runBlocking {
        val base = "https://addons.example/best-quality"
        val addon = PlaybackSource(PlaybackSource.addonId(base), PlaybackSourceKind.ADDON, "Preferred", baseUrl = base)
        val sources = listOf(addon, PlaybackSource.jio, PlaybackSource.youtube)
        PlaybackSourcesPreferences.save(app, sources)
        until { monitor.sourcePolicy.value.upgradeSources == sources }
        youtubeAvailable = true
        val best = repository.resolvePlaybackStart(selected)
        assertEquals(PlaybackMode.BEST_QUALITY, best.mode)
        assertEquals(addonAddress, best.track.audioUrl)

        AudioQualityPreferences.setPlaybackMode(app, PlaybackMode.FAST_START)
        val initial = repository.resolvePlaybackStart(selected)
        assertEquals(PlaybackMode.FAST_START, initial.mode)
        assertEquals("xtreme-youtube", Uri.parse(initial.track.audioUrl).scheme)
        val policy = monitor.sourcePolicy.value
        val upgraded = checkNotNull(repository.resolveFastStartUpgrade(initial.track, policy))
        assertEquals(initial.track.id, upgraded.id)
        assertEquals(best.track.audioUrl, upgraded.audioUrl)
        assertTrue(upgraded.isLossless)
        assertNull(repository.resolveFastStartUpgrade(upgraded, policy))
    }

    @Test fun fastStartTriesAnotherHigherPrioritySourceWhenTheBestStreamCannotResume() = runBlocking {
        val base = "https://addons.example/unseekable"
        val addon = PlaybackSource(PlaybackSource.addonId(base), PlaybackSourceKind.ADDON, "Preferred", baseUrl = base)
        val sources = listOf(addon, PlaybackSource.jio, PlaybackSource.youtube)
        PlaybackSourcesPreferences.save(app, sources)
        until { monitor.sourcePolicy.value.upgradeSources == sources }
        AudioQualityPreferences.setPlaybackMode(app, PlaybackMode.FAST_START)
        youtubeAvailable = true
        val initial = repository.resolvePlaybackStart(selected).track
        val policy = monitor.sourcePolicy.value
        val prepared = mutableListOf<String>()

        val upgraded = repository.resolveFastStartUpgrade(initial, policy) { candidate ->
            val provider = checkNotNull(SourceTransitionOrigin.sourceId(candidate.id, Uri.parse(candidate.audioUrl), policy))
            prepared.add(provider)
            provider != addon.id
        }

        assertEquals(listOf(addon.id, PlaybackSource.jio.id), prepared)
        assertEquals(selected.id, upgraded?.id)
        assertEquals(selected.audioUrl, upgraded?.audioUrl)
    }

    @Test fun bestQualityQueueResolutionDoesNotPinAnAutomaticFastStartBookmark() = runBlocking {
        val base = "https://addons.example/resume"
        val addon = PlaybackSource(PlaybackSource.addonId(base), PlaybackSourceKind.ADDON, "Preferred", baseUrl = base)
        val sources = listOf(addon, PlaybackSource.jio, PlaybackSource.youtube)
        PlaybackSourcesPreferences.save(app, sources)
        until { monitor.sourcePolicy.value.upgradeSources == sources }
        youtubeAvailable = true
        val current = selected.copy(playlistEntryId = 42L).toMediaItem()
        val youtube = selected.copy(audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio", source = "YouTube Music")
        val origin = checkNotNull(SourceTransitionOrigin.capture(youtube, monitor.sourcePolicy.value,
            automaticFastStart = true))
        fun queuedItem(bookmark: SourceTransitionOrigin) = current.buildUpon().setMediaMetadata(
            current.mediaMetadata.buildUpon().setExtras(android.os.Bundle(current.mediaMetadata.extras).apply {
                SourceTransitionOrigin.write(this, bookmark)
            }).build()).build()

        val resumed = resolveQueued(queuedItem(origin))
        assertEquals(PlaybackMode.BEST_QUALITY, resumed.playbackMode)
        assertEquals(addonAddress, resumed.localConfiguration?.uri.toString())
        assertEquals(current.mediaId, resumed.mediaId)
        assertEquals(42L, resumed.mediaMetadata.extras?.getLong("playlist_entry_id"))

        val manual = resolveQueued(queuedItem(origin.copy(automaticFastStart = false)))
        assertEquals("xtreme-youtube", manual.localConfiguration?.uri?.scheme)

        monitor.setAudioEnhancer(false, returnToYouTube = true, trackId = selected.id)
        until { monitor.sourcePolicy.value.returnsToYouTube(selected.id) }
        val reverted = resolveQueued(queuedItem(origin))
        assertEquals("xtreme-youtube", reverted.localConfiguration?.uri?.scheme)
    }

    @Test fun fastStartServiceSwitchesAnUnseekableYoutubeItemAtTheLivePosition() =
        verifyFastStartHandoff(useAddon = false)

    @Test fun fastStartServiceSwitchesToTheBestQualityAddonAtTheLivePosition() =
        verifyFastStartHandoff(useAddon = true)

    private fun verifyFastStartHandoff(useAddon: Boolean) = runBlocking {
        val base = "https://addons.example/service-fast-start"
        val addon = PlaybackSource(PlaybackSource.addonId(base), PlaybackSourceKind.ADDON, "Preferred", baseUrl = base)
        val sources = if (useAddon) listOf(addon, PlaybackSource.jio, PlaybackSource.youtube)
            else listOf(PlaybackSource.jio, PlaybackSource.youtube)
        PlaybackSourcesPreferences.save(app, sources)
        until { monitor.sourcePolicy.value.upgradeSources == sources }
        val policy = monitor.sourcePolicy.value
        val candidate = if (useAddon) checkNotNull(StreamingAddons.parseStream(
            """{"url":"https://files.example/fast-start.wav","codec":"wav","bitrate":1536000}""",
            base, selected, selected, policy, addon.id)) else selected
        val bytes = wav()
        val cache = MusicCache.getSimpleCache(app)
        StreamedAudioRepresentations.remember(candidate, policy)
        val key = requireNotNull(StreamedAudioRepresentations.keyFor(Uri.parse(candidate.audioUrl)))
        val hole = cache.startReadWrite(key, 0L, bytes.size.toLong())
        try {
            val file = cache.startFile(key, 0L, bytes.size.toLong())
            file.writeBytes(bytes)
            cache.commitFile(file, bytes.size.toLong())
        } finally { cache.releaseHoleSpan(hole) }
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().also {
            ContentMetadataMutations.setContentLength(it, bytes.size.toLong())
        })
        StreamedAudioRepresentations.persistBinding(cache, key)
        val best = repository.resolvePlaybackStart(selected)
        val starting = selected.copy(audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?codec=opus",
            source = "YouTube Music", bitrateKbps = 128).toMediaItem()
        var item = starting.buildUpon().setMediaMetadata(starting.mediaMetadata.buildUpon()
            .setExtras(android.os.Bundle(starting.mediaMetadata.extras).apply {
                putString("source_policy_key", policy.key)
                putPlaybackMode(PlaybackMode.FAST_START)
            }).build()).build()
        var seek: Pair<Int, Long>? = null
        var resume = false
        var isPlaying = false
        var position = 38_000L
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentMediaItem" -> item
                "getCurrentTimeline" -> Timeline.EMPTY
                "getCurrentMediaItemIndex" -> 1
                "getMediaItemCount" -> 3
                "getRepeatMode" -> Player.REPEAT_MODE_OFF
                "isPlaying" -> isPlaying
                "getPlayWhenReady" -> true
                "isCurrentMediaItemSeekable", "isCurrentMediaItemLive", "getShuffleModeEnabled" -> false
                "getPlaybackState" -> Player.STATE_READY
                "getDuration" -> selected.durationMs
                "getCurrentPosition" -> position
                "getPlaybackParameters" -> androidx.media3.common.PlaybackParameters.DEFAULT
                "replaceMediaItem" -> {
                    assertEquals(1, args!![0])
                    item = args[1] as MediaItem
                    ReflectionHelpers.setField(service, "verifiedSignalUri", item.localConfiguration!!.uri)
                    ReflectionHelpers.setField(service, "verifiedSignalFormat", Format.Builder()
                        .setSampleMimeType(if (useAddon) MimeTypes.AUDIO_RAW else MimeTypes.AUDIO_AAC)
                        .setSampleRate(if (useAddon) 48_000 else 44_100).setChannelCount(2).build())
                    ReflectionHelpers.setField(service, "verifiedSignalBitrate", candidate.bitrateKbps)
                    null
                }
                "seekTo" -> { seek = args!![0] as Int to (args[1] as Long); null }
                "setPlayWhenReady" -> { resume = args!![0] as Boolean; null }
                else -> null
            }
        } as ExoPlayer
        try {
            ReflectionHelpers.setField(service, "exoPlayer", player)
            ReflectionHelpers.callInstanceMethod<Unit>(service, "scheduleHeadlessLossless",
                ReflectionHelpers.ClassParameter.from(PlaybackSourcePolicy::class.java, policy))
            assertNull(ReflectionHelpers.getField<Job?>(service, "sourceRecoveryJob"))
            assertEquals(0, addonRequests.get())
            isPlaying = true
            ReflectionHelpers.callInstanceMethod<Unit>(service, "scheduleHeadlessLossless",
                ReflectionHelpers.ClassParameter.from(PlaybackSourcePolicy::class.java, policy))
            position = 42_500L
            until { seek != null }
            assertEquals(1 to 42_500L, seek)
            assertTrue(resume)
            assertEquals(starting.mediaId, item.mediaId)
            assertEquals(PlaybackMode.FAST_START, item.playbackMode)
            val active = item.localConfiguration!!.uri
            assertEquals(if (useAddon) addon.id else PlaybackSource.jio.id, active.getQueryParameter("provider"))
            assertEquals(best.track.audioUrl, active.toString())
            assertEquals(useAddon, item.mediaMetadata.extras?.getBoolean("isLossless"))
            assertEquals(0, addonRequests.get())
            val origin = checkNotNull(SourceTransitionOrigin.read(item, selected))
            assertEquals(PlaybackSource.youtube.id, origin.sourceId)
            assertTrue(origin.automaticFastStart)
        } finally { cache.removeResource(key) }
    }

    @Test fun speculativeServiceResolutionSharesAnAudibleLookupsResultWithoutOwningItsLifetime() = runBlocking {
        val audible = async(Dispatchers.IO) { repository.resolvePlayableTrack(selected) }
        withTimeout(3_000) { entered.await() }
        val queued = async(Dispatchers.IO) { resolveQueued(selected.toMediaItem()) }
        val flights = ReflectionHelpers.getField<MutableMap<*, *>>(resolver, "archiveFlights")
        until { synchronized(flights) { flights.values.any { flight ->
            ReflectionHelpers.getField<Set<*>>(flight, "readers").isNotEmpty()
        } } }
        assertFalse(queued.isCompleted)
        assertEquals(selected.audioUrl, audible.await().audioUrl)
        released.complete(Unit)
        val prepared = withTimeout(4_000) { queued.await() }
        assertEquals(selected.id, prepared.mediaId)
        assertEquals(archive.audioUrl, prepared.localConfiguration!!.uri.toString())
        assertEquals(PlaybackMode.BEST_QUALITY, prepared.playbackMode)
        assertEquals("The service's speculative reader must join the existing Archive flight", 1, requests.get())
        assertFalse(cancelled.isCompleted)
        until { StreamResolver.getCachedLosslessMatch(selected) != null }
        assertEquals(archive.audioUrl, StreamResolver.getCachedLosslessMatch(selected)!!.audioUrl)
        assertEquals(1, requests.get())
    }

    @Test fun removingAnUnresolvedQueueSourceCancelsItsLastReaderAndCannotPublishALateTimeline() = runBlocking {
        standardGate = CompletableDeferred()
        var timeline: Timeline? = null
        val source = PolicyResolvingMediaSource(selected.copy(audioUrl = "").toMediaItem(),
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(app), lookupScope, resolve = ::resolveQueued)
        val caller = MediaSource.MediaSourceCaller { _, value -> timeline = value }
        source.prepareSource(caller, null); sources.add(source to caller)
        until { source.maybeThrowSourceInfoRefreshError(); entered.isCompleted }
        source.releaseSource(caller); sources.clear()
        withTimeout(2_000) { cancelled.await() }
        standardGate!!.complete(Unit); shadowOf(Looper.getMainLooper()).idle()
        assertNull(timeline)
        assertFalse(resolver.hasPendingLosslessDiscovery(selected.id, monitor.sourcePolicySnapshot().key))
        assertEquals(1, requests.get())
    }

    @Test fun aRequiredQueuedArchiveRecordingCanFinishVerificationWhenNoLossyFallbackExists() = runBlocking {
        standardAvailable = false
        archiveDelayMs = StreamResolver.LOSSLESS_LOOKUP_TIMEOUT_MS + 700L
        val resolved = withTimeout(6_000) { withContext(Dispatchers.IO) { resolveQueued(selected.copy(audioUrl = "").toMediaItem()) } }
        assertEquals(selected.id, resolved.mediaId)
        assertEquals(archive.audioUrl, resolved.localConfiguration!!.uri.toString())
        assertTrue(resolved.mediaMetadata.extras!!.getBoolean("isLossless"))
        assertFalse(resolved.mediaMetadata.extras!!.getBoolean("audio_actual"))
        assertEquals(1, requests.get())
    }

    @Test fun releasingAnUnresolvedServiceSourceCannotCancelAnotherAudibleOwner() = runBlocking {
        val audible = async(Dispatchers.IO) { repository.resolvePlayableTrack(selected) }
        withTimeout(3_000) { entered.await(); audible.await() }
        standardGate = CompletableDeferred()
        val source = PolicyResolvingMediaSource(selected.copy(audioUrl = "").toMediaItem(),
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(app), lookupScope, resolve = ::resolveQueued)
        val caller = MediaSource.MediaSourceCaller { _, _ -> fail("A released unresolved source cannot publish a timeline") }
        source.prepareSource(caller, null); sources.add(source to caller)
        val flights = ReflectionHelpers.getField<MutableMap<*, *>>(resolver, "archiveFlights")
        until { synchronized(flights) { flights.values.any { flight ->
            ReflectionHelpers.getField<Set<*>>(flight, "readers").isNotEmpty()
        } } }
        source.releaseSource(caller); sources.clear()
        assertTrue(resolver.hasPendingLosslessDiscovery(selected.id, monitor.sourcePolicySnapshot().key))
        assertFalse(cancelled.isCompleted)
        assertEquals(1, requests.get())
        standardGate!!.complete(Unit); released.complete(Unit)
        until { StreamResolver.getCachedLosslessMatch(selected) != null }
        assertEquals(archive.audioUrl, StreamResolver.getCachedLosslessMatch(selected)!!.audioUrl)
        assertFalse(cancelled.isCompleted)
        assertEquals(1, requests.get())
    }

    @Test fun threeTracksAutomaticallyAdvanceThroughTheRealServiceResolverAndMedia3PcmOutput() = playRealQueue(0)
    @Test fun customPlaylistStartsAtTheSelectedEntryAndPlaysItBeforeAdvancing() = playRealQueue(1)

    private fun playRealQueue(startIndex: Int) = runBlocking {
        AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.HIGH)
        assertFalse(monitor.sourcePolicySnapshot().allowsArchive)
        val capabilities = org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(android.media.MediaFormat.createAudioFormat(androidx.media3.common.MimeTypes.AUDIO_RAW, 48000, 2))
            .setIsEncoder(false).build()
        org.robolectric.shadows.ShadowMediaCodecList.addCodec(org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder()
            .setName("OMX.google.raw.decoder").setIsEncoder(false).setCapabilities(capabilities).build())
        androidx.media3.exoplayer.mediacodec.MediaCodecUtil.clearDecoderInfoCache()
        val samples = wav()
        val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        val prepared = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val pcmStreams = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val streamOffset = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
        val audioWrites = AtomicInteger()
        val firstAudio = java.util.concurrent.ConcurrentHashMap<Long, Long>()
        val advancing = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val audioListener = org.robolectric.shadows.ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
            if (data.isNotEmpty()) {
                audioWrites.incrementAndGet(); pcmStreams.add(streamOffset.get())
                firstAudio.putIfAbsent(streamOffset.get(), android.os.SystemClock.elapsedRealtime())
            }
        }
        val transport = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(app).setDataSourceFactory {
            val data = androidx.media3.datasource.ByteArrayDataSource(samples)
            object : androidx.media3.datasource.DataSource by data {
                override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                    opened.add(spec.uri.toString()); return data.open(spec)
                }
            }
        }
        val factory = object : MediaSource.Factory by transport {
            override fun createMediaSource(item: MediaItem): MediaSource = PolicyResolvingMediaSource(item, transport, lookupScope,
                resolvedIsCurrent = { it.mediaMetadata.extras?.getString("source_policy_key") == monitor.sourcePolicySnapshot().key }) {
                prepared.getOrPut(it.mediaId) { AtomicInteger() }.incrementAndGet()
                resolveQueued(it)
            }
        }
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(app) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink {
                val actual = BitPerfectAudioSink(context, reportSignalPath = false, allowDirect = { false })
                return object : androidx.media3.exoplayer.audio.AudioSink by actual {
                    override fun setOutputStreamOffsetUs(offset: Long) {
                        streamOffset.set(offset); actual.setOutputStreamOffsetUs(offset)
                    }
                }
            }
        }.forceDisableMediaCodecAsynchronousQueueing()
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(app, renderers).setMediaSourceFactory(factory).build()
        val transitions = mutableListOf<Pair<String, Int>>()
        val states = mutableListOf<Int>()
        var failure: androidx.media3.common.PlaybackException? = null
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { item?.let { transitions.add(it.mediaId to reason) } }
            override fun onPlaybackStateChanged(state: Int) { states.add(state) }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failure = error }
        })
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioPositionAdvancing(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                playoutStartSystemTimeMs: Long) { advancing.add(playoutStartSystemTimeMs) }
        })
        org.robolectric.shadows.ShadowAudioTrack.addAudioDataListener(audioListener)
        try {
            val tracks = (1..3).map { selected.copy(id = "online_queue_$it", title = "Song $it", durationMs = 600,
                playlistEntryId = if (startIndex > 0) 100L + it else 0L,
                audioUrl = "https://aac.saavncdn.com/queue_${it}_320.mp4") }
            val expected = tracks.drop(startIndex)
            player.skipSilenceEnabled = true // Exercise retained silence/EOS across all three sources too.
            player.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0L); player.prepare(); player.play()
            withTimeout(12_000) {
                while (player.playbackState != androidx.media3.common.Player.STATE_ENDED) {
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
                    failure?.let { throw AssertionError("Actual Media3 source preparation failed; policy=${monitor.sourcePolicySnapshot().key}", it) }
                    delay(5)
                }
            }
            assertEquals(expected.map { it.id }, transitions.map { it.first })
            assertEquals(expected.size - 1, transitions.count { it.second == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO })
            assertTrue(states.contains(androidx.media3.common.Player.STATE_READY))
            assertEquals(2, player.currentMediaItemIndex)
            assertTrue(player.playWhenReady)
            assertEquals(expected.map { it.audioUrl }.toSet(), opened.toSet())
            assertTrue(prepared.values.all { it.get() == 1 })
            assertEquals(expected.size, prepared.size)
            assertTrue("AudioTrack received decoded PCM", audioWrites.get() > 0)
            assertTrue("Media3 observed its audio output clock advancing", advancing.isNotEmpty())
            assertEquals("Each source produced PCM under its own renderer stream offset", expected.size, pcmStreams.size)
            assertEquals(expected.size, firstAudio.size)
            println("Media3 queue evidence: transitions=$transitions, states=$states, firstPcmByStream=$firstAudio, " +
                "advancingCallbacks=${advancing.size}, audioWrites=${audioWrites.get()}")
            assertEquals(0, requests.get())
        } finally {
            player.release()
            org.robolectric.shadows.ShadowAudioTrack.removeAudioDataListener(audioListener)
            org.robolectric.shadows.ShadowMediaCodecList.reset()
            androidx.media3.exoplayer.mediacodec.MediaCodecUtil.clearDecoderInfoCache()
        }
    }

    /** Generated PCM avoids remote audio, copyright material and extra test assets. */
    private fun wav(): ByteArray {
        val frames = 28_800
        return java.nio.ByteBuffer.allocate(44 + frames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 4); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(48000); putInt(48000 * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(frames * 4)
            repeat(frames) { index -> repeat(2) { putShort(if (index < 9600) 4000 else 0) } }
        }.array()
    }
}
