package com.example.data.remote

/** Reads bounded lossless headers without deriving recording identity from filenames. */
internal object ArchiveAudioMetadata {
    data class Tags(val title: String = "", val artist: String = "", val album: String = "",
        val isrc: String = "", val durationMs: Long = 0L, val sampleRate: Int = 0,
        val bitDepth: Int = 0, val channels: Int = 0)

    private fun flacStart(bytes: ByteArray): Int? {
        var start = 0
        if (bytes.size >= 10 && String(bytes, 0, 3, Charsets.US_ASCII) == "ID3") {
            if ((6..9).any { bytes[it].toInt() and 0x80 != 0 }) return null
            var length = 0
            for (i in 6..9) length = (length shl 7) or (bytes[i].toInt() and 0x7f)
            start = 10 + length + if (bytes[5].toInt() and 0x10 != 0) 10 else 0
        }
        if (start < 0 || start + 4 > bytes.size || String(bytes, start, 4, Charsets.US_ASCII) != "fLaC") return null
        return start + 4
    }

    fun flac(bytes: ByteArray): Tags? = flacStart(bytes)?.let { blocks(bytes, it, null) }

    /** Find the next metadata block without downloading a large embedded picture or padding block. */
    fun followingMetadataOffset(bytes: ByteArray, offset: Int, absoluteStart: Long): Long? {
        var position = offset
        repeat(64) {
            if (position + 4 > bytes.size) return absoluteStart + position
            val header = bytes[position].toInt()
            if (header and 0x80 != 0) return null
            val next = position + 4 + blockLength(bytes, position)
            if (next > bytes.size) return absoluteStart + next
            position = next
        }
        return null
    }

    fun followingFlacMetadataOffset(bytes: ByteArray): Long? =
        flacStart(bytes)?.let { followingMetadataOffset(bytes, it, 0L) }

    fun extendFlacTags(bytes: ByteArray, previous: Tags): Tags = blocks(bytes, 0, previous) ?: previous

