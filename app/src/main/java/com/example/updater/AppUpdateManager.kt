package com.example.updater

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long = 0L,
    val publishedAt: String = "",
    val htmlUrl: String = "",
    val sha256: String? = null
)

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateStatus()
    data class UpToDate(val currentVersion: String) : UpdateStatus()
    data class Downloading(
        val progressPercent: Int,
        val downloadId: Long,
        val updateInfo: UpdateInfo,
        val message: String = "Downloading Update",
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = updateInfo.apkSizeBytes
    ) : UpdateStatus()
    data class ReadyToInstall(
        val apkFile: File,
        val updateInfo: UpdateInfo,
        val message: String = "Tap to complete installation"
    ) : UpdateStatus()
    data class Error(
        val message: String,
        val showNotice: Boolean = false,
        val retryInfo: UpdateInfo? = null
    ) : UpdateStatus()
}

object AppUpdateManager {
    private const val LATEST = "https://api.github.com/repos/rsrathore2196/WavTape/releases/latest"
    private val assetPaths = listOf(
        "/rsrathore2196/WavTape/releases/download/",
        "/rsrathore2196/Xtreme-Player/releases/download/"
    )
    private val abiInAssetName = Regex("(?<![a-z0-9])(arm64-v8a|armeabi-v7a|x86_64|x86)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private const val MAX_AGE = 24 * 60 * 60 * 1000L
    private const val MAX_REDIRECTS = 6

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val downloadClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus = _updateStatus.asStateFlow()
    private val _isDismissedForSession = MutableStateFlow(false)
    val isDismissedForSession = _isDismissedForSession.asStateFlow()
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress = _downloadProgress.asStateFlow()

    private var session: Session? = null
    private var initialized = false
    private var foreground = false
    private var downloadJob: Job? = null
    private var checking: Job? = null
    private var checkListeners = UpdateCheckListeners()
    @Volatile private var manualCheckRequested = false
    private var installing = false

    private data class Session(
        val id: Long,
        val info: UpdateInfo,
        val name: String,
        val created: Long,
        val ready: Boolean = false,
        val installRequested: Boolean = true,
        val waitingPermission: Boolean = false
    )

    private fun extractVersion(value: String): String? {
        val candidate = value.trim()
        if (candidate.isEmpty()) return null
        return Regex("""(?i)(?:^|[^0-9])v?(\d+(?:\.\d+)*)(?!\d)""").find(candidate)?.groupValues?.get(1)
    }

    fun dismissForSession() {
        _isDismissedForSession.value = true
    }

    fun showUpdatePopup() {
        _isDismissedForSession.value = false
    }

    private fun prefs(c: Context) = c.getSharedPreferences("apk_update_session_v2", Context.MODE_PRIVATE)
    private fun updatesDir(c: Context) = File(c.filesDir, "update_apks")
    private fun staged(c: Context, s: Session) = File(updatesDir(c), s.name)
    private fun partFile(c: Context, s: Session) = File(updatesDir(c), "${s.name}.part")

    internal fun validAsset(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.port == -1 &&
            uri.userInfo == null &&
            assetPaths.any { uri.path.orEmpty().startsWith(it) } &&
            uri.query == null &&
            uri.fragment == null
    }

    internal fun isTrustedDownloadRedirect(url: String): Boolean {
        val uri = Uri.parse(url)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return false
        val host = uri.host?.lowercase().orEmpty()
        return host == "github.com" ||
            host.endsWith(".github.com") ||
            host == "githubusercontent.com" ||
            host.endsWith(".githubusercontent.com")
    }

    private fun infoJson(i: UpdateInfo) = JSONObject()
        .put("version", i.versionName)
        .put("title", i.releaseTitle)
        .put("notes", i.releaseNotes)
        .put("url", i.apkDownloadUrl)
        .put("asset", i.apkFileName)
        .put("size", i.apkSizeBytes)
        .put("published", i.publishedAt)
        .put("html", i.htmlUrl)
        .put("sha256", i.sha256.orEmpty())

    private suspend fun persist(c: Context, s: Session?) = withContext(Dispatchers.IO) {
        val editor = prefs(c).edit()
        if (s == null) {
            editor.remove("session")
        } else {
            editor.putString(
                "session",
                JSONObject()
                    .put("id", s.id)
                    .put("info", infoJson(s.info))
                    .put("name", s.name)
                    .put("created", s.created)
                    .put("ready", s.ready)
                    .put("requested", s.installRequested)
                    .put("permission", s.waitingPermission)
                    .toString()
            )
        }
        check(editor.commit()) { "Could not save update download state." }
    }

    private suspend fun restore(c: Context) {
        if (initialized) return
        initialized = true
        session = withContext(Dispatchers.IO) {
            prefs(c).getString("session", null)?.let { value ->
                try {
                    val j = JSONObject(value)
                    val i = j.getJSONObject("info")
                    val info = UpdateInfo(
                        versionName = i.getString("version"),
                        releaseTitle = i.getString("title"),
                        releaseNotes = i.getString("notes"),
                        apkDownloadUrl = i.getString("url"),
                        apkFileName = i.getString("asset"),
                        apkSizeBytes = i.getLong("size"),
                        publishedAt = i.optString("published"),
                        htmlUrl = i.optString("html"),
                        sha256 = i.optString("sha256").takeIf(String::isNotBlank)
                    )
                    val name = j.getString("name")
                    require(name.matches(Regex("xtreme-update-[a-f0-9-]+\\.apk")) && validAsset(info.apkDownloadUrl))
                    Session(
                        id = j.getLong("id"),
                        info = info,
                        name = name,
                        created = j.getLong("created"),
                        ready = j.optBoolean("ready"),
                        installRequested = j.optBoolean("requested"),
                        waitingPermission = j.optBoolean("permission")
                    )
                } catch (_: Exception) {
                    prefs(c).edit().remove("session").commit()
                    null
                }
            }
        }
    }

    fun onForeground(context: Context) {
        foreground = true
        val app = context.applicationContext
        scope.launch {
            operations.withLock {
                try {
                    restore(app)
                    if (session != null) {
                        inspectReadySession(app)
                        resumeInstall(app, fromResume = true)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(app, e.message ?: "Could not recover the update.")
                }
            }
        }
    }

    fun onBackground() {
        foreground = false
    }

    fun checkForUpdate(context: Context, isManual: Boolean = false, onResult: ((UpdateStatus) -> Unit)? = null) {
        if (checking?.isActive == true) {
            if (isManual) manualCheckRequested = true
            checkListeners.add(onResult)
            return
        }
        manualCheckRequested = isManual
        val listeners = UpdateCheckListeners().also { it.add(onResult); checkListeners = it }
        val app = context.applicationContext
        checking = scope.launch {
            try {
                val recovering = operations.withLock {
                    restore(app)
                    val currentSession = session
                    if (currentSession != null) {
                        if (downloadJob?.isActive == true) {
                            if (manualCheckRequested) _isDismissedForSession.value = false
                            listeners.complete(_updateStatus.value)
                            true
                        } else if (currentSession.ready) {
                            inspectReadySession(app)
                            if (session != null) {
                                if (manualCheckRequested) _isDismissedForSession.value = false
                                listeners.complete(_updateStatus.value)
                                true
                            } else {
                                false
                            }
                        } else {
                            // Interrupted download from an earlier app process: clean up so check runs fresh.
                            clear(app)
                            false
                        }
                    } else {
                        false
                    }
                }
                if (recovering) return@launch

                // Never hold the download/installer lock while waiting for optional
                // startup admission. Recovery and manual checks remain immediate.
                com.example.performance.BackgroundWorkCoordinator.startup(
                    if (isManual) com.example.performance.BackgroundWorkCoordinator.StartupPriority.CRITICAL
                    else com.example.performance.BackgroundWorkCoordinator.StartupPriority.MAINTENANCE,
                    urgent = { manualCheckRequested }
                ) {
                    operations.withLock {
                        if (downloadJob?.isActive == true || session?.ready == true) {
                            if (manualCheckRequested) _isDismissedForSession.value = false
                            listeners.complete(_updateStatus.value)
                            return@withLock
                        }
                        _updateStatus.value = UpdateStatus.Checking
                        val result = withContext(Dispatchers.IO) { latestRelease() }
                        _updateStatus.value = result
                        if (manualCheckRequested && result is UpdateStatus.UpdateAvailable) {
                            _isDismissedForSession.value = false
                        }
                        listeners.complete(result)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = UpdateStatus.Error(
                    message = e.message ?: "Could not check for updates.",
                    showNotice = false
                )
                _updateStatus.value = error
                listeners.complete(error)
            }
        }
    }

    private fun get(url: String, maxBytes: Long = 2_000_000, allowNotFound: Boolean = false): String? =
        client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "WavTape-Android/${BuildConfig.VERSION_NAME}")
                .build()
        ).execute().use { response ->
            if (allowNotFound && response.code == 404) return null
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        403, 429 -> "Update server rate limit reached. Please try again later."
                        else -> "Update server returned HTTP ${response.code}."
                    }
                )
            }
            val body = response.body ?: throw IOException("Empty update response.")
            val buffer = okio.Buffer()
            var total = 0L
            while (total <= maxBytes) {
                val count = body.source().read(buffer, minOf(8192L, maxBytes + 1 - total))
                if (count < 0) break
                total += count
            }
            require(total <= maxBytes) { "Update metadata exceeds the allowed size." }
            buffer.readUtf8()
        }

    private fun latestRelease(): UpdateStatus {
        val raw = get(LATEST, allowNotFound = true) ?: return UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)
        return parseReleaseJson(
            rawJson = raw,
            currentVersion = BuildConfig.VERSION_NAME,
            supportedAbis = Build.SUPPORTED_ABIS,
            fetchSidecar = { url ->
                require(validAsset(url)) { "Untrusted release checksum URL." }
                get(url, maxBytes = 128_000) ?: throw IOException("Empty release checksum file.")
            }
        )
    }

    internal fun parseReleaseJson(
        rawJson: String,
        currentVersion: String = BuildConfig.VERSION_NAME,
        supportedAbis: Array<String> = Build.SUPPORTED_ABIS,
        fetchSidecar: ((String) -> String)? = null
    ): UpdateStatus {
        val json = JSONObject(rawJson)
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
            return UpdateStatus.UpToDate(currentVersion)
        }
        val version = extractVersion(json.optString("tag_name"))
            ?: extractVersion(json.optString("name"))
            ?: return UpdateStatus.UpToDate(currentVersion)
        if (!isNewerVersion(version, currentVersion)) {
            return UpdateStatus.UpToDate(currentVersion)
        }

        val array = json.optJSONArray("assets")
        val assets = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
        val allowedTypes = setOf(
            "application/vnd.android.package-archive",
            "application/octet-stream",
            "application/zip",
            ""
        )
        val apks = assets.filter { a ->
            val assetName = a.optString("name")
            val downloadUrl = a.optString("browser_download_url")
            val state = a.optString("state")
            assetName.endsWith(".apk", ignoreCase = true) &&
                validAsset(downloadUrl) &&
                Uri.parse(downloadUrl).lastPathSegment.orEmpty().endsWith(".apk", ignoreCase = true) &&
                a.optLong("size") > 0L &&
                (state.isEmpty() || state == "uploaded") &&
                a.optString("content_type").lowercase() in allowedTypes
        }

        val byAbi = apks.groupBy { abiInAssetName.find(it.optString("name"))?.value?.lowercase(Locale.ROOT) }
        val generic = byAbi[null].orEmpty()
        val apk = generic.firstOrNull { it.optString("name").contains("universal", ignoreCase = true) }
            ?: generic.singleOrNull()?.takeIf { apks.size == 1 }
            ?: supportedAbis.firstNotNullOfOrNull { abi ->
                byAbi[abi.lowercase(Locale.ROOT)]?.singleOrNull()
            }
            ?: generic.singleOrNull { !it.optString("name").contains("debug", ignoreCase = true) }
            ?: throw IOException("This release has no compatible APK asset.")

        val name = apk.getString("name")
        val digest = apk.optString("digest")
            .takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.lowercase()
        if (digest != null) {
            require(digest.matches(Regex("[a-f0-9]{64}"))) { "The release SHA-256 is malformed." }
        }

        val sidecar = assets.firstOrNull {
            it.optString("name").equals("$name.sha256", ignoreCase = true) ||
                it.optString("name").equals("$name.sha256sum", ignoreCase = true)
        } ?: assets.firstOrNull {
            it.optString("name").lowercase() in setOf("sha256sums", "sha256sums.txt", "checksums.txt")
        }

        val checksum = digest ?: sidecar?.let { asset ->
            val url = asset.getString("browser_download_url")
            val content = fetchSidecar?.invoke(url) ?: return@let null
            val matches = content.lineSequence().mapNotNull { line ->
                val trimmed = line.trim()
                val m = Regex("^([a-fA-F0-9]{64})\\s+\\*?(.+?)\\s*$").matchEntire(trimmed)
                val bsd = Regex("^SHA256 \\((.+)\\) = ([a-fA-F0-9]{64})$", RegexOption.IGNORE_CASE).matchEntire(trimmed)
                when {
                    m != null && m.groupValues[2].removePrefix("./") == name -> m.groupValues[1].lowercase()
                    bsd != null && bsd.groupValues[1] == name -> bsd.groupValues[2].lowercase()
                    asset.optString("name").startsWith("$name.", ignoreCase = true) &&
                        trimmed.matches(Regex("[a-fA-F0-9]{64}")) -> trimmed.lowercase()
                    else -> null
                }
            }.distinct().toList()
            matches.singleOrNull() ?: throw IOException("No unique SHA-256 for this APK in the published checksum file.")
        }

        val notesChecksum = if (checksum == null && apks.size == 1) {
            Regex("(?i)SHA[- ]?256\\s*[:=]\\s*`?([a-f0-9]{64})(?![a-f0-9])")
                .findAll(json.optString("body"))
                .map { it.groupValues[1].lowercase() }
                .distinct()
                .toList()
                .singleOrNull()
        } else null

        return UpdateStatus.UpdateAvailable(
            UpdateInfo(
                versionName = version,
                releaseTitle = json.optString("name", version).ifBlank { version },
                releaseNotes = json.optString("body"),
                apkDownloadUrl = apk.getString("browser_download_url"),
                apkFileName = name,
                apkSizeBytes = apk.getLong("size"),
                publishedAt = json.optString("published_at"),
                htmlUrl = json.optString("html_url"),
                sha256 = checksum ?: notesChecksum
            )
        )
    }

    fun isNewerVersion(remoteVersion: String, localVersion: String): Boolean {
        fun parts(v: String): List<Long>? {
            val numeric = extractVersion(v) ?: return null
            return numeric.split('.').map { it.toLongOrNull() ?: return null }
        }
        val remote = parts(remoteVersion) ?: return false
        val local = parts(localVersion) ?: return false
        for (index in 0 until maxOf(remote.size, local.size)) {
            val comparison = remote.getOrElse(index) { 0L }.compareTo(local.getOrElse(index) { 0L })
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    fun downloadAndInstallUpdate(context: Context, updateInfo: UpdateInfo) {
        val app = context.applicationContext
        foreground = true
        scope.launch {
            operations.withLock {
                try {
                    restore(app)
                    if (downloadJob?.isActive == true) {
                        _isDismissedForSession.value = false
                        return@withLock
                    }
                    val existing = session
                    if (existing != null && existing.ready && existing.info == updateInfo) {
                        inspectReadySession(app)
                        if (session?.ready == true) {
                            session = session?.copy(installRequested = true)
                            persist(app, session)
                            _isDismissedForSession.value = false
                            resumeInstall(app)
                            return@withLock
                        }
                    } else if (existing != null) {
                        clear(app)
                    }

                    val allowedInfo = (_updateStatus.value as? UpdateStatus.UpdateAvailable)?.updateInfo
                        ?: (_updateStatus.value as? UpdateStatus.Error)?.retryInfo
                    require(allowedInfo == updateInfo) { "Check for this update again before downloading." }
                    require(
                        validAsset(updateInfo.apkDownloadUrl) &&
                            updateInfo.apkFileName.endsWith(".apk", ignoreCase = true) &&
                            updateInfo.apkSizeBytes > 0L
                    ) { "Invalid release APK." }

                    _isDismissedForSession.value = false
                    val downloadId = System.currentTimeMillis()
                    val newSession = Session(
                        id = downloadId,
                        info = updateInfo,
                        name = "xtreme-update-${UUID.randomUUID()}.apk",
                        created = downloadId,
                        ready = false,
                        installRequested = true,
                        waitingPermission = false
                    )
                    session = newSession
                    persist(app, newSession)
                    _downloadProgress.value = 0
                    _updateStatus.value = UpdateStatus.Downloading(
                        progressPercent = 0,
                        downloadId = downloadId,
                        updateInfo = updateInfo,
                        message = "Connecting to download server...",
                        downloadedBytes = 0L,
                        totalBytes = updateInfo.apkSizeBytes
                    )

                    downloadJob = scope.launch {
                        runStreamingDownload(app, newSession)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(app, e.message ?: "Could not start the download.", retryInfo = updateInfo)
                }
            }
        }
    }

    fun cancelDownload(context: Context) {
        val app = context.applicationContext
        scope.launch {
            operations.withLock {
                val pending = session?.takeUnless { it.ready } ?: return@withLock
                try {
                    clear(app)
                    _downloadProgress.value = 0
                    _updateStatus.value = UpdateStatus.UpdateAvailable(pending.info)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    fail(app, e.message ?: "Could not cancel the update download.", retryInfo = pending.info)
                }
            }
        }
    }

    private suspend fun publishDownloadProgress(active: Session, status: UpdateStatus.Downloading) =
        withContext(Dispatchers.Main.immediate) {
            if (session?.name == active.name) {
                _downloadProgress.value = status.progressPercent
                _updateStatus.value = status
            }
        }

    private suspend fun runStreamingDownload(app: Context, activeSession: Session) {
        val info = activeSession.info
        try {
            val message = withContext(Dispatchers.IO) {
                val dir = updatesDir(app)
                check(dir.mkdirs() || dir.isDirectory) { "Update storage is unavailable." }
                // Remove stale update APKs or partial downloads before starting.
                dir.listFiles()?.forEach { existing ->
                    currentCoroutineContext().ensureActive()
                    if (existing.name.startsWith("xtreme-update-")) {
                        runCatching { existing.delete() }
                    }
                }

                val tempFile = partFile(app, activeSession)
                val finalFile = staged(app, activeSession)

                openValidatedDownloadResponse(info.apkDownloadUrl).use { response ->
                    val body = response.body ?: throw IOException("Empty APK download response.")
                    val contentLength = body.contentLength()
                    val expectedTotal = when {
                        contentLength > 0L -> contentLength
                        info.apkSizeBytes > 0L -> info.apkSizeBytes
                        else -> -1L
                    }
                    if (info.apkSizeBytes > 0L && contentLength > 0L && contentLength != info.apkSizeBytes) {
                        throw IOException("The APK file size does not match the release.")
                    }

                    var downloaded = 0L
                    var lastReportTime = 0L
                    var lastPercent = -1
                    val buffer = ByteArray(64 * 1024)

                    FileOutputStream(tempFile).use { output ->
                        body.byteStream().use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                currentCoroutineContext().ensureActive()
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read

                                if (info.apkSizeBytes > 0L && downloaded > info.apkSizeBytes) {
                                    throw IOException("The APK download exceeded the expected release size.")
                                }

                                val percent = if (expectedTotal > 0L) {
                                    ((downloaded.toDouble() / expectedTotal) * 100.0).toInt().coerceIn(0, 99)
                                } else {
                                    0
                                }
                                val now = System.currentTimeMillis()
                                if (percent != lastPercent || now - lastReportTime >= 150L) {
                                    lastPercent = percent
                                    lastReportTime = now
                                    publishDownloadProgress(activeSession, UpdateStatus.Downloading(
                                        progressPercent = percent,
                                        downloadId = activeSession.id,
                                        updateInfo = info,
                                        message = "Downloading update...",
                                        downloadedBytes = downloaded,
                                        totalBytes = if (expectedTotal > 0L) expectedTotal else downloaded
                                    ))
                                }
                            }
                            output.flush()
                        }
                    }

                    require(downloaded > 0L && (expectedTotal <= 0L || downloaded == expectedTotal)) {
                        "The APK download was interrupted before completing."
                    }
                }

                currentCoroutineContext().ensureActive()
                if (finalFile.exists()) finalFile.delete()
                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }

                publishDownloadProgress(activeSession, UpdateStatus.Downloading(
                    progressPercent = 99,
                    downloadId = activeSession.id,
                    updateInfo = info,
                    message = "Verifying APK integrity...",
                    downloadedBytes = finalFile.length(),
                    totalBytes = finalFile.length()
                ))

                ApkVerifier.verify(app, finalFile, info)
            }

            operations.withLock {
                if (session?.name != activeSession.name) return@withLock
                val completed = activeSession.copy(ready = true)
                session = completed
                persist(app, completed)
                downloadJob = null
                _downloadProgress.value = 100
                _isDismissedForSession.value = false
                _updateStatus.value = UpdateStatus.ReadyToInstall(staged(app, completed), info, message)
                resumeInstall(app)
            }
        } catch (e: CancellationException) {
            withContext(Dispatchers.IO + NonCancellable) {
                runCatching { partFile(app, activeSession).delete() }
                runCatching { staged(app, activeSession).delete() }
            }
            throw e
        } catch (e: ObsoleteApk) {
            operations.withLock {
                if (session?.name != activeSession.name) return@withLock
                clear(app)
                _downloadProgress.value = 0
                _updateStatus.value = UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)
            }
        } catch (e: Exception) {
            operations.withLock {
                if (session?.name == activeSession.name) {
                    downloadJob = null
                    fail(app, e.message ?: "Update download failed. Please retry.", retryInfo = info)
                }
            }
        }
    }

    private fun openValidatedDownloadResponse(initialUrl: String): Response {
        var currentUrl = initialUrl
        require(validAsset(currentUrl)) { "Untrusted APK download URL." }

        repeat(MAX_REDIRECTS + 1) {
            val response = downloadClient.newCall(
                Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "WavTape-Android/${BuildConfig.VERSION_NAME}")
                    .header("Accept", "application/octet-stream")
                    .build()
            ).execute()

            if (response.code in listOf(301, 302, 303, 307, 308)) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) {
                    throw IOException("Update server returned an empty redirect.")
                }
                val resolved = response.request.url.resolve(location)?.toString()
                    ?: throw IOException("Invalid update redirect URL.")
                require(isTrustedDownloadRedirect(resolved)) { "Blocked redirect to untrusted host." }
                currentUrl = resolved
            } else {
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    throw IOException(
                        when (code) {
                            403, 429 -> "Download server rate limit reached. Please try again later."
                            404 -> "The release APK file was not found on the server."
                            else -> "Update download failed (HTTP $code). Please retry."
                        }
                    )
                }
                return response
            }
        }
        throw IOException("Too many redirects while downloading update.")
    }

    private suspend fun inspectReadySession(app: Context) {
        val s = session ?: return
        if (!s.ready) {
            if (downloadJob?.isActive != true) {
                clear(app)
            }
            return
        }
        if (System.currentTimeMillis() - s.created > MAX_AGE) {
            clear(app)
            _updateStatus.value = UpdateStatus.Idle
            return
        }
        val file = staged(app, s)
        if (!file.isFile) {
            clear(app)
            _updateStatus.value = UpdateStatus.Idle
            return
        }
        if (_updateStatus.value !is UpdateStatus.ReadyToInstall) {
            try {
                val message = withContext(Dispatchers.IO) { ApkVerifier.verify(app, file, s.info) }
                _downloadProgress.value = 100
                _updateStatus.value = UpdateStatus.ReadyToInstall(file, s.info, message)
            } catch (_: ObsoleteApk) {
                clear(app)
                _downloadProgress.value = 0
                _updateStatus.value = UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)
            }
        }
    }

    private suspend fun clear(app: Context) {
        val old = session
        session = null
        val job = downloadJob
        downloadJob = null
        if (job != null && job != currentCoroutineContext()[Job]) {
            job.cancel()
        }
        withContext(Dispatchers.IO + NonCancellable) {
            if (old != null) {
                runCatching { partFile(app, old).delete() }
                runCatching { staged(app, old).delete() }
            }
            persist(app, null)
        }
    }

    private suspend fun fail(app: Context, message: String, retryInfo: UpdateInfo? = null) {
        try {
            clear(app)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // State still reports failure if storage is full.
        }
        _updateStatus.value = UpdateStatus.Error(message, showNotice = true, retryInfo = retryInfo)
        _isDismissedForSession.value = false
        _downloadProgress.value = 0
        if (foreground) {
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
    }

    fun installDownloadedApk(context: Context, apkFile: File) {
        val app = context.applicationContext
        foreground = true
        if (installing) return
        installing = true
        scope.launch {
            operations.withLock {
                try {
                    restore(app)
                    val s = session ?: throw IOException("Check for an update before installing.")
                    withContext(Dispatchers.IO) {
                        require(s.ready && apkFile.canonicalFile == staged(app, s).canonicalFile) {
                            "Only a verified downloaded update can be installed."
                        }
                    }
                    session = s.copy(installRequested = true)
                    persist(app, session)
                    resumeInstall(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(app, e.message ?: "Could not launch installation.", retryInfo = session?.info)
                } finally {
                    installing = false
                }
            }
        }
    }

    private suspend fun resumeInstall(app: Context, fromResume: Boolean = false) {
        var s = session ?: return
        if (!foreground || !s.ready || !s.installRequested) return
        if (s.waitingPermission && !fromResume) return
        val file = staged(app, s)
        val message = try {
            withContext(Dispatchers.IO) { ApkVerifier.verify(app, file, s.info) }
        } catch (_: ObsoleteApk) {
            clear(app)
            _downloadProgress.value = 0
            _updateStatus.value = UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)
            return
        }
        if (!foreground) return

        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            if (s.waitingPermission) {
                s = s.copy(installRequested = false, waitingPermission = false)
                session = s
                persist(app, s)
                _updateStatus.value = UpdateStatus.ReadyToInstall(
                    file,
                    s.info,
                    "Install permission was not granted. Tap Install to allow it."
                )
                return
            }
            s = s.copy(waitingPermission = true)
            session = s
            persist(app, s)
            _updateStatus.value = UpdateStatus.ReadyToInstall(
                file,
                s.info,
                "Allow app installations in Settings, then return to continue."
            )
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${app.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(permIntent)
            return
        }

        // Clear the request before opening Android so cancel/back cannot produce an install loop.
        s = s.copy(installRequested = false, waitingPermission = false)
        session = s
        persist(app, s)
        _updateStatus.value = UpdateStatus.ReadyToInstall(file, s.info, message)

        val uri = withContext(Dispatchers.IO) {
            FileProvider.getUriForFile(app, "${app.packageName}.provider", file)
        }
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("App update", uri) }
        app.startActivity(installIntent)
    }
}
