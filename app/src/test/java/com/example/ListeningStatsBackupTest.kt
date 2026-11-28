package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.repository.ListeningStatsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ListeningStatsBackupTest {
    @Test fun exportedStatsRestoreOfflineAndRepeatedImportsDoNotDuplicateMinutesOrPlays() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = MusicDatabase.createInMemoryDatabase(context)
        val target = MusicDatabase.createInMemoryDatabase(context)
        val repository = ListeningStatsRepository(context, source, File(context.cacheDir, "source-pending.json"))
        val restored = ListeningStatsRepository(context, target, File(context.cacheDir, "restore-pending.json"))
        val folder = File(context.filesDir, "stats-backup-test")
        BackupRestoreManager.setAutoBackupLocation(context, folder.absolutePath)
        try {
            val track = MusicTrack("yt_abcdefghijk", "Song", "Main Artist", "Album", 120_000, "", "source", isLiked = true)
            repository.record(ListeningSnapshot("one", track, mapOf("2026-10-26" to ListeningDay(60_000, 1)), 60_000, true))
            repository.flush()
            val oldStats = source.listeningStatsDao().all()
            repository.record(ListeningSnapshot("one", track.copy(isrc = "USABC2600001"),
                mapOf("2026-10-26" to ListeningDay(90_000, 1)), 90_000, true))
            repository.flush()
            val backup = BackupRestoreManager.createBackup(context, source).getOrThrow()
            val body = File(backup.filePath).readText()
            assertEquals(8, JSONObject(body).getInt("schemaVersion"))
            assertEquals(4, JSONObject(body).getJSONArray("listeningStats").length())
            assertEquals(2, JSONObject(body).getJSONArray("listeningIdentityMigrations").length())
            repeat(2) {
                val result = BackupRestoreManager.restoreBackup(context, target, rawJson = body)
                assertTrue(result.message, result.success)
            }
            ListeningStatsBackup.merge(target.listeningStatsDao(), oldStats)
            restored.invalidate()
            assertEquals(90_000, restored.summary(ListeningPeriod.LIFETIME).millis)
            assertEquals(1, restored.summary(ListeningPeriod.LIFETIME).plays)
            assertTrue(target.musicDao().getTrackById(track.id)!!.isLiked)
            assertEquals("Raw historical events restore without being counted twice", 1, target.listeningEventDao().getActualPlays().size)
        } finally { repository.close(); restored.close(); source.close(); target.close(); folder.deleteRecursively() }
    }
    @Test fun exportedAddonHistoryRetainsPlaysWithoutSignedMediaOrArtworkUrls() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = MusicDatabase.createInMemoryDatabase(context)
        val folder = File(context.filesDir, "addon-history-backup-test")
        val pending = File(context.cacheDir, "addon-history-pending.json")
        val repository = ListeningStatsRepository(context, database, pending)
        BackupRestoreManager.setAutoBackupLocation(context, folder.absolutePath)
        try {
            val marker = "private-fixture-credential"
            val track = MusicTrack("addon_fixture:abc", "Song", "Singer", "Album", 120_000,
                "https://relay.example/$marker/cover.jpg", "https://relay.example/song.flac?token=$marker",
                source = "Streaming Addon: $marker", isLiked = true)
            repository.record(ListeningSnapshot("session-one", track, mapOf("2026-10-26" to ListeningDay(60_000, 1)), 60_000, true))
            repository.flush()
            val backup = BackupRestoreManager.createBackup(context, database).getOrThrow()
            val raw = File(backup.filePath).readText()
            assertFalse(raw.contains(marker))
            val restored = MusicDatabase.createInMemoryDatabase(context)
            try {
                val result = BackupRestoreManager.restoreBackup(context, restored, rawJson = raw)
                assertTrue(result.message, result.success)
                assertEquals(1, restored.listeningEventDao().getActualPlays().size)
                assertEquals("", restored.musicDao().getTrackById(track.id)?.audioUrl)
            } finally { restored.close() }
        } finally { repository.close(); database.close(); folder.deleteRecursively(); pending.delete() }
    }

    @Test fun malformedStatsAbortTheWholeRestoreBeforeAnyLibraryChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = MusicDatabase.createInMemoryDatabase(context)
        try {
            val row = ListeningTotal("device", "2026-10", "song", "song", "one", "Song", "Artist", "", "", 120_000, "", 60_000, 1)
            val stats = ListeningStatsBackup.encode(listOf(row))
            stats.getJSONObject(0).put("listenedMs", -1)
            val backup = JSONObject().put("schemaVersion", 4).put("libraryTracks", JSONArray().put(JSONObject().put("id", "new-song")))
                .put("listeningStats", stats)
            assertFalse(BackupRestoreManager.restoreBackup(context, db, rawJson = backup.toString()).success)
            assertTrue(db.musicDao().getAllTracksSync().isEmpty())
            assertTrue(db.listeningStatsDao().all().isEmpty())
        } finally { db.close() }
    }
}
