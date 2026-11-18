package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** The theme owns the window; a visible header supplies only its contrast preference. */
@Stable
internal class StatusBarAppearance {
    var headerDarkIcons: Boolean? by mutableStateOf(null)
        private set
    private var owner: Any? = null

    fun showHeader(token: Any, darkIcons: Boolean) {
        owner = token
        headerDarkIcons = darkIcons
    }

    fun hideHeader(token: Any) {
        // An outgoing retained page must not clear the incoming page's preference.
        if (owner === token) {
            owner = null
            headerDarkIcons = null
        }
    }
}

internal val LocalStatusBarAppearance = staticCompositionLocalOf<StatusBarAppearance?> { null }
internal val LocalAccentHeaderVisible = compositionLocalOf { true }

@Composable
internal fun AccentHeaderStatusBar(foreground: Color = LocalAppColors.current.onAccentGradient) {
    val appearance = LocalStatusBarAppearance.current
    val visible = LocalAccentHeaderVisible.current
    val token = remember { Any() }
    val darkIcons = foreground.luminance() < .5f
    DisposableEffect(appearance, visible, darkIcons) {
        if (visible) appearance?.showHeader(token, darkIcons)
        onDispose { appearance?.hideHeader(token) }
    }
}
