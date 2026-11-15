package com.example

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MusicTrack
import com.example.playback.NetworkQualityMonitor
import com.example.service.MusicCache
import com.example.service.StreamedAudioRepresentations
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackSource
import com.example.playback.NetworkAudioPreferences
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PersistentStreamPlaybackTest {
    private var previousNetwork: NetworkAudioPreferences? = null
    private var previousSources: List<PlaybackSource>? = null
    @Before fun configureSourceFixture() = runBlocking<Unit>(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        previousNetwork = com.example.data.local.AudioQualityPreferences.getNetworkSettings(context)
        previousSources = com.example.data.local.OtherSettingsPreferences.loadPlaybackSources(context)
        com.example.data.local.OtherSettingsPreferences.savePlaybackSources(context, listOf(PlaybackSource.jio, PlaybackSource.youtube))
        com.example.data.local.AudioQualityPreferences.setWifiQualityCeiling(context, AudioQualityCeiling.HIGH)
        com.example.data.local.AudioQualityPreferences.setCellularQualityCeiling(context, AudioQualityCeiling.HIGH)
    }
    @After fun restoreSourceFixture() = runBlocking<Unit>(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        previousSources?.let { com.example.data.local.OtherSettingsPreferences.savePlaybackSources(context, it) }
        previousNetwork?.let {
            com.example.data.local.AudioQualityPreferences.setWifiQualityCeiling(context, it.wifiQualityCeiling)
            com.example.data.local.AudioQualityPreferences.setCellularQualityCeiling(context, it.cellularQualityCeiling)
        }
    }
    @Test fun completeStreamPlaysAndSeeksAfterOwnerRestartWithoutAnHttpTransport() = runBlocking(Dispatchers.Main) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = ByteBuffer.allocate(44 + 16000 * 5).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + 16000 * 5); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(16000 * 5)
        }.array()
        val cached = withContext(Dispatchers.IO) {
            com.example.data.local.OtherSettingsPreferences.loadPlaybackSources(context)
            val policy = NetworkQualityMonitor.getInstance(context).sourcePolicySnapshot()
            val track = MusicTrack("online_cached_wav", "Cached recording", "Artist", "Album", 5000, "",
                "https://aac.saavncdn.com/cached.wav?length=${payload.size}", bitrateKbps = 128, source = "JIOSAAVN")
            StreamedAudioRepresentations.remember(context, track, policy)
            val key = StreamedAudioRepresentations.keyFor(Uri.parse(track.audioUrl))!!
            val cache = MusicCache.getSimpleCache(context)
            val hole = cache.startReadWrite(key, 0, payload.size.toLong())
            try {
                val file = cache.startFile(key, 0, payload.size.toLong())
                file.writeBytes(payload); cache.commitFile(file, payload.size.toLong())
                cache.applyContentMetadataMutations(key, ContentMetadataMutations().set("exo_len", payload.size.toLong()))
            } finally { cache.releaseHoleSpan(hole) }
            val field = MusicCache::class.java.getDeclaredField("simpleCacheInstance").apply { isAccessible = true }
            cache.release(); field.set(null, null)
            StreamedAudioRepresentations.lookup(context, track.copy(audioUrl = ""), policy.copy(online = false))!!
        }
        var error: PlaybackException? = null
        val player = ExoPlayer.Builder(context).build()
        try {
            player.addListener(object : Player.Listener { override fun onPlayerError(failure: PlaybackException) { error = failure } })
            val item = MediaItem.Builder().setMediaId(cached.id).setUri(cached.audioUrl).build()
            player.setMediaSource(MusicCache.cachedSource(context, item)); player.prepare(); player.play()
            withTimeout(20_000) { while (!player.isPlaying) { error?.let { throw AssertionError("Cached decode failed", it) }; delay(20) } }
            player.seekTo(2_000L)
            withTimeout(10_000) { while (!player.isPlaying || player.currentPosition < 2_000L) { error?.let { throw it }; delay(20) } }
            assertEquals("xtreme-cached", Uri.parse(cached.audioUrl).scheme)
            assertEquals(cached.id, player.currentMediaItem?.mediaId)
            assertNotNull(player.audioFormat)
        } finally { player.release() }
    }
}
