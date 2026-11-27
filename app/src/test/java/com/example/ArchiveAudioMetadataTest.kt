package com.example

import com.example.data.remote.ArchiveAudioMetadata
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArchiveAudioMetadataTest {
    private fun header(title: String = "Song", artist: String = "Artist"): ByteArray {
        val info = ByteArray(34)
        val packed = (96000L shl 44) or (1L shl 41) or (23L shl 36) or (96000L * 183)
        for (i in 0..7) info[10 + i] = (packed ushr ((7 - i) * 8)).toByte()
        fun ByteArrayOutputStream.le(value: Int) { for (i in 0..3) write(value ushr (i * 8) and 255) }
        val comments = ByteArrayOutputStream().apply {
            le(0); le(4)
            for (value in listOf("TITLE=$title", "ARTIST=$artist", "ALBUM=Album", "ISRC=USABC2000001")) {
                val bytes = value.toByteArray(Charsets.UTF_8); le(bytes.size); write(bytes)
            }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("fLaC".toByteArray()); write(byteArrayOf(0, 0, 0, 34)); write(info)
            write(0x84); write(comments.size ushr 16); write(comments.size ushr 8); write(comments.size)
            write(comments)
        }.toByteArray()
    }
    @Test fun streamInfoAndRealUnicodeTagsAreReadWithoutAudioDecoding() {
        val tags = ArchiveAudioMetadata.flac(header("ਪੰਜਾਬੀ ਗੀਤ", "कलाकार"))!!
        assertEquals("ਪੰਜਾਬੀ ਗੀਤ", tags.title)
        assertEquals("कलाकार", tags.artist)
        assertEquals(183000L, tags.durationMs)
        assertEquals(96000, tags.sampleRate)
        assertEquals(24, tags.bitDepth)
        assertEquals(2, tags.channels)
        assertEquals("USABC2000001", tags.isrc)
    }
    @Test fun incompleteCommentBlocksRetainOnlyVerifiedStreamInfoWithoutInventingTags() {
        val tags = ArchiveAudioMetadata.flac(header().copyOf(48))!!
        assertEquals(183000L, tags.durationMs)
        assertEquals("", tags.title)
        assertEquals("", tags.artist)
    }
    @Test fun filenamesAndMalformedOrNonFlacHeadersCannotEstablishLosslessMetadata() {
        assertNull(ArchiveAudioMetadata.flac("Song.flac Artist 24bit".toByteArray()))
        assertNull(ArchiveAudioMetadata.flac(byteArrayOf(1, 2, 3)))
        val malformed = header().apply { this[7] = 33 }
        assertNull(ArchiveAudioMetadata.flac(malformed))
    }
    @Test fun aBoundedFollowUpRangeCanSkipPictureBytesAndRecoverComments() {
        val original = header()
        val pictureBytes = 500_000
        val prefix = original.copyOf(42) + byteArrayOf(6, (pictureBytes ushr 16).toByte(),
            (pictureBytes ushr 8).toByte(), pictureBytes.toByte())
        assertEquals("", ArchiveAudioMetadata.flac(prefix)!!.title)
        assertEquals(500046L, ArchiveAudioMetadata.followingFlacMetadataOffset(prefix))
        val recovered = ArchiveAudioMetadata.extendFlacTags(original.copyOfRange(42, original.size),
            ArchiveAudioMetadata.flac(prefix)!!)
        assertEquals("Song", recovered.title)
        assertEquals("Artist", recovered.artist)
        assertEquals(183000L, recovered.durationMs)
    }

    private fun atom(type: String, body: ByteArray, extended: Boolean = false): ByteArray {
        val size = body.size + if (extended) 16 else 8
        return ByteBuffer.allocate(size).apply {
            putInt(if (extended) 1 else size)
            put(type.toByteArray())
            if (extended) putLong(size.toLong())
            put(body)
        }.array()
    }

    private fun description(sample: String = "alac", entries: Int = 1, config: Boolean = true): ByteArray {
        val header = ByteBuffer.allocate(8).putInt(0).putInt(entries).array()
        val cookie = if (config) atom("alac", byteArrayOf(
            0, 0, 0, 0, 0, 0, 16, 0, 0, 16, 40, 10, 14, 2, 0, 0,
            0, 0, 64, 4, 0, 21, -120, -128, 0, 0, -84, 68)) else ByteArray(0)
        return atom("stsd", header + atom(sample, ByteArray(28) + cookie))
    }

    @Test fun actualLosslessWaveAndAlacSignaturesAreDistinctFromLossyContainers() = runBlocking {
        val riff = "RIFF".toByteArray() + byteArrayOf(36, 0, 0, 0) + "WAVEfmt ".toByteArray() +
            byteArrayOf(16, 0, 0, 0) + ByteArray(16).apply { this[0] = 1 }
        assertTrue(ArchiveAudioMetadata.pcmWave(riff))
        assertFalse(ArchiveAudioMetadata.pcmWave(riff.copyOf().apply { this[20] = 6 }))
        fun mp4(stsd: ByteArray): ByteArray = listOf("stbl", "minf", "mdia", "trak", "moov")
            .fold(stsd) { body, box -> atom(box, body) }
        suspend fun alac(bytes: ByteArray) = ArchiveAudioMetadata.alac(bytes) { _, _ -> error("File is in prefix") }
        assertTrue(alac(mp4(description())))
        assertFalse(alac(mp4(description("mp4a"))))
        assertFalse(alac(mp4(description(entries = 0))))
        assertFalse(alac(mp4(description(config = false))))
        assertFalse(alac(atom("free", mp4(description()))))
        assertFalse(alac(mp4(description()).copyOf(50)))
    }

    @Test fun sparseScanSkipsLateExtendedMdatAndLargeNestedMoovPadding() = runBlocking {
        val skipped = atom("mdat", ByteArray(600_000), extended = true)
        val padding = atom("free", ByteArray(300_000))
        val moov = atom("moov", atom("trak", atom("mdia", atom("minf",
            atom("stbl", padding + description())))))
        val file = skipped + moov
        val requests = mutableListOf<Pair<Long, Int>>()
        assertTrue(ArchiveAudioMetadata.alac(file.copyOf(256 * 1024)) { offset, length ->
            requests += offset to length
            file.copyOfRange(offset.toInt(), minOf(file.size, offset.toInt() + length))
        })
        assertEquals(listOf(skipped.size.toLong(), (skipped.size + 40 + padding.size).toLong()), requests.map { it.first })
        assertTrue(requests.all { it.second in 1..ArchiveAudioMetadata.MP4_WINDOW_BYTES })
    }

    @Test fun sparseScanHonorsItsNetworkWindowBudget() = runBlocking {
        val mdat = atom("mdat", ByteArray(270_000))
        val padding = atom("free", ByteArray(20_000))
        val file = mdat + List(20) { padding }.fold(ByteArray(0), ByteArray::plus)
        var probes = 0
        assertFalse(ArchiveAudioMetadata.alac(file.copyOf(256 * 1024)) { offset, length ->
            probes++
            file.copyOfRange(offset.toInt(), offset.toInt() + length)
        })
        assertEquals(12, probes)
    }
}
