package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import java.util.Locale

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

/**
 * Polished Liquid Glass Version Update Pop-up Dialog.
 * Displays WavTape emblem, current-to-new version transition pill, formatted release notes,
 * live MB/percent download progress with cancellation, verification status, and install action.
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

    val isVisible = !isDismissed && (
        updateStatus is UpdateStatus.UpdateAvailable ||
            updateStatus is UpdateStatus.Downloading ||
            updateStatus is UpdateStatus.ReadyToInstall ||
            (updateStatus as? UpdateStatus.Error)?.showNotice == true
        )

    if (!isVisible) return

    val updateInfo = when (val s = updateStatus) {
        is UpdateStatus.UpdateAvailable -> s.updateInfo
        is UpdateStatus.Downloading -> s.updateInfo
        is UpdateStatus.ReadyToInstall -> s.updateInfo
        is UpdateStatus.Error -> s.retryInfo
        else -> null
    }

    val accentColor = appColors.primaryAccent
    val accentContent = LiquidGlass.lightContentColor(appColors, accentColor)
    val animatedProgress by animateFloatAsState(
        targetValue = (downloadProgress.coerceIn(0, 100)) / 100f,
        animationSpec = tween(durationMillis = 180),
        label = "update_download_progress"
    )

    Dialog(
        onDismissRequest = { AppUpdateManager.dismissForSession() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val popupShape = RoundedCornerShape(28.dp)
        val headerGlowBrush = remember(appColors.isDark, accentColor, appColors.secondaryAccent) {
            Brush.verticalGradient(
                listOf(
                    accentColor.copy(alpha = if (appColors.isDark) 0.22f else 0.14f),
                    appColors.secondaryAccent.copy(alpha = if (appColors.isDark) 0.08f else 0.05f),
                    Color.Transparent
                )
            )
        }

        Surface(
            shape = popupShape,
            color = appColors.cardBackgroundElevated,
            border = BorderStroke(
                1.2.dp,
                LiquidGlass.specularBorderBrush(
                    appColors,
                    highlightAlpha = if (appColors.isDark) 0.48f else 0.72f
                )
            ),
            modifier = modifier
                .widthIn(max = 392.dp)
                .fillMaxWidth(0.92f)
                .shadow(
                    elevation = 24.dp,
                    shape = popupShape,
                    spotColor = accentColor.copy(alpha = 0.36f),
                    ambientColor = Color.Black.copy(alpha = 0.28f)
                )
                .clip(popupShape)
                .testTag("auto_update_popup_dialog")
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LiquidGlass.miniPlayerAndBottomBarBrush(appColors))
            ) {
                // Soft upper accent-glass glow + specular reflection
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(148.dp)
                        .background(headerGlowBrush)
                )

                // Dismiss button ('X') with accessible 48.dp target
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { AppUpdateManager.dismissForSession() }
                        .zIndex(1f)
                        .testTag("dismiss_update_bar_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(
                                if (appColors.isDark) Color.White.copy(alpha = 0.12f)
                                else Color.Black.copy(alpha = 0.07f)
                            )
                            .border(
                                0.8.dp,
                                if (appColors.isDark) Color.White.copy(alpha = 0.18f)
                                else Color.Black.copy(alpha = 0.10f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss update notice",
                            tint = appColors.textPrimary.copy(alpha = 0.8f),
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
                    // WavTape emblem badge
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (appColors.isDark) Color.White.copy(alpha = 0.08f)
                                else Color.White.copy(alpha = 0.86f)
                            )
                            .border(
                                1.dp,
                                accentColor.copy(alpha = if (appColors.isDark) 0.40f else 0.28f),
                                RoundedCornerShape(18.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AppDynamicLogo(
                            modifier = Modifier.size(40.dp),
                            primaryAccent = accentColor,
                            secondaryAccent = appColors.secondaryAccent,
                            cutoutColor = appColors.cardBackgroundElevated,
                            contentDescriptionText = "WavTape Update",
                            showGlow = false,
                            adaptForLightSurface = !appColors.isDark
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val headline = when (updateStatus) {
                        is UpdateStatus.Downloading -> "Downloading Update"
                        is UpdateStatus.ReadyToInstall -> "Ready to Install"
                        is UpdateStatus.Error -> "Update Error"
                        else -> "New Update Available"
                    }
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.3).sp,
                        color = appColors.textPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Version transition & APK size pill row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = if (appColors.isDark) 0.18f else 0.12f))
                                .border(
                                    1.dp,
                                    accentColor.copy(alpha = 0.36f),
                                    CircleShape
                                )
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                fontSize = 12.sp,
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
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accentContent
                                )
                            }
                        }

                        val sizeLabel = remember(updateInfo?.apkSizeBytes) {
                            formatUpdateBytes(updateInfo?.apkSizeBytes ?: 0L)
                        }
                        if (sizeLabel.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (appColors.isDark) Color.White.copy(alpha = 0.08f)
                                        else Color.Black.copy(alpha = 0.06f)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = sizeLabel,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = appColors.textSecondary
                                )
                            }
                        }
                    }

                    if (updateInfo != null) {
                        Spacer(modifier = Modifier.height(16.dp))

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
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = appColors.textMuted
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val changelogEntries = remember(updateInfo) { parseUpdateChangelog(updateInfo) }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 84.dp, max = 180.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    if (appColors.isDark) Color.Black.copy(alpha = 0.28f)
                                    else Color.White.copy(alpha = 0.62f)
                                )
                                .border(
                                    BorderStroke(1.dp, appColors.cardBorder.copy(alpha = 0.55f)),
                                    RoundedCornerShape(18.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
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

                    // Live Download Progress Section
                    when (val status = updateStatus) {
                        is UpdateStatus.Downloading -> {
                            Spacer(modifier = Modifier.height(14.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (appColors.isDark) Color.White.copy(alpha = 0.06f)
                                        else Color.Black.copy(alpha = 0.04f)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = status.message,
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

                                val downloadedStr = formatUpdateBytes(status.downloadedBytes)
                                val totalStr = formatUpdateBytes(status.totalBytes)
                                if (downloadedStr.isNotEmpty() && totalStr.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "$downloadedStr / $totalStr",
                                        fontSize = 11.sp,
                                        color = appColors.textMuted
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(7.dp)
                                        .clip(CircleShape),
                                    color = accentColor,
                                    trackColor = accentColor.copy(alpha = 0.20f),
                                    strokeCap = StrokeCap.Round
                                )
                            }
                        }

                        is UpdateStatus.ReadyToInstall -> {
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(accentColor.copy(alpha = if (appColors.isDark) 0.14f else 0.10f))
                                    .border(1.dp, accentColor.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = accentContent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = status.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = appColors.textPrimary,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        is UpdateStatus.Error -> {
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                                    .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = status.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        else -> Unit
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Primary Pill Action Button
                    com.example.ui.theme.AccentButton(
                        onClick = {
                            when (val status = updateStatus) {
                                is UpdateStatus.UpdateAvailable -> {
                                    AppUpdateManager.downloadAndInstallUpdate(context, status.updateInfo)
                                }
                                is UpdateStatus.ReadyToInstall -> {
                                    AppUpdateManager.installDownloadedApk(context, status.apkFile)
                                }
                                is UpdateStatus.Error -> {
                                    val retryInfo = status.retryInfo
                                    if (retryInfo != null) {
                                        AppUpdateManager.downloadAndInstallUpdate(context, retryInfo)
                                    } else {
                                        AppUpdateManager.checkForUpdate(context, isManual = true)
                                    }
                                }
                                else -> {}
                            }
                        },
                        enabled = updateStatus !is UpdateStatus.Downloading,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = appColors.onPrimaryAccent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("update_action_pill_button")
                    ) {
                        when (updateStatus) {
                            is UpdateStatus.Downloading -> {
                                CircularProgressIndicator(
                                    progress = { animatedProgress },
                                    color = appColors.onPrimaryAccent,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
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

                    // Cancel button while actively downloading, or subtle "Not Now" when update available
                    if (updateStatus is UpdateStatus.Downloading) {
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
                    } else if (updateStatus is UpdateStatus.UpdateAvailable) {
                        Spacer(modifier = Modifier.height(2.dp))
                        TextButton(
                            onClick = { AppUpdateManager.dismissForSession() },
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
                }
            }
        }
    }
}
