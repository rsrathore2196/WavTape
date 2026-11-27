package com.example.downloads

import com.example.data.model.MusicTrack
import com.example.performance.AppDispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

internal data class DownloadSummary(
    val bytes: Long = 0L, val lossless: Int = 0, val high: Int = 0, val standard: Int = 0,
    val partial: Boolean = false, val hasRecords: Boolean = false
) {
    val saved get() = lossless + high + standard
}

/** Progress from another track must not invalidate a track menu or a saved collection. */
internal fun OfflineDownloads.observeRecord(id: String?) = records.map { it[id] }.distinctUntilChanged()
internal fun OfflineDownloads.observeStatus(id: String) = records.map { it[id]?.status }.distinctUntilChanged()
internal fun OfflineDownloads.observeSavedCount() = records.map { records ->
    records.values.count { it.status == DownloadStatus.COMPLETE && it.plan != null }
}.distinctUntilChanged().flowOn(AppDispatchers.CpuBounded)

internal fun OfflineDownloads.observeSavedRecords() = records.map { records ->
    records.values.filter { it.status == DownloadStatus.COMPLETE && it.plan != null }
}.distinctUntilChanged().map { saved ->
    // Sorting occurs only when the saved collection actually changes.
    saved.sortedWith(compareByDescending<DownloadRecord> { it.addedAt }.thenBy { it.track.id })
}.flowOn(AppDispatchers.CpuBounded)

internal fun OfflineDownloads.observeSummary() = records.map { summarizeDownloads(it.values) }
    .distinctUntilChanged().flowOn(AppDispatchers.CpuBounded)

internal fun summarizeDownloads(records: Collection<DownloadRecord>): DownloadSummary {
    var bytes = 0L
    var lossless = 0
    var high = 0
    var standard = 0
    var partial = false
    for (record in records) {
        bytes += record.bytes.coerceAtLeast(0)
        if (record.status == DownloadStatus.COMPLETE && record.plan != null) {
            // The worker persists the resolved representation's quality, including fallback downloads.
            when {
                record.track.isLossless -> lossless++
                record.track.bitrateKbps > DownloadQuality.STANDARD.kbps -> high++
                else -> standard++
            }
        } else if (record.status != DownloadStatus.COMPLETE && record.bytes > 0) partial = true
    }
    return DownloadSummary(bytes, lossless, high, standard, partial, records.isNotEmpty())
}

internal fun OfflineDownloads.observeCollectionProgress(tracks: List<MusicTrack>, key: String, complete: Boolean) =
    collections.map { it[key] }.distinctUntilChanged()
        .map { CollectionDownloadMembership(tracks, it, complete) }
        .combine(records) { membership, records -> membership.progress(records) }
        .distinctUntilChanged().flowOn(AppDispatchers.CpuBounded)
