package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.LocalAppColors
import com.example.updater.AppUpdateManager
import com.example.updater.UpdateInfo
import com.example.updater.UpdateStatus
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal data class ChangelogEntry(
    val text: String,
    val isHeading: Boolean
)

internal fun parseUpdateChangelog(updateInfo: UpdateInfo?): List<ChangelogEntry> {
    val rawNotes = updateInfo?.releaseNotes?.trim().orEmpty()
    if (rawNotes.isNotBlank()) {
        val parsed = rawNotes.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return@mapNotNull null
            val isHeading = trimmed.startsWith("#")
            val cleaned = trimmed
                .removePrefix("###").removePrefix("##").removePrefix("#")
                .trim()
                .removePrefix("- ").removePrefix("* ").removePrefix("• ")
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
                .replace(Regex("__(.+?)__"), "$1")
                .replace(Regex("`([^`]+)`"), "$1")
                .trim()
            if (cleaned.isEmpty()) null else ChangelogEntry(cleaned, isHeading)
        }
        if (parsed.isNotEmpty()) return parsed
    }
    val rawTitle = updateInfo?.releaseTitle?.trim().orEmpty()
    if (rawTitle.isNotBlank() && !rawTitle.equals("v${updateInfo?.versionName}", ignoreCase = true) && rawTitle != updateInfo?.versionName) {
        return listOf(ChangelogEntry(rawTitle, isHeading = false))
    }
    return listOf(
        ChangelogEntry("Performance improvements and playback optimizations", isHeading = false),
        ChangelogEntry("Bug fixes and overall stability improvements", isHeading = false),
        ChangelogEntry("UI polish and smoother animations", isHeading = false)
    )
}

internal fun formatUpdateBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}

internal fun formatReleaseDate(isoTimestamp: String?): String {
    val raw = isoTimestamp?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = parser.parse(raw) ?: return ""
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
    } catch (_: Exception) {
        ""
    }
}

/**
 * Modern Liquid Glass Version Update Pop-up Dialog with smooth spring opening/closing motion.
 * Displays WavTape emblem, status pill, version transition & size metadata, formatted release notes,
 * animated gradient download telemetry with cancellation, verification badge, and install action.
 */
