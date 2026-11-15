package com.example

import android.app.Application
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import com.example.service.PersistentAudioCacheMigration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [DirectorySyncOsShadow::class])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PersistentAudioCacheMigrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun paths(name: String): Pair<File, File> {
        val old = File(app.cacheDir, "migration-$name").apply { deleteRecursively(); mkdirs() }
        val parent = File(app.noBackupFilesDir, "migration-$name").apply { deleteRecursively(); mkdirs() }
        return old to File(parent, "audio")
    }
    private fun write(cache: SimpleCache) {
        val hole = cache.startReadWrite("youtube:recording:251", 0, 256)
        try {
            val file = cache.startFile(hole.key, 0, 256)
            file.writeBytes(ByteArray(256) { it.toByte() }); cache.commitFile(file, 256)
            cache.applyContentMetadataMutations(hole.key, ContentMetadataMutations().set(ContentMetadata.KEY_CONTENT_LENGTH, 256L)
                .set("representation", "codec=opus;itag=251"))
        } finally { cache.releaseHoleSpan(hole) }
    }
    @Test fun directoryRenamePreservesUidDatabaseMetadataAndCoverageAfterRestartAndSystemCleanup() {
        val (old, next) = paths("rename")
        val db = StandaloneDatabaseProvider(app)
        val initial = SimpleCache(old, NoOpCacheEvictor(), db)
        write(initial)
        val uid = initial.uid
        initial.release()
        assertEquals(next, PersistentAudioCacheMigration.migrate(old, next))
        assertFalse(old.exists())
        val restarted = SimpleCache(next, NoOpCacheEvictor(), db)
        try {
            assertEquals(uid, restarted.uid)
            assertTrue(restarted.isCached("youtube:recording:251", 0, 256))
            assertEquals("codec=opus;itag=251", restarted.getContentMetadata("youtube:recording:251").get("representation", ""))
            // Disposable cache cleanup cannot reach the new directory.
            old.deleteRecursively()
            assertTrue(restarted.isCached("youtube:recording:251", 0, 256))
        } finally { restarted.release() }
        assertEquals(next, PersistentAudioCacheMigration.migrate(old, next))
    }
    @Test fun verifiedCopyFallbackPreservesIndexAndDoesNotTouchExplicitDownloads() {
        val (old, next) = paths("copy")
        val db = StandaloneDatabaseProvider(app)
        SimpleCache(old, NoOpCacheEvictor(), db).also { write(it); it.release() }
        val downloads = File(next.parentFile, "offline_audio/index.json").apply { parentFile!!.mkdirs(); writeText("saved") }
        assertEquals(next, PersistentAudioCacheMigration.migrate(old, next,
            rename = { from, to -> from != old && from.renameTo(to) }, freeBytes = { Long.MAX_VALUE }))
        assertFalse(old.exists())
        assertEquals("saved", downloads.readText())
        val restored = SimpleCache(next, NoOpCacheEvictor(), db)
        try { assertTrue(restored.isCached("youtube:recording:251", 0, 256)) }
        finally { restored.release() }
    }
    @Test fun anInterruptedCopyKeepsLegacyThenRetriesWithoutMergingPartialFiles() {
        val (old, next) = paths("interrupted")
        File(old, "track.bin").writeBytes(ByteArray(4096) { 9 })
        assertEquals(old, PersistentAudioCacheMigration.migrate(old, next,
            rename = { _, _ -> false }, freeBytes = { Long.MAX_VALUE }))
        assertTrue(old.resolve("track.bin").isFile)
        val staging = File(next.parentFile, ".${next.name}.staging")
        File(staging, "unexpected-partial").writeText("interrupted")
        assertEquals(next, PersistentAudioCacheMigration.migrate(old, next,
            rename = { from, to -> from != old && from.renameTo(to) }, freeBytes = { Long.MAX_VALUE }))
        assertFalse(File(next, "unexpected-partial").exists())
        assertArrayEquals(ByteArray(4096) { 9 }, File(next, "track.bin").readBytes())
        assertEquals(next, PersistentAudioCacheMigration.migrate(old, next))
    }
    @Test fun aCommittedCopyFinishesCleanupAfterProcessDeathUsingItsJournal() {
        val (old, next) = paths("committed")
        File(old, "cache.uid").writeText("identity")
        // Leave a fully copied staging directory and journal by failing the commit rename.
        PersistentAudioCacheMigration.migrate(old, next, rename = { _, _ -> false }, freeBytes = { Long.MAX_VALUE })
        assertTrue(File(next.parentFile, ".${next.name}.staging").renameTo(next))
        assertTrue(old.exists())
        assertEquals(next, PersistentAudioCacheMigration.migrate(old, next))
        assertFalse(old.exists())
        assertEquals("identity", File(next, "cache.uid").readText())
    }
    @Test fun aCorruptInterruptedCommitIsQuarantinedAndRetriedFromVerifiedLegacy() {
        val (old, next) = paths("corrupt-commit")
        File(old, "cache.uid").writeText("identity")
        PersistentAudioCacheMigration.migrate(old, next, rename = { _, _ -> false }, freeBytes = { Long.MAX_VALUE })
        assertTrue(File(next.parentFile, ".${next.name}.staging").renameTo(next))
        File(next, "cache.uid").writeText("corrupted")
        assertEquals(next, PersistentAudioCacheMigration.migrate(old, next))
        assertFalse(old.exists())
        assertEquals("identity", File(next, "cache.uid").readText())
    }
    @Test fun verifiedDestinationChangesNeverRollBackWhenLegacyCleanupPreviouslyFailed() {
        val (old, next) = paths("verified-commit")
        val db = StandaloneDatabaseProvider(app)
        SimpleCache(old, NoOpCacheEvictor(), db).also { write(it); it.release() }
        assertEquals(next, PersistentAudioCacheMigration.migrate(old, next,
            rename = { from, to -> from != old && from.renameTo(to) },
            freeBytes = { Long.MAX_VALUE }, deleteLegacy = { false }))
        assertTrue(old.exists())
        val live = SimpleCache(next, NoOpCacheEvictor(), db)
        live.applyContentMetadataMutations("youtube:recording:251", ContentMetadataMutations().set("new-metadata", "updated"))
        val newHole = live.startReadWrite("new-recording", 0, 128)
        try {
            val newFile = live.startFile("new-recording", 0, 128)
            newFile.writeBytes(ByteArray(128) { 7 }); live.commitFile(newFile, 128)
        } finally { live.releaseHoleSpan(newHole) }
        live.release()
        // The destination index and cached span timestamps can now differ from the
        // migration snapshot. Its durable commit marker forbids restoring the old copy.
        assertEquals(next, PersistentAudioCacheMigration.migrate(old, next))
        assertFalse(old.exists())
        val reopened = SimpleCache(next, NoOpCacheEvictor(), db)
        try {
            assertEquals("updated", reopened.getContentMetadata("youtube:recording:251").get("new-metadata", ""))
            assertTrue(reopened.isCached("youtube:recording:251", 0, 256))
            assertTrue(reopened.isCached("new-recording", 0, 128))
        } finally { reopened.release() }
    }
    @Test fun clearingCopiedLegacyFilesDoesNotDeleteTheLiveOwnersSharedUidDatabase() {
        val (old, next) = paths("clear-shared-uid")
        val db = StandaloneDatabaseProvider(app)
        SimpleCache(old, NoOpCacheEvictor(), db).also { write(it); it.release() }
        assertEquals(next, PersistentAudioCacheMigration.migrate(old, next,
            rename = { from, to -> from != old && from.renameTo(to) },
            freeBytes = { Long.MAX_VALUE }, deleteLegacy = { false }))
        val live = SimpleCache(next, NoOpCacheEvictor(), db)
        try {
            assertTrue(PersistentAudioCacheMigration.clearLegacy(old, next, next) > 0)
            assertFalse(old.exists())
            assertTrue(live.isCached("youtube:recording:251", 0, 256))
        } finally { live.release() }
        val reopened = SimpleCache(next, NoOpCacheEvictor(), db)
        try {
            assertEquals("codec=opus;itag=251", reopened.getContentMetadata("youtube:recording:251").get("representation", ""))
            assertTrue(reopened.isCached("youtube:recording:251", 0, 256))
        } finally { reopened.release() }
    }
    @Test fun independentDestinationAndInsufficientSpaceNeverDeleteLegacy() {
        val (old, next) = paths("existing")
        File(old, "old.uid").writeText("old")
        next.mkdirs(); File(next, "new.uid").writeText("new")
        assertEquals(next, PersistentAudioCacheMigration.migrate(old, next))
        assertEquals("old", File(old, "old.uid").readText())
        assertFalse(File(next, "old.uid").exists())
        next.deleteRecursively()
        assertEquals(old, PersistentAudioCacheMigration.migrate(old, next,
            rename = { _, _ -> false }, freeBytes = { 0L }))
        assertEquals("old", File(old, "old.uid").readText())
        assertFalse(next.exists())
    }
    @Test fun anOpenCacheCannotBeMoved() {
        val (old, next) = paths("open")
        val live = SimpleCache(old, NoOpCacheEvictor(), StandaloneDatabaseProvider(app))
        try {
            try { PersistentAudioCacheMigration.migrate(old, next); fail("Open owner must prevent migration") }
            catch (_: IllegalStateException) { }
            assertTrue(old.isDirectory)
            assertFalse(next.exists())
        } finally { live.release() }
    }
}
