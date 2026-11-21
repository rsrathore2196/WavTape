package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.downloads.OfflineDownloads
import com.example.downloads.observeSavedRecords
import com.example.performance.AppDispatchers
import com.example.ui.components.CollectionActionSize
import com.example.ui.components.CollectionActionSurfaceSize
import com.example.ui.components.TrackRowMenuMode
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.liquidGlassButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DownloadsArtwork = Brush.linearGradient(listOf(Color(0xFF087F8C), Color(0xFF2563EB)))
internal fun downloadsArtworkBrush(): Brush = DownloadsArtwork
private val LikedSongsArtwork = Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFFDB2777)))
internal fun likedSongsArtworkBrush(): Brush = LikedSongsArtwork

@Composable
internal fun LibraryCollectionArtwork(icon: ImageVector, modifier: Modifier, brush: Brush = DownloadsArtwork) {
    Box(modifier.background(brush), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(64.dp).testTag("library_collection_symbol"))
    }
}

/** The saved collection uses the same header, track rows and playback actions as playlists. */
@Composable
fun LibraryDownloadsScreen(
    onBackClick: () -> Unit,
    onPlayTrack: suspend (MusicTrack, List<MusicTrack>) -> Boolean,
    onToggleFavorite: (MusicTrack) -> Unit,
    currentPlayingTrackId: String? = null,
    store: OfflineDownloads = OfflineDownloads.get(LocalContext.current),
    listState: LazyListState = rememberLazyListState(),
    likedIds: Set<String> = emptySet()
) {
    val saved by remember(store) { store.observeSavedRecords() }.collectAsStateWithLifecycle(emptyList())
    var loading by remember(store) { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var deletionCandidates by remember(store) { mutableStateOf<Set<String>?>(null) }
    var deleting by remember(store) { mutableStateOf(false) }
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) {
        try { store.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Saved downloads could not be loaded." }
        finally { loading = false }
    }
    val collection by produceState<PlaylistWithTracks?>(null, saved, likedIds) {
        val offline = try {
            val tracks = withContext(AppDispatchers.CpuBounded) { saved.map { it.track.copy(isLiked = it.track.id in likedIds) } }
            store.localQueue(tracks)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Saved downloads could not be loaded."; emptyList() }
        value = withContext(AppDispatchers.CpuBounded) {
            PlaylistWithTracks(PlaylistEntity(-2L, "Downloads", "Your music, available offline"),
                offline.map(TrackEntity::fromMusicTrack)).also { it.musicTracks }
        }
    }
    fun playSaved(selected: MusicTrack, queue: List<MusicTrack>) {
        scope.launch {
            try {
                if (!onPlayTrack(selected, queue)) {
                    error = "Saved audio is missing. Download this track again."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Saved audio could not be opened. Try downloading it again." }
        }
    }
    deletionCandidates?.let { candidates ->
        AlertDialog(
            onDismissRequest = { deletionCandidates = null },
            title = { Text("Delete downloaded tracks?") },
            text = { Text("Remove ${candidates.size} downloaded ${if (candidates.size == 1) "track" else "tracks"} from this device? Your liked songs and playlists will be kept.") },
            confirmButton = {
                TextButton(onClick = {
                    deletionCandidates = null
                    deleting = true
                    scope.launch {
                        try { store.removeSaved(candidates) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "Downloaded tracks could not be removed. Try again." }
                        finally { deleting = false }
                    }
                }, modifier = Modifier.testTag("downloads_delete_confirm")) { Text("Delete", color = colors.primaryAccent) }
            },
            dismissButton = {
                TextButton(onClick = { deletionCandidates = null }, modifier = Modifier.testTag("downloads_delete_cancel")) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
            containerColor = colors.cardBackground,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary
        )
    }
    Box(Modifier.fillMaxSize().background(colors.scaffoldBackground).testTag("library_downloads_page")) {
        val ready = collection
        if (loading || ready == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            PlaylistDetailScreen(ready, currentPlayingTrackId = currentPlayingTrackId,
                onBackClick = onBackClick, onPlayTrack = ::playSaved,
                onToggleFavorite = { track -> onToggleFavorite((store.records.value[track.id]?.track ?: track).copy(isLiked = track.isLiked)) },
                onDeletePlaylist = {}, onRemoveTrack = {}, readOnly = true, listState = listState,
                showDownloadAction = false, artworkIcon = Icons.Default.Download, artworkBrush = downloadsArtworkBrush(),
                collectionLabel = "OFFLINE COLLECTION", emptyTitle = "No downloads yet",
                emptyDescription = "Download songs from their track menu to listen here without internet.",
                menuMode = TrackRowMenuMode.DOWNLOADS,
                onRemoveTrackRequest = { track -> deletionCandidates = setOf(track.id) },
                downloadStore = store,
                trailingPlaybackAction = {
                    val canDelete = saved.isNotEmpty() && !deleting
                    IconButton(onClick = { deletionCandidates = saved.mapTo(mutableSetOf()) { it.track.id } },
                        enabled = canDelete, modifier = Modifier.size(CollectionActionSize).testTag("downloads_delete")) {
                        Box(Modifier.size(CollectionActionSurfaceSize)
                            .liquidGlassButton(colors, shape = CircleShape, elevation = 3.dp),
                            contentAlignment = Alignment.Center) {
                            if (deleting) CircularProgressIndicator(Modifier.size(22.dp), color = colors.primaryAccent, strokeWidth = 2.dp)
                            else Icon(Icons.Default.DeleteOutline, "Delete downloaded tracks",
                                tint = if (canDelete) colors.primaryAccent else colors.textMuted,
                                modifier = Modifier.size(24.dp))
                        }
                    }
                })
        }
        error?.let { message ->
            Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 160.dp),
                action = { TextButton(onClick = { error = null }) { Text("Dismiss") } }) { Text(message) }
        }
    }
}
