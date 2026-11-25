package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import com.example.playback.AudioEffectsManager
import com.example.playback.AudioSignalPath
import com.example.playback.SignalPathState
import com.example.performance.AppDispatchers
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Only this small subtree observes live audio data; artwork/lyrics never observe animation frames. */
@Composable
fun AudioQualityBadge(trackId: String, @Suppress("UNUSED_PARAMETER") fallbackText: String,
    content: Color, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showPipeline by remember(trackId) { mutableStateOf(false) }
    val pill = remember { PlayerPopupAnchor() }
    var origin by remember(trackId) { mutableStateOf<Rect?>(null) }
    val labels = remember(trackId) {
        AudioSignalPath.state.map { it.badgeText(trackId) }.distinctUntilChanged().flowOn(AppDispatchers.Presentation)
    }
    val label by labels.collectAsStateWithLifecycle(
        initialValue = remember(trackId) { AudioSignalPath.state.value.badgeText(trackId) }
    )
    val shape = RoundedCornerShape(10.dp)
    Surface(color = Color.Transparent, shape = shape,
        modifier = modifier.playerLiquidGlassButton(LocalAppColors.current, shape)
            .onGloballyPositioned(pill::update)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Show audio pipeline") {
                origin = pill.capture()
                showPipeline = true
            }) {
        Row(Modifier.padding(horizontal = if (landscape) 12.dp else 10.dp, vertical = if (landscape) 5.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (landscape) 7.dp else 6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(content))
            Spacer(Modifier.width(if (landscape) 8.dp else 6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall.copy(color = content,
                fontWeight = FontWeight.ExtraBold, fontSize = if (landscape) 12.sp else 10.sp, letterSpacing = 0.5.sp), maxLines = 1)
        }
    }
    if (showPipeline) AudioPipelineDialog(trackId, origin) { showPipeline = false }
}

private fun SignalPathState.badgeText(trackId: String): String = quality
    .takeIf { this.trackId == trackId && it.verified && it.codec.isNotBlank() }
    ?.badgeLabel ?: "Resolving audio"

@Composable
private fun AudioPipelineDialog(trackId: String, origin: Rect?, onDismiss: () -> Unit) {
    val path by AudioSignalPath.state.collectAsStateWithLifecycle()
    val state = remember(trackId, path) { path.takeIf { it.trackId == trackId } ?: SignalPathState() }
    AnchoredPlayerPopupDialog(origin, onDismiss, decorFitsSystemWindows = true, animated = true) { motion, dismiss ->
        SignalPathPopupCard(state, Modifier.padding(16.dp).then(motion), onClose = dismiss)
    }
}

private fun rate(hz: Int): String = if (hz > 0) String.format(Locale.ROOT, "%.1f kHz", hz / 1000.0) else "—"
private fun encoding(value: Int): String = SignalPathState.pcmEncodingLabel(value).takeUnless { it == "Format not reported" } ?: "—"

