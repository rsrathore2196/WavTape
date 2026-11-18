package com.example.data.local

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import android.util.Base64
import com.example.performance.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Small private copies keep avatars independent of gallery providers and their URI grants. */
internal object AvatarImageStore {
    internal const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    private const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
    private const val MAX_EDGE = 512
    private val ownedId = Regex("app-avatar://([a-f0-9]{32})")
    private val cleanupScope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)

    fun isOwned(value: String): Boolean = ownedId.matches(value)

    private fun ownedFile(context: Context, value: String): File? = ownedId.matchEntire(value)?.groupValues?.get(1)
        ?.let { File(File(context.filesDir, "profile_avatars"), "$it.png") }

    /** Resolves only our opaque IDs or legacy document URIs; no arbitrary file paths. No IO. */
    fun imageModel(context: Context, value: String): Any? = ownedFile(context, value)
        ?: value.takeIf { it.startsWith("content://") }?.let(Uri::parse)

    private fun readBounded(input: InputStream, limit: Int): ByteArray = input.use {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= limit) { "The selected avatar image is too large" }
            bytes.write(buffer, 0, count)
        }
        bytes.toByteArray()
    }

    private fun decodeUprightImage(source: ByteArray, maxEdge: Int, backup: Boolean = false): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth <= 32_768 && bounds.outHeight <= 32_768 &&
            bounds.outMimeType?.startsWith("image/") == true) { "The selected file is not a supported image" }
        if (backup) require(bounds.outMimeType == "image/png" && max(bounds.outWidth, bounds.outHeight) <= MAX_EDGE) {
            "Invalid avatar image in backup"
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > min(maxEdge * 2, 2_048)) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw IllegalArgumentException("The selected image could not be decoded")
        var oriented: Bitmap? = null
        var output: Bitmap? = null
        try {
            val orientation = if (backup) ExifInterface.ORIENTATION_NORMAL else runCatching {
                ExifInterface(source.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val transform = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            val upright = if (transform.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.width, bitmap.height, transform, true).also { oriented = it }
            val largest = max(upright.width, upright.height)
            output = if (largest > maxEdge) Bitmap.createScaledBitmap(upright,
                max(1, upright.width * maxEdge / largest), max(1, upright.height * maxEdge / largest), true) else upright
            return output
        } finally {
            oriented?.takeIf { it !== bitmap && it !== output }?.recycle()
            if (bitmap !== output) bitmap.recycle()
        }
    }

    private fun encodeImage(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().let { bytes ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) { "Could not prepare avatar image" }
        bytes.toByteArray().also { require(it.size <= MAX_IMAGE_BYTES) { "Avatar image is too large" } }
    }

    private fun normalizedImage(source: ByteArray, backup: Boolean = false): ByteArray {
        val bitmap = decodeUprightImage(source, MAX_EDGE, backup)
        return try { encodeImage(bitmap) } finally { bitmap.recycle() }
    }

    /** Decode only on IO. Retain enough detail for adjustment without decoding full camera photos. */
    fun readCropSource(context: Context, uri: Uri): Bitmap {
        require(uri.scheme == "content") { "Choose an image from the gallery" }
        val stream = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Image is unavailable")
        return decodeUprightImage(readBounded(stream, MAX_SOURCE_BYTES), maxEdge = 2_048)
    }

    fun importCrop(context: Context, source: Bitmap, transform: AvatarCropTransform): String {
        val cropped = renderAvatarCrop(source, transform)
        return try { writeImage(context, encodeImage(cropped)) } finally { cropped.recycle() }
    }

    /** Call on IO. Each save owns a unique immutable file, so rollback cannot overwrite an old avatar. */
    fun writeImage(context: Context, png: ByteArray): String {
        require(png.size <= MAX_IMAGE_BYTES) { "Avatar image is too large" }
        val value = "app-avatar://${UUID.randomUUID().toString().replace("-", "")}"
        val file = requireNotNull(ownedFile(context, value))
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Could not create avatar storage" }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(png); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
        return value
    }

    fun importImage(context: Context, uri: Uri): String {
        require(uri.scheme == "content") { "Choose an image from the gallery" }
        val stream = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Image is unavailable")
        return writeImage(context, normalizedImage(readBounded(stream, MAX_SOURCE_BYTES)))
    }

    /** Missing legacy gallery documents already display a default avatar; they must not block backups. */
    fun backupImage(context: Context, value: String): ByteArray? {
        if (value.isBlank()) return null
        return try {
            val file = ownedFile(context, value)
            val stream = if (file != null) file.inputStream() else value.takeIf { it.startsWith("content://") }
                ?.let { context.contentResolver.openInputStream(Uri.parse(it)) }
            stream?.let { normalizedImage(readBounded(it, MAX_SOURCE_BYTES)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { android.util.Log.w("AvatarImageStore", "Avatar image is unavailable for backup", error); null }
    }

    fun encodeBackup(png: ByteArray): JSONObject = JSONObject().put("mimeType", "image/png")
        .put("data", Base64.encodeToString(png, Base64.NO_WRAP))

    /** Validate before any restore mutation, including encoded size and actual decoded image type. */
    fun decodeBackup(value: Any): ByteArray {
        require(value is JSONObject && value.opt("mimeType") == "image/png" && value.opt("data") is String) {
            "Invalid avatar image in backup"
        }
        val encoded = value.getString("data")
        require(encoded.length <= ((MAX_IMAGE_BYTES + 2) / 3) * 4) { "Avatar backup is too large" }
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES &&
            Base64.encodeToString(bytes, Base64.NO_WRAP) == encoded) { "Invalid avatar image encoding" }
        return normalizedImage(bytes, backup = true)
    }

    fun discardIfUnreferenced(context: Context, value: String) {
        if (UserProfilePreferences.getUserProfile(context).avatarUri != value) ownedFile(context, value)?.delete()
    }

    fun discardLater(context: Context, values: Collection<String>) = cleanupScope.launch {
        values.forEach { runCatching { discardIfUnreferenced(context, it) } }
    }

    /** Run only after profile persistence succeeds; never revoke an unrelated backup-folder grant. */
    fun retirePrevious(context: Context, previous: String, current: String) {
        if (previous == current || UserProfilePreferences.getUserProfile(context).avatarUri != current) return
        runCatching { ownedFile(context, previous)?.delete() }
        if (previous.startsWith("content://") && previous != BackupRestoreManager.getAutoBackupLocation(context)) {
            val uri = Uri.parse(previous)
            if (!DocumentsContract.isTreeUri(uri)) runCatching {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

}

/** A dismissed editor owns its drafts, not the chosen gallery document or the saved avatar. */
internal class AvatarDraftSession(
    context: Context,
    initialDraft: String? = null,
    private val importImage: suspend (Context, Uri) -> String = { app, uri -> AvatarImageStore.importImage(app, uri) }
) {
    private val context = context.applicationContext
    private val drafts = mutableSetOf<String>()
    private var closed = false
    private var selection = 0L

    init { initialDraft?.takeIf(AvatarImageStore::isOwned)?.let(drafts::add) }

    suspend fun select(uri: Uri): String = createDraft { importImage(it, uri) }

    suspend fun selectCropped(source: Bitmap, transform: AvatarCropTransform): String =
        createDraft { AvatarImageStore.importCrop(it, source, transform) }

    private suspend fun createDraft(createImage: suspend (Context) -> String): String {
        val request = synchronized(this) {
            if (closed) throw CancellationException("Avatar editor was dismissed")
            ++selection
        }
        var created: String? = null
        try {
            val value = withContext(AppDispatchers.Io) {
                createImage(context).also { image ->
                    created = image
                    synchronized(this@AvatarDraftSession) { drafts.add(image) }
                }
            }
            // Check on the caller's dispatcher immediately before publishing to the UI.
            // A newer selection can start after IO completes but before this continuation runs.
            if (synchronized(this) { closed || request != selection }) {
                throw CancellationException("Avatar selection was replaced")
            }
            return value
        } catch (error: Throwable) {
            created?.let { image ->
                withContext(NonCancellable + AppDispatchers.Io) {
                    synchronized(this@AvatarDraftSession) { drafts.remove(image) }
                    AvatarImageStore.discardIfUnreferenced(context, image)
                }
            }
            throw error
        }
    }

    fun discardExcept(value: String): Job {
        val discarded = synchronized(this) {
            // Choosing a default or committing this draft also supersedes any pending import.
            selection++
            drafts.filter { it != value }.also { drafts.removeAll(it.toSet()) }
        }
        return AvatarImageStore.discardLater(context, discarded)
    }

    fun keep(value: String) { synchronized(this) { drafts.remove(value) } }

    fun close(preserve: String? = null): Job {
        val discarded = synchronized(this) {
            closed = true
            selection++
            drafts.filter { it != preserve }.also { drafts.clear() }
        }
        return AvatarImageStore.discardLater(context, discarded)
    }
}
