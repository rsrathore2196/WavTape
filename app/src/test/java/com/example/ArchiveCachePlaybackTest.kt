package com.example

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.flac.FlacExtractor
import com.example.data.model.MusicTrack
import com.example.data.remote.ArchiveCacheResume
import com.example.data.remote.ArchiveFileKey
import com.example.data.remote.ArchiveFileTransport
import com.example.data.remote.ArchiveSeekSupport
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackSourcePolicy
import com.example.service.MusicCache
import com.example.service.StreamedAudioRepresentations
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.EOFException
import java.io.IOException
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArchiveCachePlaybackTest {
    @get:Rule val folder = TemporaryFolder()
    private val recording = ByteArray(1024) { (it % 231).toByte() }

    private fun file(name: String) = ArchiveFileKey.create(name, "track.flac")!!

    private class Origin(var bytes: ByteArray, var supportsRanges: Boolean = false, var actualLength: Long = bytes.size.toLong()) {
        var tag: String? = null
        var enabled = true
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val range = requireNotNull(request.header("Range"))
            requests += range
            val start = range.substringAfter('=').substringBefore('-').toInt()
            val end = range.substringAfter('-').toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
            if (supportsRanges) response(request, 206, bytes.copyOfRange(start, end + 1), (end - start + 1L))
                .newBuilder().header("Content-Range", "bytes $start-$end/${bytes.size}").build()
            else response(request, 200, bytes, actualLength)
        }.build()

        private fun response(request: Request, status: Int, bytes: ByteArray, length: Long) =
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .apply { tag?.let { header("ETag", it) } }
                .body(object : ResponseBody() {
                    private val input = Buffer().write(bytes)
                    override fun contentType() = null
                    override fun contentLength() = length
                    override fun source() = input
                }).build()
    }

    private fun cache() = SimpleCache(folder.root, NoOpCacheEvictor(), StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()))

    private fun source(cache: SimpleCache, file: ArchiveFileKey, origin: Origin): DataSource =
        MusicCache.archiveCacheSource(cache, file.id, origin.enabled) { resume ->
            ArchiveFileTransport.FileSource(file, origin.client, resume, replaySequential = true) {
                if (!origin.enabled) throw IOException("Fixture offline")
                true
            }
        }

    private fun spec(file: ArchiveFileKey, position: Long = 0L) = ArchiveCacheResume.writableSpec(
        DataSpec.Builder().setUri(Uri.parse(file.url.toString())).setPosition(position)
            .setFlags(DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN).build())

    private fun DataSource.readAll(): ByteArray {
        val result = Buffer()
        val bytes = ByteArray(80)
        while (true) {
            val count = read(bytes, 0, bytes.size)
            if (count == C.RESULT_END_OF_INPUT) return result.readByteArray()
            check(count > 0)
            result.write(bytes, 0, count)
        }
    }

    private fun DataSource.readPrefix(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(bytes, offset, count - offset)
            check(read > 0)
            offset += read
        }
        return bytes
    }

    @Test fun sequentialPartialRestartAfterExpiryReusesOneZeroBasedResponseAndCompletesCache() {
        val file = file("sequential-restart")
        val origin = Origin(recording)
        cache().useCache { cache ->
            source(cache, file, origin).useSource {
                assertEquals(recording.size.toLong(), open(spec(file)))
                assertArrayEquals(recording.copyOf(345), readPrefix(345))
            }
            assertTrue(cache.isCached(file.id, 0L, 345L))
            assertFalse(cache.isCached(file.id, 0L, recording.size.toLong()))
        }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
        assertFalse(ArchiveFileTransport.isSequentialOnly(file))
        origin.requests.clear()
        cache().useCache { cache ->
            source(cache, file, origin).useSource {
                open(spec(file))
                assertArrayEquals(recording, readAll())
            }
            assertEquals(listOf("bytes=0-0"), origin.requests)
            assertTrue(cache.isCached(file.id, 0L, recording.size.toLong()))
        }
    }

    @Test fun expiredPositiveRangeHintCannotSendNonzeroRequestToSequentialServer() {
        val file = file("expired-positive")
        val origin = Origin(recording, supportsRanges = true).apply { tag = "\"same\"" }
        cache().useCache { cache ->
            source(cache, file, origin).useSource { open(spec(file)); readPrefix(367) }
            assertTrue(ArchiveFileTransport.supportsRanges(file))
            ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
            origin.supportsRanges = false
            origin.requests.clear()
            source(cache, file, origin).useSource {
                open(spec(file))
                assertArrayEquals(recording, readAll())
            }
            assertEquals(listOf("bytes=0-0"), origin.requests)
        }
    }

    @Test fun untaggedRangeResponseAlsoProvesTheExistingPrefixBeforeAppending() {
        val file = file("untagged-ranges")
        val origin = Origin(recording, supportsRanges = true)
        cache().useCache { cache ->
            source(cache, file, origin).useSource { open(spec(file)); readPrefix(256) }
            origin.requests.clear()
            source(cache, file, origin).useSource {
                open(spec(file))
                assertArrayEquals(recording, readAll())
            }
            assertTrue(cache.isCached(file.id, 0L, recording.size.toLong()))
            assertTrue(origin.requests.first().startsWith("bytes=0-"))
        }
    }

    @Test fun changedUntaggedPrefixCannotBeAppendedToAnOlderCacheEntry() {
        val file = file("changed-prefix")
        val origin = Origin(recording)
        cache().useCache { cache ->
            source(cache, file, origin).useSource { open(spec(file)); readPrefix(256) }
            origin.bytes = recording.clone().apply { this[3] = -100 }
            source(cache, file, origin).useSource {
                open(spec(file))
                assertEquals("range_changed", assertThrows(ArchiveFileTransport.Failure::class.java) { readAll() }.outcome)
            }
            assertTrue(cache.isCached(file.id, 0L, 256L))
            assertFalse(cache.isCached(file.id, 256L, 1L))
        }
    }

    @Test fun interruptedTransferKeepsPrefixThenReconnectsAndCanPlayAndSeekOffline() {
        val file = file("interrupted-reconnect")
        val origin = Origin(recording.copyOf(260), actualLength = recording.size.toLong()).apply { tag = "\"stable\"" }
        cache().useCache { cache ->
            source(cache, file, origin).useSource {
                open(spec(file))
                assertThrows(EOFException::class.java) { readAll() }
            }
            assertTrue(cache.isCached(file.id, 0L, 260L))
            origin.enabled = false
            val count = origin.requests.size
            source(cache, file, origin).useSource {
                open(spec(file))
                assertArrayEquals(recording.copyOf(260), readPrefix(260))
                assertThrows(IOException::class.java) { readAll() }
            }
            assertEquals(count, origin.requests.size)
            origin.bytes = recording
            origin.enabled = true
            source(cache, file, origin).useSource { open(spec(file)); assertArrayEquals(recording, readAll()) }
            origin.enabled = false
            val requestCount = origin.requests.size
            source(cache, file, origin).useSource {
                assertEquals(824L, open(spec(file, 200)))
                assertArrayEquals(recording.copyOfRange(200, recording.size), readAll())
            }
            assertEquals(requestCount, origin.requests.size)
        }
    }

    @Test fun nativeSeekMapIsPublishedAfterKnownLengthSequentialCacheFinishes() = seekAfterCache(knownLength = true)

    @Test fun unknownLengthFlacIsCachedAndPublishesExactFrameOffsetsOnSameExtractor() = seekAfterCache(knownLength = false)

    private fun seekAfterCache(knownLength: Boolean) {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/audio/unindexed-silence.flac")).use { it.readBytes() }
        val file = file(if (knownLength) "known-flac-extraction" else "unknown-flac-extraction")
        val origin = Origin(bytes, actualLength = if (knownLength) bytes.size.toLong() else -1L)
        cache().useCache { cache ->
            val seek = ArchiveSeekSupport({ ArchiveFileTransport.supportsRanges(file) }) {
                ContentMetadata.getContentLength(cache.getContentMetadata(file.id)).let { it > 0 && cache.isCached(file.id, 0L, it) }
            }
            val source = seek.dataSources { source(cache, file, origin) }.createDataSource()
            val maps = mutableListOf<SeekMap>()
            val frames = mutableListOf<Long>()
            val output = object : TrackOutput by DummyTrackOutput() {
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int,
                    cryptoData: TrackOutput.CryptoData?) { frames += timeUs }
            }
            val extractor = seek.wrap(FlacExtractor())
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int) = output
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) { maps += seekMap }
            })
            try {
                var input = DefaultExtractorInput(source, 0L, source.open(spec(file)))
                assertTrue(extractor.sniff(input))
                input.resetPeekPosition()
                val holder = PositionHolder()
                while (extractor.read(input, holder) != Extractor.RESULT_END_OF_INPUT) Unit
                assertTrue(frames.isNotEmpty())
                assertFalse(maps.single().isSeekable)
                source.close()
                assertEquals(bytes.size.toLong(), ContentMetadata.getContentLength(cache.getContentMetadata(file.id)))
                assertTrue(maps.last().isSeekable)
                origin.enabled = false
                val point = maps.last().getSeekPoints(10_000_000L).first
                val length = source.open(spec(file, point.position))
                input = DefaultExtractorInput(source, point.position, point.position + length)
                extractor.seek(point.position, 10_000_000L)
                frames.clear()
                repeat(64) {
                    if (frames.isEmpty() && extractor.read(input, holder) == Extractor.RESULT_SEEK) {
                        source.close()
                        val offset = holder.position
                        input = DefaultExtractorInput(source, offset, offset + source.open(spec(file, offset)))
                    }
                }
                assertTrue("Playback must emit frames after a cache-only seek", frames.isNotEmpty())
                assertTrue(frames.first() in 8_000_000L..11_000_000L)
                assertEquals(listOf("bytes=0-"), origin.requests)
            } finally {
                source.close()
                extractor.release()
            }
        }
    }

    @Test fun archiveCacheKeyIsStableAcrossChangedQualityMetadataAndKeepsDistinctFilesSeparate() {
        val file = file("stable-key")
        val uri = Uri.parse(file.url.toString())
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX)
        val track = MusicTrack(file.id, "Song", "Artist", "Album", 120_000L, "", uri.toString(),
            source = "INTERNET_ARCHIVE", isLossless = true, bitrateKbps = 900)
        StreamedAudioRepresentations.remember(track, policy)
        val key = requireNotNull(StreamedAudioRepresentations.keyFor(uri))
        StreamedAudioRepresentations.remember(track.copy(bitrateKbps = 1200, durationMs = 121_000L), policy)
        assertEquals(key, StreamedAudioRepresentations.keyFor(uri))
        val other = file("distinct-key").url.toString()
        StreamedAudioRepresentations.remember(track.copy(audioUrl = other), policy)
        assertNotEquals(key, StreamedAudioRepresentations.keyFor(Uri.parse(other)))
    }

    private inline fun DataSource.useSource(action: DataSource.() -> Unit) {
        try { action() } finally { close() }
    }

    private inline fun SimpleCache.useCache(action: (SimpleCache) -> Unit) {
        try { action(this) } finally { release() }
    }
}
