package com.example

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.local.BackupRestoreManager
import com.example.data.local.MusicDatabase
import com.example.data.local.PlaylistEntity
import com.example.data.local.TrackEntity
import com.example.performance.MaintenanceScheduler
import com.example.ui.components.formatReleaseDate
import com.example.ui.screens.settings.formatBackupLocationForDisplay
import com.example.updater.AppUpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BackupAndUpdaterFixesTest {
    private lateinit var context: Context
    private lateinit var db: MusicDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = MusicDatabase.createInMemoryDatabase(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
    }

    @After
    fun tearDown() {
        db.close()
        WorkManager.getInstance(context).cancelAllWork().result.get()
    }

    @Test
    fun createBackupCreatesNestedDirectoriesAndUsesAsciiDigitsRegardlessOfLocale() = runBlocking {
        val originalLocale = Locale.getDefault()
        val nestedDir = File(context.filesDir, "nested/level/backups")
        nestedDir.parentFile?.parentFile?.deleteRecursively()
        try {
            Locale.setDefault(Locale.Builder().setLanguage("ar").setRegion("EG").build())
            BackupRestoreManager.setAutoBackupLocation(context, nestedDir.absolutePath)
            db.musicDao().insertPlaylist(PlaylistEntity(1, "Mix"))
            db.musicDao().insertOrUpdateTrack(
                TrackEntity("t1", "Song", "Artist", "", 180_000L, "", "")
            )

            val saved = BackupRestoreManager.createBackup(context, db).getOrThrow()
            val file = File(saved.filePath)
            assertTrue("Backup file should exist in nested folder", file.isFile)
            assertTrue(
                "Filename should use ASCII digits: ${saved.fileName}",
                saved.fileName.matches(Regex("^WavTape_Backup_[0-9]{8}_[0-9]{6}\\.json$"))
            )
        } finally {
            Locale.setDefault(originalLocale)
            File(context.filesDir, "nested").deleteRecursively()
        }
    }

    @Test
    fun writeBackupDocumentFallsBackToWriteModeWhenProviderRejectsTruncateMode() {
        val backingFile = File.createTempFile("saf-fallback-", ".json", context.cacheDir)
        val uri = Uri.parse("content://saf.fallback.test/doc/1")
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
            override fun getType(uri: Uri) = "application/json"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
            override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                if (mode == "wt") throw UnsupportedOperationException("wt not supported")
                val pfdMode = if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY
                else ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                return ParcelFileDescriptor.open(backingFile, pfdMode)
            }
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "saf.fallback.test" })
        ShadowContentResolver.registerProviderInternal("saf.fallback.test", provider)
        try {
            val payload = """{"app":"WavTape"}""".toByteArray(Charsets.UTF_8)
            BackupRestoreManager.writeBackupDocument(context, uri, payload)
            assertEquals("""{"app":"WavTape"}""", backingFile.readText())
        } finally {
            backingFile.delete()
        }
    }

    @Test
    fun restoringAutoBackupPreferenceUpdatesWorkManagerSchedule() = runBlocking {
        val manager = WorkManager.getInstance(context)
        BackupRestoreManager.setAutoBackupEnabled(context, false)

        val backupWithAutoOn = """
            {
              "app": "WavTape",
              "schemaVersion": 7,
              "playlists": [{"playlistId": 1, "title": "Saved"}],
              "libraryTracks": [{"id": "t1", "title": "Track", "artist": "Artist"}],
              "settings": {
                "xtreme_backup_preferences": {
                  "auto_backup_enabled": {"type": "boolean", "value": true}
                }
              }
            }
        """.trimIndent()

        val restoredOn = BackupRestoreManager.restoreBackup(context, db, rawJson = backupWithAutoOn)
        assertTrue(restoredOn.message, restoredOn.success)
        assertTrue(BackupRestoreManager.isAutoBackupEnabled(context))
        val scheduled = withTimeout(5_000L) {
            while (manager.getWorkInfosForUniqueWork(MaintenanceScheduler.AUTO_BACKUP).get().none { !it.state.isFinished }) {
                delay(15L)
            }
            manager.getWorkInfosForUniqueWork(MaintenanceScheduler.AUTO_BACKUP).get()
        }
        assertTrue("Auto-backup worker should be enqueued after restoring enabled=true", scheduled.any { !it.state.isFinished })

        val backupWithAutoOff = backupWithAutoOn.replace("true", "false")
        val restoredOff = BackupRestoreManager.restoreBackup(context, db, rawJson = backupWithAutoOff)
        assertTrue(restoredOff.message, restoredOff.success)
        assertFalse(BackupRestoreManager.isAutoBackupEnabled(context))
        withTimeout(5_000L) {
            while (manager.getWorkInfosForUniqueWork(MaintenanceScheduler.AUTO_BACKUP).get().any { !it.state.isFinished }) {
                delay(15L)
            }
        }
    }

    @Test
    fun formatBackupLocationForDisplayMakesSafTreeUrisReadable() {
        val safUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FWavTapeBackup"
        assertEquals("Internal Storage/Download/WavTapeBackup", formatBackupLocationForDisplay(safUri))
        assertEquals("/storage/emulated/0/Download/WavTapeBackup", formatBackupLocationForDisplay("/storage/emulated/0/Download/WavTapeBackup"))
    }

    @Test
    fun updaterFriendlyErrorsAndReleaseDateFormattingWorkCleanly() {
        assertEquals(
            "No internet connection. Please check your network and try again.",
            AppUpdateManager.friendlyErrorMessage(UnknownHostException("api.github.com"), "Fallback")
        )
        assertEquals(
            "Update server timed out. Please try again.",
            AppUpdateManager.friendlyErrorMessage(SocketTimeoutException("timeout"), "Fallback")
        )
        assertEquals(
            "Update server rate limit reached. Please try again later.",
            AppUpdateManager.friendlyErrorMessage(IOException("Update server rate limit reached. Please try again later."), "Fallback")
        )
        assertTrue(formatReleaseDate("2026-11-21T12:00:00Z").isNotBlank())
        assertEquals("", formatReleaseDate(""))
        assertEquals("", formatReleaseDate("not-a-date"))
    }
}
