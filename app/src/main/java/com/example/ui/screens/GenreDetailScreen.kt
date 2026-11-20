package com.example.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.CollectionPlaybackTarget
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogAlbum
import com.example.data.repository.CatalogDestination
import com.example.data.repository.CatalogPlaylist
import com.example.playback.PlayerUiState
import com.example.ui.components.CollectionTargetPlayButton
import com.example.ui.components.TrackListItem
import com.example.ui.components.TrackOverflowMenuButton
import com.example.ui.components.TrackRowMenuMode
import com.example.ui.components.bouncyOverscroll
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.landscapeContentBounds
import com.example.ui.util.rememberOptimizedImageRequest
import com.example.ui.viewmodel.CatalogDetailState

/**
 * Full-screen catalog page for a specific music genre, opened from Track Info or player Credits.
 */
@Composable
fun GenreDetailScreen(
    genreName: String,
    state: CatalogDetailState,
    player: PlayerUiState,
    listState: LazyListState = rememberLazyListState(),
    onRetry: () -> Unit,
    onPlay: (MusicTrack, List<MusicTrack>) -> Unit,
    onFavorite: (MusicTrack) -> Unit,
    onOpenAlbum: (CatalogAlbum) -> Unit = {},
    onOpenPlaylist: (CatalogPlaylist) -> Unit = {}
) {
    val colors = LocalAppColors.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scaffoldBackground)
            .landscapeContentBounds(landscape)
            .statusBarsPadding()
            .testTag("genre_detail_screen")
            .bouncyOverscroll(),
        contentPadding = PaddingValues(bottom = if (landscape) 192.dp else 180.dp)
    ) {
        item("genre_header") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                colors.primaryAccent.copy(alpha = 0.24f),
                                colors.secondaryAccent.copy(alpha = 0.14f),
                                colors.cardBackgroundElevated
                            )
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 22.dp)
            ) {
                Column {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = colors.primaryAccent,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "GENRE",
                        color = colors.textMuted,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = genreName,
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("genre_detail_title")
                    )
                }
            }
        }

        if (state.genrePlaylists.isNotEmpty()) {
            item("genre_playlists") {
                Text(
                    text = "Playlists",
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(state.genrePlaylists, key = { "gp_${it.id}_${it.title}" }) { playlist ->
                        Column(
                            Modifier
                                .width(136.dp)
                                .clickable { onOpenPlaylist(playlist) }
                                .testTag("genre_playlist_${playlist.id}")
                        ) {
                            Box(Modifier.size(136.dp).clip(RoundedCornerShape(18.dp))) {
                                AsyncImage(
                                    model = rememberOptimizedImageRequest(playlist.image, targetSize = 600),
                                    contentDescription = playlist.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                CollectionTargetPlayButton(
                                    playlist.title,
                                    CollectionPlaybackTarget.Catalog(CatalogDestination.Playlist(playlist)),
                                    Modifier.align(Alignment.BottomEnd)
                                )
                            }
                            Text(
                                text = playlist.title,
                                color = colors.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        if (state.genreAlbums.isNotEmpty()) {
            item("genre_albums") {
                Text(
                    text = "Albums",
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(state.genreAlbums, key = { "ga_${it.id}_${it.title}" }) { album ->
                        Column(
                            Modifier
                                .width(136.dp)
                                .clickable { onOpenAlbum(album) }
                                .testTag("genre_album_${album.id}")
                        ) {
                            Box(Modifier.size(136.dp).clip(RoundedCornerShape(18.dp))) {
                                AsyncImage(
                                    model = rememberOptimizedImageRequest(album.image, targetSize = 600),
                                    contentDescription = album.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                CollectionTargetPlayButton(
                                    album.title,
                                    CollectionPlaybackTarget.Catalog(CatalogDestination.Album(album)),
                                    Modifier.align(Alignment.BottomEnd)
                                )
                            }
                            Text(
                                text = album.title,
                                color = colors.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        if (state.tracks.isNotEmpty()) {
            item("genre_songs_heading") {
                Text(
                    text = "Top Songs",
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
            items(state.tracks, key = { "gt_${it.id}" }) { song ->
                TrackListItem(
                    track = song,
                    isPlaying = player.currentTrack?.id == song.id && player.isPlaying,
                    isLoading = player.currentTrack?.id == song.id && player.isLoading,
                    onClick = { onPlay(song, state.tracks) },
                    trailingContent = {
                        TrackOverflowMenuButton(
                            track = song,
                            mode = TrackRowMenuMode.STANDARD,
                            onToggleFavorite = { onFavorite(song) }
                        )
                    },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
        }

        if (state.loading) {
            item("genre_loading") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = colors.primaryAccent)
                }
            }
        } else if (state.error != null) {
            item("genre_error") {
                Text(
                    text = state.error,
                    color = colors.textMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRetry)
                        .padding(20.dp)
                )
            }
        } else if (state.tracks.isEmpty() && state.genreAlbums.isEmpty() && state.genrePlaylists.isEmpty()) {
            item("genre_empty") {
                Text(
                    text = "No songs available for $genreName",
                    color = colors.textMuted,
                    modifier = Modifier.padding(20.dp)
                )
            }
        }
    }
}
