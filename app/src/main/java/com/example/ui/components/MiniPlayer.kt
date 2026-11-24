package com.example.ui.components

import com.example.R

import androidx.compose.ui.res.stringResource

import com.example.ui.util.localizedCatalogText

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.example.util.AppHaptics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.playback.PlayerUiState
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.onAccentGradient
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.util.ImageConfig
import com.example.ui.util.TrackPaletteCache
import com.example.ui.util.playerPaletteKey
import com.example.ui.util.playerPreviewPaletteKey
import com.example.ui.util.prepareDecodedPlayerArtworkColors
import com.example.ui.util.rememberOptimizedImageRequest
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun MiniPlayer(
    uiState: PlayerUiState,
    onPlayPauseClick: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onClick: () -> Unit,
    currentPositionProvider: () -> Long = { uiState.currentPositionMs },
    modifier: Modifier = Modifier,
    compactProgress: State<Float>? = null,
    compact: Boolean = false
) {
    val track = uiState.currentTrack ?: return
    val morph = LocalPlayerExpansionMotion.current
    val sampleMotion = remember(compactProgress, morph) {
        { compactProgress?.value; morph?.fraction; Unit }
    }
    val interactive by remember(morph) { derivedStateOf { morph?.miniInteractive != false } }
    val artworkDensity = androidx.compose.ui.platform.LocalDensity.current.density
    var morphArtworkReady by remember(track.id, track.coverUrl) { mutableStateOf(false) }
    var morphArtwork by remember(track.id, track.coverUrl) { mutableStateOf<ImageBitmap?>(null) }

    var totalDrag by remember { mutableFloatStateOf(0f) }

    val playPauseScale by animateFloatAsState(
        targetValue = if (uiState.isPlaying) 1.05f else 1.0f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "play_pause_scale"
    )

    val appColors = LocalAppColors.current
    val context = LocalContext.current
    val lightTheme = !appColors.isDark
    val labelColor = if (lightTheme) Color.Black else Color.White
    val artistColor = if (lightTheme) Color(0xFF111827) else Color(0xFFF3F4F6)
    val neutralButtonColor = if (appColors.isDark) Color.White else Color.Black
    val buttonPill = rememberDockSelectionPill(appColors)
    val buttonContentColor = appColors.onAccentGradient
    val imageRequest = rememberOptimizedImageRequest(track = track, targetSize = ImageConfig.MINI_PLAYER_SIZE)
    val prepareReflection = appColors.pureLiquidGlassUi || LocalDynamicGlassEnabled.current && appColors.dynamicAlbumGlow
    val previewKey = remember(track.id, track.title, track.artist, track.album, track.coverUrl) {
        playerPreviewPaletteKey(track)
    }
    LaunchedEffect(previewKey, morphArtwork, prepareReflection) {
        val bitmap = morphArtwork?.asAndroidBitmap() ?: return@LaunchedEffect
        if (!prepareReflection || TrackPaletteCache.get(playerPaletteKey(track, true, Color.White, Color.Black))?.colorField != null)
            return@LaunchedEffect
        try {
            // The dock already has a bitmap, even if finding verified album art is still in progress.
            prepareDecodedPlayerArtworkColors(previewKey, bitmap, urgent = { true })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { /* The native artwork remains visible if reflection preparation fails. */ }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (!interactive) Modifier.clearAndSetSemantics { } else Modifier)
            .clickable(enabled = interactive) {
                AppHaptics.performTap(context)
                onClick()
            }
            .pointerInput(track.id, interactive) {
                if (!interactive) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        totalDrag += dragAmount
                    },
                    onDragEnd = {
                        if (totalDrag < -60f) {
                            AppHaptics.performTap(context)
                            onSkipNext()
                        } else if (totalDrag > 60f) {
                            AppHaptics.performTap(context)
                            onSkipPrevious()
                        }
                        totalDrag = 0f
                    }
                )
            }
            .testTag("mini_player_container")
    ) {
        // LAYER 1: GLASS BACKGROUND SURFACE ONLY (Colorless Liquid Glass)
        Box(
            modifier = Modifier
                .matchParentSize()
                .playerMorphElement(PlayerMorphSide.MINI, PlayerMorphPart.SURFACE, track.id)
                .xtremeLiquidGlassBackground(
                    shape = CircleShape,
                    floatingDock = true,
                    surfaceTintAlpha = 0f,
                    borderWidth = 0.8.dp,
                    elevation = 14.dp,
                    appColors = appColors,
                    baseFillAlpha = .33f,
                    neutralFinish = true,
                    onSample = sampleMotion
                )
        )

        // LAYER 2: UNBLURRED FOREGROUND CONTENT
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    // Grow the measured height only, including touch-target/font-scale
                    // minimums. Artwork, text, controls and horizontal spacing stay intact.
                    val content = measurable.measure(constraints)
                    val progress = (compactProgress?.value ?: 0f).coerceIn(0f, 1f)
                    val height = (content.height * (1.15f - 0.15f * progress)).roundToInt()
                        .coerceIn(constraints.minHeight, constraints.maxHeight)
                    layout(content.width, height) {
                        content.placeRelative(0, (height - content.height) / 2)
                    }
                }
                .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
                // Album Art - rounded rectangle matching Screenshot 2
                AsyncImage(
                    model = imageRequest,
                    contentDescription = stringResource(R.string.player_artwork),
                    onSuccess = { state ->
                        morphArtwork = (state.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.asImageBitmap()
                        morphArtworkReady = true
                    },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val progress = (compactProgress?.value ?: 0f).coerceIn(0f, 1f)
                            val side = (46.dp.toPx() - 10.dp.toPx() * progress).roundToInt()
                            val image = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(side, side))
                            layout(image.width, image.height) { image.placeRelative(0, 0) }
                        }
                        .size(46.dp)
                        .playerMorphElement(PlayerMorphSide.MINI, PlayerMorphPart.ARTWORK, track.id,
                            ready = morphArtworkReady, image = morphArtwork,
                            radiusPixels = { (12f + 6f * (compactProgress?.value ?: 0f).coerceIn(0f, 1f)) * artworkDensity })
                        .graphicsLayer {
                            val progress = (compactProgress?.value ?: 0f).coerceIn(0f, 1f)
                            shape = RoundedCornerShape((12f + 6f * progress).dp)
                            clip = true
                        }
                        .background(Color(0xFF262A34))
                )

                Spacer(modifier = Modifier.compactWidth(compactProgress, 2f / 3f).width(12.dp))

                // Title & Artist (clean, bold, high contrast, non-overlapping)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = localizedCatalogText(track.fullTitle),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.5.sp,
                                color = labelColor
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.weight(1f, fill = false)
                                .playerMorphElement(PlayerMorphSide.MINI, PlayerMorphPart.TITLE, track.id)
                                .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1000,
                                    animationMode = if (interactive) androidx.compose.foundation.MarqueeAnimationMode.Immediately
                                        else androidx.compose.foundation.MarqueeAnimationMode.WhileFocused)
                                .testTag("mini_player_track_title")
                        )
                        if (uiState.isPlaying) {
                            Spacer(modifier = Modifier.width(6.dp))
                            MiniAnimatedEqualizerBars(
                                modifier = Modifier.playerMorphMiniControls(PlayerMorphPart.EQUALIZER, track.id),
                                active = uiState.isPlaying
                            )
                        }
                    }

                    Spacer(modifier = Modifier.compactHeight(compactProgress).height(2.dp))

                    Text(
                        text = localizedCatalogText(track.artist),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = artistColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .testTag("mini_player_track_artist")
                            .playerMorphElement(PlayerMorphSide.MINI, PlayerMorphPart.ARTIST, track.id)
                            .then(if (compact) Modifier.clearAndSetSemantics { } else Modifier)
                            .compactHeight(compactProgress)
                            .graphicsLayer { alpha = 1f - (compactProgress?.value ?: 0f).coerceIn(0f, 1f) }
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(44.dp).playerMorphMiniControls(PlayerMorphPart.PLAY_PAUSE, track.id)
                ) {
                    if (uiState.isLoading) {
                        VisibleCircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp,
                            color = neutralButtonColor
                        )
                    } else {
                        val isPlaying = uiState.isPlaying
                        Box(Modifier.matchParentSize()
                            .graphicsLayer {
                                scaleX = playPauseScale
                                scaleY = playPauseScale
                            }
                            .dockSelectionPill(buttonPill)) {
                            Surface(
                                enabled = interactive,
                                onClick = {
                                    AppHaptics.performTap(context)
                                    onPlayPauseClick()
                                },
                                shape = CircleShape,
                                color = Color.Transparent,
                                contentColor = buttonContentColor,
                                modifier = Modifier.fillMaxSize().testTag("mini_player_play_pause")
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.compactWidth(compactProgress).width(6.dp))

                Box(
                    Modifier
                        .playerMorphMiniControls(PlayerMorphPart.NEXT, track.id)
                        .then(if (compact) Modifier.clearAndSetSemantics { } else Modifier)
                        .compactWidth(compactProgress)
                        .graphicsLayer { alpha = 1f - (compactProgress?.value ?: 0f).coerceIn(0f, 1f) }
                        .size(38.dp)
                        .dockSelectionPill(buttonPill)
                ) {
                    Surface(
                        onClick = {
                            AppHaptics.performTap(context)
                            onSkipNext()
                        },
                        enabled = !compact && interactive,
                        shape = CircleShape,
                        color = Color.Transparent,
                        contentColor = buttonContentColor,
                        modifier = Modifier.fillMaxSize().testTag("mini_player_next")
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Skip Next",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }

/** Only layout/layers observe the dock spring; artwork requests and text stay composed. */
private fun Modifier.compactWidth(progress: State<Float>?, retainedFraction: Float = 0f): Modifier =
    if (progress == null) this else drawWithContent {
        if (progress.value > 0f) clipRect { this@drawWithContent.drawContent() } else drawContent()
    }.layout { measurable, constraints ->
        val child = measurable.measure(constraints)
        val fraction = 1f - (1f - retainedFraction) * progress.value.coerceIn(0f, 1f)
        layout((child.width * fraction).roundToInt(), child.height) { child.placeRelative(0, 0) }
    }

private fun Modifier.compactHeight(progress: State<Float>?): Modifier =
    if (progress == null) this else drawWithContent {
        if (progress.value > 0f) clipRect { this@drawWithContent.drawContent() } else drawContent()
    }.layout { measurable, constraints ->
        val child = measurable.measure(constraints)
        layout(child.width, (child.height * (1f - progress.value.coerceIn(0f, 1f))).roundToInt()) {
            child.placeRelative(0, 0)
        }
    }

@Composable
fun MiniAnimatedEqualizerBars(
    modifier: Modifier = Modifier,
    active: Boolean = true
) {
    val fraction1 = rememberVisibleInfiniteFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq_bar_1",
        active = active
    )

    val fraction2 = rememberVisibleInfiniteFloat(
        initialValue = 0.85f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(350),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq_bar_2",
        active = active
    )

    val fraction3 = rememberVisibleInfiniteFloat(
        initialValue = 0.40f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(450),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq_bar_3",
        active = active
    )

    val eqColor = LocalAppColors.current.primaryAccent

    Canvas(
        modifier = modifier
            .size(width = 14.dp, height = 14.dp)
            .graphicsLayer()
    ) {
        val totalH = size.height
        val barW = 2.5.dp.toPx()
        val space = 2.dp.toPx()
        val corner = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())

        val h1 = (totalH * fraction1.value).coerceIn(3f, totalH)
        drawRoundRect(
            color = eqColor,
            topLeft = androidx.compose.ui.geometry.Offset(0f, totalH - h1),
            size = androidx.compose.ui.geometry.Size(barW, h1),
            cornerRadius = corner
        )

        val h2 = (totalH * fraction2.value).coerceIn(3f, totalH)
        drawRoundRect(
            color = eqColor,
            topLeft = androidx.compose.ui.geometry.Offset(barW + space, totalH - h2),
            size = androidx.compose.ui.geometry.Size(barW, h2),
            cornerRadius = corner
        )

        val h3 = (totalH * fraction3.value).coerceIn(3f, totalH)
        drawRoundRect(
            color = eqColor,
            topLeft = androidx.compose.ui.geometry.Offset((barW + space) * 2f, totalH - h3),
            size = androidx.compose.ui.geometry.Size(barW, h3),
            cornerRadius = corner
        )
    }
}
