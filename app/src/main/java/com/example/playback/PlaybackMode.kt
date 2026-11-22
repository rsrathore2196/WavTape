package com.example.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.data.model.MusicTrack

enum class PlaybackMode(val title: String, val description: String) {
    FAST_START("Fast Start", "Start with YouTube Music, then switch to a higher-priority stream when it’s ready."),
    BEST_QUALITY("Best Quality", "Follow your playback source order before starting. This can take longer.");

    companion object {
        fun fromPreference(value: String?): PlaybackMode = entries.firstOrNull { it.name == value } ?: BEST_QUALITY
    }
}

internal data class PlaybackStart(val track: MusicTrack, val mode: PlaybackMode)

private const val PLAYBACK_MODE_EXTRA = "playback_start_mode"

internal fun Bundle.putPlaybackMode(mode: PlaybackMode?) {
    if (mode == null) remove(PLAYBACK_MODE_EXTRA) else putString(PLAYBACK_MODE_EXTRA, mode.name)
}

internal val MediaItem.playbackMode: PlaybackMode?
    get() = mediaMetadata.extras?.getString(PLAYBACK_MODE_EXTRA)?.let(PlaybackMode::fromPreference)

internal fun PlaybackSourcePolicy.startupSourcesFor(trackId: String): List<PlaybackSource> =
    if (returnsToYouTube(trackId)) resolutionSourcesFor(trackId) else upgradeSources

internal fun PlaybackSourcePolicy.fastStartUpgradeSources(track: MusicTrack): List<PlaybackSource> {
    if (!online || SourceTransitionOrigin.sourceId(track.id, Uri.parse(track.audioUrl), this) != PlaybackSource.youtube.id)
        return emptyList()
    return startupSourcesFor(track.id).takeWhile { it.kind != PlaybackSourceKind.YOUTUBE }
}

internal fun Player.canSwitchStartupStream(): Boolean =
    isPlaying && isCurrentMediaItemSeekable && !isCurrentMediaItemLive &&
        (duration <= 0L || duration - currentPosition > 8_000L)
