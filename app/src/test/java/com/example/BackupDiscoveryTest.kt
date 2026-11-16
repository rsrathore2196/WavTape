package com.example

import android.app.Application
import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.BackupRestoreManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupDiscoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun backupPickerExcludesThemeExportsUnrelatedJsonAndTruncatedFiles() {
        val directory = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
        File(directory, "customtheme.json").writeText("""{"formatVersion":1,"appName":"Xtreme Player","themeName":"Custom"}""")
        File(directory, "unrelated.json").writeText("""{"name":"Other data"}""")
        File(directory, "broken.json").writeText("""{"playlists":[""")
        File(directory, "valid.json").writeText("""{"app":"Xtreme Player","schemaVersion":6,"playlists":[],"libraryTracks":[]}""")
        val names = BackupRestoreManager.getAvailableBackups(context).map { it.fileName }
        assertEquals(listOf("valid.json"), names)
    }

    @Test fun renamedLegacyBackupsRetainTheirTrackAndPlaylistCounts() {
        val directory = File(context.filesDir, "selected-backup-folder").apply { mkdirs() }
        BackupRestoreManager.setAutoBackupLocation(context, directory.absolutePath)
        File(directory, "my-music.json").writeText("""{"playlists":[{"playlistId":1,"title":"Mix"}],"favoriteTracks":[{"id":"a"},{"id":"b"}]}""")
        val result = BackupRestoreManager.getAvailableBackups(context).single { it.fileName == "my-music.json" }
        assertEquals(1, result.playlistsCount)
        assertEquals(2, result.tracksCount)
    }

    @Test fun selectedDocumentFoldersUseRealCountsAndIgnoreNonBackupDocuments() {
        val tree = Uri.parse("content://backups.test/tree/music")
        BackupRestoreManager.setAutoBackupLocation(context, tree.toString())
        val provider = PreviewProvider(context, tree)
        ShadowContentResolver.registerProviderInternal("backups.test", provider)
        val result = BackupRestoreManager.getAvailableBackups(context)
        assertEquals(listOf("XtremePlayer_Backup-valid.json"), result.map { it.fileName })
        assertEquals(2, result.single().tracksCount)
        assertEquals(1, result.single().playlistsCount)
        assertTrue(result.single().contentUri!!.startsWith("content://backups.test/"))
    }

    @Test fun mediaStoreSkipsNamelessAndInvalidRowsWithoutHidingLaterValidBackups() {
        ShadowContentResolver.registerProviderInternal("media", PreviewProvider(context))
        val result = BackupRestoreManager.getAvailableBackups(context)
        assertEquals(listOf("XtremePlayer_Backup-valid.json"), result.map { it.fileName })
        assertEquals(2, result.single().tracksCount)
        assertEquals(1, result.single().playlistsCount)
    }

    private class PreviewProvider(val app: Context, val tree: Uri? = null) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/json"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): MatrixCursor {
            val names = listOf(null, "XtremePlayer_Backup-invalid.json", "XtremePlayer_Backup-valid.json")
            val bodies = listOf("{}", """{"themeName":"Custom"}""",
                """{"playlists":[{"playlistId":1,"title":"Mix"}],"favoriteTracks":[{"id":"a"},{"id":"b"}]}""")
            return MatrixCursor(projection!!).apply {
                names.forEachIndexed { index, name ->
                    val document = tree?.let { DocumentsContract.buildDocumentUriUsingTree(it, "item$index") }
                        ?: Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, index.toString())
                    shadowOf(app.contentResolver).registerInputStream(document, bodies[index].byteInputStream())
                    addRow(projection.map { column -> when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "item$index"
                        MediaStore.MediaColumns._ID -> index
                        MediaStore.MediaColumns.DISPLAY_NAME -> name
                        MediaStore.MediaColumns.SIZE -> bodies[index].length
                        else -> 1L
                    } }.toTypedArray<Any?>())
                }
            }
        }
    }
}
