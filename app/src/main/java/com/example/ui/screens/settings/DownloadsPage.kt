package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.downloads.*
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

@Composable
fun DownloadsPage(modifier: Modifier = Modifier, store: OfflineDownloads = OfflineDownloads.get(LocalContext.current)) {
    val summary by remember(store) { store.observeSummary() }
        .collectAsStateWithLifecycle(DownloadSummary())
    val preferences by store.preferences.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val colors = LocalAppColors.current
    var error by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    fun action(block: suspend () -> Unit) { scope.launch {
        try { block() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Downloads could not be updated. Check available storage and try again." }
    } }
    LaunchedEffect(store) {
        try { store.load() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Saved downloads could not be loaded." }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Remove all downloads?") },
        text = { Text("This removes saved audio from this device. Your playlists stay in your library.") },
        confirmButton = { TextButton(onClick = { clear = false; action { store.clear() } }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
    LazyColumn(modifier.fillMaxSize().testTag("downloads_page"), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Listen anywhere", color = colors.textPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Save tracks from their menu, or download an entire album or playlist. Saved audio stays available without internet.",
                color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = colors.cardBackground,
                border = BorderStroke(1.dp, colors.cardBorder), modifier = Modifier.testTag("downloads_options_frame")) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Wi-Fi only", color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                            Text("Wait for an unmetered connection", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        com.example.ui.theme.AccentSwitch(preferences.wifiOnly, { value -> action { store.updatePreferences { it.copy(wifiOnly = value) } } },
                            modifier = Modifier.testTag("downloads_wifi_only"))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = colors.cardBorder)
                    Box {
                        Row(Modifier.fillMaxWidth().clickable { qualityMenu = true }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Download quality", color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                                Text(preferences.quality.label, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.ExpandMore, null, tint = colors.textSecondary)
                        }
                        DropdownMenu(qualityMenu, { qualityMenu = false }) {
                            DownloadQuality.entries.forEach { quality -> DropdownMenuItem(text = { Text(quality.label) }, onClick = {
                                qualityMenu = false; action { store.updatePreferences { it.copy(quality = quality) } }
                            }) }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 16.dp), color = colors.cardBorder)
                    DownloadStorageSummary(
                        downloadedBytes = summary.bytes,
                        hasPartialAudio = summary.partial
                    )
                }
            }
        }
        item { DownloadedSongsCard(summary) }
        error?.let { message -> item { Text(message, color = colors.primaryAccent) } }
        if (summary.hasRecords) item {
            TextButton(onClick = { clear = true }, modifier = Modifier.testTag("clear_downloads")) { Text("Remove all downloads") }
        }
    }
}

@Composable
private fun DownloadedSongsCard(summary: DownloadSummary) {
    val colors = LocalAppColors.current
    Surface(shape = RoundedCornerShape(20.dp), color = colors.cardBackground,
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = Modifier.fillMaxWidth().testTag("downloaded_songs_summary")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = colors.primaryAccent.copy(alpha = .12f)) {
                    Icon(Icons.Default.CloudDownload, null, Modifier.padding(9.dp).size(20.dp), tint = colors.primaryAccent)
                }
                Text("Downloaded songs", Modifier.weight(1f), color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Text(summary.saved.toString(), Modifier.testTag("downloaded_songs_total"),
                    color = colors.textPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadQualityCount("Lossless", summary.lossless, colors.primaryAccent,
                    Modifier.weight(1f).testTag("downloaded_songs_lossless"))
                DownloadQualityCount("High quality", summary.high, colors.textSecondary,
                    Modifier.weight(1f).testTag("downloaded_songs_high"))
                DownloadQualityCount("Standard quality", summary.standard, colors.textSecondary,
                    Modifier.weight(1f).testTag("downloaded_songs_standard"))
            }
        }
    }
}

@Composable
private fun DownloadQualityCount(label: String, count: Int, tint: Color, modifier: Modifier) {
    val colors = LocalAppColors.current
    Surface(modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(14.dp), color = tint.copy(alpha = .09f)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(count.toString(), color = colors.textPrimary, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(label, color = colors.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal fun formatDownloadBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "${bytes.coerceAtLeast(0) / 1024} KB"
}
