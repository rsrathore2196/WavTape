package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.MusicTrack
import com.example.downloads.DownloadStatus
import com.example.downloads.OfflineDownloads
import com.example.downloads.observeStatus
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.XtremeRose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Controls which options appear in the compact 3-dot overflow popup next to each track row.
 */
enum class TrackRowMenuMode {
    /** Album, public playlist, and liked songs: Like, Download/Remove download, Track Info. */
    STANDARD,
    /** Custom playlist: Like, Download/Remove download, Remove from playlist, Track Info. */
    CUSTOM_PLAYLIST,
    /** Offline downloaded songs: Like, Remove (from downloads), Track Info. */
    DOWNLOADS
}

/** App-wide callback to open the full-screen Track Info catalog page from any track menu. */
val LocalOpenTrackInfo = staticCompositionLocalOf<((MusicTrack, String?) -> Unit)?> { null }

/**
 * Compact 3-dot menu button and popup for track rows across albums, playlists,
 * custom playlists, offline downloads, and liked songs.
 */
@Composable
fun TrackOverflowMenuButton(
    track: MusicTrack,
    mode: TrackRowMenuMode,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    onTrackInfo: ((MusicTrack) -> Unit)? = null,
    sourcePlaylistTitle: String? = null,
    downloadStore: OfflineDownloads = OfflineDownloads.get(LocalContext.current)
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val localTrackInfo = LocalOpenTrackInfo.current
    var expanded by remember { mutableStateOf(false) }

    val showDownloadOption = mode == TrackRowMenuMode.STANDARD || mode == TrackRowMenuMode.CUSTOM_PLAYLIST
    val downloadStatus by remember(downloadStore, track.id, showDownloadOption) {
        downloadStore.observeStatus(track.id)
    }.collectAsStateWithLifecycle(downloadStore.records.value[track.id]?.status)

    LaunchedEffect(expanded, showDownloadOption) {
        if (expanded && showDownloadOption) runCatching { downloadStore.load() }
    }

    val iconBg = if (appColors.isAmoled) {
        Color(0xFF141414)
    } else if (appColors.isDark) {
        appColors.cardBorder.copy(alpha = 0.35f)
    } else {
        appColors.chipBackground
    }

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(40.dp)
                .testTag("track_menu_${track.id}")
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Track options for ${track.title}",
                tint = appColors.textMuted,
                modifier = Modifier.size(20.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = appColors.cardBackground,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .widthIn(min = 196.dp)
                .testTag("track_menu_popup_${track.id}")
        ) {
            // 1. Like / Unlike
            CompactTrackMenuItem(
                label = if (track.isLiked) "Unlike" else "Like",
                icon = if (track.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                iconTint = if (track.isLiked) XtremeRose else appColors.primaryAccent,
                iconBg = iconBg,
                tag = "track_menu_like",
                onClick = {
                    expanded = false
                    onToggleFavorite()
                }
            )

            // 2. Download / Remove download (Album, Public Playlist, Liked Songs, Custom Playlist)
            if (showDownloadOption) {
                val (downloadLabel, downloadIcon) = when (downloadStatus) {
                    DownloadStatus.COMPLETE -> "Remove download" to Icons.Default.DeleteOutline
                    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> "Pause download" to Icons.Default.Pause
                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> "Resume download" to Icons.Default.Download
                    else -> "Download" to Icons.Default.Download
                }
                CompactTrackMenuItem(
                    label = downloadLabel,
                    icon = downloadIcon,
                    iconTint = appColors.primaryAccent,
                    iconBg = iconBg,
                    tag = "track_menu_download",
                    onClick = {
                        expanded = false
                        scope.launch {
                            try {
                                when (downloadStatus) {
                                    DownloadStatus.COMPLETE -> downloadStore.remove(track.id)
                                    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> downloadStore.pause(track.id)
                                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> downloadStore.resume(track.id)
                                    else -> downloadStore.enqueue(listOf(track))
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                Toast.makeText(
                                    context,
                                    "Could not update download. Try again.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )
            }

            // 3. Remove (Custom Playlist -> remove from playlist; Downloads -> remove download)
            if ((mode == TrackRowMenuMode.CUSTOM_PLAYLIST || mode == TrackRowMenuMode.DOWNLOADS) && onRemove != null) {
                CompactTrackMenuItem(
                    label = "Remove",
                    icon = Icons.Default.DeleteOutline,
                    iconTint = Color(0xFFEF4444),
                    iconBg = iconBg,
                    tag = "track_menu_remove",
                    onClick = {
                        expanded = false
                        onRemove()
                    }
                )
            }

            // 4. Track Info
            CompactTrackMenuItem(
                label = "Track Info",
                icon = Icons.Default.Info,
                iconTint = appColors.primaryAccent,
                iconBg = iconBg,
                tag = "track_menu_info",
                onClick = {
                    expanded = false
                    if (onTrackInfo != null) {
                        onTrackInfo(track)
                    } else {
                        localTrackInfo?.invoke(track, sourcePlaylistTitle)
                    }
                }
            )
        }
    }
}

@Composable
private fun CompactTrackMenuItem(
    label: String,
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    tag: String,
    onClick: () -> Unit
) {
    val appColors = LocalAppColors.current
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = appColors.textPrimary
                )
            )
        },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .testTag(tag)
    )
}
