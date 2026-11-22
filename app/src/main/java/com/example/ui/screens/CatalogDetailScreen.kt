package com.example.ui.screens

import com.example.ui.util.localizedCatalogText

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.ui.util.landscapeContentBounds

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.example.data.model.MusicTrack
import com.example.data.model.CollectionPlaybackTarget
import com.example.ui.components.CollectionTargetPlayButton
import com.example.data.repository.CatalogDestination
import com.example.data.repository.CatalogAlbum
import com.example.data.repository.CatalogArtist
import com.example.data.repository.CatalogPlaylist
import com.example.ui.util.rememberOptimizedImageRequest
import androidx.compose.ui.text.style.TextOverflow
import com.example.playback.PlayerUiState
import com.example.ui.components.ArtistPortrait
import com.example.ui.components.playerArtworkEdgeBlend
import com.example.ui.components.headerArtworkEdgeBlend
import com.example.ui.components.TrackListItem
import com.example.ui.components.TrackOverflowMenuButton
import com.example.ui.components.TrackRowMenuMode
import com.example.ui.components.bouncyOverscroll
import com.example.ui.components.CollectionActionSize
import com.example.ui.components.CollectionActionSurfaceSize
import com.example.ui.components.CollectionTitleRow
import com.example.ui.components.rememberCollectionDownloadAction
import com.example.ui.util.CollectionPageMetadata
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.accentBrush
import com.example.ui.theme.liquidGlassButton
import com.example.ui.theme.onAccentGradient
import com.example.ui.theme.bouncyClickable
import com.example.ui.viewmodel.CatalogDetailState
import kotlinx.coroutines.flow.distinctUntilChanged

