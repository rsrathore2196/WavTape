package com.example.ui.screens

import com.example.R

import androidx.compose.ui.res.stringResource

import com.example.ui.util.localizedCatalogText

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.ui.util.landscapeContentBounds

import com.example.ui.components.VisibleCircularProgressIndicator
import java.util.Locale
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.example.ui.components.bouncyOverscroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.rememberLiquidGlassPill
import com.example.ui.theme.bouncyClickable
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextMuted
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.example.ui.components.TrackListItem
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.util.ImageConfig
import com.example.ui.util.rememberOptimizedImageRequest
import com.example.data.model.MusicTrack
import com.example.data.model.CollectionPlaybackTarget
import com.example.ui.components.CollectionTargetPlayButton
import com.example.data.remote.MusicDataSource
import com.example.data.repository.ExploreCategory
import com.example.playback.PlayerUiState
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.XtremeBorder
import com.example.ui.theme.XtremeCard
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.theme.XtremePurple
import com.example.ui.theme.XtremeRose
import com.example.ui.viewmodel.SearchUiState

/** History belongs to the focused field rather than the Explore content below it. */
@Composable
private fun RecentSearchList(
    queries: List<String>, onSearch: (String) -> Unit,
    onRemove: (String) -> Unit, onClear: () -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val colors = LocalAppColors.current
    val maxHeight = minOf(300.dp, LocalConfiguration.current.screenHeightDp.dp * 0.4f)
    // Keep vertical boundary drags/flings inside the history list so swiping down
    // does not bubble to the parent Search LazyColumn and dismiss the keyboard.
    val keepScrollInsideHistory = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = Offset(0f, available.y)

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity = Velocity(0f, available.y)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(max = maxHeight)
            .clip(RoundedCornerShape(12.dp)).background(colors.inputBackground)
            .nestedScroll(keepScrollInsideHistory)
            .testTag("recent_searches_list")
    ) {
        item(key = "history_heading") {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.search_recent), Modifier.weight(1f), color = colors.textMuted,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.clear_all), color = colors.textMuted, fontSize = 11.5.sp,
                    modifier = Modifier.clickable(onClick = onClear).padding(8.dp)
                        .testTag("clear_recent_searches_button"))
            }
        }
        items(queries, key = { "query:$it" }) { query ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .clickable { onSearch(query) }.padding(start = 14.dp, end = 6.dp)
                .testTag("recent_search_item_$query"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, contentDescription = null, tint = colors.textMuted,
                    modifier = Modifier.size(18.dp))
                Text(query, Modifier.weight(1f).padding(horizontal = 10.dp),
                    color = colors.textPrimary, fontSize = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Box(Modifier.size(32.dp).clip(CircleShape).clickable { onRemove(query) }
                    .testTag("remove_recent_search_$query"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Clear, "Remove $query", tint = colors.textMuted,
                        modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** A separate genre destination; the parent Search list keeps its own scroll state. */
@Composable
private fun GenreResultsPage(
    state: SearchUiState, modifier: Modifier, playingId: String?, isPlaying: Boolean,
    isLoading: Boolean, pendingTrackId: String?, isShufflingGenre: Boolean,
    onShuffleGenre: () -> Unit,
    onPlay: (MusicTrack, List<MusicTrack>) -> Unit, onFavorite: (MusicTrack) -> Unit,
    onOpenCatalog: (com.example.data.repository.CatalogDestination) -> Unit,
    onOpenAlbumByName: (String) -> Unit
) {
    val colors = LocalAppColors.current
    val list = rememberLazyListState()
    val tracks = state.result.songs
    val playlists = state.result.catalogPlaylists
    val albums = state.result.catalogAlbums
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val cardBg = if (colors.isDark) Color(0xFF18181B) else Color(0xFFFFFFFF)
    val cardBorderColor = if (colors.isDark) Color(0xFF27272A) else Color(0xFFE4E4E7)
    val textPrimaryColor = if (colors.isDark) Color(0xFFFFFFFF) else Color(0xFF09090B)
    val textMutedColor = if (colors.isDark) Color(0xFFA1A1AA) else Color(0xFF71717A)

    LazyColumn(state = list, modifier = modifier.fillMaxSize().background(colors.scaffoldBackground)
        .landscapeContentBounds(landscape, maxWidthDp = null).statusBarsPadding().bouncyOverscroll().testTag("genre_results_page"),
        contentPadding = PaddingValues(bottom = if (landscape) 192.dp else 150.dp)) {
        item("genre_header", contentType = "header") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val title = MaterialTheme.typography.titleLarge
                Text(state.selectedGenre, style = title.copy(fontSize = title.fontSize * 1.2f),
                    fontWeight = FontWeight.Bold, color = colors.textPrimary, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onShuffleGenre,
                    enabled = !isShufflingGenre,
                    modifier = Modifier.testTag("reshuffle_genre_button")
                ) {
                    if (isShufflingGenre) {
                        VisibleCircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.primaryAccent
                        )
                    } else {
                        Icon(Icons.Default.Shuffle, "Shuffle genre", tint = colors.primaryAccent)
                    }
                }
            }
        }
        if (tracks.isNotEmpty()) {
            item("genre_songs_header", contentType = "header") { CatalogSectionTitle("Songs") }
            item("genre_songs_row", contentType = "shelf") {
                val rowState = rememberLazyListState()
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    items(tracks, key = { "genre_song:${it.id}" }, contentType = { "track" }) { track ->
                        ShelfTrackCard(
                            track = track,
                            isPlaying = playingId == track.id && isPlaying,
                            cardBg = cardBg,
                            cardBorderColor = cardBorderColor,
                            textPrimaryColor = textPrimaryColor,
                            textMutedColor = textMutedColor,
                            modifier = Modifier.testTag("track_item_${track.id}"),
                            onClick = { onPlay(track, tracks) }
                        )
                    }
                }
            }
        }
        if (playlists.isNotEmpty()) {
            item("genre_playlists_header", contentType = "header") { CatalogSectionTitle("Playlists") }
            item("genre_playlists_row", contentType = "shelf") {
                val rowState = rememberLazyListState()
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    items(playlists, key = { "genre_playlist:${com.example.data.repository.CatalogDestination.Playlist(it).key}" },
                        contentType = { "catalog_entity" }) { playlist ->
                        ShelfContentCard(
                            title = playlist.title,
                            subtitle = "Playlist",
                            artwork = rememberOptimizedImageRequest(
                                playlist.image,
                                targetSize = ImageConfig.RECOMMENDATION_CARD_SIZE,
                                crossfadeEnabled = false,
                                preferStableArtwork = true
                            ),
                            isPlaying = false,
                            cardBg = cardBg,
                            cardBorderColor = cardBorderColor,
                            textPrimaryColor = textPrimaryColor,
                            textMutedColor = textMutedColor,
                            modifier = Modifier.testTag("playlist_item_${playlist.id}"),
                            playTarget = CollectionPlaybackTarget.Catalog(com.example.data.repository.CatalogDestination.Playlist(playlist)),
                            onClick = { onOpenCatalog(com.example.data.repository.CatalogDestination.Playlist(playlist)) }
                        )
                    }
                }
            }
        }
        val stringAlbums = state.result.albums
        if (albums.isNotEmpty() || stringAlbums.isNotEmpty()) {
            item("genre_albums_header", contentType = "header") {
                Box(Modifier.testTag("genre_albums_section")) { CatalogSectionTitle("Albums") }
            }
            item("genre_albums_row", contentType = "shelf") {
                val rowState = rememberLazyListState()
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    if (albums.isNotEmpty()) {
                        items(albums, key = { "genre_album:${com.example.data.repository.CatalogDestination.Album(it).key}" },
                            contentType = { "catalog_entity" }) { album ->
                            ShelfContentCard(
                                title = album.title,
                                subtitle = album.artists.joinToString { it.name }.ifBlank { album.year.ifBlank { "Album" } },
                                artwork = rememberOptimizedImageRequest(
                                    album.image,
                                    targetSize = ImageConfig.RECOMMENDATION_CARD_SIZE,
                                    crossfadeEnabled = false,
                                    preferStableArtwork = true
                                ),
                                isPlaying = false,
                                cardBg = cardBg,
                                cardBorderColor = cardBorderColor,
                                textPrimaryColor = textPrimaryColor,
                                textMutedColor = textMutedColor,
                                modifier = Modifier.testTag("album_item_${album.id.ifBlank { album.title.lowercase() }}"),
                                playTarget = CollectionPlaybackTarget.Catalog(com.example.data.repository.CatalogDestination.Album(album)),
                                onClick = { onOpenCatalog(com.example.data.repository.CatalogDestination.Album(album)) }
                            )
                        }
                    } else {
                        items(stringAlbums, key = { "genre_album:$it" }, contentType = { "catalog_entity" }) { name ->
                            ShelfContentCard(
                                title = name,
                                subtitle = "Album",
                                artwork = rememberOptimizedImageRequest(
                                    "",
                                    targetSize = ImageConfig.RECOMMENDATION_CARD_SIZE,
                                    crossfadeEnabled = false,
                                    preferStableArtwork = true
                                ),
                                isPlaying = false,
                                cardBg = cardBg,
                                cardBorderColor = cardBorderColor,
                                textPrimaryColor = textPrimaryColor,
                                textMutedColor = textMutedColor,
                                modifier = Modifier.testTag("album_item_${name.lowercase()}"),
                                playTarget = CollectionPlaybackTarget.AlbumName(name),
                                onClick = { onOpenAlbumByName(name) }
                            )
                        }
                    }
                }
            }
        }
        if (state.isSearching) item("genre_loading", contentType = "loading") {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                VisibleCircularProgressIndicator(Modifier.size(24.dp), color = colors.primaryAccent)
            }
        } else if (state.genreError != null) {
            item("genre_error", contentType = "error") {
                Text(state.genreError, color = colors.textMuted, modifier = Modifier.padding(20.dp))
            }
        } else if (tracks.isEmpty() && albums.isEmpty() && playlists.isEmpty() && stringAlbums.isEmpty()) {
            item("genre_empty", contentType = "empty") {
                Text("No results found", color = colors.textMuted, modifier = Modifier.padding(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    searchState: SearchUiState,
    playerUiState: PlayerUiState? = null,
    currentPlayingTrackId: String? = null,
    isPlaying: Boolean = playerUiState?.isPlaying == true,
    isLoading: Boolean = playerUiState?.isLoading == true,
    onQueryChange: (String) -> Unit,
    onSelectGenre: (String) -> Unit,
    onSelectSource: (String) -> Unit = {},
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier,
    isScreenActive: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
    catalogState: com.example.ui.viewmodel.CatalogSearchState = com.example.ui.viewmodel.CatalogSearchState(),
    onSelectType: (String) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onArchiveMore: () -> Unit = {},
    onRetrySearch: () -> Unit = {},
    onOpenCatalog: (com.example.data.repository.CatalogDestination) -> Unit = {},
    onOpenAlbumByName: (String) -> Unit = {},
    onRememberSearch: (String) -> Unit = {},
    onRemoveRecentSearch: (String) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
    onExploreVisibilityChanged: (Boolean) -> Unit = {},
    onSelectExploreCategory: ((ExploreCategory, Boolean) -> Unit)? = null,
    onShuffleGenre: (() -> Unit)? = null,
    isShufflingGenre: Boolean = false
) {
    val currentPlayingId = currentPlayingTrackId ?: playerUiState?.currentTrack?.id
    val isCurrentlyPlaying = isPlaying || (playerUiState?.isPlaying == true)
    val isCurrentlyLoading = isLoading || (playerUiState?.isLoading == true)
    val hasError = !playerUiState?.errorMessage.isNullOrBlank()
    var pendingLoadingTrackId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentPlayingId, isCurrentlyPlaying, isCurrentlyLoading, hasError) {
        if (pendingLoadingTrackId != null) {
            when {
                hasError -> {
                    pendingLoadingTrackId = null
                }
                currentPlayingId != null && currentPlayingId != pendingLoadingTrackId -> {
                    // Selection changed to a different track or superseded
                    pendingLoadingTrackId = null
                }
                currentPlayingId == pendingLoadingTrackId -> {
                    if (isCurrentlyPlaying || !isCurrentlyLoading) {
                        pendingLoadingTrackId = null
                    }
                }
                !isCurrentlyLoading -> {
                    // Loading finished without transitioning to this track
                    pendingLoadingTrackId = null
                }
            }
        }
    }

    // Safety watchdog: never allow loading indicator to hang for more than 6 seconds
    LaunchedEffect(pendingLoadingTrackId) {
        if (pendingLoadingTrackId != null) {
            kotlinx.coroutines.delay(6000L)
            pendingLoadingTrackId = null
        }
    }

    var queryFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = searchState.query,
                selection = TextRange(searchState.query.length)
            )
        )
    }
    val pendingLocalQueries = remember { ArrayDeque<String>() }
    var lastExternalQuery by remember { mutableStateOf(searchState.query) }
    if (searchState.query != lastExternalQuery) {
        val external = searchState.query
        lastExternalQuery = external
        val pendingIndex = pendingLocalQueries.indexOf(external)
        if (pendingIndex >= 0) {
            repeat(pendingIndex + 1) { pendingLocalQueries.removeFirst() }
        } else if (external != queryFieldValue.text) {
            pendingLocalQueries.clear()
            queryFieldValue = TextFieldValue(
                text = external,
                selection = TextRange(external.length)
            )
        }
    }

    val dispatchTypedQuery: (String) -> Unit = { text ->
        if (pendingLocalQueries.size >= 32) pendingLocalQueries.removeFirst()
        pendingLocalQueries.addLast(text)
        onQueryChange(text)
    }
    val setExplicitQuery: (String) -> Unit = { text ->
        queryFieldValue = TextFieldValue(text = text, selection = TextRange(text.length))
        // Earlier edits can still arrive from the derived ViewModel state.
        // Keep their acknowledgements so they cannot replace this selection.
        if (text != searchState.query || pendingLocalQueries.isNotEmpty()) {
            dispatchTypedQuery(text)
        } else {
            onQueryChange(text)
        }
    }

    val hasQuery = queryFieldValue.text.isNotBlank()
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isGenreActive = searchState.selectedExploreCategory != null || !searchState.selectedGenre.equals("All", ignoreCase = true)
    val exploreVisibilityChanged by rememberUpdatedState(onExploreVisibilityChanged)
    val localeKey = configuration.locales.toLanguageTags()
    LaunchedEffect(isScreenActive, hasQuery, isGenreActive, localeKey) {
        exploreVisibilityChanged(isScreenActive && !hasQuery && !isGenreActive)
    }
    DisposableEffect(Unit) { onDispose { exploreVisibilityChanged(false) } }
    val appColors = LocalAppColors.current
    // These pills tint a surface; they do not use the solid accent button fill.
    val selectedPillContentColor = remember(appColors) {
        val surfaces = if (appColors.dynamicGlassEnabled) {
            listOf(
                Color.White.copy(alpha = if (appColors.isDark) 0.08f else 0.35f).compositeOver(
                    appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.35f else 0.25f)
                        .compositeOver(appColors.scaffoldBackground)),
                Color.White.copy(alpha = if (appColors.isDark) 0.02f else 0.08f).compositeOver(
                    appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.18f else 0.12f)
                        .compositeOver(appColors.scaffoldBackground))
            )
        } else listOf(appColors.primaryAccent.copy(alpha = 0.16f).compositeOver(appColors.cardBackground))
        val whiteContrast = surfaces.minOf { 1.05f / (it.luminance() + 0.05f) }
        val blackContrast = surfaces.minOf { (it.luminance() + 0.05f) / 0.05f }
        if (whiteContrast >= blackContrast) Color.White else Color.Black
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    val exploreRows = remember(searchState.exploreCategories) {
        // A pending restore is not an offline result. Publishing legacy pills
        // here first would replace/reflow the whole list when its index arrives.
        val raw = if (searchState.exploreCategories.isNotEmpty()) searchState.exploreCategories.map { it.title to it }
        else emptyList()
        val cleaned = raw.mapNotNull { (title, cat) ->
            if (com.example.data.remote.YouTubeExploreParser.isExcludedGenre(title)) null
            else {
                val cleanTitle = com.example.data.remote.YouTubeExploreParser.normalizeGenreTitle(title)
                cleanTitle to (cat?.copy(title = cleanTitle) ?: cat)
            }
        }
        orderExplorePills(cleaned).chunked(2)
    }
    var wasScreenActive by remember { mutableStateOf(isScreenActive) }
    LaunchedEffect(isScreenActive) {
        // Retention must preserve the old disposal behavior when leaving Search,
        // without letting an initially hidden page steal another screen's focus.
        if (wasScreenActive && !isScreenActive) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
        wasScreenActive = isScreenActive
    }

    val selectedTypeFilter: String? = catalogState.type.takeUnless { it == "All" }
    // The catalog starts with All. Retain the selected filter on tab return,
    // avoiding a redundant request while the existing transition is running.

    // Gracefully handle back press: Return to default "All" genres page rather than closing the app
    BackHandler(enabled = isScreenActive && (searchFocused || isGenreActive || hasQuery || selectedTypeFilter != null)) {
        val dismissHistory = searchFocused
        keyboardController?.hide()
        focusManager.clearFocus()
        if (dismissHistory) return@BackHandler
        if (isGenreActive) {
            onSelectGenre("All")
        } else if (selectedTypeFilter != null) {
            onSelectType("All")
        } else if (hasQuery) {
            setExplicitQuery("")
        }
    }

    val recentSearchesListState = rememberLazyListState()

    // Automatically close keyboard when user starts scrolling the results list,
    // without dismissing the keyboard while interacting with the recent searches list.
    LaunchedEffect(isScreenActive, listState.isScrollInProgress, recentSearchesListState.isScrollInProgress) {
        if (isScreenActive && listState.isScrollInProgress && !recentSearchesListState.isScrollInProgress) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }

    // When Android Back button/gesture closes the soft keyboard, clear search focus so recent searches hide too.
    if (isScreenActive && searchFocused) {
        ObserveImeCloseToClearFocus {
            focusManager.clearFocus()
        }
    }

    val recentSearches = searchState.recentSearches.take(com.example.data.local.RecentSearchPreferences.MAX_RECENT_SEARCHES)

    val triggerSearch: (String) -> Unit = { queryText ->
        val clean = queryText.trim()
        keyboardController?.hide()
        focusManager.clearFocus()
        if (clean.isNotEmpty()) {
            onRememberSearch(clean)
            setExplicitQuery(clean)
        }
    }

    val context = LocalContext.current
    val voicePrompt = stringResource(R.string.voice_search_prompt)
    val voiceUnavailableText = stringResource(R.string.voice_search_unavailable)
    val voiceSearchLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        extractVoiceSearchQuery(result.resultCode, result.data)?.let(triggerSearch)
    }
    val launchVoiceSearch: () -> Unit = {
        keyboardController?.hide()
        focusManager.clearFocus()
        try {
            val locale = configuration.locales[0] ?: Locale.getDefault()
            voiceSearchLauncher.launch(buildVoiceSearchIntent(voicePrompt, locale))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, voiceUnavailableText, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, voiceUnavailableText, Toast.LENGTH_SHORT).show()
        }
    }

    if (isGenreActive) {
        GenreResultsPage(
            state = searchState, modifier = modifier,
            playingId = currentPlayingId, isPlaying = isCurrentlyPlaying,
            isLoading = isCurrentlyLoading, pendingTrackId = pendingLoadingTrackId,
            isShufflingGenre = isShufflingGenre,
            onShuffleGenre = {
                if (onShuffleGenre != null) {
                    onShuffleGenre()
                } else {
                    val shuffled = com.example.ui.viewmodel.buildShuffledGenreQueue(
                        tracks = searchState.result.songs,
                        currentTrackId = currentPlayingId
                    )
                    if (shuffled.isNotEmpty()) {
                        pendingLoadingTrackId = shuffled.first().id
                        onTrackClick(shuffled.first(), shuffled)
                    }
                }
            },
            onPlay = { track, queue -> pendingLoadingTrackId = track.id; onTrackClick(track, queue) },
            onFavorite = onToggleFavorite, onOpenCatalog = onOpenCatalog, onOpenAlbumByName = onOpenAlbumByName
        )
        return
    }

    com.example.ui.components.TabHeaderScaffold(
        modifier = modifier.fillMaxSize().background(appColors.scaffoldBackground),
        header = {
            com.example.ui.components.AccentPageHeader(stringResource(R.string.search_title),
                stringResource(R.string.search_subtitle), "search_fixed_header")
        }
    ) { headerInsets ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .landscapeContentBounds(landscape, maxWidthDp = null)
                .clipToBounds()
                .bouncyOverscroll()
                .testTag("search_screen"),
            contentPadding = PaddingValues(top = headerInsets.calculateTopPadding(), bottom = if (landscape) 192.dp else 150.dp)
        ) {
        // SEARCH INPUT BAR
        item(key = "search_input", contentType = "search_input") {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp)) {
                OutlinedTextField(
                    value = queryFieldValue,
                    onValueChange = { newValue ->
                        val previousText = queryFieldValue.text
                        if (newValue.text.contains('\n')) {
                            val clean = newValue.text.replace("\n", "").trim()
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            setExplicitQuery(clean)
                        } else {
                            queryFieldValue = newValue
                            if (newValue.text != previousText) {
                                dispatchTypedQuery(newValue.text)
                            }
                        }
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_placeholder),
                            color = appColors.textMuted,
                            fontSize = 14.sp
                        )
                    },
                    trailingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            if (searchState.isSearching || catalogState.loading) {
                                VisibleCircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = appColors.primaryAccent
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            } else if (hasQuery) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            setExplicitQuery("")
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        tint = appColors.textMuted,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }

                            // Icon-only voice search mic button directly in the search bar (no background)
                            IconButton(
                                onClick = launchVoiceSearch,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("voice_search_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = stringResource(R.string.voice_search),
                                    tint = if (appColors.isDark) Color.White.copy(alpha = 0.85f) else appColors.textMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Pill-shaped 3D clear water glass search action button (icon only)
                            val searchButtonShape = CircleShape
                            val isSearchActive = hasQuery

                            val searchButtonBrush = remember(appColors, isSearchActive) {
                                if (!appColors.dynamicGlassEnabled) {
                                    Brush.verticalGradient(listOf(appColors.cardBackground, appColors.cardBackground))
                                } else if (isSearchActive) {
                                    Brush.verticalGradient(
                                        listOf(
                                            appColors.primaryAccent.copy(alpha = 0.32f),
                                            appColors.primaryAccent.copy(alpha = 0.18f)
                                        )
                                    )
                                } else {
                                    LiquidGlass.miniPlayerAndBottomBarBrush(appColors)
                                }
                            }

                            val searchButtonBorder = remember(appColors, isSearchActive) {
                                BorderStroke(
                                    1.2.dp,
                                    if (!appColors.dynamicGlassEnabled) {
                                        Brush.verticalGradient(listOf(appColors.cardBorder, appColors.cardBorder))
                                    } else if (isSearchActive) {
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.White.copy(alpha = 0.40f),
                                                appColors.primaryAccent.copy(alpha = 0.45f)
                                            )
                                        )
                                    } else {
                                        LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (appColors.isDark) 0.38f else 0.45f)
                                    }
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(width = 56.dp, height = 40.dp)
                                    .clip(searchButtonShape)
                                    .background(searchButtonBrush)
                                    .border(searchButtonBorder, searchButtonShape)
                                    .clickable {
                                        val activeText = queryFieldValue.text
                                        if (activeText.isNotBlank()) {
                                            triggerSearch(activeText)
                                        } else {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                        }
                                    }
                                    .testTag("finish_search_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = stringResource(R.string.nav_search),
                                    tint = if (isSearchActive) {
                                        if (appColors.isDark) Color.White else appColors.primaryAccent
                                    } else {
                                        if (appColors.isDark) Color.White.copy(alpha = 0.75f) else appColors.textMuted
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            triggerSearch(queryFieldValue.text)
                        },
                        onDone = {
                            triggerSearch(queryFieldValue.text)
                        },
                        onGo = {
                            triggerSearch(queryFieldValue.text)
                        }
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = appColors.inputBackground,
                        unfocusedContainerColor = appColors.inputBackground,
                        focusedBorderColor = appColors.primaryAccent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = appColors.textPrimary,
                        unfocusedTextColor = appColors.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = if (appColors.isDark) 4.dp else 6.dp,
                            shape = RoundedCornerShape(16.dp),
                            spotColor = if (appColors.isDark) Color.Black.copy(alpha = 0.40f) else appColors.primaryAccent.copy(alpha = 0.10f),
                            ambientColor = if (appColors.isDark) Color.Black.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.05f)
                        )
                        .border(
                            BorderStroke(
                                1.3.dp,
                                LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (appColors.isDark) 0.35f else 0.55f)
                            ),
                            RoundedCornerShape(16.dp)
                        )
                        .onPreInterceptKeyBeforeSoftKeyboard { event ->
                            if (searchFocused &&
                                event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                                event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP
                            ) {
                                focusManager.clearFocus()
                            }
                            false
                        }
                        .onFocusChanged { searchFocused = it.isFocused }
                        .testTag("search_query_input")
                )

                if (isScreenActive && searchFocused && !hasQuery && recentSearches.isNotEmpty()) {
                    RecentSearchList(
                        queries = recentSearches,
                        onSearch = triggerSearch,
                        onRemove = onRemoveRecentSearch,
                        onClear = onClearRecentSearches,
                        listState = recentSearchesListState
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Pill-shaped category filter buttons attached under the search bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_type_filters"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val filterCategories = listOf("All", "Tracks", "Artists", "Albums")
                    filterCategories.forEach { filterName ->
                        val isSelected = catalogState.type == filterName
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .rememberLiquidGlassPill(
                                    colors = appColors,
                                    shape = RoundedCornerShape(20.dp),
                                    isActive = isSelected,
                                    elevation = if (isSelected) 6.dp else 2.dp
                                )
                                .bouncyClickable {
                                    onSelectType(filterName)
                                }
                                .testTag("filter_pill_${filterName.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (filterName) {
                                    "Tracks" -> stringResource(R.string.filter_tracks)
                                    "Artists" -> stringResource(R.string.filter_artists)
                                    "Albums" -> stringResource(R.string.filter_albums)
                                    else -> stringResource(R.string.filter_all)
                                },
                                color = if (isSelected) selectedPillContentColor else (if (appColors.isDark) Color.White else appColors.textPrimary),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                fontSize = 13.5.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2-COLUMN EXPLORE GENRE GRID
        if (!hasQuery) {
            item(key = "search_genres_header", contentType = "header") {
                Text(
                    text = stringResource(R.string.search_explore),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = appColors.textPrimary,
                        fontSize = 18.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp)
                )
            }

            if (exploreRows.isEmpty() && !searchState.exploreIndexReady) {
                items(3, key = { "search_genres_loading_$it" }, contentType = { "genre_grid_row" }) { index ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)
                        .testTag("search_genres_loading_$index"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(2) {
                            Box(Modifier.weight(1f).height(88.dp).clip(RoundedCornerShape(16.dp))
                                .background(appColors.cardBackground))
                        }
                    }
                }
            }

            items(
                items = exploreRows,
                key = { row -> "genre_grid_row_${row.first().second?.key ?: row.first().first}" },
                contentType = { "genre_grid_row" }
            ) { rowItems ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val first = rowItems[0]
                    ExploreGenreCard(
                        genre = first.first,
                        category = first.second,
                        isSelected = if (first.second == null) searchState.selectedGenre.equals(first.first, ignoreCase = true)
                            else searchState.selectedExploreCategory?.key == first.second?.key,
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            if (first.second != null && onSelectExploreCategory != null) onSelectExploreCategory(first.second!!, false)
                            else onSelectGenre(first.first)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (rowItems.size > 1) {
                        val second = rowItems[1]
                        ExploreGenreCard(
                            genre = second.first,
                            category = second.second,
                            isSelected = if (second.second == null) searchState.selectedGenre.equals(second.first, ignoreCase = true)
                                else searchState.selectedExploreCategory?.key == second.second?.key,
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                if (second.second != null && onSelectExploreCategory != null) onSelectExploreCategory(second.second!!, false)
                                else onSelectGenre(second.first)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            item(key = "search_genres_spacing", contentType = "spacing") {
                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        // IF QUERY ACTIVE OR FILTER SELECTED -> SHOW CATEGORIZED RESULTS
        if (hasQuery || searchState.selectedGenre != "All") {
            if (hasQuery) {
                catalogSearchResults(catalogState, currentPlayingId, isCurrentlyPlaying, isCurrentlyLoading,
                    onPlay = { song, queue ->
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        pendingLoadingTrackId = song.id
                        onTrackClick(song, queue)
                    }, onFavorite = onToggleFavorite, onOpen = { destination ->
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        onOpenCatalog(destination)
                    }, onMore = onLoadMore, onRetry = onRetrySearch, onArchiveMore = onArchiveMore)
            } else {
            val results = searchState.result
            val showTracks = selectedTypeFilter == null || selectedTypeFilter == "Tracks"
            val showArtists = selectedTypeFilter == null || selectedTypeFilter == "Artists"
            val showAlbums = selectedTypeFilter == null || selectedTypeFilter == "Albums"
            val showPlaylists = selectedTypeFilter == "Playlists"

            // GENRE BREADCRUMB & RESHUFFLE BAR
            if (isGenreActive) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                    ) {


                        Box(
                            modifier = Modifier
                                .rememberLiquidGlassPill(
                                    colors = appColors,
                                    shape = RoundedCornerShape(20.dp),
                                    isActive = true,
                                    elevation = 4.dp
                                )
                                .bouncyClickable {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    onSelectGenre(searchState.selectedGenre)
                                }
                                .testTag("reshuffle_genre_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Shuffle songs",
                                    tint = selectedPillContentColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Shuffle Songs",
                                    color = selectedPillContentColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // TOP RESULT CARD
            if (showTracks) {
                results.topResult?.let { top ->
                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Text(
                                text = "Top Result",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = appColors.textPrimary,
                                    fontSize = 18.sp
                                )
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            val isTopLoading = (top.id == pendingLoadingTrackId) || (top.id == currentPlayingId && isCurrentlyLoading)
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = appColors.cardBackground),
                                border = BorderStroke(1.dp, appColors.cardBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        pendingLoadingTrackId = top.id
                                        onTrackClick(top, results.songs)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = rememberOptimizedImageRequest(track = top, targetSize = ImageConfig.LIST_ITEM_SIZE),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(68.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )

                                    Spacer(modifier = Modifier.width(14.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = top.title,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = appColors.textPrimary
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Song • ${top.artist}",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = appColors.textSecondary
                                            ),
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (false) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(appColors.primaryAccent.copy(alpha = 0.18f))
                                                        .border(BorderStroke(0.5.dp, appColors.primaryAccent.copy(alpha = 0.5f)), RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = "Hi-Res Lossless",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = appColors.primaryAccent
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Play button
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(CircleShape)
                                            .background(XtremeGradients.ButtonGradient),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isTopLoading) {
                                            VisibleCircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                strokeWidth = 2.5.dp,
                                                color = appColors.onPrimaryAccent
                                            )
                                        } else {
                                            Icon(
                                                imageVector = if (top.id == currentPlayingId && isCurrentlyPlaying) Icons.Default.Equalizer else Icons.Default.PlayArrow,
                                                contentDescription = if (top.id == currentPlayingId && isCurrentlyPlaying) "Playing" else "Play",
                                                tint = appColors.onPrimaryAccent,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // EXACT MATCHES / SONGS MATCHING QUERY (Strictly 4 to 5 options when searching)
            val displayDirectList = searchState.displayDirectTracks ?: run {
                // Retain the standalone/preview overload; production receives prepared VM data.
                val rawList = if (results.exactMatches.isNotEmpty()) results.exactMatches else results.songs
                val directList = when (searchState.selectedSource) {
                    "HD Stream" -> results.jioMatches.ifEmpty { rawList.filter { it.source != "Extended Stream" && !it.id.startsWith("yt_") } }
                    "Extended Stream" -> results.ytMatches.ifEmpty { rawList.filter { it.source == "Extended Stream" || it.id.startsWith("yt_") } }
                    else -> rawList
                }
                if (hasQuery) directList.take(5) else directList
            }

            if (showTracks && displayDirectList.isNotEmpty()) {
                item {
                    val sectionTitle = if (hasQuery) {
                        "Songs Matching \"${searchState.query.trim()}\""
                    } else {
                        "${searchState.selectedGenre} Hits (Shuffled)"
                    }
                    Text(
                        text = sectionTitle,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 18.sp
                        ),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)
                    )
                }

                items(displayDirectList, key = { "direct_${it.id}" }, contentType = { "track" }) { song ->
                    val isTrackLoading = (song.id == pendingLoadingTrackId) || (song.id == currentPlayingId && isCurrentlyLoading)
                    TrackListItem(
                        track = song,
                        isPlaying = song.id == currentPlayingId && isCurrentlyPlaying,
                        isLoading = isTrackLoading,
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            pendingLoadingTrackId = song.id
                            onTrackClick(song, results.songs)
                        },
                        onToggleFavorite = { onToggleFavorite(song) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }

            // RECOMMENDED TRACKS VIA RECOMMENDATION ENGINE (Strictly 5 to 6 songs matching mood, language, genre, vibe and artist)
            if (showTracks && hasQuery && results.recommendedTracks.isNotEmpty()) {
                item(key = "rec_section_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recommended For You",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 18.sp
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = appColors.primaryAccent.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Recommendation Engine",
                                color = appColors.primaryAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                items(results.recommendedTracks.take(6), key = { "rec_${it.id}" }, contentType = { "track" }) { song ->
                    val isTrackLoading = (song.id == pendingLoadingTrackId) || (song.id == currentPlayingId && isCurrentlyLoading)
                    TrackListItem(
                        track = song,
                        isPlaying = song.id == currentPlayingId && isCurrentlyPlaying,
                        isLoading = isTrackLoading,
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            pendingLoadingTrackId = song.id
                            onTrackClick(song, results.songs)
                        },
                        onToggleFavorite = { onToggleFavorite(song) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }

            // SAME SINGER'S OTHER SONGS
            if (showArtists && results.artistSongs.isNotEmpty()) {
                item(key = "artist_songs_header") {
                    val singerTitle = if (results.matchedArtistName.isNotBlank()) {
                        "More by ${results.matchedArtistName}"
                    } else {
                        "Songs by Same Singer"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = singerTitle,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 18.sp
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = XtremePurple.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Same Singer",
                                color = XtremePurple,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                items(results.artistSongs, key = { "artist_${it.id}" }, contentType = { "track" }) { song ->
                    val isTrackLoading = (song.id == pendingLoadingTrackId) || (song.id == currentPlayingId && isCurrentlyLoading)
                    TrackListItem(
                        track = song,
                        isPlaying = song.id == currentPlayingId && isCurrentlyPlaying,
                        isLoading = isTrackLoading,
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            pendingLoadingTrackId = song.id
                            onTrackClick(song, results.songs)
                        },
                        onToggleFavorite = { onToggleFavorite(song) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }

            // SAME TYPE / SAME GENRE SONGS
            if (showTracks && results.similarTypeSongs.isNotEmpty() && !isGenreActive) {
                item(key = "similar_type_header") {
                    val genreTitle = if (results.matchedGenreOrType.isNotBlank()) {
                        "Similar Songs • ${results.matchedGenreOrType} Vibe"
                    } else {
                        "Similar Songs & Same Type"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = genreTitle,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 18.sp
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = XtremeCyan.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Same Vibe",
                                color = XtremeCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                items(results.similarTypeSongs, key = { "sim_${it.id}" }, contentType = { "track" }) { song ->
                    val isTrackLoading = (song.id == pendingLoadingTrackId) || (song.id == currentPlayingId && isCurrentlyLoading)
                    TrackListItem(
                        track = song,
                        isPlaying = song.id == currentPlayingId && isCurrentlyPlaying,
                        isLoading = isTrackLoading,
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            pendingLoadingTrackId = song.id
                            onTrackClick(song, results.songs)
                        },
                        onToggleFavorite = { onToggleFavorite(song) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }

            // ARTISTS & ALBUMS CATEGORIES (Genre view shows strictly Albums only)
            val hasArtistsToShow = showArtists && results.artists.isNotEmpty() && !isGenreActive
            val hasAlbumsToShow = showAlbums && (results.catalogAlbums.isNotEmpty() || results.albums.isNotEmpty())
            if (hasArtistsToShow || hasAlbumsToShow) {
                item {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                            .testTag("genre_albums_section")
                    ) {
                        val headerLabel = if (isGenreActive) {
                            "Albums"
                        } else if (hasArtistsToShow && hasAlbumsToShow) {
                            "Artists & Albums"
                        } else if (hasArtistsToShow) {
                            "Artists"
                        } else {
                            "Albums"
                        }
                        Text(
                            text = headerLabel,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 18.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        if (hasArtistsToShow) {
                            results.artists.forEach { artist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                            onQueryChange(artist)
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF163255)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            tint = XtremeLightBlue,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(text = artist, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                        Text(text = "Artist • Tap to view songs", color = TextMuted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        if (hasAlbumsToShow) {
                            if (results.catalogAlbums.isNotEmpty()) {
                                results.catalogAlbums.forEach { album ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                                onOpenCatalog(com.example.data.repository.CatalogDestination.Album(album))
                                            }
                                            .testTag("album_item_${album.id.ifBlank { album.title.lowercase() }}")
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(Modifier.size(48.dp)) {
                                            if (album.image.isNotBlank()) {
                                                AsyncImage(
                                                    model = album.image,
                                                    contentDescription = album.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(Color(0xFF163255)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Album,
                                                        contentDescription = null,
                                                        tint = XtremeLightBlue,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                            }
                                            CollectionTargetPlayButton(album.title,
                                                CollectionPlaybackTarget.Catalog(com.example.data.repository.CatalogDestination.Album(album)),
                                                Modifier.align(Alignment.BottomEnd), small = true)
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = localizedCatalogText(album.title),
                                                color = TextPrimary,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            val artistStr = album.artists.joinToString { it.name }.takeIf { it.isNotBlank() }
                                            val desc = listOfNotNull(artistStr, album.year.takeIf { it.isNotBlank() }).joinToString(" • ")
                                            Text(
                                                text = if (desc.isNotBlank()) desc else "Album • Tap to view songs",
                                                color = TextMuted,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            } else {
                                results.albums.forEach { albumName ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                                onOpenAlbumByName(albumName)
                                            }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF163255)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Album,
                                                contentDescription = null,
                                                tint = XtremeLightBlue,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            CollectionTargetPlayButton(albumName, CollectionPlaybackTarget.AlbumName(albumName),
                                                Modifier.align(Alignment.BottomEnd), small = true)
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = albumName, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(text = "Album • Tap to view songs", color = TextMuted, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // PLAYLISTS CATEGORY
            if (showPlaylists) {
                val matchingGenres = searchState.matchingPlaylistGenres ?: searchState.exploreCategories.map { it.title }.filter {
                    it.contains(searchState.query, ignoreCase = true)
                }.ifEmpty { searchState.exploreCategories.take(6).map { it.title } }

                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(
                            text = "Playlists",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 18.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        matchingGenres.forEach { genreName ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(appColors.chipBackground)
                                    .border(BorderStroke(1.dp, appColors.chipBorder), RoundedCornerShape(14.dp))
                                    .clickable {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        onSelectGenre(genreName)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(appColors.primaryAccent.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = null,
                                        tint = appColors.primaryAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "$genreName Essentials",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Curated Playlist",
                                        color = TextMuted,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ObserveImeCloseToClearFocus(onImeClosed: () -> Unit) {
    val currentOnImeClosed by rememberUpdatedState(onImeClosed)
    val isImeVisible = WindowInsets.isImeVisible
    var sawImeVisible by remember { mutableStateOf(false) }

    LaunchedEffect(isImeVisible) {
        if (isImeVisible) {
            sawImeVisible = true
        } else if (sawImeVisible) {
            sawImeVisible = false
            currentOnImeClosed()
        }
    }
}

private fun topGenreRank(title: String): Int {
    val clean = title.trim().lowercase()
    return when {
        clean == "hindi" || clean.startsWith("hindi") -> 0
        clean == "punjabi" || clean.startsWith("punjabi") -> 1
        else -> -1
    }
}

private fun bottomGenreRank(title: String): Int {
    val clean = title.trim().lowercase()
    return when {
        clean == "chill" || clean.startsWith("chill") -> 0
        clean == "commute" || clean.startsWith("commute") -> 1
        clean == "diwali" || clean.startsWith("diwali") -> 2
        clean == "energize" || clean.startsWith("energize") -> 3
        clean == "feel good" || clean.startsWith("feel good") -> 4
        clean == "focus" || clean.startsWith("focus") -> 5
        clean == "gaming" || clean.startsWith("gaming") -> 6
        clean == "party" || clean.startsWith("party") -> 7
        clean == "romance" || clean.startsWith("romance") || clean.contains("romantic") -> 8
        clean == "sad" || clean.startsWith("sad") -> 9
        clean == "sleep" || clean.startsWith("sleep") -> 10
        clean == "workout" || clean.startsWith("workout") -> 11
        clean == "african" || clean.startsWith("african") -> 12
        clean == "arabic" || clean.startsWith("arabic") -> 13
        clean == "bengali" || clean.startsWith("bengali") -> 14
        clean == "bhojpuri" || clean.startsWith("bhojpuri") -> 15
        else -> -1
    }
}

private fun <T> orderExplorePills(items: List<Pair<String, T>>): List<Pair<String, T>> {
    val topItems = mutableListOf<Pair<String, T>>()
    val middleItems = mutableListOf<Pair<String, T>>()
    val bottomItems = mutableListOf<Pair<String, T>>()

    for (item in items) {
        val top = topGenreRank(item.first)
        val bottom = bottomGenreRank(item.first)
        if (top != -1) {
            topItems.add(item)
        } else if (bottom != -1) {
            bottomItems.add(item)
        } else {
            middleItems.add(item)
        }
    }

    val sortedTop = topItems.sortedBy { topGenreRank(it.first) }
    val sortedBottom = bottomItems.sortedBy { bottomGenreRank(it.first) }

    return sortedTop + middleItems + sortedBottom
}

internal fun buildVoiceSearchIntent(prompt: String, locale: Locale): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
    }

internal fun extractVoiceSearchQuery(resultCode: Int, data: Intent?): String? {
    if (resultCode != Activity.RESULT_OK) return null
    return data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        ?.firstOrNull { !it.isNullOrBlank() }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

