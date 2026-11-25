package com.example.ui.components

import com.example.ui.util.localizedCatalogText

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import coil.compose.AsyncImage
import com.example.data.model.MusicTrack
import com.example.data.model.queueIdentity
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.util.ImageConfig
import com.example.ui.util.rememberOptimizedImageRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    queue: List<MusicTrack>,
    currentTrack: MusicTrack?,
    onTrackClick: (MusicTrack) -> Unit,
    onMoveTrack: (fromIndex: Int, toIndex: Int) -> Unit,
    onDismiss: () -> Unit,
    isAutoplayEnabled: Boolean = true,
    nextRecommendedTrack: MusicTrack? = null,
    currentQueueIndex: Int = -1,
    onToggleAutoplay: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val latestDismiss by rememberUpdatedState(onDismiss)
    var dismissing by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }

    fun finishDismiss() {
        if (dismissed) return
        dismissed = true
        latestDismiss()
    }
    fun dismissSheet() {
        if (dismissing || dismissed) return
        dismissing = true
        scope.launch {
            try {
                sheetState.hide()
                if (!sheetState.isVisible) finishDismiss()
            } finally {
                dismissing = false
            }
        }
    }

    val displayQueue = remember(queue, currentTrack) {
        if (queue.isEmpty() && currentTrack != null) listOf(currentTrack) else queue
    }
    val currentIdentity = currentTrack?.queueIdentity
    val currentIdx = remember(displayQueue, currentIdentity, currentQueueIndex) {
        if (displayQueue.getOrNull(currentQueueIndex)?.queueIdentity == currentIdentity && currentIdentity != null) {
            currentQueueIndex
        } else displayQueue.indexOfFirst { it.queueIdentity == currentIdentity }
    }
    val appColors = PlayerGlassPopupColors
    val glassEnabled = LocalDynamicGlassEnabled.current
    val presentation = updateTransition(sheetState.targetValue != SheetValue.Hidden, label = "queue_presentation")
    PrioritizePlayerPopupMotion(!sheetState.isVisible || sheetState.currentValue != sheetState.targetValue ||
        dismissing || presentation.isRunning)
    val reveal = presentation.animateFloat(
        transitionSpec = { tween(if (targetState) 220 else 160, easing = FastOutSlowInEasing) },
        label = "queue_glass_reveal"
    ) { if (it) 1f else 0f }

    PlayerPopupTheme(appColors) {
        val sheetContent: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp).playerPopupControlContrast()
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Now Playing & Queue",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                            Text(
                                text = "${displayQueue.size} tracks in queue",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = Color.White
                                )
                            )
                        }
                    }

                    IconButton(
                        onClick = { dismissSheet() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Queue",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp).playerPopupControlContrast()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Infinity Autoplay Banner
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onToggleAutoplay() },
                    color = if (isAutoplayEnabled) {
                        playerPopupHighlight(0.16f)
                    } else if (glassEnabled) {
                        playerPopupHighlight(0.06f)
                    } else {
                        appColors.cardBackgroundElevated
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isAutoplayEnabled) appColors.primaryAccent.copy(alpha = 0.5f)
                        else appColors.cardBorder
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isAutoplayEnabled) appColors.primaryAccent.copy(alpha = 0.22f)
                                        else playerPopupHighlight(0.12f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AllInclusive,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp).playerPopupControlContrast()
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Infinite Autoplay",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = if (isAutoplayEnabled && nextRecommendedTrack != null) {
                                        "Next: ${nextRecommendedTrack.title} • ${nextRecommendedTrack.artist}"
                                    } else if (isAutoplayEnabled) {
                                        "Continuous play using recommendation engine"
                                    } else {
                                        "Queue stops when tracks finish"
                                    },
                                    fontSize = 11.sp,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Switch(
                            checked = isAutoplayEnabled,
                            onCheckedChange = { onToggleAutoplay() },
                            modifier = Modifier.playerPopupControlContrast(translucent = true),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color.White.copy(alpha = 0.36f),
                                checkedBorderColor = Color.Transparent,
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                                uncheckedBorderColor = appColors.cardBorder
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (displayQueue.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(40.dp).playerPopupControlContrast()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Queue is empty",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Play songs or tap 'Add to Queue' to build your list",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = Color.White
                                )
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = displayQueue,
                            key = { index, track -> "${track.id}_$index" },
                            contentType = { _, _ -> "queue_item" }
                        ) { index, track ->
                        val isCurrentlyPlaying = index == currentIdx
                        val isUpNext = !isCurrentlyPlaying && (index == currentIdx + 1 || (currentIdx == -1 && index == 0))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("queue_track_$index")
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isCurrentlyPlaying) {
                                        playerPopupHighlight(0.18f)
                                    } else if (isUpNext) {
                                        playerPopupHighlight(0.08f)
                                    } else if (glassEnabled) {
                                        playerPopupHighlight(0.06f)
                                    } else {
                                        appColors.cardBackgroundElevated
                                    }
                                )
                                .border(
                                    1.dp,
                                    if (isCurrentlyPlaying) appColors.primaryAccent.copy(alpha = 0.55f)
                                    else if (isUpNext) appColors.primaryAccent.copy(alpha = 0.30f)
                                    else appColors.cardBorder,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { onTrackClick(track) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Artwork
                            AsyncImage(
                                model = rememberOptimizedImageRequest(track = track, targetSize = ImageConfig.LIST_ITEM_SIZE),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = localizedCatalogText(track.fullTitle),
                                        color = Color.White,
                                        fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )

                                    if (isCurrentlyPlaying) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = playerPopupHighlight(0.20f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "PLAYING",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isCurrentlyPlaying) {
                                        Icon(
                                            imageVector = Icons.Default.Equalizer,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp).playerPopupControlContrast()
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = localizedCatalogText(track.artist),
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Reorder buttons (Move Up / Move Down)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (index > 0) {
                                    IconButton(
                                        onClick = { onMoveTrack(index, index - 1) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowUpward,
                                            contentDescription = "Move Up",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp).playerPopupControlContrast()
                                        )
                                    }
                                }
                                if (index < displayQueue.lastIndex) {
                                    IconButton(
                                        onClick = { onMoveTrack(index, index + 1) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowDownward,
                                            contentDescription = "Move Down",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp).playerPopupControlContrast()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        val sheetShape = BottomSheetDefaults.ExpandedShape
        ModalBottomSheet(
            onDismissRequest = ::finishDismiss,
            sheetState = sheetState,
            shape = sheetShape,
            containerColor = Color.Transparent,
            scrimColor = Color.Transparent,
            tonalElevation = 0.dp,
            dragHandle = null,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            KeepPlayerPopupWindowClear(disableWindowAnimations = true)
            // Material owns the vertical position and gestures. A separate visual-only reveal
            // needs no layout coordinates or offset from the dialog window.
            Column(Modifier.fillMaxWidth()
                .graphicsLayer {
                    val revealed = reveal.value
                    alpha = (.35f + revealed * 1.3f).coerceAtMost(1f)
                    scaleX = .98f + .02f * revealed
                    transformOrigin = TransformOrigin(.5f, 1f)
                }
                .testTag("queue_bottom_sheet").background(playerPopupSurfaceColor(), sheetShape)
                .playerPopupGlass(sheetShape, onMotion = { reveal.value })
                .windowInsetsPadding(BottomSheetDefaults.windowInsets)) {
                Box(Modifier.align(Alignment.CenterHorizontally).semantics {
                    dismiss("Dismiss queue") {
                        dismissSheet()
                        true
                    }
                }.padding(vertical = 12.dp).width(40.dp).height(4.dp)
                    .playerPopupControlContrast().clip(RoundedCornerShape(2.dp)).background(Color.White))
                sheetContent()
            }
        }
    }
}
