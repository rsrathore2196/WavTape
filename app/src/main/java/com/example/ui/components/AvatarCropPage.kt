package com.example.ui.components

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.data.local.AvatarCropTransform
import com.example.data.local.AvatarImageStore
import com.example.performance.AppDispatchers
import com.example.ui.theme.AccentButton
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** A submit keeps the image alive even if its dialog is disposed while IO is finishing. */
private class CropImage(val bitmap: Bitmap) {
    private val references = AtomicInteger(1)
    fun retain() { check(references.incrementAndGet() > 1) }
    fun release() {
        if (references.decrementAndGet() == 0) cleanup.launch { bitmap.recycle() }
    }
    companion object { private val cleanup = CoroutineScope(SupervisorJob() + AppDispatchers.Io) }
}

/** Gallery input remains a temporary preview until Submit creates an owned avatar draft. */
@Composable
internal fun AvatarCropPage(
    uri: Uri,
    onDismiss: () -> Unit,
    onSubmit: suspend (Bitmap, AvatarCropTransform) -> Unit
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sourceState = remember(uri) { mutableStateOf<CropImage?>(null) }
    var source by sourceState
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var submitting by remember(uri) { mutableStateOf(false) }
    var turns by rememberSaveable(uri.toString()) { mutableIntStateOf(0) }
    var zoom by rememberSaveable(uri.toString()) { mutableFloatStateOf(1f) }
    var panX by rememberSaveable(uri.toString()) { mutableFloatStateOf(0f) }
    var panY by rememberSaveable(uri.toString()) { mutableFloatStateOf(0f) }
    val transform = AvatarCropTransform(turns, zoom, panX, panY)
    val currentTransform by rememberUpdatedState(transform)
    fun update(value: AvatarCropTransform) {
        turns = value.quarterTurns; zoom = value.zoom; panX = value.offsetX; panY = value.offsetY
    }
    LaunchedEffect(uri) {
        var decoded: Bitmap? = null
        var transferred = false
        try {
            val bitmap = withContext(AppDispatchers.Io) {
                AvatarImageStore.readCropSource(context.applicationContext, uri).also { decoded = it }
            }
            source = CropImage(bitmap)
            transferred = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not open this image. Please choose another image." }
        finally { if (!transferred) decoded?.recycle() }
    }
    DisposableEffect(sourceState) {
        onDispose { sourceState.value?.release() }
    }
    val hostWindow = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window
    }
    val hostSafePadding = WindowInsets.safeDrawing.asPaddingValues()
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            dialogWindow?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                ViewCompat.requestApplyInsets(window.decorView)
            }
            onDispose { }
        }
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val dialogSafePadding = WindowInsets.safeDrawing.asPaddingValues()
        val activityInsets = hostWindow?.decorView?.let { decor ->
            ViewCompat.getRootWindowInsets(decor)?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
        }
        val safeStart = maxOf(
            dialogSafePadding.calculateStartPadding(layoutDirection),
            hostSafePadding.calculateStartPadding(layoutDirection),
            with(density) { (activityInsets?.left ?: 0).toDp() }
        )
        val safeTop = maxOf(
            dialogSafePadding.calculateTopPadding(),
            hostSafePadding.calculateTopPadding(),
            with(density) { (activityInsets?.top ?: 0).toDp() }
        )
        val safeEnd = maxOf(
            dialogSafePadding.calculateEndPadding(layoutDirection),
            hostSafePadding.calculateEndPadding(layoutDirection),
            with(density) { (activityInsets?.right ?: 0).toDp() }
        )
        val safeBottom = maxOf(
            dialogSafePadding.calculateBottomPadding(),
            hostSafePadding.calculateBottomPadding(),
            with(density) { (activityInsets?.bottom ?: 0).toDp() }
        )
        Surface(color = colors.scaffoldBackground, modifier = Modifier.fillMaxSize().testTag("avatar_crop_page")) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = safeStart, top = safeTop, end = safeEnd, bottom = safeBottom)
                    .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 28.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Adjust your photo", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, color = colors.textPrimary)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp).clip(CircleShape)
                        .background(colors.cardBackground).testTag("avatar_crop_cancel")) {
                        Icon(Icons.Default.Close, "Cancel crop", tint = colors.textPrimary, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Drag to move · Pinch to zoom", color = colors.textMuted,
                    style = MaterialTheme.typography.bodyMedium)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center) {
                    val edge = minOf(maxWidth, maxHeight)
                    val image = source
                    if (image != null) {
                        val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or
                            android.graphics.Paint.FILTER_BITMAP_FLAG) }
                        Canvas(Modifier.size(edge).clip(RoundedCornerShape(20.dp))
                            .border(BorderStroke(1.dp, colors.cardBorder), RoundedCornerShape(20.dp))
                            .testTag("avatar_crop_viewport")
                            .pointerInput(image, submitting) {
                                if (!submitting) detectTransformGestures { centroid, pan, change, _ ->
                                    if (size.width > 0) update(currentTransform.gesture(image.bitmap.width,
                                        image.bitmap.height, pan.x / size.width, pan.y / size.height, change,
                                        centroid.x / size.width, centroid.y / size.height))
                                }
                            }) {
                            drawIntoCanvas { canvas ->
                                canvas.nativeCanvas.drawBitmap(image.bitmap,
                                    transform.matrix(image.bitmap.width, image.bitmap.height, size.width), paint)
                            }
                            val guide = colors.textPrimary.copy(alpha = .23f)
                            for (part in 1..2) {
                                val at = size.width * part / 3f
                                drawLine(guide, Offset(at, 0f), Offset(at, size.height), strokeWidth = 1.dp.toPx())
                                drawLine(guide, Offset(0f, at), Offset(size.width, at), strokeWidth = 1.dp.toPx())
                            }
                        }
                    } else if (error == null) CircularProgressIndicator(color = colors.primaryAccent)
                    error?.let { Text(it, color = colors.textMuted, modifier = Modifier.padding(20.dp)) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = { update(AvatarCropTransform(quarterTurns = (turns + 1) % 4)) },
                        enabled = source != null && !submitting, shape = CircleShape,
                        modifier = Modifier.height(48.dp).testTag("avatar_crop_rotate")) {
                        Icon(Icons.Default.RotateRight, null, Modifier.size(20.dp), tint = colors.primaryAccent)
                        Spacer(Modifier.width(8.dp))
                        Text("Rotate", color = colors.textPrimary)
                    }
                    AccentButton(onClick = {
                        val image = source ?: return@AccentButton
                        if (!submitting) {
                            submitting = true
                            image.retain()
                            val selectedCrop = currentTransform
                            // Enter finally before dismissal can cancel the pending job.
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                try { onSubmit(image.bitmap, selectedCrop) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "Could not save this image. Please try again." }
                                finally { image.release(); submitting = false }
                            }
                        }
                    }, enabled = source != null && !submitting, shape = CircleShape,
                        modifier = Modifier.height(48.dp).testTag("avatar_crop_submit")) {
                        if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Submit", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
