package com.example

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.OtherSettingsPreferences
import com.example.data.local.PlaybackSourcesPreferences
import com.example.data.model.MusicTrack
import com.example.data.model.toMediaItem
import com.example.playback.NetworkQualityMonitor
import com.example.playback.PlaybackManager
import com.example.playback.PlaybackSource
import com.example.service.PlaybackSessionStore
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Exercises the player selection boundary and persisted session, beyond the cache-only data source. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CachedSelectionSessionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun cachedTrack(): MusicTrack {
        val uri = Uri.Builder().scheme("xtreme-cached").authority("audio").appendPath("youtube:abcdefghijk:251:128")
            .appendQueryParameter("provider", PlaybackSource.youtube.id).appendQueryParameter("kind", "YOUTUBE")
            .appendQueryParameter("bitrate", "48").appendQueryParameter("lossless", "false")
            .appendQueryParameter("codec", "Opus").appendQueryParameter("mime", "audio/webm")
            .appendQueryParameter("track", "yt_abcdefghijk").build()
        return MusicTrack("yt_abcdefghijk", "Cached recording", "Artist", "Album", 180_000, "", uri.toString(),
            bitrateKbps = 48, source = "YouTube Music", playlistEntryId = 789)
    }

    private class QueuePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var snapshot = State.Builder().setAvailableCommands(Player.Commands.Builder().addAllCommands().build()).build()
        override fun getState(): State = snapshot
        override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
            snapshot = snapshot.buildUpon().setPlaylist(mediaItems.mapIndexed { index, item ->
                MediaItemData.Builder(index).setMediaItem(item).setDurationUs(180_000_000L).setIsSeekable(true).build()
            }).setCurrentMediaItemIndex(startIndex).setContentPositionMs(startPositionMs.coerceAtLeast(0L)).build()
            return Futures.immediateVoidFuture()
        }
        override fun handlePrepare(): ListenableFuture<*> {
            snapshot = snapshot.buildUpon().setPlaybackState(Player.STATE_BUFFERING).build()
            return Futures.immediateVoidFuture()
        }
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            snapshot = snapshot.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
            return Futures.immediateVoidFuture()
        }
        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    @Test fun selectingACachedPlaylistOccurrenceReachesTheConnectedSessionQueue() = runBlocking(Dispatchers.Main) {
        withContext(Dispatchers.IO) {
            PlaybackSourcesPreferences.save(context, listOf(PlaybackSource.youtube))
            OtherSettingsPreferences.loadPlaybackSources(context)
        }
        val monitor = NetworkQualityMonitor.getInstance(context)
        withTimeout(10_000) { while (!monitor.sourcePolicy.value.ready) delay(20) }
        val selected = cachedTrack()
        assertTrue(monitor.sourcePolicy.value.acceptsRemoteTrack(selected))
        val manager = PlaybackManager(context)
        val native = QueuePlayer()
        val session = MediaSession.Builder(context, native).build()
        val pending = MediaController.Builder(context, session.token).buildAsync()
        var controller: MediaController? = null
        try {
            withTimeout(10_000) { while (!pending.isDone) delay(20) }
            controller = pending.get()
            PlaybackManager::class.java.getDeclaredMethod("setController", MediaController::class.java)
                .apply { isAccessible = true }.invoke(manager, controller)
            PlaybackManager::class.java.getDeclaredMethod("beginSelection", MusicTrack::class.java)
                .apply { isAccessible = true }.invoke(manager, selected)
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                try {
                    PlaybackManager::class.java.getDeclaredMethod("playTrackInternal", MusicTrack::class.java,
                        List::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Continuation::class.java)
                        .apply { isAccessible = true }.invoke(manager, selected, listOf(selected), true, false, continuation)
                } catch (error: InvocationTargetException) { throw error.cause ?: error }
            }
            withTimeout(10_000) { while (native.mediaItemCount != 1) delay(20) }
            assertEquals(selected.id, native.currentMediaItem?.mediaId)
            assertEquals(selected.audioUrl, native.currentMediaItem?.localConfiguration?.uri.toString())
            assertEquals(789L, native.currentMediaItem?.mediaMetadata?.extras?.getLong("playlist_entry_id"))
            assertEquals(selected.audioUrl, manager.uiState.value.currentTrack?.audioUrl)
            assertNull(manager.uiState.value.errorMessage)
        } finally {
            manager.release()
            controller?.release()
            session.release()
            native.release()
        }
    }

    @Test fun mixedCachedDownloadedAndRemoteQueueRestoresFromDiskWithTheSelectedOccurrence() = runBlocking {
        val selected = cachedTrack()
        val items = listOf(selected.toMediaItem(), selected.copy(id = "saved_download", audioUrl = "xtreme-offline://audio/saved", playlistEntryId = 790).toMediaItem(),
            selected.copy(id = "yt_lmnopqrstuv", audioUrl = "xtreme-youtube://audio/lmnopqrstuv/stream.audio?cap=96000", playlistEntryId = 791).toMediaItem())
        assertTrue(items.all(PlaybackSessionStore::isReal))
        val position = 34_567L
        PlaybackSessionStore.checkpoint(context, PlaybackSessionStore.Snapshot(items, 0, position))
        withTimeout(10_000) {
            while (!withContext(Dispatchers.IO) {
                runCatching {
                    val root = JSONObject(context.filesDir.resolve("real_playback_session.json").readText())
                    root.optLong("position") == position && root.getJSONArray("queue").getJSONObject(0).getString("uri") == selected.audioUrl
                }.getOrDefault(false)
            }) delay(20)
        }
        PlaybackSessionStore::class.java.getDeclaredField("latest").apply { isAccessible = true }.set(null, null)
        val restored = PlaybackSessionStore.restore(context)!!
        assertEquals(3, restored.items.size)
        assertEquals(0, restored.index)
        assertEquals(position, restored.positionMs)
        assertEquals(selected.audioUrl, restored.items[0].localConfiguration?.uri.toString())
        assertEquals(789L, restored.items[0].mediaMetadata.extras?.getLong("playlist_entry_id"))
    }
}