@Composable
fun UpdateNotificationBar(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val updateStatus by AppUpdateManager.updateStatus.collectAsStateWithLifecycle()
    val isDismissed by AppUpdateManager.isDismissedForSession.collectAsStateWithLifecycle()
    val downloadProgress by AppUpdateManager.downloadProgress.collectAsStateWithLifecycle()

    val shouldShow = !isDismissed && (
        updateStatus is UpdateStatus.UpdateAvailable ||
            updateStatus is UpdateStatus.Downloading ||
            updateStatus is UpdateStatus.ReadyToInstall ||
            (updateStatus as? UpdateStatus.Error)?.showNotice == true
        )

    var renderedStatus by remember { mutableStateOf<UpdateStatus?>(null) }
    var isClosing by remember { mutableStateOf(false) }

    if (shouldShow) {
        renderedStatus = updateStatus
    }

    val visibilityState = remember { MutableTransitionState(false) }

    LaunchedEffect(shouldShow) {
        if (shouldShow) {
            isClosing = false
        }
    }

    LaunchedEffect(shouldShow, isClosing) {
        visibilityState.targetState = shouldShow && !isClosing
    }

    LaunchedEffect(visibilityState.currentState, visibilityState.targetState, visibilityState.isIdle, isClosing, shouldShow) {
        if (visibilityState.isIdle && !visibilityState.currentState && !visibilityState.targetState) {
            if (isClosing) {
                AppUpdateManager.dismissForSession()
            }
            if (!shouldShow) {
                isClosing = false
                renderedStatus = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isClosing) {
                AppUpdateManager.dismissForSession()
            }
        }
    }

    val activeStatus = renderedStatus
    val isMounted = activeStatus != null && (shouldShow || !visibilityState.isIdle || visibilityState.currentState)
    if (!isMounted || activeStatus == null) return

    val requestDismiss: () -> Unit = {
        if (!isClosing) {
            isClosing = true
            visibilityState.targetState = false
        }
    }

    val updateInfo = when (activeStatus) {
        is UpdateStatus.UpdateAvailable -> activeStatus.updateInfo
        is UpdateStatus.Downloading -> activeStatus.updateInfo
        is UpdateStatus.ReadyToInstall -> activeStatus.updateInfo
        is UpdateStatus.Error -> activeStatus.retryInfo
        else -> null
    }

    val accentColor = appColors.primaryAccent
    val secondaryAccent = appColors.secondaryAccent
    val accentContent = LiquidGlass.lightContentColor(appColors, accentColor)
    val animatedProgress by animateFloatAsState(
        targetValue = (downloadProgress.coerceIn(0, 100)) / 100f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "update_download_progress"
    )

    val motion = rememberTransition(visibilityState, label = "update_popup_motion")
    val scrimAlpha by motion.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = if (targetState) 250 else 180,
                easing = FastOutSlowInEasing
            )
        },
        label = "update_popup_scrim"
    ) { visible -> if (visible) 1f else 0f }

    val cardScale by motion.animateFloat(
        transitionSpec = {
            if (targetState) {
                spring(dampingRatio = 0.80f, stiffness = 420f)
            } else {
                tween(durationMillis = 175, easing = FastOutSlowInEasing)
            }
        },
        label = "update_popup_scale"
    ) { visible -> if (visible) 1f else 0.91f }

    val cardTranslationY by motion.animateDp(
        transitionSpec = {
            if (targetState) {
                spring(dampingRatio = 0.82f, stiffness = 400f)
            } else {
                tween(durationMillis = 175, easing = FastOutSlowInEasing)
            }
        },
        label = "update_popup_offset"
    ) { visible -> if (visible) 0.dp else 24.dp }

    val cardAlpha by motion.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = if (targetState) 210 else 155,
                easing = if (targetState) LinearOutSlowInEasing else FastOutSlowInEasing
            )
        },
        label = "update_popup_alpha"
    ) { visible -> if (visible) 1f else 0f }

    Dialog(
        onDismissRequest = requestDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        KeepPlayerPopupWindowClear()

        val popupShape = RoundedCornerShape(30.dp)
        val isErrorState = activeStatus is UpdateStatus.Error
        val heroTint = if (isErrorState) MaterialTheme.colorScheme.error else accentColor
        val headerGlowBrush = remember(appColors.isDark, heroTint, secondaryAccent, isErrorState) {
            Brush.verticalGradient(
                listOf(
                    heroTint.copy(alpha = if (appColors.isDark) 0.26f else 0.16f),
                    (if (isErrorState) heroTint else secondaryAccent).copy(alpha = if (appColors.isDark) 0.09f else 0.05f),
                    Color.Transparent
                )
            )
        }
        val surfaceGradientBrush = remember(appColors.isDark, appColors.cardBackgroundElevated, appColors.cardBackground) {
            LiquidGlass.miniPlayerAndBottomBarBrush(appColors)
        }
        val rimBorderBrush = remember(appColors.isDark, heroTint) {
            LiquidGlass.specularBorderBrush(
                appColors,
                highlightAlpha = if (appColors.isDark) 0.54f else 0.78f
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = (if (appColors.isDark) 0.64f else 0.44f) * scrimAlpha))
                .pointerInput(Unit) { detectTapGestures { requestDismiss() } }
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            val maxChangelogHeight = (maxHeight * 0.27f).coerceIn(96.dp, 210.dp)

            Surface(
                shape = popupShape,
                color = appColors.cardBackgroundElevated,
                border = BorderStroke(1.2.dp, rimBorderBrush),
                modifier = modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = cardScale
                        scaleY = cardScale
                        translationY = cardTranslationY.toPx()
                        alpha = cardAlpha
                    }
                    .shadow(
                        elevation = 28.dp,
                        shape = popupShape,
                        spotColor = heroTint.copy(alpha = if (appColors.isDark) 0.40f else 0.28f),
                        ambientColor = Color.Black.copy(alpha = 0.34f)
                    )
                    .clip(popupShape)
                    .pointerInput(Unit) { detectTapGestures { /* Keep taps inside the card */ } }
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                    .testTag("auto_update_popup_dialog")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(surfaceGradientBrush)
                ) {
                    // Ambient luminous top glass aura
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(172.dp)
                            .background(headerGlowBrush)
                    )

                    // Subtle top specular highlight line
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 1.dp)
                            .fillMaxWidth(0.55f)
                            .height(1.5.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = if (appColors.isDark) 0.36f else 0.85f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    // Dismiss button ('X') with accessible 48.dp touch target
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(onClick = requestDismiss)
                            .zIndex(2f)
                            .testTag("dismiss_update_bar_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (appColors.isDark) Color.White.copy(alpha = 0.11f)
                                    else Color.Black.copy(alpha = 0.06f)
                                )
                                .border(
                                    0.8.dp,
                                    if (appColors.isDark) Color.White.copy(alpha = 0.20f)
                                    else Color.Black.copy(alpha = 0.10f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss update notice",
                                tint = appColors.textPrimary.copy(alpha = 0.85f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Compact status eyebrow pill
                        val eyebrowText = when (activeStatus) {
                            is UpdateStatus.Downloading -> "DOWNLOADING UPDATE"
                            is UpdateStatus.ReadyToInstall -> "VERIFIED & READY"
                            is UpdateStatus.Error -> "UPDATE ISSUE"
                            else -> "SOFTWARE UPDATE"
                        }
                        val eyebrowColor = if (isErrorState) MaterialTheme.colorScheme.error else accentContent
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(heroTint.copy(alpha = if (appColors.isDark) 0.16f else 0.11f))
                                .border(
                                    0.8.dp,
                                    heroTint.copy(alpha = if (appColors.isDark) 0.34f else 0.26f),
                                    CircleShape
                                )
                                .padding(horizontal = 11.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(eyebrowColor)
                            )
                            Text(
                                text = eyebrowText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.9.sp,
                                color = eyebrowColor
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // WavTape squircle emblem badge with progress/status halo
                        Box(
                            modifier = Modifier.size(66.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (activeStatus is UpdateStatus.Downloading) {
                                CircularProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier.size(66.dp),
                                    color = accentColor,
                                    trackColor = accentColor.copy(alpha = 0.18f),
                                    strokeWidth = 3.dp
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        if (appColors.isDark) Color.White.copy(alpha = 0.09f)
                                        else Color.White.copy(alpha = 0.90f)
                                    )
                                    .border(
                                        1.dp,
                                        heroTint.copy(alpha = if (appColors.isDark) 0.44f else 0.30f),
                                        RoundedCornerShape(18.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                AppDynamicLogo(
                                    modifier = Modifier.size(38.dp),
                                    primaryAccent = accentColor,
                                    secondaryAccent = secondaryAccent,
                                    cutoutColor = appColors.cardBackgroundElevated,
                                    contentDescriptionText = "WavTape Update",
                                    showGlow = false,
                                    adaptForLightSurface = !appColors.isDark
                                )
                            }
                            if (activeStatus is UpdateStatus.ReadyToInstall) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(accentColor)
                                        .border(2.dp, appColors.cardBackgroundElevated, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = "Verified update",
                                        tint = appColors.onPrimaryAccent,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val headline = when (activeStatus) {
                            is UpdateStatus.Downloading -> "Downloading Update"
                            is UpdateStatus.ReadyToInstall -> "Ready to Install"
                            is UpdateStatus.Error -> "Update Error"
                            else -> "New Update Available"
                        }
                        AnimatedContent(
                            targetState = headline,
                            transitionSpec = {
                                (fadeIn(tween(180)) + scaleIn(initialScale = 0.96f, animationSpec = tween(180)))
                                    .togetherWith(fadeOut(tween(120)))
                            },
                            label = "update_headline"
                        ) { title ->
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.35).sp,
                                color = appColors.textPrimary,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Version transition, APK size & release date pill strip
                        val sizeLabel = remember(updateInfo?.apkSizeBytes) {
                            formatUpdateBytes(updateInfo?.apkSizeBytes ?: 0L)
                        }
                        val releaseDateLabel = remember(updateInfo?.publishedAt) {
                            formatReleaseDate(updateInfo?.publishedAt)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(accentColor.copy(alpha = if (appColors.isDark) 0.18f else 0.12f))
                                    .border(
                                        0.9.dp,
                                        accentColor.copy(alpha = 0.36f),
                                        CircleShape
                                    )
                                    .padding(horizontal = 11.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "v${BuildConfig.VERSION_NAME}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = appColors.textSecondary
                                )
                                if (updateInfo != null) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = accentContent,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "v${updateInfo.versionName}",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = accentContent
                                    )
                                }
                            }

                            if (sizeLabel.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(
                                            if (appColors.isDark) Color.White.copy(alpha = 0.08f)
                                            else Color.Black.copy(alpha = 0.055f)
                                        )
                                        .border(
                                            0.8.dp,
                                            if (appColors.isDark) Color.White.copy(alpha = 0.12f)
                                            else Color.Black.copy(alpha = 0.08f),
                                            CircleShape
                                        )
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = sizeLabel,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = appColors.textSecondary
                                    )
                                }
                            }
                        }

                        if (releaseDateLabel.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Released $releaseDateLabel",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = appColors.textMuted
                            )
                        }

                        // Release Notes inset frosted card
                        if (updateInfo != null) {
                            Spacer(modifier = Modifier.height(16.dp))

                            val changelogEntries = remember(updateInfo) { parseUpdateChangelog(updateInfo) }
                            val insetCardShape = RoundedCornerShape(20.dp)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(insetCardShape)
                                    .background(
                                        if (appColors.isDark) Color.Black.copy(alpha = 0.30f)
                                        else Color.White.copy(alpha = 0.68f)
                                    )
                                    .border(
                                        0.9.dp,
                                        if (appColors.isDark) Color.White.copy(alpha = 0.13f)
                                        else appColors.cardBorder.copy(alpha = 0.55f),
                                        insetCardShape
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "What's New",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = appColors.textPrimary
                                    )
                                    Text(
                                        text = "WavTape",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = appColors.textMuted
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 64.dp, max = maxChangelogHeight)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    changelogEntries.forEach { entry ->
                                        if (entry.isHeading) {
                                            Text(
                                                text = entry.text,
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = appColors.textPrimary,
                                                lineHeight = 17.sp
                                            )
                                        } else {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .padding(top = 6.dp)
                                                        .size(5.dp)
                                                        .clip(CircleShape)
                                                        .background(accentContent)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = entry.text,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        color = appColors.textSecondary,
                                                        lineHeight = 18.sp
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Live Download / Verified / Error status panel
                        when (activeStatus) {
                            is UpdateStatus.Downloading -> {
                                Spacer(modifier = Modifier.height(14.dp))
                                val progressShape = RoundedCornerShape(18.dp)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(progressShape)
                                        .background(
                                            if (appColors.isDark) Color.White.copy(alpha = 0.065f)
                                            else Color.Black.copy(alpha = 0.04f)
                                        )
                                        .border(
                                            0.8.dp,
                                            accentColor.copy(alpha = if (appColors.isDark) 0.26f else 0.20f),
                                            progressShape
                                        )
                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = activeStatus.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = appColors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "$downloadProgress%",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = accentContent
                                        )
                                    }

                                    val downloadedStr = formatUpdateBytes(activeStatus.downloadedBytes)
                                    val totalStr = formatUpdateBytes(activeStatus.totalBytes)
                                    if (downloadedStr.isNotEmpty() && totalStr.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = "$downloadedStr / $totalStr",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = appColors.textMuted
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    // Sleek gradient pill progress bar
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(7.dp)
                                            .clip(CircleShape)
                                            .background(accentColor.copy(alpha = if (appColors.isDark) 0.20f else 0.16f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                                                .height(7.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    Brush.horizontalGradient(
                                                        listOf(accentColor, secondaryAccent)
                                                    )
                                                )
                                        )
                                    }
                                }
                            }

                            is UpdateStatus.ReadyToInstall -> {
                                Spacer(modifier = Modifier.height(14.dp))
                                val verifiedShape = RoundedCornerShape(16.dp)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(verifiedShape)
                                        .background(accentColor.copy(alpha = if (appColors.isDark) 0.15f else 0.10f))
                                        .border(0.9.dp, accentColor.copy(alpha = 0.30f), verifiedShape)
                                        .padding(horizontal = 13.dp, vertical = 11.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = null,
                                        tint = accentContent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(9.dp))
                                    Text(
                                        text = activeStatus.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = appColors.textPrimary,
                                        lineHeight = 17.sp
                                    )
                                }
                            }

                            is UpdateStatus.Error -> {
                                Spacer(modifier = Modifier.height(14.dp))
                                val errorShape = RoundedCornerShape(16.dp)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(errorShape)
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                                        .border(0.9.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.32f), errorShape)
                                        .padding(horizontal = 13.dp, vertical = 11.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(9.dp))
                                    Text(
                                        text = activeStatus.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.error,
                                        lineHeight = 17.sp
                                    )
                                }
                            }

                            else -> Unit
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Primary Pill Action Button
                        com.example.ui.theme.AccentButton(
                            onClick = {
                                when (activeStatus) {
                                    is UpdateStatus.UpdateAvailable -> {
                                        AppUpdateManager.downloadAndInstallUpdate(context, activeStatus.updateInfo)
                                    }
                                    is UpdateStatus.ReadyToInstall -> {
                                        AppUpdateManager.installDownloadedApk(context, activeStatus.apkFile)
                                    }
                                    is UpdateStatus.Error -> {
                                        val retryInfo = activeStatus.retryInfo
                                        if (retryInfo != null) {
                                            AppUpdateManager.downloadAndInstallUpdate(context, retryInfo)
                                        } else {
                                            AppUpdateManager.checkForUpdate(context, isManual = true)
                                        }
                                    }
                                    else -> Unit
                                }
                            },
                            enabled = activeStatus !is UpdateStatus.Downloading,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = appColors.onPrimaryAccent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("update_action_pill_button")
                        ) {
                            when (activeStatus) {
                                is UpdateStatus.Downloading -> {
                                    CircularProgressIndicator(
                                        progress = { animatedProgress },
                                        color = appColors.onPrimaryAccent,
                                        strokeWidth = 2.4.dp,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(9.dp))
                                    Text("Downloading $downloadProgress%", fontWeight = FontWeight.Bold)
                                }
                                is UpdateStatus.ReadyToInstall -> {
                                    Icon(
                                        imageVector = Icons.Default.SystemUpdate,
                                        contentDescription = "Install update",
                                        tint = appColors.onPrimaryAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Install Update", fontWeight = FontWeight.Bold)
                                }
                                is UpdateStatus.Error -> {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Retry update",
                                        tint = appColors.onPrimaryAccent,
                                        modifier = Modifier.size(19.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Try Again", fontWeight = FontWeight.Bold)
                                }
                                else -> {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Download update",
                                        tint = appColors.onPrimaryAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download Update", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Secondary subtle action below primary CTA
                        when (activeStatus) {
                            is UpdateStatus.Downloading -> {
                                Spacer(modifier = Modifier.height(4.dp))
                                TextButton(
                                    onClick = { AppUpdateManager.cancelDownload(context) },
                                    modifier = Modifier.testTag("cancel_update_download_button")
                                ) {
                                    Text(
                                        text = "Cancel Download",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = appColors.textMuted
                                    )
                                }
                            }
                            is UpdateStatus.UpdateAvailable -> {
                                Spacer(modifier = Modifier.height(2.dp))
                                TextButton(
                                    onClick = requestDismiss,
                                    modifier = Modifier.testTag("update_later_button")
                                ) {
                                    Text(
                                        text = "Maybe Later",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = appColors.textMuted
                                    )
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}
