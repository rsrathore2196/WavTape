package com.example.data.local

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Functional exporter for custom themes.
 * Bundles the active app theme settings (Accent Color, Hue weight, Canvas Color,
 * Card Color, text colors, and gradients) into a structured JSON payload and
 * writes to `customtheme.json` in the app's backup directory and public Downloads.
 */
object ThemeExportManager {
    private const val TAG = "ThemeExportManager"
    const val THEME_FILE_NAME = "customtheme.json"
    private val exports = Mutex()

    suspend fun exportCustomTheme(context: Context, state: CustomThemeState): Result<String> = withContext(Dispatchers.IO) {
      exports.withLock {
        try {
            val json = JSONObject().apply {
                put("formatVersion", 1)
                put("appName", "Xtreme Player")
                put("themeName", state.currentThemeName)
                put("presetId", state.presetId)
                put("isPresetDark", state.isPresetDark)
                put("isAmoled", state.isAmoled)
                put("accent", JSONObject().apply {
                    put("name", state.accentName)
                    put("shade", state.accentShade)
                    put("hex", state.accentColorHex)
                    put("secondaryHex", state.secondaryAccentHex)
                })
                put("canvas", JSONObject().apply {
                    put("name", state.canvasColorName)
                    put("hex", state.canvasColorHex)
                })
                put("card", JSONObject().apply {
                    put("name", state.cardColorName)
                    put("hex", state.cardColorHex)
                    put("borderHex", state.cardBorderHex)
                })
                put("text", JSONObject().apply {
                    put("primaryHex", state.textPrimaryHex)
                    put("secondaryHex", state.textSecondaryHex)
                    put("mutedHex", state.textMutedHex)
                })
                put("gradients", JSONObject().apply {
                    put("backgroundName", state.bgGradientName)
                    put("backgroundColors", JSONArray(state.bgGradientColorsHex))
                    put("cardName", state.cardGradientName)
                    put("cardColors", JSONArray(state.cardGradientColorsHex))
                    put("bottomSheetName", state.bottomSheetGradientName)
                    put("bottomSheetColors", JSONArray(state.bottomSheetGradientColorsHex))
                })
                put("exportedAt", System.currentTimeMillis())
                put("exportedAtFormatted", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            }
            val jsonString = json.toString(2)

            // 1. Primary write: App external files backup directory
            val backupDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")
            check(backupDir.isDirectory || backupDir.mkdirs()) { "Theme storage is unavailable" }
            val primaryFile = File(backupDir, THEME_FILE_NAME)
            val storage = AtomicFile(primaryFile)
            val output = storage.startWrite()
            try { output.write(jsonString.toByteArray(Charsets.UTF_8)); storage.finishWrite(output) }
            catch (error: Exception) { storage.failWrite(output); throw error }

            var savedPath = "backups/$THEME_FILE_NAME"
            ensureActive()

            // 2. Android 10+ MediaStore public Downloads/XtremeBackup mirror
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, THEME_FILE_NAME)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/XtremeBackup")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        var published = false
                        try {
                            val stream = resolver.openOutputStream(uri) ?: throw IOException("Theme output is unavailable")
                            stream.use { it.write(jsonString.toByteArray(Charsets.UTF_8)) }
                            ensureActive()
                            check(resolver.update(uri, ContentValues().apply {
                                put(MediaStore.MediaColumns.IS_PENDING, 0)
                            }, null, null) > 0) { "Theme could not be published" }
                            published = true
                            savedPath = "Downloads/XtremeBackup/$THEME_FILE_NAME"
                        } finally {
                            if (!published) runCatching { resolver.delete(uri, null, null) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.w(TAG, "MediaStore mirror skipped: ${e.message}")
                }
            } else {
                // Pre-Android 10 public directory
                val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "XtremeBackup")
                try {
                    if (!publicDir.exists()) publicDir.mkdirs()
                    if (publicDir.exists()) {
                        File(publicDir, THEME_FILE_NAME).writeText(jsonString)
                        savedPath = "Download/XtremeBackup/$THEME_FILE_NAME"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Public dir export skipped: ${e.message}")
                }
            }

            // Keep the UI-owned theme current if the user edits it during the file export.
            // SharedPreferences.apply only updates the small in-memory UI preference snapshot here.
            withContext(Dispatchers.Main.immediate) {
                if (ThemePreferences.getCustomThemeState(context) == state) {
                    ThemePreferences.saveCustomThemeState(context, state)
                }
            }

            Result.success(savedPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export custom theme: ${e.message}", e)
            Result.failure(e)
        }
      }
    }
}
