package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MusicTrack
import com.example.data.remote.StreamingAddons
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackSource
import com.example.playback.PlaybackSourceKind
import com.example.playback.PlaybackSourcePolicy
import com.example.service.MusicCache
import com.example.service.StreamedAudioRepresentations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StreamedAudioRepresentationsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val policy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, online = false,
        sources = listOf(PlaybackSource.jio, PlaybackSource.youtube))
    private val track = MusicTrack("online_recording", "Exact Song (Reverbed)", "Singer", "Album", 180_000, "",
        "https://aac.saavncdn.com/song_160.m4a?token=private-value&length=128", bitrateKbps = 160, source = "JIOSAAVN")

    private fun closeCache() {
        val field = MusicCache::class.java.getDeclaredField("simpleCacheInstance").apply { isAccessible = true }
        (field.get(null) as? androidx.media3.datasource.cache.SimpleCache)?.release()
        field.set(null, null)
    }
    @Before fun setup() = runBlocking<Unit>(Dispatchers.IO) {
        closeCache()
        context.cacheDir.resolve("xtreme_audio_cache").deleteRecursively()
        context.noBackupFilesDir.resolve("xtreme_streamed_audio_cache").deleteRecursively()
    }
    @After fun cleanup() = runBlocking(Dispatchers.IO) { closeCache() }

    private fun save(selected: MusicTrack = track, bytes: Int = 128, contentLength: Long? = 128L,
        sourcePolicy: PlaybackSourcePolicy = policy): String {
        StreamedAudioRepresentations.remember(selected, sourcePolicy.copy(online = true))
        val key = StreamedAudioRepresentations.keyFor(Uri.parse(selected.audioUrl))!!
        val cache = MusicCache.getSimpleCache(context)
        StreamedAudioRepresentations.persistBinding(cache, key)
        val hole = cache.startReadWrite(key, 0L, bytes.toLong())
        try {
            val file = cache.startFile(key, 0L, bytes.toLong())
            file.writeBytes(ByteArray(bytes) { 42 })
            cache.commitFile(file, bytes.toLong())
            if (contentLength != null) cache.applyContentMetadataMutations(key,
                ContentMetadataMutations().set("exo_len", contentLength))
        } finally { cache.releaseHoleSpan(hole) }
        return key
    }

    @Test fun resolvingARepresentationDoesNotOpenTheStreamedAudioCache() = runBlocking(Dispatchers.IO) {
        StreamedAudioRepresentations.remember(track, policy.copy(online = true))
        val key = StreamedAudioRepresentations.keyFor(Uri.parse(track.audioUrl))!!
        assertNull(MusicCache.peekCacheUsage())

        val cache = MusicCache.getSimpleCache(context)
        assertNull(cache.getContentMetadata(key).get("xtreme_streamed_representation_v1", null as String?))
        StreamedAudioRepresentations.persistBinding(cache, key)
        assertNotNull(cache.getContentMetadata(key).get("xtreme_streamed_representation_v1", null as String?))
    }

    @Test fun completeRepresentationSurvivesRestartAndExpiredCredentialsWithoutPersistingTransport() = runBlocking(Dispatchers.IO) {
        val key = save()
        val cache = MusicCache.getSimpleCache(context)
        val encoded = cache.getContentMetadata(key).get("xtreme_streamed_representation_v1", "")!!
        assertFalse(encoded.contains("private-value")); assertFalse(encoded.contains("https://")); assertFalse(encoded.contains("token"))
        closeCache()
        val cached = StreamedAudioRepresentations.lookup(context, track.copy(audioUrl = ""), policy)!!
        assertEquals(track.id, cached.id)
        assertEquals(track.title, cached.title)
        assertEquals(160, cached.bitrateKbps)
        assertEquals("xtreme-cached", Uri.parse(cached.audioUrl).scheme)
        assertEquals(key, Uri.parse(cached.audioUrl).pathSegments.single())
        assertTrue(StreamedAudioRepresentations.validateCached(context, Uri.parse(cached.audioUrl), policy))
        assertTrue(policy.acceptsRemoteTrack(cached))
        assertEquals("jiosaavn", com.example.playback.SourceTransitionOrigin.sourceId(cached.id, Uri.parse(cached.audioUrl), policy))
    }

    @Test fun onlyArchiveCacheCanReuseARecordingAfterDurationMetadataChanges() = runBlocking(Dispatchers.IO) {
        save()
        assertNull(StreamedAudioRepresentations.lookup(context, track.copy(durationMs = 0), policy))
        val archive = track.copy(
            id = "online_archive_recording", audioUrl = "https://archive.org/download/recording/song.flac",
            source = "INTERNET_ARCHIVE", isLossless = true, bitrateKbps = 730
        )
        val maximum = PlaybackSourcePolicy(AudioQualityCeiling.MAX, online = false)
        val key = save(archive, sourcePolicy = maximum)
        val request = archive.copy(audioUrl = "", durationMs = 0)
        val cached = StreamedAudioRepresentations.lookup(context, request, maximum)!!
        assertEquals(key, Uri.parse(cached.audioUrl).pathSegments.single())
        assertTrue(StreamedAudioRepresentations.validateCached(context, Uri.parse(cached.audioUrl), maximum))
        assertNull(StreamedAudioRepresentations.lookup(context, request.copy(artist = "Other Artist"), maximum))
        assertNull(StreamedAudioRepresentations.lookup(context, request, maximum.copy(ceiling = AudioQualityCeiling.HIGH)))
        assertNull(StreamedAudioRepresentations.lookup(context, request,
            maximum.copy(sources = listOf(PlaybackSource.archive.copy(enabled = false), PlaybackSource.youtube))))
    }

    @Test fun missingBytesUnknownLengthWrongRecordingAndModifiedMetadataAreNeverOfflineReady() = runBlocking(Dispatchers.IO) {
        save(bytes = 64)
        assertNull(StreamedAudioRepresentations.lookup(context, track, policy))
        MusicCache.clearAudioCache(context)
        save(contentLength = null)
        assertNull(StreamedAudioRepresentations.lookup(context, track, policy))
        MusicCache.clearAudioCache(context)
        save()
        assertNull(StreamedAudioRepresentations.lookup(context, track.copy(id = "online_different"), policy))
        assertNull(StreamedAudioRepresentations.lookup(context, track.copy(title = "Exact Song"), policy))
    }

    @Test fun cacheReuseRespectsSourceQualityPreferencesAndRejectsForgedUriMetadata() = runBlocking(Dispatchers.IO) {
        save()
        val cached = StreamedAudioRepresentations.lookup(context, track, policy)!!
        assertNull(StreamedAudioRepresentations.lookup(context, track, policy, preferredSourceId = "youtube"))
        assertNull(StreamedAudioRepresentations.lookup(context, track,
            policy.copy(sources = listOf(PlaybackSource.youtube))))
        assertNull(StreamedAudioRepresentations.lookup(context, track, policy.copy(ceiling = AudioQualityCeiling.DATA_SAVER)))
        val forged = Uri.parse(cached.audioUrl).buildUpon().clearQuery().appendQueryParameter("provider", "youtube").build()
        assertFalse(StreamedAudioRepresentations.validateCached(context, forged, policy))
        assertNull(StreamedAudioRepresentations.lookup(context, track.copy(audioUrl = "file:///music/exact.flac"), policy))
        assertEquals(1, StreamedAudioRepresentations.completeSongCount(MusicCache.getSimpleCache(context)))
        // An index alone cannot make a truncated representation eligible.
        MusicCache.getSimpleCache(context).getCachedSpans(Uri.parse(cached.audioUrl).pathSegments.single()).first().file!!
            .writeBytes(ByteArray(32))
        assertFalse(StreamedAudioRepresentations.validateCached(context, Uri.parse(cached.audioUrl), policy))
    }

    @Test fun aCachedDescriptorCannotOverrideChangedMedia3Metadata() = runBlocking(Dispatchers.IO) {
        val key = save()
        val source = StreamedAudioRepresentations.lookup(context, track, policy)!!
        val uri = Uri.parse(source.audioUrl)
        val cache = MusicCache.getSimpleCache(context)
        val field = "xtreme_streamed_representation_v1"
        val descriptor = cache.getContentMetadata(key).get(field, "")!!
        assertTrue(StreamedAudioRepresentations.validateCached(context, uri, policy))
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(field, "{}"))
        assertNull(StreamedAudioRepresentations.lookup(context, track, policy))
        assertFalse(StreamedAudioRepresentations.validateCached(context, uri, policy))
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(field, descriptor))
        assertTrue(StreamedAudioRepresentations.validateCached(context, uri, policy))
    }

    @Test fun reusingTheSameTransportCannotRewriteDifferentRepresentationEvidence() = runBlocking(Dispatchers.IO) {
        val first = save()
        val cache = MusicCache.getSimpleCache(context)
        val before = cache.getContentMetadata(first).get("xtreme_streamed_representation_v1", "")
        val changed = track.copy(bitrateKbps = 320)
        StreamedAudioRepresentations.remember(changed, policy.copy(online = true))
        val second = StreamedAudioRepresentations.keyFor(Uri.parse(changed.audioUrl))!!
        StreamedAudioRepresentations.persistBinding(cache, second)
        assertNotEquals(first, second)
        assertEquals(before, cache.getContentMetadata(first).get("xtreme_streamed_representation_v1", ""))
        assertTrue(cache.isCached(first, 0L, 128L))
        assertFalse(cache.isCached(second, 0L, 128L))
        // The new incomplete quality cannot hide or overwrite the complete copy.
        assertEquals(160, StreamedAudioRepresentations.lookup(context, track, policy)!!.bitrateKbps)
        StreamedAudioRepresentations.remember(changed, policy.copy(online = true))
        assertEquals(second, StreamedAudioRepresentations.keyFor(Uri.parse(changed.audioUrl)))
    }

    @Test fun progressiveQualityVariantsRemainSeparateAndAdaptiveOrLocalSourcesAreNotRegistered() = runBlocking(Dispatchers.IO) {
        val first = save()
        val secondTrack = track.copy(audioUrl = "https://aac.saavncdn.com/song_320.m4a?length=256", bitrateKbps = 320)
        val second = save(secondTrack, bytes = 256, contentLength = 256)
        assertNotEquals(first, second)
        val high = StreamedAudioRepresentations.lookup(context, track, policy)!!
        assertEquals(320, high.bitrateKbps)
        assertTrue(policy.acceptsStreamBitrate(336, Uri.parse(high.audioUrl)))
        assertFalse(policy.acceptsStreamBitrate(337, Uri.parse(high.audioUrl)))
        for (uri in listOf("file:///music/song.flac", "content://music/item/1", "https://aac.saavncdn.com/manifest.m3u8")) {
            StreamedAudioRepresentations.remember(track.copy(audioUrl = uri), policy.copy(online = true))
            assertNull(StreamedAudioRepresentations.keyFor(Uri.parse(uri)))
        }
    }

    @Test fun completeAddonRelaysRetainTheirCodecForOfflineReplay() = runBlocking(Dispatchers.IO) {
        val base = "https://addons.example/lossless"
        val addon = PlaybackSource(PlaybackSource.addonId(base), PlaybackSourceKind.ADDON, "Lossless relay", baseUrl = base)
        val maximum = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = listOf(addon, PlaybackSource.youtube))
        for ((codec, path) in listOf("flac" to "_playback/123", "alac" to "recording.m4a", "aac" to "compatible.m4a")) {
            val catalog = track.copy(id = "cached_$codec", audioUrl = "")
            val response = JSONObject().put("url", "$base/$path?length=128").put("codec", codec).put("bitrate", 1411200)
            if (codec == "flac") response.put("mimeType", "audio/flac")
            val resolved = checkNotNull(StreamingAddons.parseStream(response.toString(), base, catalog, catalog, maximum))
            save(resolved, sourcePolicy = maximum)
            val offline = maximum.copy(online = false)
            val cached = checkNotNull(StreamedAudioRepresentations.lookup(context, catalog, offline))
            val uri = Uri.parse(cached.audioUrl)
            assertEquals(codec.uppercase(java.util.Locale.ROOT), uri.getQueryParameter("codec"))
            assertEquals(codec != "aac", cached.isLossless)
            assertTrue(StreamedAudioRepresentations.validateCached(context, uri, offline))
            assertNull(StreamedAudioRepresentations.lookup(context, catalog, offline.copy(ceiling = AudioQualityCeiling.HIGH)))
        }
    }
}