/** Shared artist/album destination using the existing track rows, typography and theme. */
@Composable
fun CatalogDetailScreen(state: CatalogDetailState, player: PlayerUiState, onBack: () -> Unit,
    onMore: () -> Unit, onPlay: (MusicTrack, List<MusicTrack>) -> Unit, onFavorite: (MusicTrack) -> Unit,
    onScroll: (Int, Int) -> Unit, handleBack: Boolean = true,
    onOpenAlbum: (CatalogAlbum) -> Unit = {}, onOpenArtist: (CatalogArtist) -> Unit = {},
    onOpenAlbumByName: (String, String) -> Unit = { _, _ -> },
    onOpenGenre: (String) -> Unit = {},
    onOpenPlaylist: (CatalogPlaylist) -> Unit = {},
    onShuffleCollection: (() -> Unit)? = null,
    onPlayArtistRadio: ((CatalogArtist, List<MusicTrack>, Boolean) -> Unit)? = null,
    onOpenArtistYouTube: ((CatalogArtist) -> Unit)? = null,
    downloadStore: com.example.downloads.OfflineDownloads = com.example.downloads.OfflineDownloads.get(androidx.compose.ui.platform.LocalContext.current)) {
    val colors = LocalAppColors.current
    val downloadContext = androidx.compose.ui.platform.LocalContext.current
    val downloadRepository by remember(downloadContext) {
        lazy { (downloadContext.applicationContext as com.example.XtremeMusicApp).repository.catalogBrowser }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val list = rememberLazyListState(state.scrollIndex, state.scrollOffset)
    LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        .distinctUntilChanged().collect { (index, offset) -> onScroll(index, offset) } }
    BackHandler(enabled = handleBack, onBack = onBack)
    if (state.destination is CatalogDestination.TrackInfo) {
        TrackInfoScreen(
            track = state.destination.track,
            sourcePlaylistTitle = state.destination.sourcePlaylistTitle,
            listState = list,
            onOpenArtist = onOpenArtist,
            onOpenAlbum = onOpenAlbum,
            onOpenAlbumByName = onOpenAlbumByName,
            onOpenGenre = onOpenGenre
        )
        return
    }
    if (state.destination is CatalogDestination.Genre) {
        GenreDetailScreen(
            genreName = state.destination.genre,
            state = state,
            player = player,
            listState = list,
            onRetry = onMore,
            onPlay = onPlay,
            onFavorite = onFavorite,
            onOpenAlbum = onOpenAlbum,
            onOpenPlaylist = onOpenPlaylist
        )
        return
    }
    if (state.destination is CatalogDestination.Playlist) {
        val playlist = state.destination.playlist
        val content = state.playlistContent ?: remember(playlist) {
            com.example.data.local.PlaylistWithTracks(
                com.example.data.local.PlaylistEntity(playlistId = playlist.id.toLongOrNull() ?: 0,
                    title = playlist.title, coverUrl = playlist.image),
                emptyList())
        }
        PlaylistDetailScreen(playlistWithTracks = content, playerUiState = player, onBackClick = onBack,
            onPlayTrack = onPlay, onToggleFavorite = onFavorite, onDeletePlaylist = {}, onRemoveTrack = {},
            readOnly = true, listState = list,
            menuMode = TrackRowMenuMode.STANDARD,
            downloadCollectionComplete = !state.loading && state.nextPage == null && state.pending.isEmpty(),
            downloadCollectionKey = state.destination.key,
            downloadStore = downloadStore,
            loadDownloadTracks = { com.example.downloads.loadCollectionTracks(downloadRepository, state.destination) },
            footer = {
                if (state.loading) Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = colors.primaryAccent)
                }
                else if (state.error != null || state.nextPage != null || state.pending.isNotEmpty()) {
                    Text(state.error ?: "See More", color = colors.primaryAccent,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onMore).padding(20.dp))
                }
            })
        return
    }
    val albumDownload = (state.destination as? CatalogDestination.Album)?.let { dest ->
        rememberCollectionDownloadAction(state.tracks, CollectionPageMetadata.title(dest.album.title, "Untitled album"),
            dest.key, !state.loading && state.nextPage == null && state.pending.isEmpty(),
            loadTracks = { com.example.downloads.loadCollectionTracks(downloadRepository, dest) },
            store = downloadStore)
    }
    val title = when (val target = state.destination) {
        is CatalogDestination.Artist -> target.artist.name
        is CatalogDestination.Album -> target.album.title
        is CatalogDestination.Playlist -> target.playlist.title
        is CatalogDestination.TrackInfo -> target.track.cleanTitle
        is CatalogDestination.Genre -> target.genre
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("catalog_detail_list").background(colors.scaffoldBackground)
        .then(if (state.destination is CatalogDestination.Artist || state.destination is CatalogDestination.Album) Modifier
            else Modifier.landscapeContentBounds(landscape).statusBarsPadding()).bouncyOverscroll(),
        contentPadding = PaddingValues(bottom = if (landscape) 192.dp else 180.dp)) {
        item("header") {
            when (val dest = state.destination) {
                is CatalogDestination.Artist -> {
                    ArtistProfileHeader(state.artistProfile?.artist ?: dest.artist)
                }
                is CatalogDestination.Album -> {
                    AlbumProfileHeader(dest.album, state.tracks,
                        !state.loading && state.nextPage == null && state.pending.isEmpty())
                }
                is CatalogDestination.Playlist, is CatalogDestination.TrackInfo, is CatalogDestination.Genre -> Unit
            }
        }
        if (state.destination is CatalogDestination.Artist) item("artist_playback_actions") {
            val resolvedArtist = state.artistProfile?.artist ?: state.destination.artist
            val artistTracks = remember(state.tracks, state.artistProfile) {
                (state.tracks + state.artistProfile?.songs.orEmpty()).distinctBy { it.id }
            }
            ArtistPlaybackButtons(
                artist = resolvedArtist,
                onShuffle = {
                    if (onPlayArtistRadio != null) {
                        onPlayArtistRadio(resolvedArtist, artistTracks, true)
                    } else {
                        val shuffled = artistTracks.shuffled()
                        shuffled.firstOrNull()?.let { onPlay(it, shuffled) }
                    }
                },
                onPlayRadio = {
                    if (onPlayArtistRadio != null) {
                        onPlayArtistRadio(resolvedArtist, artistTracks, false)
                    } else {
                        artistTracks.firstOrNull()?.let { onPlay(it, artistTracks) }
                    }
                },
                onOpenYouTube = {
                    if (onOpenArtistYouTube != null) {
                        onOpenArtistYouTube(resolvedArtist)
                    } else {
                        openArtistOnYouTube(downloadContext, resolvedArtist)
                    }
                }
            )
        }
        if (state.destination is CatalogDestination.Album) item("album_playback_actions") {
            CollectionPlaybackButtons(state.tracks, onPlay, tagPrefix = "album", onShuffle = onShuffleCollection,
                download = albumDownload)
        }
        state.artistProfile?.let { profile ->
            item("artist_stats") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ArtistStatistic("Subscribers", profile.subscriberCount, Modifier.weight(1f))
                    ArtistStatistic(if (profile.monthlyListeners.contains("audience", true)) "Monthly audience" else "Monthly listeners",
                        profile.monthlyListeners, Modifier.weight(1f))
                }
            }
            if (profile.description.isNotBlank()) item("artist_about") {
                var expanded by remember(profile.artist.id) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("About", color = colors.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(profile.description, color = colors.textSecondary, maxLines = if (expanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis)
                    Text(if (expanded) "Show less" else "Read more", color = colors.primaryAccent,
                        modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 8.dp))
                }
            }
            profile.releases.forEachIndexed { index, section -> item("artist_releases_$index") {
                Text(section.title, color = colors.textPrimary, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(section.albums, key = { it.id }, contentType = { "artist_release" }) { album ->
                        Column(Modifier.width(136.dp).clickable { onOpenAlbum(album) }.testTag("artist_release_${album.id}")) {
                            Box(Modifier.size(136.dp).clip(RoundedCornerShape(18.dp))) {
                                AsyncImage(rememberOptimizedImageRequest(album.image, targetSize = 600), album.title,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                CollectionTargetPlayButton(album.title, CollectionPlaybackTarget.Catalog(CatalogDestination.Album(album)),
                                    Modifier.align(Alignment.BottomEnd))
                            }
                            Text(localizedCatalogText(album.title), color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            } }
            if (profile.relatedArtists.isNotEmpty()) item("related_artists") {
                Text("Related Artists", color = colors.textPrimary, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(profile.relatedArtists, key = { it.id }, contentType = { "related_artist" }) { artist ->
                        Column(Modifier.width(112.dp).clickable { onOpenArtist(artist) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            ArtistPortrait(artist, Modifier.size(112.dp).clip(CircleShape))
                            Text(localizedCatalogText(artist.name), color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
        val isAlbumPage = state.destination is CatalogDestination.Album
        items(state.tracks, key = { it.id }, contentType = { "track" }) { song ->
            TrackListItem(
                track = song,
                isPlaying = player.currentTrack?.id == song.id && player.isPlaying,
                isLoading = player.currentTrack?.id == song.id && player.isLoading,
                onClick = { onPlay(song, state.tracks) },
                onToggleFavorite = if (isAlbumPage) null else ({ onFavorite(song) }),
                trailingContent = if (isAlbumPage) {
                    {
                        TrackOverflowMenuButton(
                            track = song,
                            mode = TrackRowMenuMode.STANDARD,
                            onToggleFavorite = { onFavorite(song) },
                            downloadStore = downloadStore
                        )
                    }
                } else null,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }
        if (state.loading) item("loading") { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), color = colors.primaryAccent)
        } }
        else if (state.error != null) item("error") {
            Text(state.error, color = colors.textMuted, modifier = Modifier.fillMaxWidth().clickable(onClick = onMore).padding(20.dp))
        }
        else {
            if (state.tracks.isEmpty()) item("empty") { Text("No songs available", color = colors.textMuted, modifier = Modifier.padding(20.dp)) }
            if (state.nextPage != null || state.pending.isNotEmpty()) item("more") {
                Text("See More", color = colors.primaryAccent, modifier = Modifier.fillMaxWidth().clickable(onClick = onMore).padding(20.dp))
            }
        }
        state.warning?.let { warning -> item("warning") { Text(warning, color = colors.textMuted, modifier = Modifier.padding(20.dp)) } }
    }
}

/** Full-bleed official artist photography; alpha edges reveal the same page canvas.
 * The small Home/related portraits retain their existing size, shape and requests. */
@Composable
private fun ArtistProfileHeader(artist: CatalogArtist) {
    val colors = LocalAppColors.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val height = if (landscape) (configuration.screenHeightDp * .72f).dp.coerceIn(220.dp, 360.dp)
        else (configuration.screenHeightDp * .54f).dp.coerceIn(320.dp, 520.dp)
    Box(Modifier.fillMaxWidth().height(height).testTag("artist_profile_header")) {
        ArtistPortrait(artist, Modifier.matchParentSize().playerArtworkEdgeBlend(true), targetSize = 1200,
            backgroundColor = colors.scaffoldBackground)
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text("ARTIST", color = colors.textMuted, style = MaterialTheme.typography.labelMedium)
            Text(localizedCatalogText(artist.name), color = colors.textPrimary, style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("artist_profile_name"))
        }
    }
}

private val YOUTUBE_CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{10,}$")

internal fun artistYouTubeUrl(artist: CatalogArtist): String {
    val channelId = artist.id.trim()
    return if (YOUTUBE_CHANNEL_ID.matches(channelId)) {
        "https://www.youtube.com/channel/$channelId"
    } else {
        "https://www.youtube.com/results?search_query=${Uri.encode(artist.name.trim())}"
    }
}

internal fun openArtistOnYouTube(context: Context, artist: CatalogArtist) {
    val uri = Uri.parse(artistYouTubeUrl(artist))
    val youtubeIntent = Intent(Intent.ACTION_VIEW, uri)
        .setPackage("com.google.android.youtube")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(youtubeIntent)
    } catch (_: ActivityNotFoundException) {
        val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(fallbackIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Could not open YouTube", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Catalog-matched circular action cluster for Artist pages: Shuffle, Play Radio, and Open on YouTube. */
@Composable
private fun ArtistPlaybackButtons(
    artist: CatalogArtist,
    onShuffle: () -> Unit,
    onPlayRadio: () -> Unit,
    onOpenYouTube: () -> Unit
) {
    val colors = LocalAppColors.current
    val canPlay = artist.name.isNotBlank()
    val canOpenYouTube = artist.id.isNotBlank() || artist.name.isNotBlank()
    val playButtonScale = 1.15f * 1.08f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag("artist_actions"),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onShuffle,
            enabled = canPlay,
            modifier = Modifier
                .size(CollectionActionSize)
                .testTag("artist_shuffle")
        ) {
            Box(
                modifier = Modifier
                    .size(CollectionActionSurfaceSize)
                    .liquidGlassButton(colors, shape = CircleShape, elevation = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = stringResource(R.string.artist_shuffle),
                    tint = if (canPlay) colors.primaryAccent else colors.textMuted,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        IconButton(
            onClick = onPlayRadio,
            enabled = canPlay,
            modifier = Modifier
                .size(CollectionActionSize * playButtonScale)
                .testTag("artist_play")
        ) {
            val fill = if (canPlay) colors.accentBrush()
                else Brush.linearGradient(listOf(colors.cardBackgroundElevated, colors.cardBackgroundElevated))
            Box(
                modifier = Modifier
                    .size(CollectionActionSurfaceSize * playButtonScale)
                    .clip(CircleShape)
                    .background(fill)
                    .border(BorderStroke(1.dp, colors.primaryAccent.copy(alpha = .2f)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.play_radio),
                    tint = if (canPlay) colors.onAccentGradient else colors.textMuted,
                    modifier = Modifier.size(30.dp * 1.08f)
                )
            }
        }
        IconButton(
            onClick = onOpenYouTube,
            enabled = canOpenYouTube,
            modifier = Modifier
                .size(CollectionActionSize)
                .testTag("artist_youtube")
        ) {
            Box(
                modifier = Modifier
                    .size(CollectionActionSurfaceSize)
                    .liquidGlassButton(colors, shape = CircleShape, elevation = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_youtube_logo),
                    contentDescription = stringResource(R.string.artist_open_youtube),
                    tint = if (canOpenYouTube) colors.primaryAccent else colors.textMuted,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun ArtistStatistic(label: String, rawValue: String, modifier: Modifier) {
    val colors = LocalAppColors.current
    // Some API variants return bare counts; others include the unit. The heading
    // always identifies it, and a missing provider value is never invented as zero.
    val value = remember(rawValue) { rawValue.replace(Regex("(?i)\\s*(?:subscribers?|monthly (?:listeners?|audience))\\s*$"), "").trim() }
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(colors.cardBackgroundElevated)
        .border(BorderStroke(1.dp, colors.cardBorder), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = colors.textMuted, style = MaterialTheme.typography.labelMedium)
        Text(value.ifBlank { "Not provided" }, color = colors.textPrimary,
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/** Full-bleed official album artwork; alpha edges reveal the same page canvas. */
@Composable
private fun AlbumProfileHeader(album: CatalogAlbum,
    tracks: List<MusicTrack>, collectionComplete: Boolean) {
    val colors = LocalAppColors.current
    val title = remember(album.title) { CollectionPageMetadata.title(album.title, "Untitled album") }
    val subtitle = remember(album) { CollectionPageMetadata.albumSubtitle(album) }
    val summary = remember(tracks, collectionComplete) { CollectionPageMetadata.trackSummary(tracks, collectionComplete) }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.scaffoldBackground)
                .testTag("album_profile_header")
        ) {
            AsyncImage(
                model = rememberOptimizedImageRequest(album.image, targetSize = 1200),
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .testTag("album_profile_artwork")
                    .headerArtworkEdgeBlend(topFade = 0.10f, bottomFade = 0.10f, topMargin = 0.01f, bottomMargin = 0.02f)
            )

        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text("ALBUM", color = colors.textMuted, style = MaterialTheme.typography.labelMedium)
            CollectionTitleRow(title, MaterialTheme.typography.headlineLarge, "album_profile_name")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(summary, color = colors.primaryAccent, style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
