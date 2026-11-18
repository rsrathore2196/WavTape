package com.example.downloads

import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Optional artwork never replaces a valid saved cover until the entire image is validated. */
internal object OfflineArtwork {
    private const val MAX_BYTES = 8L * 1024 * 1024

    fun existing(directory: File): String = File(directory, "cover").takeIf(::isImage)?.absolutePath.orEmpty()

    fun save(directory: File, contentLength: Long, open: () -> InputStream): String {
        // The worker serializes each track. A fixed staging name also replaces a
        // previous process's interrupted write instead of accumulating orphan files.
        val temporary = File(directory, "cover.part")
        try {
            if (contentLength > MAX_BYTES) throw IOException("Artwork too large")
            open().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var bytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        if (bytes > MAX_BYTES) throw IOException("Artwork too large")
                        output.write(buffer, 0, count)
                    }
                    if (contentLength >= 0 && bytes != contentLength) throw IOException("Artwork was truncated")
                    output.fd.sync()
                }
            }
            if (!isImage(temporary)) throw IOException("Artwork is not an image")
            val target = File(directory, "cover")
            if (!temporary.renameTo(target)) throw IOException("Could not save artwork")
            return target.absolutePath
        } finally {
            temporary.delete()
        }
    }

    private fun isImage(file: File): Boolean {
        if (!file.isFile || file.length() !in 1..MAX_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > 16_384 || bounds.outHeight > 16_384 ||
            bounds.outWidth.toLong() * bounds.outHeight > 64L * 1024 * 1024) return false
        var sample = 1
        while (bounds.outWidth / sample > 128 || bounds.outHeight / sample > 128) sample *= 2
        val image = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        image.recycle()
        return true
    }
}
