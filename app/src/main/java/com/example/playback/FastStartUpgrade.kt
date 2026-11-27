package com.example.playback

import android.content.Context
import androidx.media3.common.MediaItem
import com.example.data.model.MusicTrack
import com.example.data.repository.RecordingIdentity
import com.example.service.FastStartManifests
import com.example.service.MusicCache

/** Coalesces automatic requests for one playing occurrence and source policy. */
internal class FastStartUpgrade {
    private var attemptedKey: String? = null

    fun claim(trackId: String, policy: PlaybackSourcePolicy): Boolean {
        // Switching transport for this occurrence must not start the same Best Quality lookup again.
        val key = "$trackId:${policy.key}"
        if (attemptedKey == key) return false
        attemptedKey = key
        return true
    }

    fun reset() {
        attemptedKey = null
    }
}

// Media3's default resume threshold is five seconds. Keep extra read-ahead while the old source plays.
internal const val FAST_START_BUFFER_MS = 5_000L
internal const val FAST_START_READ_AHEAD_MS = 9_000L

internal data class FastStartBuffer(val fromMs: Long, val untilMs: Long, val manifests: FastStartManifests? = null,
    val archiveCacheKey: String? = null) {
    fun covers(positionMs: Long): Boolean = positionMs >= fromMs && untilMs - positionMs >= FAST_START_BUFFER_MS

    fun forHandoff(item: MediaItem): MediaItem = when {
        archiveCacheKey != null -> item.buildUpon().setCustomCacheKey(archiveCacheKey).build()
        else -> manifests?.forHandoff(item) ?: item
    }
}

/** Check the replacement at the live playhead before changing the active Media3 item. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun prepareFastStartReplacement(
    context: Context, item: MediaItem, original: MusicTrack, replacement: MusicTrack,
    position: () -> Long, stillCurrent: () -> Boolean, automatic: Boolean
): FastStartBuffer? {
    if (!stillCurrent()) return null
    if (item.localConfiguration?.uri?.scheme == MusicCache.OFFLINE_SCHEME) return FastStartBuffer(0L, Long.MAX_VALUE)
    val matchingIsrc = RecordingIdentity.isrc(original.isrc)?.let { it == com.example.data.remote.ArchiveFileEvidence.verifiedReplacementIsrc(replacement) } == true
    val tolerance = if (matchingIsrc) 8_000L else 5_000L
    if (LosslessStreamPrebuffer.isAdaptiveSource(item)) {
        return LosslessStreamPrebuffer.prepareAdaptiveFastStart(context, item, original.durationMs, stillCurrent,
            durationToleranceMs = tolerance, currentPosition = position, automatic = automatic,
            requireLossless = replacement.isLossless)
    }
    val prepared = LosslessStreamPrebuffer.prepare(context, item, original.durationMs, position, stillCurrent,
        durationToleranceMs = tolerance, allowUnknownDuration = matchingIsrc, automatic = automatic,
        requireLossless = replacement.isLossless, fastStart = true)
    if (!stillCurrent()) return null
    val buffer = prepared?.let { FastStartBuffer(it.fromMs, it.untilMs, archiveCacheKey = it.archiveCacheKey) }
    if (buffer?.covers(position()) == true) return buffer
    LosslessDiagnostics.prebufferRejected(original.id, prepared != null)
    return null
}
