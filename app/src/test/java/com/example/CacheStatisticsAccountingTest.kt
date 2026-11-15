package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.CacheStats
import com.example.data.cache.SmartCacheManager
import com.example.data.model.MusicTrack
import com.example.downloads.DownloadIndex
import com.example.downloads.DownloadRecord
import com.example.downloads.DownloadStatus
import com.example.downloads.OfflineDownloads
import com.example.downloads.OfflineJson
import com.example.downloads.downloadKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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
