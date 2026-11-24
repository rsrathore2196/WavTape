package com.example.ui.util

import android.graphics.Bitmap
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.ImageLoader
import coil.imageLoader
import coil.size.Precision
import coil.size.Scale
import com.example.data.local.OtherSettingsPreferences
import com.example.data.local.PlayerEffect
import com.example.data.model.MusicTrack
import com.example.data.remote.resolveYouTubePlayerCover
import com.example.data.remote.playerCoverIdentity
import com.example.data.remote.playerCoverLookupIdentity
import com.example.playback.PlayerUiState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.example.performance.AppDispatchers
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.theme.AppThemeColors
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Presentation-only cover; playback/catalog identity and the native shelf image stay intact. */
internal data class ResolvedPlayerCover(val identity: String, val url: String)
// Remember only selected, verified song-cover identities. Image bytes remain owned by Coil.
private val selectedSongCovers = android.util.LruCache<String, ResolvedPlayerCover>(64)
private val recordingCovers = android.util.LruCache<String, ResolvedPlayerCover>(64)
private val currentSelectedCover = MutableStateFlow<ResolvedPlayerCover?>(null)
internal val selectedPlayerCover = currentSelectedCover.asStateFlow()
internal fun isSelectedSongCover(trackId: String?, url: String): Boolean =
    trackId != null && selectedSongCovers.get(trackId)?.url == url

internal fun cachedPlayerCover(track: MusicTrack): ResolvedPlayerCover? = recordingCovers.get(playerCoverIdentity(track))

