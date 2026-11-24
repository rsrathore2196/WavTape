package com.example.ui.screens

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import com.example.ui.util.landscapeContentBounds

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import com.example.ui.components.bouncyOverscroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import com.example.ui.theme.ThemeAccentButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.example.ui.components.TrackListItem
import com.example.ui.components.TrackOverflowMenuButton
import com.example.ui.components.TrackRowMenuMode
import com.example.ui.components.playerArtworkEdgeBlend
import com.example.ui.components.headerArtworkEdgeBlend
import com.example.ui.components.CollectionTitleRow
import com.example.ui.components.rememberCollectionDownloadAction
import com.example.ui.util.CollectionPageMetadata
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.util.ImageConfig
import com.example.ui.util.rememberPlaylistCoverRequest
import com.example.data.local.PlaylistWithTracks
import com.example.data.model.MusicTrack
import com.example.data.model.queueIdentity
import com.example.playback.PlayerUiState
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.liquidGlassButton
import com.example.ui.theme.bouncyClickable
import com.example.ui.components.CollectionActionSize
import com.example.ui.components.CollectionActionSurfaceSize
import com.example.ui.components.CollectionDownloadButton
import com.example.ui.theme.accentBrush
import com.example.ui.theme.onAccentGradient

@Composable
fun PlaylistDetailScreen(
    playlistWithTracks: PlaylistWithTracks,
    playerUiState: PlayerUiState? = null,
    currentPlayingTrackId: String? = null,
    onBackClick: () -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    onDeletePlaylist: () -> Unit,
    onRemoveTrack: (String) -> Unit,
    onRenamePlaylist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    pinPlaybackActions: Boolean = false,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    footer: (@Composable () -> Unit)? = null,
    onRemoveEntry: ((MusicTrack) -> Unit)? = null,
    downloadCollectionComplete: Boolean = true,
    loadDownloadTracks: (suspend () -> List<MusicTrack>)? = null,
    showDownloadAction: Boolean = true,
    downloadCollectionKey: String = "playlist:${playlistWithTracks.playlist.playlistId}",
    downloadStore: com.example.downloads.OfflineDownloads = com.example.downloads.OfflineDownloads.get(androidx.compose.ui.platform.LocalContext.current),
    artworkIcon: ImageVector? = null,
    artworkBrush: Brush? = null,
    collectionLabel: String = "PLAYLIST",
    emptyTitle: String = "Playlist is empty",
    emptyDescription: String? = null,
    trailingPlaybackAction: (@Composable () -> Unit)? = null,
    menuMode: TrackRowMenuMode = if (readOnly) TrackRowMenuMode.STANDARD else TrackRowMenuMode.CUSTOM_PLAYLIST,
    onRemoveTrackRequest: ((MusicTrack) -> Unit)? = null,
    onTrackInfo: ((MusicTrack) -> Unit)? = null,
    showHeaderArtwork: Boolean = true,
    blendTopArtworkEdge: Boolean = true
) {
    val playlist = playlistWithTracks.playlist
    val tracks = playlistWithTracks.musicTracks
    val playbackScope = androidx.compose.runtime.rememberCoroutineScope()
    val latestPlay by androidx.compose.runtime.rememberUpdatedState(onPlayTrack)
    val playback = remember(playbackScope) {
        com.example.ui.util.CollectionPlaybackPreparation(playbackScope) { track, queue -> latestPlay(track, queue) }
    }
    androidx.compose.runtime.DisposableEffect(tracks) { onDispose { playback.cancel() } }
    val currentPlayingId = currentPlayingTrackId ?: playerUiState?.currentTrack?.id
    val appColors = LocalAppColors.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val title = remember(playlist.title) { CollectionPageMetadata.title(playlist.title, "Untitled playlist") }
    val description = remember(playlist.description, title) { CollectionPageMetadata.description(playlist.description, title) }
    val trackSummary = remember(tracks, downloadCollectionComplete) { CollectionPageMetadata.trackSummary(tracks, downloadCollectionComplete) }
    val download = if (showDownloadAction) rememberCollectionDownloadAction(tracks, title, downloadCollectionKey,
        downloadCollectionComplete, loadDownloadTracks, downloadStore) else null

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeletePlaylistDialog by remember { mutableStateOf(false) }
    var newTitleInput by remember(playlist.title) { mutableStateOf(playlist.title) }
    var trackPendingRemoval by remember { mutableStateOf<MusicTrack?>(null) }

    if (trackPendingRemoval != null) {
        val pendingTrack = trackPendingRemoval!!
        AlertDialog(
            onDismissRequest = { trackPendingRemoval = null },
            title = {
                Text(
                    text = "Remove from Playlist",
                    fontWeight = FontWeight.Bold,
                    color = appColors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to remove \"${pendingTrack.title}\" from this playlist?",
                    color = appColors.textSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                com.example.ui.theme.AccentButton(
                    onClick = {
                        if (onRemoveEntry != null) onRemoveEntry(pendingTrack) else onRemoveTrack(pendingTrack.id)
                        trackPendingRemoval = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = appColors.primaryAccent,
                        contentColor = appColors.onPrimaryAccent
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Remove", color = appColors.onPrimaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { trackPendingRemoval = null }
                ) {
                    Text("Cancel", color = appColors.textMuted)
                }
            },
            containerColor = appColors.cardBackground,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showDeletePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showDeletePlaylistDialog = false },
            title = {
                Text(
                    text = "Delete Playlist",
                    fontWeight = FontWeight.Bold,
                    color = appColors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${playlist.title}\"? This action cannot be undone.",
                    color = appColors.textSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeletePlaylistDialog = false
                        onDeletePlaylist()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeletePlaylistDialog = false }
                ) {
                    Text("Cancel", color = appColors.textMuted)
                }
            },
            containerColor = appColors.cardBackground,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = {
                Text(
                    text = "Edit Playlist Name",
                    fontWeight = FontWeight.Bold,
                    color = appColors.textPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter a new name for your playlist:",
                        fontSize = 13.sp,
                        color = appColors.textMuted
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newTitleInput,
                        onValueChange = { newTitleInput = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = appColors.primaryAccent,
                            unfocusedBorderColor = appColors.cardBorder,
                            focusedTextColor = appColors.textPrimary,
                            unfocusedTextColor = appColors.textPrimary,
                            cursorColor = appColors.primaryAccent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_playlist_name_input")
                    )
                }
            },
            confirmButton = {
                com.example.ui.theme.AccentButton(
                    onClick = {
                        val trimmed = newTitleInput.trim()
                        if (trimmed.isNotBlank()) {
                            onRenamePlaylist(trimmed)
                        }
                        showRenameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = appColors.primaryAccent,
                        contentColor = appColors.onPrimaryAccent
                    ),
                    modifier = Modifier.testTag("save_playlist_name_button")
                ) {
                    Text("Save", color = appColors.onPrimaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = appColors.textMuted)
                }
            },
            containerColor = appColors.cardBackground,
            shape = RoundedCornerShape(18.dp)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(appColors.scaffoldBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .landscapeContentBounds(landscape)
                .then(if (landscape) Modifier.statusBarsPadding() else Modifier)
                .testTag("playlist_detail_screen")
        ) {
        if (pinPlaybackActions) CollectionPlaybackButtons(tracks, playback::play,
            onShuffle = { playback.shuffle(tracks) }, download = download,
            trailingAction = trailingPlaybackAction)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .bouncyOverscroll(),
            contentPadding = PaddingValues(bottom = if (landscape) 192.dp else 120.dp)
        ) {
            // PLAYLIST HEADER
            if (landscape) item("landscape_playlist_header") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (showHeaderArtwork) {
                        val artworkModifier = Modifier.size(112.dp).clip(RoundedCornerShape(16.dp))
                            .background(appColors.cardBackgroundElevated).testTag("landscape_playlist_artwork")
                        if (artworkIcon != null) LibraryCollectionArtwork(artworkIcon, artworkModifier, artworkBrush ?: downloadsArtworkBrush())
                        else AsyncImage(rememberPlaylistCoverRequest(playlist.coverUrl, ImageConfig.PLAYLIST_HEADER_SIZE),
                            contentDescription = null, contentScale = ContentScale.Crop, modifier = artworkModifier)
                        Spacer(Modifier.width(20.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        CollectionTitleRow(title, MaterialTheme.typography.headlineMedium, "playlist_profile_name") {
                            if (!readOnly) PlaylistTitleActions(
                                onRename = { newTitleInput = playlist.title; showRenameDialog = true },
                                onDelete = { showDeletePlaylistDialog = true }
                            )
                        }
                        if (description.isNotBlank()) Text(description, color = appColors.textSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(trackSummary, color = appColors.primaryAccent, fontSize = 12.sp)
                    }
                }
            } else {
                item("portrait_playlist_header") {
                    Column(Modifier.fillMaxWidth().then(if (showHeaderArtwork) Modifier else Modifier.statusBarsPadding())) {
                        if (showHeaderArtwork) Box(
                            Modifier
                                .fillMaxWidth()
                                .background(appColors.scaffoldBackground)
                                .testTag("playlist_profile_header")
                        ) {
                            val artworkModifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                .testTag("playlist_profile_artwork")
                                .headerArtworkEdgeBlend(
                                    topFade = if (blendTopArtworkEdge) 0.10f else 0f,
                                    bottomFade = 0.10f,
                                    topMargin = if (blendTopArtworkEdge) 0.01f else 0f,
                                    bottomMargin = 0.02f
                                )
                            if (artworkIcon != null) LibraryCollectionArtwork(artworkIcon, artworkModifier, artworkBrush ?: downloadsArtworkBrush())
                            else AsyncImage(model = rememberPlaylistCoverRequest(playlist.coverUrl, 1200),
                                contentDescription = title, contentScale = ContentScale.Fit, modifier = artworkModifier)
                    }

                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                        Text(collectionLabel, color = appColors.textMuted, style = MaterialTheme.typography.labelMedium)
                        CollectionTitleRow(title, MaterialTheme.typography.headlineLarge, "playlist_profile_name") {
                            if (!readOnly) PlaylistTitleActions(
                                onRename = { newTitleInput = playlist.title; showRenameDialog = true },
                                onDelete = { showDeletePlaylistDialog = true }
                            )
                        }
                        if (description.isNotBlank()) {
                            Text(
                                text = description,
                                color = appColors.textSecondary,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Text(
                            text = trackSummary,
                            color = appColors.primaryAccent,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            }

            if (!pinPlaybackActions) item("playlist_actions") {
                CollectionPlaybackButtons(tracks, playback::play, tagPrefix = "playlist",
                    onShuffle = { playback.shuffle(tracks) }, download = download,
                    trailingAction = trailingPlaybackAction)
            }

            if (tracks.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = emptyTitle,
                            color = appColors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = emptyDescription ?: if (readOnly) "No playable tracks are available." else "Add tracks from the Home or Search screen using the 3-dot menu.",
                            color = appColors.textMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            } else {
                val sourcePlaylistTitle = if (collectionLabel == "PLAYLIST" && !title.equals("Liked Songs", ignoreCase = true)) {
                    playlist.title
                } else null
                itemsIndexed(tracks, key = { index, track -> if (track.playlistEntryId > 0L) "pl_entry_${track.playlistEntryId}" else "pl_track_${track.id}_$index" }) { _, track ->
                    TrackListItem(
                        track = track,
                        isPlaying = track.id == currentPlayingId,
                        onClick = { playback.play(track, tracks) },
                        onToggleFavorite = null,
                        onRemoveClick = null,
                        trailingContent = {
                            TrackOverflowMenuButton(
                                track = track,
                                mode = menuMode,
                                onToggleFavorite = { onToggleFavorite(track) },
                                onRemove = if (menuMode == TrackRowMenuMode.CUSTOM_PLAYLIST || menuMode == TrackRowMenuMode.DOWNLOADS) {
                                    {
                                        if (onRemoveTrackRequest != null) {
                                            onRemoveTrackRequest(track)
                                        } else {
                                            trackPendingRemoval = track
                                        }
                                    }
                                } else null,
                                onTrackInfo = onTrackInfo,
                                sourcePlaylistTitle = sourcePlaylistTitle,
                                downloadStore = downloadStore
                            )
                        },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }
            footer?.let { content -> item(key = "catalog_footer") { content() } }
        }
    }
}
}

@Composable
private fun PlaylistTitleActions(onRename: () -> Unit, onDelete: () -> Unit) {
    val appColors = LocalAppColors.current
    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(38.dp)
            .liquidGlassButton(appColors, shape = CircleShape, elevation = 3.dp)
            .bouncyClickable(onClick = onRename).testTag("edit_playlist_name_button"),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Edit, "Edit Playlist Name", tint = appColors.primaryAccent,
                modifier = Modifier.size(18.dp))
        }
        Box(Modifier.size(38.dp)
            .liquidGlassButton(appColors, shape = CircleShape, elevation = 3.dp)
            .bouncyClickable(onClick = onDelete).testTag("delete_playlist_button"),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.DeleteOutline, "Delete Playlist", tint = appColors.textMuted,
                modifier = Modifier.size(18.dp))
        }
    }
}

/** The same compact controls serve albums, playlists and the built-in collections. */
@Composable
internal fun CollectionPlaybackButtons(tracks: List<MusicTrack>, onPlay: (MusicTrack, List<MusicTrack>) -> Unit,
    tagPrefix: String = "liked_songs", onShuffle: (() -> Unit)? = null,
    download: com.example.ui.components.CollectionDownloadAction? = null,
    trailingAction: (@Composable () -> Unit)? = null) {
    val colors = LocalAppColors.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var preparation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("${tagPrefix}_actions"),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            preparation?.cancel()
            if (onShuffle != null) onShuffle() else preparation = scope.launch {
                val queue = kotlinx.coroutines.withContext(com.example.performance.AppDispatchers.CpuBounded) { tracks.shuffled() }
                queue.firstOrNull()?.let { onPlay(it, queue) }
            }
        }, enabled = tracks.isNotEmpty(), modifier = Modifier.size(CollectionActionSize).testTag("${tagPrefix}_shuffle")) {
            Box(Modifier.size(CollectionActionSurfaceSize).liquidGlassButton(colors, shape = CircleShape, elevation = 3.dp),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (tracks.isNotEmpty()) colors.primaryAccent else colors.textMuted,
                    modifier = Modifier.size(24.dp))
            }
        }
        val playButtonScale = 1.15f * 1.08f
        IconButton(onClick = {
            preparation?.cancel()
            tracks.firstOrNull()?.let { onPlay(it, tracks) }
        }, enabled = tracks.isNotEmpty(), modifier = Modifier.size(CollectionActionSize * playButtonScale).testTag("${tagPrefix}_play")) {
            val fill = if (tracks.isNotEmpty()) colors.accentBrush()
                else Brush.linearGradient(listOf(colors.cardBackgroundElevated, colors.cardBackgroundElevated))
            Box(Modifier.size(CollectionActionSurfaceSize * playButtonScale).clip(CircleShape).background(fill)
                .border(BorderStroke(1.dp, colors.primaryAccent.copy(alpha = .2f)), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "Play all",
                    tint = if (tracks.isNotEmpty()) colors.onAccentGradient else colors.textMuted,
                    modifier = Modifier.size(30.dp * 1.08f))
            }
        }
        if (download != null) CollectionDownloadButton(download)
        trailingAction?.invoke()
    }
}
