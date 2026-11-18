package com.example

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ThemeExportManager
import com.example.data.local.ThemePresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemeExportRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var provider: DownloadsProvider
    private val theme = ThemePresets.AmoledPureBlack.toCustomThemeState()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        provider = DownloadsProvider(File(context.cacheDir, "theme-mirror.json"))
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun assertPrivateCopyIntact() {
        val file = File(context.getExternalFilesDir(null), "backups/${ThemeExportManager.THEME_FILE_NAME}")
        assertEquals(theme.currentThemeName, JSONObject(file.readText()).getString("themeName"))
    }

    @Test fun successfulExportPublishesThePublicRowOnlyAfterWritingTheCompleteTheme() = runTest {
        val result = ThemeExportManager.exportCustomTheme(context, theme).getOrThrow()
        assertEquals("Downloads/WavTapeBackup/customtheme.json", result)
        assertEquals(1, provider.inserted!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(0, provider.published!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(theme.currentThemeName, JSONObject(provider.file.readText()).getString("themeName"))
        assertEquals("WavTape", JSONObject(provider.file.readText()).getString("appName"))
        assertFalse(provider.deleted)
        assertPrivateCopyIntact()
    }

    @Test fun aNullPublicOutputStreamFallsBackToTheSavedPrivateCopyAndRemovesTheEmptyRow() = runTest {
        provider.mode = "null"
        val result = ThemeExportManager.exportCustomTheme(context, theme).getOrThrow()
        assertEquals("backups/customtheme.json", result)
        assertTrue(provider.deleted)
        assertNull(provider.published)
        assertPrivateCopyIntact()
    }

    @Test fun aFailedPublicWriteDoesNotLeaveABrokenFileInDownloads() = runTest {
        provider.mode = "error"
        assertEquals("backups/customtheme.json", ThemeExportManager.exportCustomTheme(context, theme).getOrThrow())
        assertTrue(provider.deleted)
        assertNull(provider.published)
        assertPrivateCopyIntact()
    }

    @Test fun aFailedPublicationRemovesThePendingRowAndKeepsThePrivateCopy() = runTest {
        provider.mode = "publish-error"
        assertEquals("backups/customtheme.json", ThemeExportManager.exportCustomTheme(context, theme).getOrThrow())
        assertTrue(provider.deleted)
        assertPrivateCopyIntact()
    }

    private class DownloadsProvider(val file: File) : ContentProvider() {
        var mode = "success"
        var inserted: ContentValues? = null
        var published: ContentValues? = null
        var deleted = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/json"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted = ContentValues(values)
            return Uri.parse("content://media/external/downloads/42")
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            // A published row must already contain the complete, parseable document.
            JSONObject(file.readText())
            if (mode == "publish-error") return 0
            published = ContentValues(values)
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deleted = true; return 1 }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = when (this.mode) {
            "null" -> null
            "error" -> throw FileNotFoundException("Injected unavailable output")
            else -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        }
    }
}
