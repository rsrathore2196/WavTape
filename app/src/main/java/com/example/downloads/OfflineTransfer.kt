package com.example.downloads

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.dash.offline.DashDownloader
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import com.example.data.remote.ArchiveCacheResume
import com.example.data.remote.ArchiveFileKey
import com.example.data.remote.ArchiveFileTransport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.IOException

/** Downloads complete manifests/keys/segments or a complete progressive resource into permanent storage. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object OfflineTransfer {
    fun archivePlan(key: ArchiveFileKey): OfflinePlan {
        val uri = key.url.toString()
        val mime = when (key.extension) {
            "flac" -> MimeTypes.AUDIO_FLAC
            "wav" -> MimeTypes.AUDIO_WAV
            else -> MimeTypes.AUDIO_MP4
        }
        // Do not resume a partial transfer saved by the old generic HTTP reader.
        return OfflinePlan(uri, mime, "archive-file-v1:$uri", progressive = true)
    }

    suspend fun saveArchive(store: OfflineDownloads, id: String, plan: OfflinePlan,
        source: (ArchiveCacheResume) -> DataSource, progress: (Long, Long) -> Unit): Long {
        val upstream = DataSource.Factory { source(ArchiveCacheResume(store.cache(id), OFFLINE_AUDIO_KEY)) }
        return try {
            save(store, id, plan, upstream, progress)
        } catch (error: IOException) {
            if (ArchiveFileTransport.failureOf(error)?.outcome != "range_changed") throw error
            currentCoroutineContext().ensureActive()
            // The mirror changed bytes or validators. Retry once from zero without mixing encodes.
            store.clearPartial(id)
            save(store, id, plan, upstream, progress)
        }
    }

    suspend fun save(store: OfflineDownloads, id: String, plan: OfflinePlan, upstream: DataSource.Factory,
        progress: (Long, Long) -> Unit = { _, _ -> }): Long {
        if (!store.retainCache(id)) throw IOException("This download has been removed")
        try {
            val factory = store.dataSource(id, plan.progressive, upstream)
            val item = MediaItem.Builder().setUri(plan.uri).setMimeType(plan.mime).build()
            val downloader: Downloader = when (plan.mime) {
                MimeTypes.APPLICATION_M3U8 -> HlsDownloader(item, factory)
                MimeTypes.APPLICATION_MPD -> DashDownloader(item, factory)
                else -> ProgressiveDownloader(item, factory)
            }
            try {
                runInterruptible { downloader.download { total, bytes, _ -> progress(total, bytes) } }
                val cache = store.cache(id)
                if (plan.progressive) {
                    val length = ContentMetadata.getContentLength(cache.getContentMetadata(OFFLINE_AUDIO_KEY))
                    if (length <= 0 || !cache.isCached(OFFLINE_AUDIO_KEY, 0, length))
                        throw IOException("The full audio file was not saved")
                    return length
                }
                return cache.cacheSpace.takeIf { it > 0L } ?: throw IOException("No audio was saved")
            } finally { downloader.cancel() }
        } finally { store.releaseOwner(id) }
    }
}
