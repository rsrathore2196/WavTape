package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.downloads.OfflineArtwork
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineArtworkTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val directory by lazy { File(context.cacheDir, "offline-artwork-test").apply { mkdirs() } }
    private val image by lazy {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        try { ByteArrayOutputStream().use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.toByteArray()
        } } finally { bitmap.recycle() }
    }

    @After fun cleanup() { directory.deleteRecursively() }

    private fun seed(): File {
        val path = OfflineArtwork.save(directory, image.size.toLong()) { ByteArrayInputStream(image) }
        return File(path).also { assertArrayEquals(image, it.readBytes()) }
    }

    private fun assertNoPartialFiles() = assertEquals(listOf("cover"), directory.listFiles()!!.map { it.name }.sorted())

    @Test fun aTruncatedImageResponseCannotReplaceAnExistingOfflineCover() {
        val original = seed()
        assertThrows(IOException::class.java) {
            OfflineArtwork.save(directory, image.size.toLong() + 12) { ByteArrayInputStream(image) }
        }
        assertArrayEquals(image, original.readBytes())
        assertEquals(original.absolutePath, OfflineArtwork.existing(directory))
        assertNoPartialFiles()
    }

    @Test fun aReadFailurePreservesThePreviousCoverAndRemovesThePartialWrite() {
        val original = seed()
        assertThrows(IOException::class.java) {
            OfflineArtwork.save(directory, -1L) {
                object : InputStream() {
                    var reads = 0
                    override fun read(): Int = if (reads++ < 8) 42 else throw IOException("Connection dropped")
                }
            }
        }
        assertArrayEquals(image, original.readBytes())
        assertNoPartialFiles()
    }

    @Test fun invalidAndOversizedBodiesNeverCreatePermanentArtwork() {
        assertThrows(IOException::class.java) {
            OfflineArtwork.save(directory, 4L) { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }
        }
        var opened = false
        assertThrows(IOException::class.java) {
            OfflineArtwork.save(directory, 8L * 1024 * 1024 + 1) { opened = true; ByteArrayInputStream(image) }
        }
        assertFalse(opened)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun unknownLengthResponsesStillEnforceTheSizeLimitAndPreserveSavedArtwork() {
        val original = seed()
        assertThrows(IOException::class.java) {
            OfflineArtwork.save(directory, -1L) {
                object : InputStream() {
                    var remaining = 8 * 1024 * 1024 + 1
                    override fun read(): Int = if (remaining-- > 0) 42 else -1
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (remaining == 0) return -1
                        val count = minOf(remaining, length)
                        buffer.fill(42, offset, offset + count)
                        remaining -= count
                        return count
                    }
                }
            }
        }
        assertArrayEquals(image, original.readBytes())
        assertNoPartialFiles()
    }

    @Test fun aCompleteUnknownLengthImageIsSavedForOfflineUse() {
        val path = OfflineArtwork.save(directory, -1L) { ByteArrayInputStream(image) }
        assertArrayEquals(image, File(path).readBytes())
        assertEquals(path, OfflineArtwork.existing(directory))
        assertNoPartialFiles()
    }

    @Test fun retryingAfterAnInterruptedProcessReplacesItsStagingFile() {
        val original = seed()
        File(directory, "cover.part").writeBytes(ByteArray(1024) { 42 })
        val path = OfflineArtwork.save(directory, image.size.toLong()) { ByteArrayInputStream(image) }
        assertEquals(original.absolutePath, path)
        assertArrayEquals(image, original.readBytes())
        assertNoPartialFiles()
    }
}
