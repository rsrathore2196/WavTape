package com.example

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.example.service.MusicCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalAudioCacheBypassTest {
    @Test fun localThirtyMebibyteFileAndAudioExtensionsDoNotCreateStreamCacheOrDuplicateBytes() = runBlocking(Dispatchers.IO) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val owner = MusicCache::class.java.getDeclaredField("simpleCacheInstance").apply { isAccessible = true }
        val before = MusicCache.peekCacheUsage()
        val factory = MusicCache.createCacheDataSourceFactory(app)
        for (extension in listOf("flac", "mp3", "aac", "opus")) {
            val file = app.filesDir.resolve("cache-bypass.$extension")
            val length = if (extension == "flac") 30L * 1024 * 1024 else 4096L
            RandomAccessFile(file, "rw").use { it.setLength(length); it.write(byteArrayOf(1, 2, 3, 4)) }
            try {
                factory.createDataSource().let { source ->
                    try {
                        assertEquals(length, source.open(DataSpec(Uri.fromFile(file))))
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; read += count }
                        assertEquals(length, read)
                    } finally { source.close() }
                }
                factory.createDataSource().let { source ->
                    try {
                        source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).setPosition(length - 4).setLength(4).build())
                        assertEquals(4, source.read(ByteArray(4), 0, 4))
                    } finally { source.close() }
                }
            } finally { file.delete() }
        }
        assertEquals(before, MusicCache.peekCacheUsage())
        if (before == null) assertNull(owner.get(null))
    }

    @Test fun contentProviderAudioRemainsSeekableWithoutCachingAnotherCopy() = runBlocking(Dispatchers.IO) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = app.filesDir.resolve("saf-audio.opus").apply { writeBytes(ByteArray(8192) { (it % 127).toByte() }) }
        val before = MusicCache.peekCacheUsage()
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.provider", file)
            val source = MusicCache.createCacheDataSourceFactory(app).createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(uri).setPosition(2048).setLength(128).build())
                val bytes = ByteArray(128)
                assertEquals(128, source.read(bytes, 0, bytes.size))
                assertArrayEquals(file.readBytes().copyOfRange(2048, 2176), bytes)
            } finally { source.close() }
            assertEquals(before, MusicCache.peekCacheUsage())
        } finally { file.delete() }
    }

    @Test fun localAndRemoteSchemesAreClassifiedWithoutTreatingNetworkProtocolsAsFiles() {
        for (scheme in listOf("file", "content", "asset", "rawresource", "android.resource", "data"))
            assertTrue(scheme, MusicCache.isLocal(Uri.parse("$scheme:///audio")))
        assertTrue(MusicCache.isLocal(Uri.parse("/storage/audio.flac")))
        for (scheme in listOf("http", "https", "smb", "xtreme-youtube", "xtreme-sabr"))
            assertFalse(scheme, MusicCache.isLocal(Uri.parse("$scheme://host/audio")))
    }
}
