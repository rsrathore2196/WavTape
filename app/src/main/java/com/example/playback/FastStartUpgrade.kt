package com.example.playback

import android.content.Context
import androidx.media3.common.MediaItem
import com.example.data.model.MusicTrack
import com.example.data.repository.RecordingIdentity
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

/** Check the replacement at the live playhead before changing the active Media3 item. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun prepareFastStartReplacement(
    context: Context, item: MediaItem, original: MusicTrack, replacement: MusicTrack,
    position: () -> Long, stillCurrent: () -> Boolean, automatic: Boolean
): Boolean {
    if (!stillCurrent()) return false
    if (item.localConfiguration?.uri?.scheme == MusicCache.OFFLINE_SCHEME) return true
    val matchingIsrc = RecordingIdentity.isrc(original.isrc)?.let { it == RecordingIdentity.isrc(replacement.isrc) } == true
    val tolerance = if (matchingIsrc) 8_000L else 5_000L
    if (LosslessStreamPrebuffer.isAdaptiveSource(item)) {
        return LosslessStreamPrebuffer.prepareAdaptiveSource(context, item, original.durationMs, stillCurrent,
            durationToleranceMs = tolerance, currentPosition = position, automatic = automatic,
            requireLossless = replacement.isLossless)
    }
    val prepared = LosslessStreamPrebuffer.prepare(context, item, original.durationMs, position, stillCurrent,
        durationToleranceMs = tolerance, allowUnknownDuration = matchingIsrc, automatic = automatic,
        requireLossless = replacement.isLossless)
    if (!stillCurrent()) return false
    val ready = prepared?.covers(position()) == true
    if (!ready) LosslessDiagnostics.prebufferRejected(original.id, prepared != null)
    return ready
}
