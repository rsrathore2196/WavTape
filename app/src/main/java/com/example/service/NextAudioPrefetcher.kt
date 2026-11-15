package com.example.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.cache.SmartCacheManager
import com.example.playback.AudioQualityCeiling
import com.example.playback.NetworkConnectionType
import com.example.playback.NetworkQualityMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Optional byte work has one owner. Player reads remain on its application looper;
 * storage, battery checks, resolution and cache writes run on IO. No extra player. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class NextAudioPrefetcher(
    private val context: Context,
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val network: NetworkQualityMonitor,
    private val resolve: suspend (MediaItem) -> MediaItem,
    private val fetch: suspend (android.net.Uri, Boolean, () -> Boolean) -> Unit = { uri, full, allowed ->
        MusicCache.prefetch(context, uri, full, allowed)
    }
) : AutoCloseable {
    private val work = NextPrefetchWork(scope)
    private var observation: Job? = null
    private var watchdog: Job? = null
    private var initialized = false
    private var closed = false
    private var preloadUs = Long.MIN_VALUE
    private var queueRevision = 0L

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) queueRevision++
            update()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // Seeking/rapid skips cancel old work even when the same next item remains selected.
            queueRevision++
            update()
        }
    }

    init {
        player.addListener(listener)
        observation = scope.launch {
            try {
                withContext(Dispatchers.IO) { SmartCacheManager.initialize(context, scheduleMaintenance = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.w("AudioPrefetch", "Cache preferences are unavailable", error)
                return@launch
            }
            initialized = true
            combine(network.sourcePolicy, network.connectionType, SmartCacheManager.stats,
                MusicCache.prefetchEpoch) { _, _, _, _ -> Unit }.collect { update() }
        }
        watchdog = scope.launch {
            while (true) { update(); delay(1_000L) }
        }
    }

    private fun stopRequest() = work.cancel()

    private fun update() {
        if (closed) return
        val duration = player.duration
        val remaining = duration.takeIf { it > 0L }?.minus(player.currentPosition)
        val nearEnd = remaining != null && remaining in 1L..IMMINENT_MS
        // Keep the existing Media3 period preloader for imminent gapless/crossfade
        // preparation. Long-range optional bytes use the single cancellable job below.
        val desiredPreload = if (player.playWhenReady && nearEnd) CROSSFADE_READ_AHEAD_US else 0L
        if (desiredPreload != preloadUs) {
            preloadUs = desiredPreload
            player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(desiredPreload))
        }
        val settings = SmartCacheManager.stats.value
        val policy = network.sourcePolicy.value
        val connection = network.connectionType.value
        val nextIndex = player.nextMediaItemIndex
        val eligible = initialized && player.isPlaying && player.playerError == null &&
            player.repeatMode != Player.REPEAT_MODE_ONE && nextIndex != C.INDEX_UNSET &&
            nextIndex != player.currentMediaItemIndex && !nearEnd && !player.isCurrentMediaItemLive &&
            !player.isCurrentMediaItemDynamic && duration > 0L &&
            (player.bufferedPosition - player.currentPosition >= HEALTHY_BUFFER_MS || player.bufferedPosition >= duration) &&
            NextPrefetchPolicy.allowed(settings.autoPreCacheEnabled, settings.cacheOnWifiOnly, connection,
                policy.ready && policy.online, policy.ceiling)
        if (!eligible) { stopRequest(); return }
        val next = player.getMediaItemAt(nextIndex)
        val uri = next.localConfiguration?.uri ?: run { stopRequest(); return }
        if (MusicCache.isLocal(uri) || uri.scheme in setOf(com.example.downloads.OfflineDownloads.SCHEME, MusicCache.OFFLINE_SCHEME)) {
            stopRequest(); return
        }
        val key = listOf(queueRevision, player.currentMediaItemIndex, player.currentMediaItem?.mediaId,
            nextIndex, next.mediaId, uri, next.mediaMetadata.extras?.getLong("playlist_entry_id"),
            policy.key, connection, settings.autoPreCacheEnabled, settings.cacheOnWifiOnly,
            MusicCache.prefetchEpoch.value).joinToString("|")
        val full = NextPrefetchPolicy.fetchFull(connection, policy.ceiling)
        work.submit(key) { allowed ->
            try {
                val safe = withContext(Dispatchers.IO) { NextPrefetchPolicy.resourcesAvailable(context) }
                if (!safe || !allowed()) return@submit
                val prepared = withContext(Dispatchers.IO) { resolve(next) }
                currentCoroutineContext().ensureActive()
                val resolved = prepared.localConfiguration?.uri ?: return@submit
                if (MusicCache.isLocal(resolved) || resolved.scheme in setOf(com.example.downloads.OfflineDownloads.SCHEME, MusicCache.OFFLINE_SCHEME)) return@submit
                withContext(Dispatchers.IO) { fetch(resolved, full, allowed) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // One bounded attempt per stable queue state. Playback owns retry/recovery.
                android.util.Log.d("AudioPrefetch", "Optional audio preparation deferred: ${error.javaClass.simpleName}")
            }
        }
    }

    override fun close() {
        closed = true
        player.removeListener(listener)
        stopRequest()
        observation?.cancel(); observation = null
        watchdog?.cancel(); watchdog = null
    }

    companion object {
        internal const val GRACE_MS = 8_000L
        internal const val IMMINENT_MS = 20_000L
        private const val HEALTHY_BUFFER_MS = 10_000L
        private const val CROSSFADE_READ_AHEAD_US = 3_000_000L
    }
}


/** Stable queue snapshots start once; cancellation revokes IO admission before stopping its coroutine. */
internal class NextPrefetchWork(private val scope: CoroutineScope) {
    private var key: String? = null
    private var job: Job? = null
    private var permitted = AtomicBoolean(false)
    fun submit(identity: String, action: suspend (() -> Boolean) -> Unit) {
        if (identity == key) return
        cancel()
        key = identity
        val admitted = AtomicBoolean(true).also { permitted = it }
        job = scope.launch {
            delay(NextAudioPrefetcher.GRACE_MS)
            action { admitted.get() }
        }
    }
    fun cancel() {
        permitted.set(false)
        job?.cancel(); job = null
        key = null
    }
}

internal object NextPrefetchPolicy {
    fun allowed(enabled: Boolean, wifiOnly: Boolean, connection: NetworkConnectionType,
        online: Boolean, ceiling: AudioQualityCeiling): Boolean = enabled && online &&
        connection != NetworkConnectionType.OFFLINE &&
        (!wifiOnly || connection == NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET) &&
        (connection == NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET || ceiling != AudioQualityCeiling.DATA_SAVER)

    fun fetchFull(connection: NetworkConnectionType, ceiling: AudioQualityCeiling): Boolean =
        connection == NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET && ceiling != AudioQualityCeiling.DATA_SAVER

    fun resourcesAvailable(context: Context): Boolean {
        // Reserve space for active playback and OS work, rather than filling disk to quota.
        if (context.noBackupFilesDir.usableSpace < 256L * 1024L * 1024L) return false
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return plugged || level < 0 || scale <= 0 || level.toLong() * 100L / scale >= 15L
    }
}
