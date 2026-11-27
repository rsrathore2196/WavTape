package com.example.data.remote

import androidx.media3.common.DataReader
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import java.io.EOFException
import java.io.FileInputStream

/** The validator belongs to the byte cache key, never to a URL or a transient connection. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ArchiveCacheResume(private val cache: Cache, private val key: String) {
    val validator: String? get() = cache.getContentMetadata(key).get(VALIDATOR, null as String?)

    fun hasCachedBytes(): Boolean = cache.getCachedSpans(key).isNotEmpty()

    fun hasUnverifiedSparseSpans(): Boolean {
        if (validator != null) return false
        var end = 0L
        for (span in cache.getCachedSpans(key)) {
            if (span.position != end) return true
            end += span.length
        }
        return false
    }

    fun verifyHeaders(tag: String?, length: Long, conditionalRange: Boolean) {
        val expected = validator
        if (expected != null && expected != tag && !(tag == null && conditionalRange)) changed()
        val known = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (known >= 0 && length >= 0 && known != length) changed()
    }

    fun remember(tag: String?, provenPrefix: Boolean) {
        if (tag == null) return
        synchronized(cache) {
            val previous = validator
            if (previous != null && previous != tag) changed()
            if (previous == null && (provenPrefix || cache.getCachedSpans(key).isEmpty())) {
                cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(VALIDATOR, tag))
            }
        }
    }

    /** A legacy cache has no strong validator. Compare its bytes before appending to it. */
    fun verifyPrefix(input: DataReader, position: Long, compare: Boolean, checkpoint: () -> Unit) {
        if (!compare) {
            discard(input, position, checkpoint)
            return
        }
        val bytes = ByteArray(32 * 1024)
        val expected = ByteArray(bytes.size)
        var offset = 0L
        for (span in cache.getCachedSpans(key)) {
            if (span.position < offset || span.position > position || span.length > position - span.position) changed()
            discard(input, span.position - offset, checkpoint)
            val file = span.file ?: changed()
            FileInputStream(file).use { local ->
                var remaining = span.length
                while (remaining > 0) {
                    checkpoint()
                    val count = minOf(remaining, bytes.size.toLong()).toInt()
                    var received = 0
                    while (received < count) {
                        val read = input.read(bytes, received, count - received)
                        if (read < 0) throw EOFException("Archive response ended inside the cached prefix")
                        received += read
                    }
                    var read = 0
                    while (read < count) {
                        val next = local.read(expected, read, count - read)
                        if (next < 0) throw EOFException("Archive cached prefix truncated")
                        read += next
                    }
                    for (i in 0 until count) if (bytes[i] != expected[i]) changed()
                    remaining -= count
                }
            }
            offset = span.position + span.length
        }
        discard(input, position - offset, checkpoint)
    }

    companion object {
        private const val VALIDATOR = "wavtape_archive_strong_etag"
        private fun changed(): Nothing = throw ArchiveFileTransport.Failure("range_changed")

        fun writableSpec(spec: DataSpec): DataSpec = spec.buildUpon()
            // Archive URLs identify finite files, including transfers without a Content-Length.
            .setFlags(spec.flags and DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN.inv())
            .build()

        fun discard(input: DataReader, count: Long, checkpoint: () -> Unit) {
            if (count == 0L) return
            val buffer = ByteArray(minOf(count, 32 * 1024L).toInt())
            var remaining = count
            while (remaining > 0) {
                checkpoint()
                val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (read < 0) throw EOFException("Archive response ended inside the requested prefix")
                remaining -= read
            }
        }
    }
}