/** Every successful lookup, including a visible-player retry, updates both players. */
private fun publishPlayerCover(track: MusicTrack, url: String): ResolvedPlayerCover {
    val cover = ResolvedPlayerCover(playerCoverIdentity(track), url)
    selectedSongCovers.put(track.id, cover)
    recordingCovers.put(cover.identity, cover)
    currentSelectedCover.value = cover
    return cover
}
internal fun hasNativeSquareCover(url: String): Boolean {
    val uri = try { java.net.URI(url) } catch (_: Exception) { return false }
    val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
    if (!host.endsWith(".googleusercontent.com") && !host.endsWith(".ggpht.com")) return false
    val width = Regex("(?:=|-)w(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
    val height = Regex("(?:=|-)h(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
    return width != null && width > 0 && width == height || Regex("=s[1-9][0-9]*(?:-|$)").containsMatchIn(url)
}
internal fun playerCoverState(state: PlayerUiState, cover: ResolvedPlayerCover?): PlayerUiState {
    val track = state.currentTrack ?: return state
    val selected = cover?.takeIf { it.identity == playerCoverIdentity(track) } ?: cachedPlayerCover(track) ?: return state
    if (selected.url == track.coverUrl) return state
    return state.copy(currentTrack = track.copy(coverUrl = selected.url))
}

/** Keep full-player detail independent of which layout or transition starts the image request. */
internal fun playerArtworkDecodeSize(context: Context): IntSize {
    val pixels = minOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        .coerceIn(1200, 3000)
    return IntSize(pixels, pixels)
}
private val neutralPlayerBackground = Brush.verticalGradient(listOf(Color(0xff101010), Color(0xff101010)))

/** Only the selected recording is prefetched; no catalog-wide artwork lookups. */
internal fun prepareCurrentPlayerArtwork(context: Context, tracks: Flow<MusicTrack?>, foreground: Flow<Boolean>,
    scope: CoroutineScope,
    onCoverResolved: (ResolvedPlayerCover) -> Unit = {},
    fullPlayerVisible: StateFlow<Boolean> = MutableStateFlow(false),
    coverUrgency: StateFlow<Boolean> = fullPlayerVisible,
    resolveCover: suspend (MusicTrack) -> String? = {
        com.example.data.remote.resolveYouTubePlayerCoverOnDemand(it, coverUrgency)
    },
    loaderFor: (Context) -> ImageLoader = { it.imageLoader }
): Job = scope.launch(AppDispatchers.Io) {
    combine(tracks.distinctUntilChanged { a, b -> a === b || a != null && b != null && playerCoverLookupIdentity(a) == playerCoverLookupIdentity(b) },
        foreground.distinctUntilChanged()) { track, visible -> track to visible }
        .collectLatest { (track, visible) ->
            if (track == null || !visible) return@collectLatest
            try {
                (context.applicationContext as? com.example.XtremeMusicApp)?.awaitDiscoveryIdentity()
                val cover = BackgroundWorkCoordinator.traceWork("Player cover identity") { resolveCover(track) }
                currentCoroutineContext().ensureActive()
                var artworkTrack = cover?.let { track.copy(coverUrl = it) } ?: track
                var usingResolvedCover = cover != null
                fun request(targetTrack: MusicTrack, full: Boolean, strictSquare: Boolean): ImageRequest {
                    val fullSize = playerArtworkDecodeSize(context)
                    val pixels = (64 * context.resources.displayMetrics.density).toInt().coerceIn(64, 384)
                    // Share the full player's source file, but decode only the small
                    // visible target until full preparation is actually admitted.
                    val built = buildOptimizedImageRequest(context, targetTrack.coverUrl, ImageConfig.PLAYER_SCREEN_SIZE,
                        targetTrack, decodeSize = if (full) fullSize else IntSize(pixels, pixels),
                        sourceSize = fullSize, currentTrack = true, requireSquareSongArtwork = strictSquare)
                    return if (full) built else built.newBuilder().setParameter(ARTWORK_METADATA_PARAMETER,
                        checkNotNull(built.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER))
                            .copy(targetSize = ImageConfig.MINI_PLAYER_SIZE), null).build()
                }
                val loader = loaderFor(context)
                val fullInitially = fullPlayerVisible.value
                var result = BackgroundWorkCoordinator.traceWork(if (fullInitially) "Player artwork FULL" else "Player artwork MINI") {
                    loader.execute(request(artworkTrack, fullInitially, strictSquare = usingResolvedCover)) as? SuccessResult
                }
                if (usingResolvedCover) {
                    val bitmap = (result?.drawable as? BitmapDrawable)?.bitmap
                    val width = bitmap?.width ?: result?.drawable?.intrinsicWidth ?: 0
                    val height = bitmap?.height ?: result?.drawable?.intrinsicHeight ?: 0
                    if (result == null || !isSquareArtworkDimensions(width, height)) {
                        com.example.data.remote.rejectSongCover(track, cover!!)
                        // Fall back to prefetching the track's own cover without publishing the failed song cover.
                        if (track.coverUrl.isBlank() || track.coverUrl == cover) return@collectLatest
                        artworkTrack = track
                        usingResolvedCover = false
                        result = BackgroundWorkCoordinator.traceWork(if (fullInitially) "Player artwork FULL" else "Player artwork MINI") {
                            loader.execute(request(artworkTrack, fullInitially, strictSquare = false)) as? SuccessResult
                        } ?: return@collectLatest
                    } else {
                        currentCoroutineContext().ensureActive()
                        onCoverResolved(publishPlayerCover(track, cover!!))
                    }
                } else if (result == null) {
                    return@collectLatest
                }
                val fullRequest = request(artworkTrack, true, strictSquare = usingResolvedCover)
                // The mini player already loaded the provider artwork. Prepare the small
                // reflection from that source before admitting a full-size decode.
                cachePlayerBackdrop(context, artworkTrack, result,
                    fullRequest.takeIf { fullInitially }, urgent = { true })
                if (fullInitially) return@collectLatest

                BackgroundWorkCoordinator.awaitCoreTabsOrUrgent { fullPlayerVisible.value }
                currentCoroutineContext().ensureActive()
                val fullResult = BackgroundWorkCoordinator.traceWork("Player artwork FULL") {
                    loader.execute(fullRequest) as? SuccessResult
                } ?: return@collectLatest
                cachePlayerBackdrop(context, artworkTrack, fullResult, fullRequest, urgent = { true })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A failed prefetch never prevents normal on-demand image loading. */ }
        }
}

/** Pre-warm the next track's full-quality artwork and palette during idle time so Skip Next is instant. */
internal fun prepareUpcomingPlayerArtwork(
    context: Context,
    upcomingTracks: Flow<MusicTrack?>,
    foreground: Flow<Boolean>,
    scope: CoroutineScope,
    loaderFor: (Context) -> ImageLoader = { it.imageLoader }
): Job = scope.launch(AppDispatchers.Io) {
    combine(
        upcomingTracks.distinctUntilChanged { a, b -> a === b || a != null && b != null && playerCoverLookupIdentity(a) == playerCoverLookupIdentity(b) },
        foreground.distinctUntilChanged()
    ) { track, visible -> track to visible }
        .collectLatest { (track, visible) ->
            if (track == null || !visible || track.coverUrl.isBlank()) return@collectLatest
            try {
                BackgroundWorkCoordinator.optionalProcessing(awaitFrameQuiet = false) {
                    BackgroundWorkCoordinator.awaitOptionalBatch()
                    currentCoroutineContext().ensureActive()
                    val fullSize = playerArtworkDecodeSize(context)
                    val coverUrl = cachedPlayerCover(track)?.url ?: track.coverUrl
                    val artworkTrack = if (coverUrl == track.coverUrl) track else track.copy(coverUrl = coverUrl)
                    val fullRequest = buildOptimizedImageRequest(
                        context, artworkTrack.coverUrl, ImageConfig.PLAYER_SCREEN_SIZE,
                        artworkTrack, decodeSize = fullSize, sourceSize = fullSize,
                        currentTrack = true, requireSquareSongArtwork = coverUrl != track.coverUrl
                    )
                    val loader = loaderFor(context)
                    val fullResult = loader.execute(fullRequest) as? SuccessResult ?: return@optionalProcessing
                    cachePlayerBackdrop(context, artworkTrack, fullResult, fullRequest)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional upcoming prefetch never interrupts playback. */ }
        }
}

private suspend fun cachePlayerBackdrop(context: Context, track: MusicTrack, artwork: SuccessResult,
    fullRequest: ImageRequest?, urgent: () -> Boolean = { false }) {
    if (!OtherSettingsPreferences.isDynamicGlassEnabled(context)) return
    val appearance = OtherSettingsPreferences.playerExperienceSettings(context)
    if (!appearance.dynamicAlbumGlow) return
    val effect = appearance.playerEffect
    fun key(url: String) = playerEffectPaletteKey(playerPaletteKey(track, true, Color.White, Color.Black, url), effect)
    try {
        val selectedKey = key(artwork.request.data.toString())
        val bitmap = (artwork.drawable as? BitmapDrawable)?.bitmap ?: return
        val colors = prepareDecodedPlayerArtworkColors(selectedKey, bitmap, urgent, effect) ?: return
        currentCoroutineContext().ensureActive()
        // An early sample supplies a fallback until the actual full-size source is known.
        for (alias in setOfNotNull(key(track.coverUrl), fullRequest?.let { key(it.data.toString()) })) {
            if (alias != selectedKey && TrackPaletteCache.get(alias) !== colors) TrackPaletteCache.put(alias, colors)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { /* Reflection preparation must not delay the cover load after a failure. */ }
}

/** Keep the neutral player canvas and typography while retaining the selected accent pair. */
internal fun playerAppearanceColors(source: AppThemeColors): AppThemeColors = source.copy(
    isDark = true, isAmoled = false, neutralSurfaces = false, settingsSurface = false,
    screenBackground = neutralPlayerBackground,
    scaffoldBackground = Color(0xff101010), cardBackground = Color(0xff181818),
    cardBackgroundElevated = Color(0xff202020), cardBorder = Color.White.copy(alpha = 0.22f),
    textPrimary = Color.White, textSecondary = Color.White.copy(alpha = 0.75f),
    textMuted = Color.White.copy(alpha = 0.55f),
    inputBackground = Color(0xff202020), chipBackground = Color(0xff202020),
    chipBorder = Color.White.copy(alpha = 0.22f), dividerColor = Color.White.copy(alpha = 0.15f)
)

/** Legacy theme arguments are retained for callers; the palette itself is mode independent. */
@Suppress("UNUSED_PARAMETER")
fun playerPaletteKey(track: MusicTrack, dark: Boolean, accent: Color, base: Color,
    artworkIdentity: String = track.coverUrl): String =
    "album-bg-colors-v10|${track.id}|${track.title}|${track.artist}|${track.album}|$artworkIdentity"

/** The dock's artwork can supply a reflection while the player verifies the definitive cover. */
internal fun playerPreviewPaletteKey(track: MusicTrack): String =
    playerPaletteKey(track, true, Color.White, Color.Black) + "|dock-preview"

// Retain existing Default entries and keep the different Legacy textures in their own cache entries.
internal fun playerEffectPaletteKey(key: String, effect: PlayerEffect): String =
    if (effect == PlayerEffect.LEGACY) "legacy:$key" else key

private val playerPaletteLocks = Array(8) { Mutex() }

/** Prefetch and the visible player share one decode/analysis, including after navigation waits. */
internal suspend fun preparePlayerArtworkColors(
    key: String,
    urgent: () -> Boolean = { false },
    prepare: suspend () -> ExtractedTrackColors?
): ExtractedTrackColors? {
    fun prepared() = TrackPaletteCache.get(key)?.takeIf { it.hasBackdrop }
    prepared()?.let { return it }
    BackgroundWorkCoordinator.awaitNavigationIdle(requireForeground = true, bypass = urgent)
    return playerPaletteLocks[(key.hashCode() and Int.MAX_VALUE) % playerPaletteLocks.size].withLock {
        prepared() ?: prepare()?.also {
            currentCoroutineContext().ensureActive()
            TrackPaletteCache.put(key, it)
        }
    }
}

/** Analyze a bounded sample of the decoded cover without another Coil request. */
internal suspend fun prepareDecodedPlayerArtworkColors(key: String, bitmap: Bitmap,
    urgent: () -> Boolean = { false }, effect: PlayerEffect = PlayerEffect.DEFAULT): ExtractedTrackColors? = preparePlayerArtworkColors(key, urgent) {
    withContext(AppDispatchers.CpuBounded) {
        val width = bitmap.width
        val height = bitmap.height
        val side = maxOf(width, height)
        if (width <= 0 || height <= 0) return@withContext null
        val limit = ImageConfig.PALETTE_THUMBNAIL_SIZE
        val scaled = if (side > limit) Bitmap.createScaledBitmap(bitmap,
            (width * limit / side).coerceAtLeast(1), (height * limit / side).coerceAtLeast(1), true) else bitmap
        try {
            // getPixels needs readable software pixels even when Coil drew a hardware bitmap.
            val sample = if (android.os.Build.VERSION.SDK_INT >= 26 && scaled.config == Bitmap.Config.HARDWARE)
                scaled.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null else scaled
            try { extractPlayerArtworkColors(sample, true, effect) }
            finally { if (sample !== scaled) sample.recycle() }
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }
}

/** Show the native cover immediately; resolve a landscape shelf image to song art while visible. */
@Composable
internal fun rememberPlayerArtworkRequest(track: MusicTrack, active: Boolean,
    resolveCover: suspend (MusicTrack) -> String? = ::resolveYouTubePlayerCover,
    retryAttempt: Int = 0
): ImageRequest {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val sourceSize = remember(context, configuration.screenWidthDp, configuration.screenHeightDp, configuration.densityDpi) {
        playerArtworkDecodeSize(context)
    }
    val identity = remember(track) { playerCoverLookupIdentity(track) }
    var resolved by remember(identity) { mutableStateOf(cachedPlayerCover(track)?.url) }
    var resolvedFailed by remember(identity) { mutableStateOf(false) }
    LaunchedEffect(identity, active, sourceSize) {
        if (!active || resolved != null || resolvedFailed || isSelectedSongCover(track.id, track.coverUrl)) return@LaunchedEffect
        // The visible player also owns its lookup. A cancelled/unfinished optional
        // prefetch must not leave a Home video's rectangular thumbnail in the player.
        withContext(AppDispatchers.Io) {
            try {
                val cover = resolveCover(track) ?: return@withContext
                val request = buildOptimizedImageRequest(context, cover, ImageConfig.PLAYER_SCREEN_SIZE,
                    track, decodeSize = sourceSize, currentTrack = true, requireSquareSongArtwork = true)
                val result = context.imageLoader.execute(request) as? SuccessResult ?: return@withContext
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                val width = bitmap?.width ?: result.drawable.intrinsicWidth
                val height = bitmap?.height ?: result.drawable.intrinsicHeight
                if (!isSquareArtworkDimensions(width, height)) return@withContext
                currentCoroutineContext().ensureActive()
                publishPlayerCover(track, cover)
                resolved = cover
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the same-track cover if song artwork is unavailable. */ }
        }
    }
    val activeResolved = resolved?.takeUnless { resolvedFailed }
    val coverUrl = activeResolved ?: track.coverUrl
    val visibility = remember(context, track.id, coverUrl) { ArtworkVisibility(active) }
    SideEffect { visibility.active.value = active }
    return remember(context, track.id, coverUrl, visibility, sourceSize, retryAttempt) {
        val base = buildOptimizedImageRequest(context, coverUrl, ImageConfig.PLAYER_SCREEN_SIZE, track,
            visibility = visibility, currentTrack = true, decodeSize = sourceSize,
            requireSquareSongArtwork = activeResolved != null)
        if (activeResolved != null && track.coverUrl.isNotBlank() && activeResolved != track.coverUrl) {
            base.newBuilder().listener(onError = { _, _ -> resolvedFailed = true }).build()
        } else if (retryAttempt > 0) {
            base.newBuilder().setParameter("com.example.artwork.retry", retryAttempt, null).build()
        } else base
    }
}

/** Reuse the selected provider source at the actual header's bounded pixel size. */
internal fun playerHeaderArtworkRequest(artwork: ImageRequest, targetPixels: Int = 192): ImageRequest = playerArtworkDerivative(artwork, targetPixels)
    .size(targetPixels.coerceAtLeast(1))
    // Small shared-transition targets must never evict the full-size decoded cover.
    .memoryCachePolicy(CachePolicy.READ_ONLY)
    .crossfade(false)
    .build()

/** Sample the exact selected provider URL without another metadata lookup or provider fallback. */
internal fun playerPaletteRequest(artwork: ImageRequest): ImageRequest =
    playerArtworkDerivative(artwork, ImageConfig.PALETTE_THUMBNAIL_SIZE, useSelectedUrl = true)
        .size(ImageConfig.PALETTE_THUMBNAIL_SIZE)
        .scale(Scale.FIT)
        .precision(Precision.EXACT)
        .allowHardware(false)
        .bitmapConfig(Bitmap.Config.ARGB_8888)
        // A small software derivative must not replace the player's full-size decoded cover.
        .memoryCachePolicy(CachePolicy.READ_ONLY)
        .decoderDispatcher(AppDispatchers.Artwork)
        .transformationDispatcher(AppDispatchers.Artwork)
        .crossfade(false)
        .build()

private fun playerArtworkDerivative(artwork: ImageRequest, targetPixels: Int,
    useSelectedUrl: Boolean = false): ImageRequest.Builder = artwork.newBuilder().apply {
    target(null)
    listener(null)
    val metadata = artwork.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)
    val source = artwork.data as? String
    if (metadata != null && !source.isNullOrBlank()) {
        // A successful request may have selected a sharper same-recording fallback.
        // Derivatives must reuse that source instead of resolving the original again.
        setParameter(ARTWORK_METADATA_PARAMETER, metadata.copy(
            providerUrl = source,
            targetSize = targetPixels,
            preserveAspectRatio = useSelectedUrl || metadata.preserveAspectRatio,
            allowVideoFallback = !useSelectedUrl && metadata.allowVideoFallback
        ), null)
    }
}

/** Called on CpuBounded with the small software sample, never a full-size hardware bitmap. */
@Suppress("UNUSED_PARAMETER")
internal fun extractPlayerArtworkColors(bitmap: Bitmap, dark: Boolean,
    effect: PlayerEffect = PlayerEffect.DEFAULT): ExtractedTrackColors? {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return null
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    return extractPlayerArtworkColors(pixels, w, h, effect)
}
