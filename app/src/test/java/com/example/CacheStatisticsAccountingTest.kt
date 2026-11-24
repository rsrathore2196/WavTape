package com.example

import android.app.Application
import android.content.Context
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import com.example.data.cache.CacheStats
import com.example.data.cache.ManagedImageDiskCache
import com.example.data.cache.SmartCacheManager
import com.example.data.model.MusicTrack
import com.example.data.remote.OnlineMusicApiService
import com.example.downloads.DownloadIndex
import com.example.downloads.DownloadRecord
import com.example.downloads.DownloadStatus
import com.example.downloads.OfflineDownloads
import com.example.downloads.OfflineJson
import com.example.downloads.downloadKey
import com.example.service.MusicCache
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CacheStatisticsAccountingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun mediaQuotaExcludesLyricsMetadataHttpDownloadsAndMemory() {
        val stats = CacheStats(totalBytes = 500, audioBytes = 80, imageBytes = 20, lyricsBytes = 100,
            metadataBytes = 100, httpBytes = 200, downloadBytes = 9_000, memoryMetadataBytes = 4_000,
            maxQuotaBytes = 200, audioTrackCount = 7, completeAudioTrackCount = 2)
        assertEquals(.5f, stats.usagePercentage, 0f)
        assertEquals(7, stats.audioTrackCount)
        assertEquals(2, stats.completeAudioTrackCount)
        assertEquals(500, stats.totalBytes)
    }

    @Test fun manualOptimizationRespondsToBothQuotaAndDevicePressure() {
        val megabyte = 1024L * 1024L
        val quota = 512 * megabyte
        val at80Percent = (quota * 80L + 99L) / 100L
        assertEquals(quota, SmartCacheManager.optimizationMediaLimit(quota, at80Percent - 1,
            64_000 * megabyte, 10_000 * megabyte))
        assertEquals(384 * megabyte, SmartCacheManager.optimizationMediaLimit(quota, at80Percent,
            64_000 * megabyte, 10_000 * megabyte))
        assertEquals(150 * megabyte, SmartCacheManager.optimizationMediaLimit(quota, 200 * megabyte,
            64_000 * megabyte, 6_400 * megabyte))
        assertEquals(quota, SmartCacheManager.optimizationMediaLimit(quota, 200 * megabyte,
            64_000 * megabyte, 10_000 * megabyte))
    }

    @Test @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun smartOptimizeReclaimsOldAudioBeforeTheQuotaAndPublishesUpdatedStats() = runBlocking(Dispatchers.IO) {
        val owner = MusicCache::class.java.getDeclaredField("simpleCacheInstance").apply { isAccessible = true }
        (owner.get(null) as? SimpleCache)?.release()
        owner.set(null, null)
        val cache = MusicCache.getSimpleCache(context)
        val megabyte = 1024L * 1024L
        val savedDownload = File(context.noBackupFilesDir, "offline_audio/smart-optimize-test/audio.bin").apply {
            parentFile!!.mkdirs()
            writeText("saved audio")
        }
        fun writeCacheEntry(key: String, size: Long) {
            val hole = cache.startReadWrite(key, 0, size)
            try {
                val file = cache.startFile(key, 0, size)
                RandomAccessFile(file, "rw").use { it.setLength(size) }
                cache.commitFile(file, size)
            } finally { cache.releaseHoleSpan(hole) }
        }
        try {
            writeCacheEntry("old", 180 * megabyte)
            ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
            writeCacheEntry("recent", 235 * megabyte)
            assertEquals(415 * megabyte, cache.cacheSpace)

            val (freed, message) = withTimeout(10_000) { SmartCacheManager.smartOptimize(context) }
            assertEquals(180 * megabyte, freed)
            assertTrue(message.contains("Freed"))
            assertFalse(cache.isCached("old", 0, 180 * megabyte))
            assertTrue(cache.isCached("recent", 0, 235 * megabyte))
            assertEquals(cache.cacheSpace, SmartCacheManager.stats.value.audioBytes)
            assertEquals("saved audio", savedDownload.readText())
        } finally {
            cache.release()
            owner.set(null, null)
            savedDownload.parentFile!!.deleteRecursively()
        }
    }

    @Test @OptIn(coil.annotation.ExperimentalCoilApi::class)
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun autoTrimChecksThe95PercentThresholdAndPreservesRecentlyUsedMedia() = runBlocking(Dispatchers.IO) {
        val owner = MusicCache::class.java.getDeclaredField("simpleCacheInstance").apply { isAccessible = true }
        (owner.get(null) as? SimpleCache)?.release()
        owner.set(null, null)
        val cache = MusicCache.getSimpleCache(context)
        val megabyte = 1024L * 1024L
        val prefs = context.getSharedPreferences("xtreme_smart_cache_preferences", Context.MODE_PRIVATE)
        val art = ManagedImageDiskCache(DiskCache.Builder().directory(File(context.cacheDir, "auto-trim-artwork"))
            .maxSizeBytes(SmartCacheManager.ARTWORK_CACHE_MAX_BYTES).build(),
            File(context.filesDir, "auto-trim-art-index.json"))
        val loader = ImageLoader.Builder(context).diskCache(art).build()
        Coil.setImageLoader(loader)
        fun writeAudio(key: String, size: Long) {
            val hole = cache.startReadWrite(key, 0, size)
            try {
                val file = cache.startFile(key, 0, size)
                RandomAccessFile(file, "rw").use { it.setLength(size) }
                cache.commitFile(file, size)
            } finally { cache.releaseHoleSpan(hole) }
        }
        fun writeArtwork(key: String, size: Long) {
            val editor = checkNotNull(art.openEditor(key))
            editor.metadata.toFile().writeText("")
            RandomAccessFile(editor.data.toFile(), "rw").use { it.setLength(size) }
            editor.commit()
        }
        try {
            writeAudio("old", 180 * megabyte)
            writeArtwork("old-cover", 6 * megabyte)
            ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
            writeAudio("recent", 270 * megabyte)
            writeArtwork("recent-cover", 30 * megabyte)
            prefs.edit().putBoolean("key_smart_auto_trim", true).commit()
            SmartCacheManager.performScheduledMaintenance(context, pressureOnly = true)
            assertEquals(486 * megabyte, cache.cacheSpace + art.size)

            writeArtwork("latest-cover", 4 * megabyte)
            prefs.edit().putBoolean("key_smart_auto_trim", false).commit()
            SmartCacheManager.performScheduledMaintenance(context, pressureOnly = true)
            assertEquals(490 * megabyte, cache.cacheSpace + art.size)

            prefs.edit().putBoolean("key_smart_auto_trim", true).commit()
            SmartCacheManager.performScheduledMaintenance(context, pressureOnly = true)
            assertFalse(cache.isCached("old", 0, 180 * megabyte))
            assertTrue(cache.isCached("recent", 0, 270 * megabyte))
            assertNull(art.openSnapshot("old-cover"))
            checkNotNull(art.openSnapshot("recent-cover")).close()
            checkNotNull(art.openSnapshot("latest-cover")).close()
            assertEquals(cache.cacheSpace + art.size, SmartCacheManager.stats.value.let { it.audioBytes + it.imageBytes })
        } finally {
            art.clear()
            loader.shutdown()
            cache.release()
            owner.set(null, null)
            prefs.edit().remove("key_smart_auto_trim").commit()
        }
    }

    @Test fun optimizeOpensTheExistingHttpJournalBeforeAnyProviderRequest() = runBlocking(Dispatchers.IO) {
        val directory = File(context.cacheDir, "http_music_cache")
        val payload = ByteArray(16_384)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Cache-Control", "public, max-age=600")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val before = Cache(directory, 40L * 1024 * 1024).use { seed ->
                val client = OkHttpClient.Builder().cache(seed).build()
                repeat(3) { index ->
                    val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/$index").build()
                    client.newCall(request).execute().use { assertEquals(payload.size, it.body.bytes().size) }
                }
                seed.size()
            }
            assertTrue(before >= 3L * payload.size)
            assertNull(OnlineMusicApiService.peekNetworkCacheSize())

            val freed = OnlineMusicApiService.optimizeExistingNetworkCache(context)
            assertTrue("Storage optimization must trim a cold HTTP cache", freed > 0L)
            assertEquals(before - freed, OnlineMusicApiService.peekNetworkCacheSize())
        } finally {
            OnlineMusicApiService.trimNetworkCache(0L)
            server.stop(0)
        }
    }

    @Test fun metadataScanDoesNotCountSeparatelyOwnedLyricsHttpArtworkOrLegacyAudioAgain() = runBlocking(Dispatchers.IO) {
        val dirs = listOf("lyrics_cache", "http_cache", "okhttp_cache", "http_music_cache", "image_cache",
            "image_manager_disk_cache", "image_cache_default", "xtreme_audio_cache")
        val created = mutableListOf<File>()
        try {
            val baseline = SmartCacheManager.calculateMetadataCacheBytes(context)
            for (name in dirs) {
                created += File(context.cacheDir, "$name/stat-test").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(127)) }
            }
            assertEquals(baseline, SmartCacheManager.calculateMetadataCacheBytes(context))
            val other = File(context.cacheDir, "stat-metadata-test/item.json").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(53)) }
            created += other
            assertEquals(baseline + 53L, SmartCacheManager.calculateMetadataCacheBytes(context))
        } finally { created.forEach { it.delete() } }
    }

    @Test fun downloadStatisticsUseActualMediaBytesAndDoNotLoadOrReconcileWorkers() = runBlocking(Dispatchers.IO) {
        val first = MusicTrack("statistics-first", "One", "Artist", "Album", 60_000, "", "")
        val missing = first.copy(id = "statistics-missing")
        val paused = first.copy(id = "statistics-paused")
        val records = listOf(DownloadRecord(first, status = DownloadStatus.COMPLETE, bytes = 999),
            DownloadRecord(missing, status = DownloadStatus.COMPLETE, bytes = 999),
            DownloadRecord(paused, status = DownloadStatus.PAUSED, bytes = 999))
        val ledger = JSONObject().put("records", JSONArray(records.map(OfflineJson::record))).toString().toByteArray()
        val index = object : DownloadIndex {
            override fun read() = ledger
            override fun write(bytes: ByteArray) = error("Statistics must never mutate the index")
        }
        val store = OfflineDownloads(context, index)
        val roots = records.map { File(context.noBackupFilesDir, "offline_audio/${downloadKey(it.track.id)}") }
        try {
            File(roots[0], "media/0/1.0.123.v3.exo").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(83)) }
            File(roots[0], "cover.jpg").writeBytes(ByteArray(200))
            File(roots[2], "media/1/2.0.123.v3.exo").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(31)) }
            File(roots[2], "media/pending.tmp").writeBytes(ByteArray(90))
            assertEquals(114L to 1, store.storageUsage())
            assertTrue(store.records.value.isEmpty())
            val loaded = OfflineDownloads::class.java.getDeclaredField("loaded").apply { isAccessible = true }
            assertFalse("A statistics read must not suppress later worker reconciliation", loaded.getBoolean(store))
            assertTrue(roots[0].isDirectory)
        } finally { roots.forEach { it.deleteRecursively() } }
    }
}