    private fun blockLength(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset + 1].toInt() and 255) shl 16) or
            ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)

    private fun blocks(bytes: ByteArray, start: Int, previous: Tags?): Tags? {
        var offset = start
        var info = previous
        val comments = linkedMapOf<String, String>()
        repeat(64) {
            if (offset + 4 > bytes.size) return merge(info, comments)
            val type = bytes[offset].toInt() and 0x7f
            val last = bytes[offset].toInt() and 0x80 != 0
            val length = blockLength(bytes, offset)
            offset += 4
            if (length > bytes.size - offset) return merge(info, comments)
            if (type == 0) {
                if (length != 34) return null
                var packed = 0L
                for (i in offset + 10 until offset + 18) packed = (packed shl 8) or (bytes[i].toLong() and 255)
                val rate = (packed ushr 44).toInt()
                val bits = ((packed ushr 36) and 31).toInt() + 1
                val channels = ((packed ushr 41) and 7).toInt() + 1
                val samples = packed and 0xfffffffffL
                if (rate !in 8_000..768_000 || bits !in 4..32) return null
                info = Tags(durationMs = samples * 1000L / rate, sampleRate = rate, bitDepth = bits, channels = channels)
            } else if (type == 4) readComments(bytes, offset, offset + length, comments)
            offset += length
            if (last) return merge(info, comments)
        }
        return merge(info, comments)
    }

    private fun merge(info: Tags?, values: Map<String, String>): Tags? = info?.copy(
        title = values["TITLE"].orEmpty().ifBlank { info.title }.let { title ->
            val version = values["VERSION"].orEmpty().takeUnless { it == "0" }.orEmpty()
            if (title.isNotBlank() && version.isNotBlank() && !title.contains(version, true)) "$title ($version)" else title
        }, artist = values["ARTIST"].orEmpty().ifBlank { values["ALBUMARTIST"].orEmpty() }
            .ifBlank { values["ALBUM ARTIST"].orEmpty() }.ifBlank { info.artist },
        album = values["ALBUM"].orEmpty().ifBlank { info.album },
        isrc = values["ISRC"].orEmpty().ifBlank { info.isrc })

    private fun readComments(bytes: ByteArray, start: Int, end: Int, values: MutableMap<String, String>) {
        var position = start
        fun integer(): Int? {
            if (position + 4 > end) return null
            var result = 0L
            for (i in 0..3) result = result or ((bytes[position++].toLong() and 255) shl (i * 8))
            return result.takeIf { it <= Int.MAX_VALUE }?.toInt()
        }
        val vendor = integer() ?: return
        if (vendor > end - position) return
        position += vendor
        val count = integer()?.coerceAtMost(512) ?: return
        repeat(count) {
            val length = integer() ?: return
            if (length > end - position) return
            val value = String(bytes, position, length, Charsets.UTF_8)
            position += length
            val key = value.substringBefore('=').uppercase(java.util.Locale.ROOT)
            if (key in setOf("TITLE", "ARTIST", "ALBUMARTIST", "ALBUM ARTIST", "ALBUM", "ISRC", "VERSION")) {
                val content = value.substringAfter('=', "").trim()
                if (content.isNotBlank()) values[key] = values[key]?.takeUnless { it == content }
                    ?.let { if (key == "ARTIST") "$it, $content" else it } ?: content
            }
        }
    }

    fun pcmWave(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || ascii(bytes, 0) !in setOf("RIFF", "RF64") || ascii(bytes, 8) != "WAVE") return false
        var start = 12
        while (start <= bytes.size - 8) {
            val size = unsigned(bytes, start + 4, littleEndian = true)
            val data = start + 8
            if (size > bytes.size - data) return false
            if (ascii(bytes, start) == "fmt " && size >= 16) {
                val encoding = (bytes[data].toInt() and 255) or ((bytes[data + 1].toInt() and 255) shl 8)
                if (encoding == 1 || encoding == 3) return true
                if (encoding != 0xfffe || size < 40) return false
                val subtype = unsigned(bytes, data + 24, littleEndian = true)
                val guidTail = byteArrayOf(0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113)
                return (subtype == 1L || subtype == 3L) &&
                    guidTail.indices.all { bytes[data + 28 + it] == guidTail[it] }
            }
            start = data + size.toInt() + (size and 1).toInt()
        }
        return false
    }

    internal const val MP4_WINDOW_BYTES = 16 * 1024
    private const val MP4_FOLLOW_UP_WINDOWS = 12
    private val alacPath = listOf("moov", "trak", "mdia", "minf", "stbl", "stsd", "alac")

    private data class Atom(val type: String, val body: Long, val end: Long)

    /** Visit only box headers and the sample description, skipping large payloads at every level. */
    suspend fun alac(prefix: ByteArray, read: suspend (offset: Long, length: Int) -> ByteArray): Boolean {
        val scanner = Mp4Scanner(prefix, read)
        return scanner.find(0, Long.MAX_VALUE, 0)
    }

    private class Mp4Scanner(private val prefix: ByteArray,
        private val read: suspend (Long, Int) -> ByteArray) {
        private var window = prefix
        private var windowStart = 0L
        private var eof = if (prefix.size < ArchiveFileTransport.PREFIX_BYTES) prefix.size.toLong() else Long.MAX_VALUE
        private var windowsRead = 0
        private var atomsRead = 0

        private fun ByteArray.offsetOf(windowStart: Long, position: Long, count: Int): Int? =
            (position - windowStart).takeIf { it in 0L..(size - count).toLong() }?.toInt()

        private suspend fun at(position: Long, count: Int, end: Long): Pair<ByteArray, Int>? {
            if (position < 0 || position > minOf(end, eof) - count) return null
            prefix.offsetOf(0, position, count)?.let { return prefix to it }
            window.offsetOf(windowStart, position, count)?.let { return window to it }
            if (windowsRead++ >= MP4_FOLLOW_UP_WINDOWS) return null
            val length = minOf(MP4_WINDOW_BYTES.toLong(), minOf(end, eof) - position).toInt()
            window = read(position, length)
            windowStart = position
            if (window.size < length) eof = minOf(eof, position + window.size)
            return window.offsetOf(windowStart, position, count)?.let { window to it }
        }

        private suspend fun atom(start: Long, parentEnd: Long): Atom? {
            if (atomsRead++ >= 1024) return null
            val (bytes, offset) = at(start, 8, parentEnd) ?: return null
            val type = ascii(bytes, offset + 4)
            val size = unsigned(bytes, offset)
            val header = if (size == 1L) 16 else 8
            val length = when (size) {
                0L -> {
                    val end = minOf(eof, parentEnd)
                    if (end == Long.MAX_VALUE) return null
                    end - start
                }
                1L -> {
                    val (extended, index) = at(start, 16, parentEnd) ?: return null
                    val upper = unsigned(extended, index + 8).takeIf { it <= Int.MAX_VALUE } ?: return null
                    (upper shl 32) + unsigned(extended, index + 12)
                }
                else -> size
            }
            if (length < header || length > minOf(parentEnd, eof) - start) return null
            return Atom(type, start + header, start + length)
        }

        suspend fun find(start: Long, end: Long, depth: Int, count: Long = Long.MAX_VALUE): Boolean {
            var position = start
            var remaining = count
            while (position < end && remaining-- > 0L) {
                val box = atom(position, end) ?: return false
                if (box.type == alacPath[depth]) {
                    val found = when (box.type) {
                        "alac" -> hasCodecConfig(box)
                        "stsd" -> {
                            val header = at(box.body, 8, box.end)
                            header != null && find(box.body + 8, box.end, depth + 1,
                                unsigned(header.first, header.second + 4))
                        }
                        else -> find(box.body, box.end, depth + 1)
                    }
                    if (found) return true
                }
                position = box.end
            }
            return false
        }

        private suspend fun hasCodecConfig(entry: Atom): Boolean {
            val (sample, offset) = at(entry.body, 28, entry.end) ?: return false
            val version = ((sample[offset + 8].toInt() and 255) shl 8) or (sample[offset + 9].toInt() and 255)
            val sampleHeaderBytes = when (version) {
                0 -> 28
                1 -> 44
                2 -> 64
                else -> return false
            }
            if (entry.end - entry.body < sampleHeaderBytes) return false
            return codecIn(entry.body + sampleHeaderBytes, entry.end, allowWave = true)
        }

        private suspend fun codecIn(start: Long, end: Long, allowWave: Boolean): Boolean {
            var position = start
            while (position < end) {
                val box = atom(position, end) ?: return false
                if (box.type == "alac" && validCodecConfig(box)) return true
                if (allowWave && box.type == "wave" && codecIn(box.body, box.end, allowWave = false)) return true
                position = box.end
            }
            return false
        }

        private suspend fun validCodecConfig(box: Atom): Boolean {
            // FullBox header followed by the 24-byte ALACSpecificConfig.
            val (bytes, offset) = at(box.body, 28, box.end) ?: return false
            val config = offset + 4
            return unsigned(bytes, config) > 0 && bytes[config + 4] == 0.toByte() &&
                bytes[config + 5].toInt() in setOf(16, 20, 24, 32) &&
                bytes[config + 9].toInt() in 1..8 && unsigned(bytes, config + 20) in 1L..768_000L
        }
    }

    private fun ascii(bytes: ByteArray, start: Int): String = String(bytes, start, 4, Charsets.US_ASCII)
    private fun unsigned(bytes: ByteArray, start: Int, littleEndian: Boolean = false): Long {
        var value = 0L
        for (i in 0..3) value = (value shl 8) or (bytes[start + if (littleEndian) 3 - i else i].toLong() and 255)
        return value
    }
}
