package com.example

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarImageStorageTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var previous: UserProfile
    private val directory get() = File(context.filesDir, "profile_avatars")

    @Before fun before() { previous = UserProfilePreferences.getUserProfile(context) }
    @After fun after() { UserProfilePreferences.saveUserProfile(context, previous); directory.deleteRecursively() }

    private fun png(width: Int = 32, height: Int = 24): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun document(name: String, image: ByteArray = png()): Uri = Uri.parse("content://avatar-test/$name").also {
        shadowOf(context.contentResolver).registerInputStream(it, image.inputStream())
    }

    @Test fun galleryPhotoIsBoundedAndOwnedWithoutTakingPersistentPermission() = runBlocking {
        val grants = context.contentResolver.persistedUriPermissions.map { it.uri }
        val session = AvatarDraftSession(context)
        val value = session.select(document("large", png(1600, 900)))
        val file = AvatarImageStore.imageModel(context, value) as File
        val decoded = BitmapFactory.decodeFile(file.path)
        try {
            assertTrue(value.startsWith("app-avatar://"))
            assertEquals(512, decoded.width)
            assertTrue(decoded.height <= 512)
            assertTrue(file.length() <= AvatarImageStore.MAX_IMAGE_BYTES)
            assertEquals(grants, context.contentResolver.persistedUriPermissions.map { it.uri })
        } finally { decoded.recycle(); session.close().join() }
        assertFalse("Canceled drafts are removed", file.exists())
    }

    @Test fun replacementAndCancelRemoveOnlyDraftsAndRotationPreservesTheSelectedDraft() = runBlocking {
        val saved = withContext(Dispatchers.IO) { AvatarImageStore.writeImage(context, png()) }
        UserProfilePreferences.saveUserProfile(context, previous.copy(avatarUri = saved))
        val session = AvatarDraftSession(context)
        val first = session.select(document("first"))
        val second = session.select(document("second"))
        session.discardExcept(second).join()
        assertFalse((AvatarImageStore.imageModel(context, first) as File).exists())
        session.close(preserve = second).join()
        val secondFile = AvatarImageStore.imageModel(context, second) as File
        assertTrue("A configuration change retains the draft", secondFile.exists())
        AvatarDraftSession(context, second).close().join()
        assertFalse(secondFile.exists())
        assertTrue("Cancel must not delete the saved photo", (AvatarImageStore.imageModel(context, saved) as File).exists())
    }

    @Test fun cameraExifOrientationIsAppliedBeforeThePrivatePngIsSaved() = runBlocking {
        val bitmap = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        android.graphics.Canvas(bitmap).drawRect(15f, 0f, 30f, 20f,
            android.graphics.Paint().apply { color = android.graphics.Color.BLUE })
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray()
        bitmap.recycle()
        // EXIF APP1, little-endian TIFF, one SHORT orientation tag: rotate 90 degrees clockwise.
        val exif = listOf(0xff, 0xe1, 0x00, 0x22, 0x45, 0x78, 0x69, 0x66, 0, 0,
            0x49, 0x49, 0x2a, 0, 8, 0, 0, 0, 1, 0, 0x12, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0)
            .map { it.toByte() }.toByteArray()
        val session = AvatarDraftSession(context)
        val value = session.select(document("camera", jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size)))
        val decoded = BitmapFactory.decodeFile((AvatarImageStore.imageModel(context, value) as File).path)
        try {
            assertEquals(20, decoded.width)
            assertEquals(30, decoded.height)
            assertTrue(android.graphics.Color.red(decoded.getPixel(5, 5)) > 200)
            assertTrue(android.graphics.Color.blue(decoded.getPixel(5, 25)) > 200)
        } finally { decoded.recycle(); session.close().join() }
    }

    @Test fun aLatePickerResultCannotLeakAnImageAfterTheEditorCloses() = runBlocking {
        val session = AvatarDraftSession(context)
        session.close().join()
        try { session.select(document("late")); fail("A closed editor must reject the result") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun aSlowPreviousPickerResultCannotReplaceOrDeleteTheNewerPhoto() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val session = AvatarDraftSession(context, importImage = { app, uri ->
            if (uri.lastPathSegment == "first") {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            AvatarImageStore.writeImage(app, png())
        })
        val first = async { session.select(Uri.parse("content://avatar-test/first")) }
        try {
            firstStarted.await()
            val newest = async { session.select(Uri.parse("content://avatar-test/second")) }.await()
            session.discardExcept(newest).join()
            val newestFile = AvatarImageStore.imageModel(context, newest) as File
            releaseFirst.complete(Unit)
            assertTrue(runCatching { first.await() }.exceptionOrNull() is CancellationException)
            assertTrue("An older import must not delete the chosen photo", newestFile.exists())
            assertEquals(listOf(newestFile.name), directory.listFiles().orEmpty().map { it.name })
        } finally {
            releaseFirst.complete(Unit)
            first.cancelAndJoin()
            session.close().join()
        }
    }

    @Test fun choosingADefaultWhileAnImportIsPendingRejectsAndCleansTheLatePhoto() = runBlocking {
        val saved = withContext(Dispatchers.IO) { AvatarImageStore.writeImage(context, png()) }
        UserProfilePreferences.saveUserProfile(context, previous.copy(avatarUri = saved))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = AvatarDraftSession(context, importImage = { app, _ ->
            started.complete(Unit)
            release.await()
            AvatarImageStore.writeImage(app, png())
        })
        val pending = async { session.select(Uri.parse("content://avatar-test/pending")) }
        try {
            started.await()
            session.discardExcept("").join()
            release.complete(Unit)
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
            val savedFile = AvatarImageStore.imageModel(context, saved) as File
            assertTrue("Selecting a default must not delete the saved profile's photo", savedFile.exists())
            assertEquals(listOf(savedFile.name), directory.listFiles().orEmpty().map { it.name })
        } finally {
            release.complete(Unit)
            pending.cancelAndJoin()
            session.close().join()
        }
    }

    @Test fun backupIncludesImageBytesAndRestoresThemAfterOriginalFilesAreGone() = runBlocking {
        val source = MusicDatabase.createInMemoryDatabase(context)
        val target = MusicDatabase.createInMemoryDatabase(context)
        val folder = File(context.filesDir, "portable-avatar-backup")
        val oldLocation = BackupRestoreManager.getAutoBackupLocation(context)
        try {
            val session = AvatarDraftSession(context)
            val image = session.select(document("portable"))
            session.keep(image); session.close().join()
            UserProfilePreferences.saveUserProfile(context, UserProfile(name = "Listener", avatarId = "radio", avatarUri = image))
            BackupRestoreManager.setAutoBackupLocation(context, folder.path)
            val backup = BackupRestoreManager.createBackup(context, source).getOrThrow()
            val body = File(backup.filePath).readText()
            val profileJson = JSONObject(body).getJSONObject("userProfile")
            assertEquals("image/png", profileJson.getJSONObject("avatarImage").getString("mimeType"))
            assertEquals("", profileJson.getString("avatarUri"))
            directory.deleteRecursively()
            UserProfilePreferences.saveUserProfile(context, UserProfile())
            val result = BackupRestoreManager.restoreBackup(context, target, rawJson = body)
            assertTrue(result.message, result.success)
            val restored = UserProfilePreferences.getUserProfile(context)
            assertEquals("radio", restored.avatarId)
            assertNotEquals(image, restored.avatarUri)
            val decoded = BitmapFactory.decodeFile((AvatarImageStore.imageModel(context, restored.avatarUri) as File).path)
            try { assertEquals(android.graphics.Color.MAGENTA, decoded.getPixel(10, 10)) }
            finally { decoded.recycle() }
        } finally {
            source.close(); target.close(); folder.deleteRecursively()
            BackupRestoreManager.setAutoBackupLocation(context, oldLocation)
        }
    }

    @Test fun invalidOrOversizedBackupImagesDoNotChangeTheProfileOrDatabase() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(context)
        val before = previous.copy(name = "Keep me")
        UserProfilePreferences.saveUserProfile(context, before)
        val invalid = listOf(
            JSONObject().put("mimeType", "text/plain").put("data", "YWJj"),
            JSONObject().put("mimeType", "image/png").put("data", "invalid!!"),
            AvatarImageStore.encodeBackup(png(600, 1)),
            JSONObject().put("mimeType", "image/png").put("data", "A".repeat(3 * 1024 * 1024))
        )
        try {
            for (image in invalid) {
                val json = JSONObject().put("schemaVersion", 7).put("userProfile",
                    JSONObject().put("name", "Overwrite").put("avatarImage", image))
                assertFalse(BackupRestoreManager.restoreBackup(context, db, rawJson = json.toString()).success)
                assertEquals(before, UserProfilePreferences.getUserProfile(context))
                assertTrue(db.musicDao().getAllTracksSync().isEmpty())
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            }
            assertNull(AvatarImageStore.imageModel(context, "file:///data/private/secret"))
            assertNull(AvatarImageStore.imageModel(context, "app-avatar://../../secret"))
        } finally { db.close() }
    }

    @Test fun failedProfilePersistenceRollsBackTheStagedAvatarAndPreservesTheOldPhoto() = runBlocking {
        val original = withContext(Dispatchers.IO) { AvatarImageStore.writeImage(context, png()) }
        val before = previous.copy(name = "Original", avatarUri = original)
        UserProfilePreferences.saveUserProfile(context, before)
        val failing = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val prefs = super.getSharedPreferences(name, mode)
                if (name != "xtreme_user_profile_prefs") return prefs
                return object : SharedPreferences by prefs {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = prefs.edit()
                        return object : SharedPreferences.Editor by editor {
                            private var imported = false
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                if (key == "user_name" && value == "Overwrite") imported = true
                                editor.putString(key, value)
                                return this
                            }
                            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                                editor.putBoolean(key, value)
                                return this
                            }
                            override fun commit(): Boolean = editor.commit() && !imported
                        }
                    }
                }
            }
        }
        val db = MusicDatabase.createInMemoryDatabase(context)
        try {
            val json = JSONObject().put("schemaVersion", 7).put("userProfile", JSONObject().put("name", "Overwrite")
                .put("avatarImage", AvatarImageStore.encodeBackup(png())))
            assertFalse(BackupRestoreManager.restoreBackup(failing, db, rawJson = json.toString()).success)
            assertEquals(before, UserProfilePreferences.getUserProfile(context))
            assertEquals(1, directory.listFiles().orEmpty().size)
            assertTrue((AvatarImageStore.imageModel(context, original) as File).exists())
        } finally { db.close() }
    }
}
