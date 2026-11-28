package com.example

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSystemClock
import com.example.data.model.MusicTrack
import com.example.data.remote.ArchiveFileEvidence
import com.example.data.remote.ArchiveFileKey
import com.example.data.remote.ArchiveFileTransport
import com.example.downloads.DownloadRecord
import com.example.downloads.DownloadStatus
import com.example.downloads.OfflineDownloads
import com.example.downloads.OfflineTransfer
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArchiveFileTransportTest {
    @Test fun encodedFileIdentitySurvivesTrustedRedirectButRejectsSubstitution() {
        val key = ArchiveFileKey.create("album", "disc 1/Porch Light (Live) %2F.flac")!!
        assertEquals(key, ArchiveFileKey.fromId(key.id))
        assertEquals(key, ArchiveFileKey.fromUrl(key.url))
        val mirror = "https://ia601406.us.archive.org/34/items/album/disc%201/Porch%20Light%20%28Live%29%20%252F.flac"
        assertEquals(key, ArchiveFileTransport.redirectTarget(key, key.url, mirror)?.let(ArchiveFileKey::fromUrl))
        assertNull(ArchiveFileTransport.redirectTarget(key, key.url, mirror.replace("https:", "http:")))
        assertNull(ArchiveFileTransport.redirectTarget(key, key.url, mirror.replace(".archive.org", ".archive.org.example.com")))
        assertNull(ArchiveFileTransport.redirectTarget(key, key.url, mirror.replace("/album/", "/other/")))
        assertNull(ArchiveFileTransport.redirectTarget(key, key.url, mirror.replace("Light", "Night")))
        assertNull(ArchiveFileKey.fromUrl("https://archive.org/download/album/dir%2Fsong.flac"))
        assertNull(ArchiveFileKey.create("album", "../song.flac"))
        assertNull(ArchiveFileKey.fromUrl("https://username@archive.org/download/album/song.flac"))
        assertEquals("album", ArchiveFileKey.itemLink("https://archive.org/details/album"))
    }

    @Test fun rangesAreBoundedAndSequentialResponsesStayAtZero() {
        fun range(code: Int, header: String?, length: Long, position: Long, wanted: Long) =
            ArchiveFileTransport.responseSpan(code, header, length, position, wanted).length
        assertEquals(4096L, range(206, "bytes 1048576-1052671/27796450", 4096, 1048576, 4096))
        assertEquals(100L, range(206, "bytes 900-999/1000", 100, 900, 4096))
        assertEquals(262144L, range(200, null, 27796450, 0, 262144))
        assertEquals(-1L, range(200, null, -1, 0, -1))
        val wrongPosition = assertThrows(ArchiveFileTransport.Failure::class.java) {
            range(206, "bytes 5-104/1000", 100, 10, 100)
        }
        assertEquals("range_unavailable", wrongPosition.outcome)
        assertThrows(ArchiveFileTransport.Failure::class.java) { range(200, null, 27796450, 1048576, 4096) }
        assertThrows(ArchiveFileTransport.Failure::class.java) { range(206, "bytes 0-10/500", 12, 0, 11) }
        for (status in listOf(401, 403, 451)) {
            assertEquals("access_denied", assertThrows(ArchiveFileTransport.Failure::class.java) {
                range(status, null, 0, 0, 8192)
            }.outcome)
        }
        assertEquals("http_unavailable", assertThrows(ArchiveFileTransport.Failure::class.java) {
            range(404, null, 0, 0, 8192)
        }.outcome)
        assertEquals("rate_limited", assertThrows(ArchiveFileTransport.Failure::class.java) {
            range(429, null, 0, 0, 8192)
        }.outcome)
    }

    private val file = ArchiveFileKey.create("range-fixture", "song.flac")!!
    private val audio = "fLaC".toByteArray() + ByteArray(36) { it.toByte() }

    private fun reply(request: Request, code: Int, body: ByteArray, range: String? = null, tag: String? = null,
        chunked: Boolean = false): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
        .body(if (!chunked) body.toResponseBody() else object : ResponseBody() {
            private val input = Buffer().write(body)
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = input
        }).apply {
            if (range != null) header("Content-Range", range)
            if (tag != null) header("ETag", tag)
        }.build()

    private fun client(respond: (Request) -> Response) =
        OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()

    private fun spec(position: Long = 0) = DataSpec.Builder()
        .setUri(Uri.parse(file.url.toString())).setPosition(position).build()

    private fun androidx.media3.datasource.DataSource.readAll(): ByteArray {
        val result = Buffer()
        val bytes = ByteArray(9)
        while (true) {
            val count = read(bytes, 0, bytes.size)
            if (count == C.RESULT_END_OF_INPUT) return result.readByteArray()
            check(count > 0)
            result.write(bytes, 0, count)
        }
    }

    @Test fun anInterruptedArchiveDownloadReopensAndSavesEveryShortRangeForOfflinePlayback() = runBlocking(Dispatchers.IO) {
        val encoded = requireNotNull(javaClass.getResourceAsStream("/audio/unindexed-silence.flac")).use { it.readBytes() }
        val context = RuntimeEnvironment.getApplication()
        val plan = OfflineTransfer.archivePlan(file)
        val track = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 20_000, "", plan.uri,
            source = "INTERNET_ARCHIVE", isLossless = true)
        var interrupted = true
        val starts = mutableListOf<Int>()
        val http = client { request ->
            val wanted = requireNotNull(request.header("Range")).substringAfter('=')
            val start = wanted.substringBefore('-').toInt()
            starts += start
            if (interrupted && start > 0) throw java.io.IOException("Lost connection")
            val end = minOf(start + 511, wanted.substringAfter('-').toIntOrNull() ?: encoded.lastIndex, encoded.lastIndex)
            reply(request, 206, encoded.copyOfRange(start, end + 1), "bytes $start-$end/${encoded.size}", "\"stable\"")
        }
        val first = OfflineDownloads(context)
        first.put(DownloadRecord(track, DownloadStatus.DOWNLOADING, plan = plan))
        suspend fun save(store: OfflineDownloads) = OfflineTransfer.saveArchive(store, track.id, plan, source = { cache ->
            ArchiveFileTransport.FileSource(file, http, cache, replaySequential = true) { true }
        }, progress = { _, _ -> })
        try {
            save(first)
            fail("An interrupted response must not finish the download")
        } catch (_: java.io.IOException) {
            assertEquals(DownloadStatus.DOWNLOADING, first.records.value[track.id]?.status)
        }
        val reopened = OfflineDownloads(context)
        reopened.loadLedger()
        interrupted = false
        starts.clear()
        val length = save(reopened)
        assertEquals(encoded.size.toLong(), length)
        assertTrue(starts.first() > 0)
        reopened.put(DownloadRecord(track, DownloadStatus.COMPLETE, length, length, plan = plan))

        val saved = OfflineDownloads(context)
        assertTrue(requireNotNull(saved.localTrack(track)).audioUrl.startsWith("xtreme-offline://"))
        val fetches = starts.size
        val reader = saved.dataSource(track.id, true).createDataSource()
        try {
            assertEquals(length, reader.open(spec()))
            assertArrayEquals(encoded, reader.readAll())
            reader.close()
            val offset = 631
            assertEquals(length - offset, reader.open(spec(offset.toLong())))
            assertArrayEquals(encoded.copyOfRange(offset, encoded.size), reader.readAll())
            assertEquals(fetches, starts.size)
        } finally {
            reader.close()
            saved.releaseCache(track.id)
        }
    }

    @Test fun archiveRetryClearsMetadataWhenTheInterruptedResponseSavedNoBytes() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val store = OfflineDownloads(context)
        val plan = OfflineTransfer.archivePlan(file)
        val track = MusicTrack(file.id, "Song", "Artist", "Album", 20_000, "", plan.uri,
            source = "INTERNET_ARCHIVE", isLossless = true)
        store.put(DownloadRecord(track, DownloadStatus.DOWNLOADING, plan = plan))
        val cache = store.cache(track.id)
        cache.applyContentMetadataMutations("audio", androidx.media3.datasource.cache.ContentMetadataMutations()
            .set(ContentMetadata.KEY_CONTENT_LENGTH, audio.size.toLong())
            .set(com.example.data.remote.ArchiveCacheResume.VALIDATOR, "\"old\""))
        var attempts = 0
        val http = client { request ->
            attempts++
            reply(request, 200, audio, tag = "\"new\"")
        }
        val length = OfflineTransfer.saveArchive(store, track.id, plan, source = { resume ->
            ArchiveFileTransport.FileSource(file, http, resume, replaySequential = true) { true }
        }, progress = { _, _ -> })
        assertEquals(2, attempts)
        assertEquals(audio.size.toLong(), length)
        store.releaseCache(track.id)
    }

    @Test fun shorterRangesCanStartResumeAndSeekThroughTheMedia3Cache() {
        val requests = mutableListOf<String>()
        val http = client { request ->
            val header = requireNotNull(request.header("Range"))
            requests += header
            val start = header.substringAfter('=').substringBefore('-').toInt()
            val requestedLast = header.substringAfter('-').toIntOrNull() ?: audio.lastIndex
            val end = minOf(start + 6, requestedLast, audio.lastIndex)
            reply(request, 206, audio.copyOfRange(start, end + 1), "bytes $start-$end/${audio.size}", "\"stable\"")
        }
        val directory = Files.createTempDirectory("wavtape-archive-cache").toFile()
        val cache = SimpleCache(directory, NoOpCacheEvictor(), StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()))
        try {
            val factory = CacheDataSource.Factory().setCache(cache).setCacheKeyFactory { file.id }
                .setUpstreamDataSourceFactory { ArchiveFileTransport.FileSource(file, http) { true } }
            val start = factory.createDataSource()
            assertEquals(audio.size.toLong(), start.open(spec()))
            val prefix = ByteArray(7)
            assertEquals(7, start.read(prefix, 0, 7))
            assertArrayEquals(audio.copyOf(7), prefix)
            start.close()
            assertEquals(audio.size.toLong(), ContentMetadata.getContentLength(cache.getContentMetadata(file.id)))
            val seek = factory.createDataSource()
            assertEquals((audio.size - 13).toLong(), seek.open(spec(13)))
            assertArrayEquals(audio.copyOfRange(13, audio.size), seek.readAll())
            seek.close()
            val resume = factory.createDataSource()
            assertEquals(audio.size.toLong(), resume.open(spec()))
            assertArrayEquals(audio, resume.readAll())
            resume.close()
            assertTrue(requests.first().startsWith("bytes=0-"))
            assertTrue(requests.any { it.startsWith("bytes=13-") })
            assertTrue(cache.isCached(file.id, 0, audio.size.toLong()))
        } finally {
            cache.release()
            directory.deleteRecursively()
        }
    }

    @Test fun media3ReadsRealFlacFramesAndSeeksAcrossShortResponses() {
        val encoded = requireNotNull(javaClass.getResourceAsStream("/audio/unindexed-silence.flac"))
            .use { it.readBytes() }
        val offsets = mutableListOf<Int>()
        val sender = client { request ->
            val start = requireNotNull(request.header("Range")).substringAfter('=').substringBefore('-').toInt()
            offsets += start
            if (start == encoded.size) reply(request, 416, ByteArray(0), "bytes */${encoded.size}")
            else {
                val end = minOf(start + 149, encoded.lastIndex)
                reply(request, 206, encoded.copyOfRange(start, end + 1), "bytes $start-$end/${encoded.size}")
            }
        }
        val source = ArchiveFileTransport.FileSource(file, sender) { true }
        val extractor = androidx.media3.extractor.flac.FlacExtractor()
        val samples = mutableListOf<Long>()
        var mime: String? = null
        var seekMap: androidx.media3.extractor.SeekMap? = null
        val output = object : androidx.media3.extractor.TrackOutput by androidx.media3.extractor.DummyTrackOutput() {
            override fun format(format: androidx.media3.common.Format) { mime = format.sampleMimeType }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int,
                cryptoData: androidx.media3.extractor.TrackOutput.CryptoData?) { samples += timeUs }
        }
        extractor.init(object : androidx.media3.extractor.ExtractorOutput {
            override fun track(id: Int, type: Int) = output
            override fun endTracks() = Unit
            override fun seekMap(value: androidx.media3.extractor.SeekMap) { seekMap = value }
        })
        fun open(position: Long): androidx.media3.extractor.DefaultExtractorInput {
            source.close()
            val length = source.open(spec(position))
            assertEquals(encoded.size - position, length)
            return androidx.media3.extractor.DefaultExtractorInput(source, position, encoded.size.toLong())
        }
        try {
            var input = open(0L)
            assertTrue(extractor.sniff(input))
            input.resetPeekPosition()
            val next = androidx.media3.extractor.PositionHolder()
            fun readSample() {
                repeat(128) {
                    val before = samples.size
                    when (extractor.read(input, next)) {
                        androidx.media3.extractor.Extractor.RESULT_SEEK -> input = open(next.position)
                        androidx.media3.extractor.Extractor.RESULT_END_OF_INPUT -> error("FLAC ended before delivering a frame")
                    }
                    if (samples.size != before) return
                }
                error("FLAC did not deliver a frame within the test budget")
            }
            readSample()
            assertEquals(androidx.media3.common.MimeTypes.AUDIO_FLAC, mime)
            assertEquals(0L, samples.first())
            assertTrue(requireNotNull(seekMap).isSeekable)
            val target = 10_000_000L
            val point = requireNotNull(seekMap).getSeekPoints(target).first
            extractor.seek(point.position, target)
            input = open(point.position)
            readSample()
            assertTrue(samples.last() in 9_000_000L..11_000_000L)
            assertTrue(offsets.any { it > 0 })
        } finally {
            source.close()
            extractor.release()
        }
    }

    @Test fun unknownLengthRangesEndOnlyWhenTheServerConfirmsExactEof() {
        val requests = mutableListOf<String>()
        val source = ArchiveFileTransport.FileSource(file, client { request ->
            val header = requireNotNull(request.header("Range"))
            requests += header
            val start = header.substringAfter('=').substringBefore('-').toInt()
            if (start == audio.size) reply(request, 416, ByteArray(0), "bytes */${audio.size}")
            else {
                val end = minOf(start + 18, audio.lastIndex)
                reply(request, 206, audio.copyOfRange(start, end + 1), "bytes $start-$end/*", chunked = true)
            }
        }) { true }
        try {
            assertEquals(-1L, source.open(spec()))
            assertArrayEquals(audio, source.readAll())
            assertEquals(listOf("bytes=0-", "bytes=19-", "bytes=38-", "bytes=40-"), requests)
        } finally { source.close() }
    }

    @Test fun sequentialUnknownLengthPlaysFromZeroAndNeverFakesASeek() {
        val source = ArchiveFileTransport.FileSource(file, client { reply(it, 200, audio, chunked = true) }) { true }
        try {
            assertEquals(-1L, source.open(spec()))
            assertTrue(ArchiveFileTransport.isSequentialOnly(file))
            assertArrayEquals(audio, source.readAll())
            source.close()
            assertEquals("range_unavailable", assertThrows(ArchiveFileTransport.Failure::class.java) {
                source.open(spec(7))
            }.outcome)
        } finally { source.close() }
    }

    @Test fun probeJoinsShortBodiesAndHonorsValidatorAndOffset() = runBlocking {
        val seen = mutableListOf<String>()
        val http = client { request ->
            val header = requireNotNull(request.header("Range"))
            seen += header
            val start = header.substringAfter('=').substringBefore('-').toInt()
            val end = minOf(start + 4, 16)
            if (start != 2) assertEquals("\"probe\"", request.header("If-Range"))
            reply(request, 206, audio.copyOfRange(start, end + 1), "bytes $start-$end/${audio.size}", "\"probe\"")
        }
        assertArrayEquals(audio.copyOfRange(2, 17), ArchiveFileTransport.probe(file, 2, 15, { true }, http))
        assertEquals(listOf("bytes=2-16", "bytes=7-16", "bytes=12-16"), seen)
    }

    @Test fun invalidRangesAndChangedRepresentationsNeverProduceFalseEof() {
        fun span(header: String) = ArchiveFileTransport.responseSpan(206, header, -1L, 0L, 20L)
        assertEquals(ArchiveFileTransport.Span(10, -1), span("bytes 0-9/*"))
        assertThrows(ArchiveFileTransport.Failure::class.java) { span("bytes 0-20/50") }
        assertThrows(ArchiveFileTransport.Failure::class.java) { span("bytes 0-9/9") }
        assertThrows(ArchiveFileTransport.Failure::class.java) { span("bytes 0-9223372036854775807/*") }
        assertThrows(ArchiveFileTransport.Failure::class.java) {
            ArchiveFileTransport.responseSpan(416, "bytes */40", 0, 39, -1)
        }
        val source = ArchiveFileTransport.FileSource(file, client { request ->
            if (request.header("If-Range") == null)
                reply(request, 206, audio.copyOf(5), "bytes 0-4/40", "\"before\"")
            else reply(request, 206, audio.copyOfRange(5, 10), "bytes 5-9/40", "\"after\"")
        }) { true }
        try {
            assertEquals(40L, source.open(spec()))
            assertEquals("range_changed", assertThrows(ArchiveFileTransport.Failure::class.java) { source.readAll() }.outcome)
        } finally { source.close() }
    }

    @Test fun prematureEndIsRetryableAndDoesNotBecomeEndOfFile() {
        val source = ArchiveFileTransport.FileSource(file, client {
            reply(it, 206, audio.copyOf(5), "bytes 0-9/40", chunked = true)
        }) { true }
        try {
            assertEquals(40L, source.open(spec()))
            assertThrows(java.io.EOFException::class.java) { source.readAll() }
        } finally { source.close() }
    }

    @Test fun archiveBackoffHonorsRetryAfterAndUsesABoundedDefaultFor429() {
        try {
            assertFalse(ArchiveFileTransport.isRateLimited())
            assertEquals(2L, ArchiveFileTransport.noteBackoff("2", 503))
            assertTrue(ArchiveFileTransport.isRateLimited())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            assertFalse(ArchiveFileTransport.isRateLimited())
            assertEquals(30L, ArchiveFileTransport.noteBackoff(null, 429))
            assertTrue(ArchiveFileTransport.isRateLimited())
        } finally { ShadowSystemClock.advanceBy(Duration.ofSeconds(30)) }
        assertFalse(ArchiveFileTransport.isRateLimited())
    }

    @Test fun archiveUpgradeIsrcComesFromTheFileAndOtherProvidersKeepTheOriginalRule() {
        val key = ArchiveFileKey.create("album_isrc", "song.flac")!!
        val catalog = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 180_000, "", key.url.toString(),
            source = "INTERNET_ARCHIVE", isrc = "USABC2000001")
        try {
            assertNull(ArchiveFileEvidence.verifiedReplacementIsrc(catalog))
            ArchiveFileEvidence.remember(key, catalog.copy(id = key.id, isrc = "USABC2000002"))
            assertEquals("USABC2000002", ArchiveFileEvidence.verifiedReplacementIsrc(catalog))
            for ((provider, url) in listOf(
                "YouTube Music" to "https://www.youtube.com/watch?v=abcdefghijk",
                "JIOSAAVN" to "https://aac.saavncdn.com/song_320.mp4",
                "Streaming Addon: Qobuz" to "https://qobuz.example/audio.flac",
                "Streaming Addon: Tidal" to "https://tidal.example/audio.mpd",
                "Streaming Addon: Deezer" to "https://deezer.example/audio.flac",
                "Streaming Addon: Other" to key.url.toString()
            )) {
                assertEquals(provider, "USABC2000001",
                    ArchiveFileEvidence.verifiedReplacementIsrc(catalog.copy(source = provider, audioUrl = url)))
            }
        } finally { ArchiveFileEvidence.forget(key) }
    }
    @Test fun archiveDiscoveryIgnoresAtmosAndStillRequiresTheActiveArchivePolicy() {
        val policy = com.example.playback.PlaybackSourcePolicy(com.example.playback.AudioQualityCeiling.MAX)
        val active = policy.copy(dolbyAtmos = true)
        assertNotEquals(policy.key, active.key)
        assertTrue(com.example.data.remote.archiveCatalogPolicyAllows(policy, active))
        assertFalse(com.example.data.remote.archiveCatalogPolicyAllows(policy, active.copy(online = false)))
        assertFalse(com.example.data.remote.archiveCatalogPolicyAllows(policy,
            active.copy(ceiling = com.example.playback.AudioQualityCeiling.HIGH)))
        assertFalse(com.example.data.remote.archiveCatalogPolicyAllows(policy,
            active.copy(sources = active.sources.filter { it.kind != com.example.playback.PlaybackSourceKind.INTERNET_ARCHIVE })))
    }

}
