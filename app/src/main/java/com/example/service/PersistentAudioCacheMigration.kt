package com.example.service

import android.content.Context
import android.os.Looper
import androidx.media3.datasource.cache.SimpleCache
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Moves an unopened Media3 directory as a unit, preserving its UID and database identity. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PersistentAudioCacheMigration {
    private const val DESTINATION = "xtreme_streamed_audio_cache"
    private const val JOURNAL_VERSION = 1
    private const val SPACE_MARGIN = 1024L * 1024L
    private data class Entry(val path: String, val length: Long, val digest: String)

    /** Must run before the sole SimpleCache owner opens either directory. Failed migrations
     * keep using the intact legacy owner for this process and retry on the next startup. */
    fun directory(context: Context): File {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Audio cache migration requires an IO thread" }
        val destination = File(context.noBackupFilesDir, DESTINATION)
        return migrate(File(context.cacheDir, "xtreme_audio_cache"), destination)
    }

    @Synchronized
    internal fun migrate(
        legacy: File,
        destination: File,
        rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
        freeBytes: () -> Long = { destination.parentFile!!.usableSpace },
        deleteLegacy: (File) -> Boolean = File::deleteRecursively
    ): File {
        val parent = requireNotNull(destination.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create persistent audio storage" }
        // Also serialize distinct callers/processes before examining the migration journal.
        return RandomAccessFile(File(parent, ".${destination.name}.migration.lock"), "rw").use { owner ->
            owner.channel.lock().use migration@ {
                check(!SimpleCache.isCacheFolderLocked(legacy) && !SimpleCache.isCacheFolderLocked(destination)) {
                    "Cannot migrate an open audio cache"
                }
                val staging = File(parent, ".${destination.name}.staging")
                val journal = File(parent, ".${destination.name}.migration")
                val verified = File(parent, ".${destination.name}.migration.verified")
                if (destination.exists() && destination.listFiles()?.isNotEmpty() != false) {
                    // Only a journal created by this migration authorizes legacy cleanup.
                    // An independent valid destination must never be overwritten or merged.
                    val committed = readJournal(verified)
                    if (committed != null) {
                        // Once a verified destination has been handed to SimpleCache,
                        // its spans and index may legitimately change. Never compare
                        // that live generation to the old copy or roll it back.
                        if (!legacy.exists() || matches(legacy, committed) && deleteLegacy(legacy)) {
                            staging.deleteRecursively(); journal.delete(); verified.delete()
                        }
                        return@migration destination
                    }
                    val expected = readJournal(journal)
                    if (expected != null && legacy.exists() && matches(legacy, expected) && !matches(destination, expected)) {
                        // The commit rename completed but verification did not. The
                        // journal proves this destination belongs to the interrupted
                        // migration; quarantine it while retaining the verified source.
                        if (staging.exists() && !staging.deleteRecursively()) return@migration legacy
                        if (!destination.renameTo(staging)) return@migration legacy
                    } else {
                        if (expected != null && matches(destination, expected) && (!legacy.exists() || matches(legacy, expected))) {
                            try { writeJournal(verified, expected) }
                            catch (_: IOException) { return@migration legacy.takeIf { it.exists() } ?: destination }
                            if (!legacy.exists() || deleteLegacy(legacy)) {
                                staging.deleteRecursively(); journal.delete(); verified.delete()
                            }
                        }
                        return@migration destination
                    }
                }
                if (!legacy.isDirectory) {
                    check(destination.isDirectory || destination.mkdirs()) { "Cannot create audio cache" }
                    staging.deleteRecursively(); journal.delete(); verified.delete()
                    return@migration destination
                }
                try {
                    val beforeMove = manifest(legacy, hashContents = false)
                    if (destination.exists() && !destination.delete()) throw IOException("Destination is not empty")
                    if (rename(legacy, destination)) {
                        if (!matches(destination, beforeMove, hashContents = false)) {
                            if (!rename(destination, legacy)) throw IOException("Cannot restore moved audio cache")
                            throw IOException("Moved audio cache did not verify")
                        }
                        staging.deleteRecursively(); journal.delete(); verified.delete()
                        return@migration destination
                    }
                    val expected = manifest(legacy)
                    // Retry a killed copy from scratch. Never copy into the live destination.
                    if (staging.exists() && !staging.deleteRecursively()) throw IOException("Cannot reset migration staging")
                    val total = expected.fold(0L) { bytes, entry -> Math.addExact(bytes, entry.length) }
                    if (freeBytes() < total || freeBytes() - total < SPACE_MARGIN)
                        throw IOException("Not enough space to migrate audio cache")
                    writeJournal(journal, expected)
                    if (!staging.mkdirs()) throw IOException("Cannot create audio staging")
                    for (entry in expected) {
                        val source = checkedChild(legacy, entry.path)
                        val target = checkedChild(staging, entry.path)
                        target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot copy audio directory") }
                        source.inputStream().use { input -> FileOutputStream(target).use { output ->
                            input.copyTo(output, 64 * 1024)
                            output.fd.sync()
                        } }
                    }
                    if (!matches(staging, expected) || !matches(legacy, expected)) throw IOException("Copied audio cache did not verify")
                    if (!rename(staging, destination)) throw IOException("Cannot commit audio migration")
                    if (!matches(destination, expected)) {
                        destination.renameTo(staging)
                        throw IOException("Committed audio cache did not verify")
                    }
                    // Persist the commit boundary BEFORE returning this directory to
                    // an owner which can change its metadata and spans immediately.
                    writeJournal(verified, expected)
                    if (deleteLegacy(legacy)) { journal.delete(); verified.delete() }
                    destination
                } catch (failure: IOException) {
                    android.util.Log.w("MusicCache", "Persistent audio migration will retry", failure)
                    // Prefer the intact original after any verification or copy error.
                    // If a rename succeeded but rollback was unavailable, the moved
                    // directory remains the only copy and must never be deleted.
                    if (legacy.isDirectory) legacy else destination
                }
            }
        }
    }

    /** Explicit cache clear removes unopened migration leftovers only. A copied
     * directory can share its UID with the live owner, so SimpleCache.delete must
     * NOT be used here: that would delete the live owner's database tables. */
    internal fun clearLegacy(context: Context, activeDirectory: File): Long = clearLegacy(
        File(context.cacheDir, "xtreme_audio_cache"), File(context.noBackupFilesDir, DESTINATION), activeDirectory)

    @Synchronized
    internal fun clearLegacy(legacy: File, destination: File, activeDirectory: File): Long {
        val parent = requireNotNull(destination.parentFile)
        if (!parent.isDirectory) return 0L
        return RandomAccessFile(File(parent, ".${destination.name}.migration.lock"), "rw").use { owner ->
            owner.channel.lock().use {
                val leftovers = listOf(legacy, destination, File(parent, ".${destination.name}.staging"),
                    File(parent, ".${destination.name}.migration"), File(parent, ".${destination.name}.migration.tmp"),
                    File(parent, ".${destination.name}.migration.verified"), File(parent, ".${destination.name}.migration.verified.tmp"))
                var freed = 0L
                for (entry in leftovers) {
                    if (entry.canonicalFile == activeDirectory.canonicalFile ||
                        entry.isDirectory && SimpleCache.isCacheFolderLocked(entry)) continue
                    val before = entry.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    entry.deleteRecursively()
                    val after = entry.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    freed += (before - after).coerceAtLeast(0L)
                }
                freed
            }
        }
    }

    private fun checkedChild(root: File, relative: String): File {
        val child = File(root, relative)
        if (relative.isBlank() || !child.canonicalPath.startsWith(root.canonicalPath + File.separator))
            throw IOException("Invalid audio migration entry")
        return child
    }
    private fun manifest(root: File, hashContents: Boolean = true): List<Entry> = root.walkTopDown().filter { it.isFile }.map { file ->
        val relative = file.relativeTo(root).invariantSeparatorsPath
        checkedChild(root, relative)
        Entry(relative, file.length(), if (hashContents) digest(file) else "")
    }.sortedBy { it.path }.toList()
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input -> while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun matches(root: File, expected: List<Entry>, hashContents: Boolean = true): Boolean = runCatching {
        root.isDirectory && manifest(root, hashContents) == expected
    }.getOrDefault(false)
    private fun writeJournal(file: File, entries: List<Entry>) {
        val temporary = File(file.path + ".tmp")
        FileOutputStream(temporary).use { output ->
            val data = DataOutputStream(output)
            data.writeInt(JOURNAL_VERSION); data.writeInt(entries.size)
            entries.forEach { data.writeUTF(it.path); data.writeLong(it.length); data.writeUTF(it.digest) }
            data.flush(); output.fd.sync()
        }
        if (!temporary.renameTo(file)) throw IOException("Cannot persist audio migration journal")
        try {
            val directory = android.system.Os.open(requireNotNull(file.parentFile).absolutePath,
                android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(directory) }
            finally { android.system.Os.close(directory) }
        } catch (error: android.system.ErrnoException) {
            throw IOException("Cannot sync audio migration journal directory", error)
        }
    }
    private fun readJournal(file: File): List<Entry>? = runCatching {
        DataInputStream(file.inputStream()).use { data ->
            if (data.readInt() != JOURNAL_VERSION) throw IOException("Unknown audio migration journal")
            val count = data.readInt()
            if (count !in 0..100_000) throw IOException("Invalid audio migration journal")
            List(count) { Entry(data.readUTF(), data.readLong(), data.readUTF()) }
        }
    }.getOrNull()
}
