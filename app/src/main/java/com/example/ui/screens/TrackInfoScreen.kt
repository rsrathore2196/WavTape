package com.example.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogAlbum
import com.example.data.repository.CatalogArtist
import com.example.data.repository.ResolvedTrackMetadata
import com.example.data.repository.TrackMetadataResolver
import com.example.ui.components.ArtistPortrait
import com.example.ui.components.bouncyOverscroll
import com.example.ui.components.headerArtworkEdgeBlend
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.rememberOptimizedImageRequest
import kotlinx.coroutines.CancellationException

/**
 * Full-screen catalog-style Track Info page displaying full-bleed merged cover art,
 * track title, round cards for Artist Names, Album Name (real album, never playlist name),
 * Composer (when available), Genre, and additional verified track details.
 */
@Composable
fun TrackInfoScreen(
    track: MusicTrack,
    sourcePlaylistTitle: String? = null,
    listState: LazyListState = rememberLazyListState(),
    onOpenArtist: (CatalogArtist) -> Unit = {},
    onOpenAlbum: (CatalogAlbum) -> Unit = {},
    onOpenAlbumByName: (String, String) -> Unit = { _, _ -> },
    onOpenGenre: (String) -> Unit = {},
    resolveRemoteMetadata: Boolean = true
) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var metadata by remember(track.id, sourcePlaylistTitle) {
        mutableStateOf(TrackMetadataResolver.initial(track, sourcePlaylistTitle))
    }

    LaunchedEffect(track.id, sourcePlaylistTitle, resolveRemoteMetadata) {
        if (!resolveRemoteMetadata) return@LaunchedEffect
        try {
            metadata = TrackMetadataResolver.resolve(context, track, sourcePlaylistTitle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            metadata = metadata.copy(isEnriched = true)
        }
    }

    val summaryLine = remember(metadata.durationMs, metadata.year, metadata.language) {
        buildList {
            if (metadata.durationMs > 0L) add(formatTrackDuration(metadata.durationMs))
            metadata.year?.takeIf { it.isNotBlank() }?.let(::add)
            metadata.language?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" • ")
    }

    val extraRows = remember(metadata) {
        buildList {
            if (metadata.lyricists.isNotEmpty()) add("Lyricist" to metadata.lyricists.joinToString(", "))
            metadata.year?.takeIf { it.isNotBlank() }?.let { add("Release Year" to it) }
            metadata.language?.takeIf { it.isNotBlank() }?.let { add("Language" to it) }
            if (metadata.durationMs > 0L) add("Duration" to formatTrackDuration(metadata.durationMs))
            metadata.label?.takeIf { it.isNotBlank() }?.let { add("Label" to it) }
            metadata.isrc?.takeIf { it.isNotBlank() }?.let { add("ISRC" to it) }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scaffoldBackground)
            .testTag("track_info_screen")
            .bouncyOverscroll(),
        contentPadding = PaddingValues(bottom = if (landscape) 192.dp else 180.dp)
    ) {
        // Full-bleed cover artwork merged seamlessly into the page background
        item("track_info_header") {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.scaffoldBackground)
                        .testTag("track_info_header")
                ) {
                    val artworkModifier = if (landscape) {
                        Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    }
                    AsyncImage(
                        model = rememberOptimizedImageRequest(metadata.coverUrl, targetSize = 1200),
                        contentDescription = metadata.title,
                        contentScale = if (landscape) ContentScale.Crop else ContentScale.Fit,
                        modifier = artworkModifier
                            .testTag("track_info_artwork")
                            .headerArtworkEdgeBlend(
                                topFade = 0.10f,
                                bottomFade = 0.10f,
                                topMargin = 0.01f,
                                bottomMargin = 0.02f
                            )
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "TRACK INFO",
                        color = colors.textMuted,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = metadata.title,
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("track_info_title")
                    )
                    if (summaryLine.isNotBlank()) {
                        Text(
                            text = summaryLine,
                            color = colors.primaryAccent,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (resolveRemoteMetadata && !metadata.isEnriched) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .height(2.dp)
                                .clip(CircleShape),
                            color = colors.primaryAccent,
                            trackColor = colors.cardBorder.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }

        // Section 1: Artist Names (round artist cards, only for this track)
        if (metadata.artists.isNotEmpty()) {
            item("track_info_artists") {
                TrackInfoSectionHeading(
                    title = "Artist Names",
                    tag = "track_info_artists_heading"
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(
                        items = metadata.artists,
                        key = { idx, artist -> "artist_${artist.id}_${artist.name}_$idx" }
                    ) { index, artist ->
                        Column(
                            modifier = Modifier
                                .width(108.dp)
                                .clickable { onOpenArtist(artist) }
                                .testTag("track_info_artist_$index"),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ArtistPortrait(
                                artist = artist,
                                modifier = Modifier
                                    .size(104.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, colors.cardBorder, CircleShape)
                            )
                            Text(
                                text = artist.name,
                                color = colors.textPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // Section 2: Album Name (real album, never playlist name, in matching round card shape)
        metadata.album?.let { album ->
            item("track_info_album") {
                TrackInfoSectionHeading(
                    title = "Album Name",
                    tag = "track_info_album_heading"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Column(
                        modifier = Modifier
                            .width(124.dp)
                            .clickable {
                                if (album.id.isNotBlank()) {
                                    onOpenAlbum(album)
                                } else {
                                    onOpenAlbumByName(album.title, metadata.primaryArtistName)
                                }
                            }
                            .testTag("track_info_album_card"),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(104.dp)
                                .clip(CircleShape)
                                .background(colors.cardBackgroundElevated)
                                .border(1.dp, colors.cardBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            val artUrl = album.image.ifBlank { metadata.coverUrl }
                            if (artUrl.isNotBlank()) {
                                AsyncImage(
                                    model = rememberOptimizedImageRequest(artUrl, targetSize = 512),
                                    contentDescription = album.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Album,
                                    contentDescription = null,
                                    tint = colors.primaryAccent,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                        Text(
                            text = album.title,
                            color = colors.textPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        if (album.year.isNotBlank()) {
                            Text(
                                text = album.year,
                                color = colors.textMuted,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // Section 3: Composer (shown ONLY when composer details are available, round cards)
        if (metadata.composers.isNotEmpty()) {
            item("track_info_composers") {
                TrackInfoSectionHeading(
                    title = "Composer",
                    tag = "track_info_composer_heading"
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(
                        items = metadata.composers,
                        key = { idx, name -> "composer_${name}_$idx" }
                    ) { index, composerName ->
                        RoundMetadataCard(
                            label = composerName,
                            icon = Icons.Default.MusicNote,
                            brush = Brush.linearGradient(
                                listOf(
                                    colors.primaryAccent.copy(alpha = 0.28f),
                                    colors.cardBackgroundElevated
                                )
                            ),
                            iconTint = colors.primaryAccent,
                            modifier = Modifier.testTag("track_info_composer_$index"),
                            onClick = null
                        )
                    }
                }
            }
        }

        // Section 4: Genre (specific genre related to this track, round card, clickable to genre page)
        metadata.genre?.takeIf { it.isNotBlank() }?.let { genreName ->
            item("track_info_genre") {
                TrackInfoSectionHeading(
                    title = "Genre",
                    tag = "track_info_genre_heading"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    RoundMetadataCard(
                        label = genreName,
                        icon = Icons.Default.GraphicEq,
                        brush = Brush.linearGradient(
                            listOf(
                                colors.primaryAccent.copy(alpha = 0.34f),
                                colors.secondaryAccent.copy(alpha = 0.24f)
                            )
                        ),
                        iconTint = colors.textPrimary,
                        modifier = Modifier.testTag("track_info_genre_card"),
                        onClick = { onOpenGenre(genreName) }
                    )
                }
            }
        }

        // Section 5: Additional available track details
        if (extraRows.isNotEmpty()) {
            item("track_info_more") {
                TrackInfoSectionHeading(
                    title = "More Information",
                    tag = "track_info_more_heading"
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.cardBackgroundElevated)
                        .border(BorderStroke(1.dp, colors.cardBorder), RoundedCornerShape(20.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    extraRows.forEachIndexed { idx, (label, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                color = colors.textMuted,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Text(
                                text = value,
                                color = colors.textPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.End,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        if (idx < extraRows.lastIndex) {
                            HorizontalDivider(color = colors.cardBorder.copy(alpha = 0.45f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackInfoSectionHeading(
    title: String,
    tag: String
) {
    val colors = LocalAppColors.current
    Text(
        text = title,
        color = colors.textPrimary,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)
            .testTag(tag)
    )
}

@Composable
private fun RoundMetadataCard(
    label: String,
    icon: ImageVector,
    brush: Brush,
    iconTint: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .width(108.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(brush)
                .border(1.dp, colors.cardBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(36.dp)
            )
        }
        Text(
            text = label,
            color = colors.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

private fun formatTrackDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format("%d:%02d", minutes, seconds)
}