/** Source, decoder and client output are separate measurements. Unknown hardware data stays unknown. */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun SignalPathPopupCard(state: SignalPathState, modifier: Modifier = Modifier, onClose: () -> Unit = {}) {
    val colors = PlayerGlassPopupColors
    val effects by AudioEffectsManager.effectsState.collectAsStateWithLifecycle()
    val height = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(120).dp
    val textColor = colors.textPrimary
    val brightArtwork = needsPopupForegroundContrast()
    val quality = state.quality
    val known = quality.verified
    val dspKnown = state.dspEncoding != C.ENCODING_INVALID && state.dspRateHz > 0
    val outputKnown = state.outputEncoding != C.ENCODING_INVALID && state.outputRateHz > 0
    val ratesKnown = state.inputRateHz > 0 && state.outputRateHz > 0
    val sameRate = ratesKnown && state.inputRateHz == state.outputRateHz && !state.appResampling
    val directRoute = outputKnown && state.outputRouteVerified && state.directApproved
    val bitExact = state.bitPerfect && directRoute && sameRate && state.decoderEncoding != C.ENCODING_INVALID &&
        !state.sourcePrecisionReduced && !state.dspActive && state.appVolume == 1f
    val bitPerfectMode = when {
        !state.bitPerfectRequested && state.bitPerfectBypassActive -> "Mode off (restoring playback processing)"
        !state.bitPerfectRequested -> "Mode off"
        !state.bitPerfectBypassActive -> "Mode on (waiting for the audio path)"
        else -> "Mode on"
    }
    val bitExactStatus = when {
        state.sourcePrecisionReduced -> "No — source precision reduced"
        bitExact -> "Verified direct PCM • DSP bypassed"
        !outputKnown || !ratesKnown || state.decoderEncoding == C.ENCODING_INVALID -> "Not yet verified"
        state.appVolume != 1f -> "No — digital volume attenuation"
        state.dspActive || state.appResampling -> "No — samples processed"
        !sameRate -> "No — sample rates differ"
        !state.outputRouteVerified -> "Output route unverified"
        directRoute -> "Direct output • bit-exactness unverified"
        state.decoderEncoding != state.outputEncoding -> "PCM format conversion • system path unverified"
        else -> "App bypassed • system mixing not verified"
    }
    val centers = remember { mutableStateMapOf<Int, Offset>() }
    val bodyCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val stageBadgeSize = 28.dp
    // Reading progress only in Canvas avoids re-composing or re-measuring the rows each frame.
    val pulse = rememberVisibleInfiniteFloat(0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), "pipeline_signal",
        active = state.playbackStatus == "Playing")
    val flowAlpha = animateFloatAsState(if (state.playbackStatus == "Playing") 1f else 0f,
        tween(if (state.playbackStatus == "Playing") 450 else 320), label = "pipeline_power")

    PlayerPopupTheme(colors) {
        val shape = RoundedCornerShape(26.dp)
        Surface(shape = shape, color = playerPopupSurfaceColor(), tonalElevation = 0.dp, shadowElevation = 0.dp,
            modifier = modifier.widthIn(max = 320.dp).fillMaxWidth().heightIn(max = height)
                .testTag("signal_path_popup").playerPopupGlass(shape)) {
            Column {
                Box(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 19.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Audio Pipeline", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center)
                        Text("Live signal path, decoder to output", Modifier.padding(top = 4.dp), fontSize = 13.sp,
                            lineHeight = 17.sp, color = Color.White, textAlign = TextAlign.Center)
                    }
                    FilledTonalIconButton(onClick = onClose,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = textColor.copy(alpha = 0.08f), contentColor = textColor),
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(28.dp).testTag("pipeline_close")) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close audio pipeline", tint = Color.White, modifier = Modifier.size(16.dp).playerPopupControlContrast())
                    }
                }
                Box(Modifier.weight(1f, fill = false).heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                    .testTag("audio_pipeline_body")) {
                    Box(Modifier.fillMaxWidth().onGloballyPositioned { bodyCoordinates[0] = it }) {
                        Canvas(Modifier.matchParentSize().graphicsLayer {}) {
                            val points = (0..4).mapNotNull { centers[it] }
                            if (points.size != 5) return@Canvas
                            val radius = stageBadgeSize.toPx() / 2f
                            val start = points.first().y
                            val length = points.last().y - start
                            val progress = pulse.value
                            val power = flowAlpha.value
                            val headY = start + (progress / 0.85f).coerceIn(0f, 1f) * length
                            val fade = (1f - ((progress - 0.85f) / 0.1f).coerceIn(0f, 1f)) * power
                            points.zipWithNext().forEach { (from, to) ->
                                val top = from.y + radius
                                val bottom = to.y - radius
                                if (bottom <= top) return@forEach
                                if (brightArtwork) {
                                    val shadowX = from.x + 1.dp.toPx()
                                    drawLine(Color.Black.copy(alpha = .22f), Offset(shadowX, top),
                                        Offset(shadowX, bottom), 2.dp.toPx())
                                }
                                drawLine(textColor.copy(alpha = (if (brightArtwork) .45f else .2f) + .06f * power), Offset(from.x, top),
                                    Offset(from.x, bottom), 2.dp.toPx())
                                val tail = max(top, headY - 38.dp.toPx())
                                val lead = min(bottom, headY + 8.dp.toPx())
                                if (fade > 0.01f && lead > tail) {
                                    val glow = Brush.verticalGradient(listOf(Color.Transparent, textColor.copy(alpha = 0.8f * fade), Color.Transparent),
                                        headY - 38.dp.toPx(), headY + 8.dp.toPx())
                                    drawLine(glow, Offset(from.x, tail), Offset(from.x, lead), 5.dp.toPx(), StrokeCap.Round, alpha = 0.3f)
                                    drawLine(glow, Offset(from.x, tail), Offset(from.x, lead), 2.dp.toPx(), StrokeCap.Round)
                                }
                            }
                            points.forEach { point ->
                                val activation = (1f - abs(headY - point.y) / 24.dp.toPx()).coerceIn(0f, 1f) * fade
                                drawCircle(Brush.radialGradient(listOf(textColor.copy(alpha = 0.07f + activation * 0.23f), Color.Transparent),
                                    point, radius + 5.dp.toPx()), radius + 5.dp.toPx(), point)
                                if (activation > 0f) drawCircle(textColor.copy(alpha = activation * 0.55f),
                                    radius, point, style = Stroke(1.dp.toPx()))
                            }
                        }
                        Column(Modifier.fillMaxWidth()) {
                            @Composable fun stage(index: Int, title: String, icon: ImageVector, rows: List<Pair<String, String>>) {
                                HorizontalDivider(Modifier.padding(start = 50.dp, end = 16.dp).playerPopupControlContrast(translucent = true), color = colors.dividerColor.copy(alpha = 0.6f))
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 18.dp, bottom = 22.dp)) {
                                    Box(
                                        modifier = Modifier.size(stageBadgeSize)
                                            .playerPopupInsetGlass(CircleShape)
                                            .testTag("pipeline_stage_icon_$index")
                                            .onGloballyPositioned { coordinates ->
                                                bodyCoordinates[0]?.takeIf { it.isAttached && coordinates.isAttached }?.let { root ->
                                                    val center = Offset(coordinates.size.width / 2f, coordinates.size.height / 2f)
                                                    val position = root.localPositionOf(coordinates, center)
                                                    if (centers[index] != position) centers[index] = position
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp).playerPopupControlContrast())
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.4.sp, modifier = Modifier.padding(bottom = 8.dp).testTag("pipeline_stage_$index"))
                                        rows.forEach { (label, value) ->
                                            Text(buildAnnotatedString {
                                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$label: ") }
                                                append(value)
                                            }, color = Color.White, fontSize = 13.sp, lineHeight = 19.sp,
                                                modifier = Modifier.padding(vertical = 1.dp).testTag("pipeline_${index}_$label"))
                                        }
                                    }
                                }
                            }
                            stage(0, "TRACK INFO", Icons.AutoMirrored.Rounded.InsertDriveFile, listOf(
                                "Source" to state.source.takeIf { known }.orEmpty().ifBlank { "—" },
                                "Format" to quality.codec.takeIf { known && it.isNotBlank() }.orEmpty().ifBlank { "—" },
                                "Bit Depth" to if (known && quality.lossless && quality.bitDepth > 0) "${quality.bitDepth}-bit" else "—",
                                "Sample Rate" to rate(quality.sampleRateHz.takeIf { known } ?: 0),
                                "Bitrate" to quality.bitrateKbps.takeIf { known && it > 0 }?.let { "$it kbps" }.orEmpty().ifBlank { "—" },
                                "PCM Data Rate" to state.sourcePcmDataRateKbps?.let { "$it kbps" }.orEmpty().ifBlank { "—" },
                                "Channels" to SignalPathState.channelLabel(state.sourceChannels.takeIf { known } ?: 0)))
                            stage(1, "DECODER", Icons.Rounded.Memory, listOf(
                                "Decoder Name" to state.decoderName.ifBlank { "—" },
                                "Format" to encoding(state.decoderEncoding),
                                "Sample Rate" to rate(state.inputRateHz),
                                "Channels" to SignalPathState.channelLabel(state.decoderChannels)))
                            stage(2, "RESAMPLER", Icons.Rounded.Tune, listOf(
                                "I/O Rate" to "${rate(state.inputRateHz)} → ${rate(state.outputRateHz)}",
                                "Type" to when {
                                    !ratesKnown -> "—"
                                    state.appResampling -> "Speed / pitch processing"
                                    sameRate -> "None detected in app"
                                    else -> "Sample rates differ"
                                },
                                "Cutoff" to "—", "Quality" to when {
                                    !ratesKnown -> "—"
                                    state.appResampling -> "Processed in app"
                                    bitExact -> "Passthrough"
                                    sameRate -> "Matched at AudioTrack"
                                    else -> "Conversion unverified"
                                },
                                "PCM Conversion" to when {
                                    state.decoderEncoding == C.ENCODING_INVALID || state.outputEncoding == C.ENCODING_INVALID -> "—"
                                    state.decoderEncoding == state.outputEncoding -> "None"
                                    bitExact && state.decoderEncoding == C.ENCODING_PCM_FLOAT &&
                                        state.outputEncoding == C.ENCODING_PCM_24BIT -> "Exact 24-bit packing"
                                    else -> "${encoding(state.decoderEncoding)} → ${encoding(state.outputEncoding)}"
                                }))
                            val buffer = if (state.bufferSizeFrames > 0 && state.outputRateHz > 0)
                                "${state.bufferSizeFrames * 1000L / state.outputRateHz} ms, ${state.bufferSizeFrames} frames" +
                                    if (state.bufferCapacityFrames > 0) " (capacity ${state.bufferCapacityFrames})" else "" else "—"
                            stage(3, "DSP", Icons.Rounded.GraphicEq, listOf(
                                "PCM Format" to encoding(state.dspEncoding), "Sample Rate" to rate(state.dspRateHz),
                                "Gain" to when {
                                    !dspKnown -> "—"
                                    state.eqActive -> String.format(Locale.ROOT, "%.1f dB (configured; automatic headroom)", effects.loudnessGainMb / 100.0)
                                    state.dspActive -> "Automatic headroom (not measured)"
                                    else -> "0.0 dB (bypassed)"
                                },
                                "Measured" to "Not measured",
                                "EQ Preset" to if (!dspKnown) "—" else if (state.eqActive) effects.selectedPreset else "Flat (bypassed)",
                                "Stereo Expand" to when {
                                    !dspKnown || state.decoderChannels <= 0 -> "—"
                                    state.eqActive && !state.spatialActive && state.decoderChannels == 2 ->
                                        "${100 + effects.virtualizerStrength.coerceIn(0, 1000) * 30 / 1000}% (configured)"
                                    else -> "100% (bypassed)"
                                },
                                "Effects" to if (!dspKnown) "—" else listOfNotNull("Equalizer".takeIf { state.eqActive }, "Spatial Audio".takeIf { state.spatialActive },
                                    "Skip Silence".takeIf { state.skipSilenceActive }).joinToString(" + ").ifBlank { "Bypassed" },
                                "Tempo" to if (dspKnown) String.format(Locale.ROOT, "%.2f× • pitch %.2f×", state.playbackSpeed, state.pitch) else "—",
                                "Buffers" to buffer, "Output API" to if (outputKnown) "AudioTrack" else "—",
                                "Bit-exact" to "$bitPerfectMode • $bitExactStatus"))
                            stage(4, "OUTPUT DEVICE", Icons.AutoMirrored.Rounded.VolumeUp, listOf(
                                "Device Name" to if (state.outputRouteVerified) state.outputDevice.ifBlank { "—" } else "—",
                                "Route" to if (state.outputRouteVerified) state.outputType else "—",
                                "Transport" to if (outputKnown) "AudioTrack • ${state.mode}" else "—",
                                "Direct" to when {
                                    !outputKnown -> "—"
                                    directRoute -> "Active (verified direct route)"
                                    !state.outputRouteVerified -> "Not yet verified"
                                    else -> "Not active (${state.mode.lowercase(Locale.ROOT)} path)"
                                },
                                "AudioTrack" to state.outputFormat,
                                "System" to when {
                                    !outputKnown -> "—"
                                    directRoute -> "Direct path • mixer bypassed"
                                    else -> "Mixer / HAL format not exposed by Android"
                                },
                                "Route Verified" to if (state.outputRouteVerified) "Yes" else "Not yet verified",
                                "Buffer" to if (state.buffering) "Buffering" else state.playbackStatus,
                                "Glitches" to if (outputKnown) state.glitches.toString() else "—"))
                        }
                    }
                }
            }
        }
    }
}
