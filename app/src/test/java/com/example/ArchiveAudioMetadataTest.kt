package com.example

import com.example.data.remote.ArchiveAudioMetadata
import java.io.ByteArrayOutputStream
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

    @Test fun actualLosslessWaveAndAlacSignaturesAreDistinctFromLossyContainers() {
        val riff = "RIFF".toByteArray() + byteArrayOf(36, 0, 0, 0) + "WAVEfmt ".toByteArray() +
            byteArrayOf(16, 0, 0, 0) + ByteArray(16).apply { this[0] = 1 }
        assertTrue(ArchiveAudioMetadata.pcmWave(riff))
        assertFalse(ArchiveAudioMetadata.pcmWave(riff.copyOf().apply { this[20] = 6 }))
        fun atom(type: String, body: ByteArray): ByteArray = java.nio.ByteBuffer.allocate(8 + body.size)
            .putInt(8 + body.size).put(type.toByteArray()).put(body).array()
        fun mp4(sample: String): ByteArray = listOf("stsd", "stbl", "minf", "mdia", "trak", "moov")
            .fold(ByteArray(8) + atom(sample, ByteArray(24))) { body, box -> atom(box, body) }
        assertTrue(ArchiveAudioMetadata.alac(mp4("alac")))
        assertFalse(ArchiveAudioMetadata.alac(mp4("mp4a")))
        val mdat = java.nio.ByteBuffer.allocate(8).putInt(1_000_000).put("mdat".toByteArray()).array()
        assertEquals(1_000_000L, ArchiveAudioMetadata.followingMp4MetadataOffset(mdat, 0L))
    }

}
