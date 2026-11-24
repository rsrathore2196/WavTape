package com.example.ui.components

import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Capture the clicked pill once. Screen coordinates also work across the new dialog window. */
internal class PlayerPopupAnchor {
    private var coordinates: LayoutCoordinates? = null

    fun update(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    fun capture(): Rect? {
        val layout = coordinates?.takeIf { it.isAttached } ?: return null
        val topLeft = layout.localToScreen(Offset.Zero)
        val bottomRight = layout.localToScreen(Offset(layout.size.width.toFloat(), layout.size.height.toFloat()))
        if (!topLeft.isSpecified || !bottomRight.isSpecified) return null
        return Rect(topLeft, bottomRight).takeUnless { it.isEmpty }
    }
}

internal fun LayoutCoordinates.centerOnScreen(): Offset =
    localToScreen(Offset(size.width / 2f, size.height / 2f))

/** The card is laid out once at its final size. The layer travels and scales from the pill. */
internal fun Modifier.anchoredPlayerPopupMotion(
    origin: Rect?,
    dialogCenter: State<Offset>,
    expansion: State<Float>,
    opacity: State<Float>,
    fallbackScale: () -> Float,
    fallbackSlide: Dp = 0.dp
): Modifier = graphicsLayer {
    val remaining = 1f - expansion.value
    val center = dialogCenter.value
    val pill = origin?.takeIf { center.isSpecified && size.width > 0f && size.height > 0f }
    val initialX = pill?.let { (it.width / size.width).coerceAtMost(1f) } ?: fallbackScale()
    val initialY = pill?.let { (it.height / size.height).coerceAtMost(1f) } ?: initialX
    scaleX = 1f - (1f - initialX) * remaining
    scaleY = 1f - (1f - initialY) * remaining
    translationX = (pill?.let { it.center.x - center.x } ?: 0f) * remaining
    translationY = (pill?.let { it.center.y - center.y } ?: fallbackSlide.toPx()) * remaining
    alpha = opacity.value
}

/** Full-window host gives the card room to travel between the pill and the center. */
@Composable
internal fun AnchoredPlayerPopupDialog(
    origin: Rect?,
    onDismiss: () -> Unit,
    decorFitsSystemWindows: Boolean = false,
    animated: Boolean = origin != null,
    content: @Composable (Modifier, () -> Unit) -> Unit
) {
    val dismissAction = rememberUpdatedState(onDismiss)
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val dismiss = remember(animated) {
        { if (animated) visible.targetState = false else dismissAction.value() }
    }
    val motion = if (animated) rememberTransition(visible, label = "pill_popup_presentation") else null
    val expansion = motion?.animateFloat(transitionSpec = {
        tween(if (targetState) 220 else 140, easing = FastOutSlowInEasing)
    }, label = "pill_popup_expansion") { if (it) 1f else 0f }
    val opacity = motion?.animateFloat(transitionSpec = {
        tween(if (targetState) 180 else 120)
    }, label = "pill_popup_opacity") { if (it) 1f else 0f }
    val center = remember { mutableStateOf(Offset.Unspecified) }
    if (animated) LaunchedEffect(visible.currentState, visible.targetState, visible.isIdle) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) dismissAction.value()
    }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = decorFitsSystemWindows)
    ) {
        val view = LocalView.current
        KeepPlayerPopupWindowClear()
        DisposableEffect(view, decorFitsSystemWindows) {
            val root = view.rootView
            val elevation = root.elevation
            if (decorFitsSystemWindows) root.elevation = 0f
            onDispose { if (decorFitsSystemWindows) root.elevation = elevation }
        }
        Box(
            Modifier.fillMaxSize().onGloballyPositioned { center.value = it.centerOnScreen() }
                .pointerInput(dismiss) { detectTapGestures { dismiss() } },
            contentAlignment = Alignment.Center
        ) {
            val cardMotion = if (expansion != null && opacity != null) Modifier.anchoredPlayerPopupMotion(origin, center, expansion, opacity,
                fallbackScale = { if (visible.targetState) .95f else .97f }) else Modifier
            content(cardMotion.pointerInput(Unit) { detectTapGestures { /* Keep taps inside the card. */ } }, dismiss)
        }
    }
}

@Composable
internal fun KeepPlayerPopupWindowClear() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(window) {
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(0f)
        onDispose { }
    }
}

@Composable
internal fun PlayerOptionsDropdown(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    val scroll = rememberScrollState()
    if (!visibility.currentState && !visibility.targetState && visibility.isIdle) return

    val motion = rememberTransition(visibility, label = "player_options_menu")
    val scale = motion.animateFloat(transitionSpec = {
        tween(if (targetState) 220 else 160, easing = FastOutSlowInEasing)
    }, label = "menu_scale") { if (it) 1f else .91f }
    val opacity = motion.animateFloat(transitionSpec = {
        tween(if (targetState) 160 else 140, easing = LinearOutSlowInEasing)
    }, label = "menu_alpha") { if (it) 1f else 0f }
    val pivot = remember { mutableStateOf(TransformOrigin.Center) }
    val margin = with(LocalDensity.current) { 48.dp.roundToPx() }
    val position = remember(margin) { PlayerOptionsPosition(margin) { pivot.value = it } }

    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = opacity.value
                transformOrigin = pivot.value
            }.playerPopupGlass(shape),
            shape = shape,
            color = playerPopupSurfaceColor()
        ) {
            Column(
                modifier = modifier.padding(vertical = 8.dp).width(IntrinsicSize.Max).verticalScroll(scroll),
                content = content
            )
        }
    }
}

private class PlayerOptionsPosition(
    private val margin: Int,
    private val onPivotChanged: (TransformOrigin) -> Unit
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        val maxX = (windowSize.width - width).coerceAtLeast(0)
        val maxY = (windowSize.height - height).coerceAtLeast(0)
        val left = anchorBounds.left
        val right = anchorBounds.right - width
        val x = (if (layoutDirection == LayoutDirection.Ltr) listOf(left, right) else listOf(right, left))
            .firstOrNull { it in 0..maxX } ?: if (anchorBounds.center.x < windowSize.width / 2) 0 else maxX
        val inset = margin.coerceAtMost(maxY / 2)
        val y = listOf(anchorBounds.bottom, anchorBounds.top - height, anchorBounds.top - height / 2)
            .firstOrNull { it in inset..(maxY - inset) }
            ?: if (anchorBounds.center.y < windowSize.height / 2) inset else maxY - inset

        fun pivot(coordinate: Int, offset: Int, length: Int) =
            ((coordinate - offset).toFloat() / length.coerceAtLeast(1)).coerceIn(0f, 1f)
        onPivotChanged(TransformOrigin(pivot(anchorBounds.center.x, x, width), pivot(anchorBounds.center.y, y, height)))
        return IntOffset(x, y)
    }
}
