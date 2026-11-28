package com.example.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.XtremeMusicApp
import com.example.data.model.MusicTrack
import com.example.data.remote.ArchiveFileKey
import com.example.data.remote.ArchiveFileTransport
import com.example.data.remote.StreamingAddons
import com.example.data.remote.YouTubeAudioStreamService
import com.example.playback.AudioQuality
import com.example.service.SabrSessionRegistry
import com.example.service.YouTubeMediaDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OfflineDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private class OfflineLicenseUnavailable : IOException()

    override suspend fun getForegroundInfo() = foreground("Preparing offline audio", 0, false)
    override suspend fun doWork(): Result {
        val id = inputData.getString("track_id") ?: return Result.failure()
        val store = OfflineDownloads.get(applicationContext)
        store.load()
        return locks[(id.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            slots.withPermit {
                val record = store.records.value[id] ?: return@withPermit Result.success()
                if (record.status !in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)) return@withPermit Result.success()
                try {
                    var claimed: DownloadRecord? = null
                    store.update(id) { current ->
                        if (current !== record || isStopped) current else current.copy(
                            status = DownloadStatus.DOWNLOADING, error = null).also { claimed = it }
                    }
                    val current = claimed ?: return@withPermit Result.success()
                    // Claim before requesting foreground execution so a rejected start
                    // follows the same durable failure/retry path as a transfer failure.
                    setForeground(foreground(record.track.title, 0, true))
                    if (!store.retainCache(id, current)) return@withPermit Result.success()
                    try {
                        try { transfer(store, current) }
                        catch (first: IOException) {
                            val renew = generateSequence<Throwable>(first) { it.cause }.take(8).any {
                                it is com.example.service.YouTubeStreamRefreshRequiredException ||
                                    it is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && it.responseCode in setOf(401, 403, 410)
                            }
                            if (!renew) throw first
                            currentCoroutineContext().ensureActive()
                            transfer(store, store.records.value[id] ?: throw first, forceRefresh = true)
                        }
                    } finally { withContext(NonCancellable + Dispatchers.IO) { store.releaseOwner(id) } }
                    Result.success()
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val cancelledByUser = WorkManager.getInstance(applicationContext)
                            .getWorkInfoById(this@OfflineDownloadWorker.id).get()?.state == androidx.work.WorkInfo.State.CANCELLED
                        store.update(id) {
                        if (it.status != DownloadStatus.DOWNLOADING) it else it.copy(status =
                            if (cancelledByUser) DownloadStatus.PAUSED else DownloadStatus.QUEUED)
                    } }
                    throw cancelled
                }
                catch (error: Exception) {
                    store.update(id) { if (it.status != DownloadStatus.DOWNLOADING) it else it.copy(
                        status = DownloadStatus.FAILED,
                        error = when (error) {
                            is OfflineLicenseUnavailable -> "This version does not provide an offline license."
                            is java.io.FileNotFoundException -> "Storage is unavailable. Free some space and retry."
                            else -> "Could not save this track. Check your connection and retry."
                        }) }
                    Result.failure()
                }
            }
        }
    }
    private suspend fun transfer(store: OfflineDownloads, record: DownloadRecord, forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        val quality = store.preferences.value.quality
        val cap = if (quality == DownloadQuality.LOSSLESS) 320_000 else quality.kbps * 1000
        val selected = record.track
        val lookup = if (quality == DownloadQuality.LOSSLESS || !YouTubeAudioStreamService.isYouTubeAudio(selected.id, selected.audioUrl))
            (applicationContext as XtremeMusicApp).repository.resolveDownloadTrack(selected, quality) else selected
        val video = if (YouTubeAudioStreamService.isYouTubeAudio(lookup.id, lookup.audioUrl))
            YouTubeAudioStreamService.videoIdFromReference(lookup.id, lookup.audioUrl) else null
        val archive = ArchiveFileKey.nativeTransport(lookup.audioUrl)
        val resolved: MusicTrack
        val plan: OfflinePlan
        if (video != null) {
            // The extractor remains anonymous. Download quality is pinned separately from streaming quality.
            val audio = YouTubeAudioStreamService.resolve(video, AudioQuality.ULTRA_HD_320, forceRefresh = forceRefresh,
                maxBitrateBps = cap)
            val uri = if (audio.sabrBootstrap != null) SabrSessionRegistry.uriFor(audio.sabrBootstrap, cap / 1000).toString() else audio.url
            val mime = when { audio.isHls -> MimeTypes.APPLICATION_M3U8
                audio.codec.equals("Opus", true) -> MimeTypes.AUDIO_WEBM
                else -> MimeTypes.AUDIO_MP4 }
            resolved = selected.copy(coverUrl = selected.coverUrl.ifBlank { lookup.coverUrl },
                bitrateKbps = audio.bitrateKbps, qualityBadge = audio.qualityBadge, isLossless = false)
            plan = OfflinePlan(uri, mime, "${audio.itag}:${audio.codec}:${audio.contentLength}:${audio.isSabr}", !audio.isHls)
        } else if (archive != null) {
            if (!lookup.isLossless || !archive.playableContainer) throw IOException("Archive audio could not be verified")
            resolved = lookup
            plan = OfflineTransfer.archivePlan(archive)
        } else {
            resolved = lookup
            if (StreamingAddons.hasLicensedDrm(resolved.audioUrl)) throw OfflineLicenseUnavailable()
            val mime = StreamingAddons.mime(resolved.audioUrl) ?: when {
                resolved.audioUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                resolved.audioUrl.substringBefore('?').endsWith(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                else -> null
            }
            plan = OfflinePlan(resolved.audioUrl, mime, resolved.audioUrl,
                mime !in setOf(MimeTypes.APPLICATION_M3U8, MimeTypes.APPLICATION_MPD))
        }
        currentCoroutineContext().ensureActive()
        // A refreshed representation may have different bytes. Never append it to a partial older encode.
        if (record.plan?.representation != plan.representation || !plan.progressive || plan.uri.startsWith("xtreme-sabr:"))
            store.clearPartial(selected.id)
        store.update(selected.id) { it.copy(plan = plan, track = resolved) }
        val songCover = if (video != null) async {
            try { withTimeoutOrNull(10_000L) { com.example.data.remote.resolveNativeSongCover(lookup, lookup.coverUrl) } }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        } else null
        var lastUpdate = 0L
        val progress: (Long, Long) -> Unit = { total, bytes ->
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastUpdate >= 750L) {
                lastUpdate = now
                runBlocking {
                    store.update(selected.id, persist = false) { if (it.status == DownloadStatus.DOWNLOADING) it.copy(bytes = bytes, total = total) else it }
                    setForeground(foreground(selected.title, if (total > 0) (bytes * 100 / total).toInt() else 0, total <= 0))
                }
            }
        }
        val cacheSize = if (archive != null) {
            OfflineTransfer.saveArchive(store, selected.id, plan, source = { resume ->
                ArchiveFileTransport.source(applicationContext, archive, 15_000, 30_000, resume)
            }, progress = progress)
        } else OfflineTransfer.save(store, selected.id, plan, upstreamFactory(addon = StreamingAddons.registeredSourceId(plan.uri) != null), progress)
        currentCoroutineContext().ensureActive()
        val finalTrack = resolved.copy(coverUrl = songCover?.await() ?: selected.coverUrl.ifBlank { resolved.coverUrl })
        val artwork = saveArtwork(store, finalTrack)
        currentCoroutineContext().ensureActive()
        store.update(selected.id) { if (it.status != DownloadStatus.DOWNLOADING) it else it.copy(
            status = DownloadStatus.COMPLETE, bytes = cacheSize, total = cacheSize, error = null, artwork = artwork, track = finalTrack) }
    }
    private fun upstreamFactory(addon: Boolean): DataSource.Factory {
        val http = if (addon) com.example.data.remote.AddonHttps.mediaFactory(15_000, 30_000)
            else DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(false)
        val base = DefaultDataSource.Factory(applicationContext, http)
        return DataSource.Factory {
            val delegate = YouTubeMediaDataSource(applicationContext, base, http)
            object : DataSource by delegate {
                override fun open(dataSpec: DataSpec): Long {
                    if (addon) com.example.data.remote.AddonHttps.requireMediaUri(dataSpec.uri)
                    val url = dataSpec.uri.toString()
                    val headers = if (YouTubeAudioStreamService.isMediaUrl(url)) YouTubeAudioStreamService.headersFor(url)
                        else StreamingAddons.headers(url)
                    return delegate.open(dataSpec.buildUpon().setHttpRequestHeaders(dataSpec.httpRequestHeaders + headers).build())
                }
            }
        }
    }
    private fun saveArtwork(store: OfflineDownloads, track: MusicTrack): String {
        val directory = store.directory(track.id)
        if (!track.coverUrl.startsWith("https://")) return OfflineArtwork.existing(directory)
        return try {
            artworkHttp.newCall(Request.Builder().url(track.coverUrl).build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) OfflineArtwork.existing(directory)
                else OfflineArtwork.save(directory, body.contentLength(), body::byteStream)
            }
        } catch (_: Exception) {
            OfflineArtwork.existing(directory)
        }
    }
    private fun foreground(title: String, percent: Int, indeterminate: Boolean): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText("Saving for offline listening")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(100, percent, indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(20_000 + (id.hashCode() and 0x3fff), notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(20_000 + (id.hashCode() and 0x3fff), notification)
    }
    companion object {
        private const val CHANNEL = "offline_downloads"
        private val slots = Semaphore(2)
        private val locks = Array(32) { Mutex() }
        private val artworkHttp by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    }
}
