package com.example

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.UpdateNotificationBar
import com.example.ui.components.formatUpdateBytes
import com.example.ui.components.parseUpdateChangelog
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.example.updater.AppUpdateManager
import com.example.updater.UpdateDownloadRequest
import com.example.updater.UpdateInfo
import com.example.updater.UpdateStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUpdaterTest {
    @get:Rule val compose = createComposeRule()

    @Suppress("UNCHECKED_CAST")
    private fun setUpdateState(status: UpdateStatus, progress: Int = 0, dismissed: Boolean = false) {
        val statusField = AppUpdateManager::class.java.getDeclaredField("_updateStatus").apply { isAccessible = true }
        val progressField = AppUpdateManager::class.java.getDeclaredField("_downloadProgress").apply { isAccessible = true }
        val dismissedField = AppUpdateManager::class.java.getDeclaredField("_isDismissedForSession").apply { isAccessible = true }
        (statusField.get(AppUpdateManager) as MutableStateFlow<UpdateStatus>).value = status
        (progressField.get(AppUpdateManager) as MutableStateFlow<Int>).value = progress
        (dismissedField.get(AppUpdateManager) as MutableStateFlow<Boolean>).value = dismissed
    }

    @After fun resetUpdaterState() {
        setUpdateState(UpdateStatus.Idle, 0, false)
    }

    @Test fun parseReleaseJsonDetectsNewerVersionAndNormalizesApkAssets() {
        val sha = "a".repeat(64)
        val json = """
            {
              "tag_name": "v2.2",
              "name": "WavTape v2.2",
              "body": "## What's New\n- **Faster** audio loading\n- Fixed search cursor",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-11-21T12:00:00Z",
              "html_url": "https://github.com/rsrathore2196/Xtreme-Player/releases/tag/v2.2",
              "assets": [
                {
                  "name": "WavTape v2.2.apk",
                  "browser_download_url": "https://github.com/rsrathore2196/Xtreme-Player/releases/download/v2.2/WavTape.v2.2.apk",
                  "size": 45200000,
                  "state": "uploaded",
                  "content_type": "application/zip",
                  "digest": "sha256:$sha"
                }
              ]
            }
        """.trimIndent()

        val result = AppUpdateManager.parseReleaseJson(json, currentVersion = "2.1")
        assertTrue("Expected UpdateAvailable, got $result", result is UpdateStatus.UpdateAvailable)
        val info = (result as UpdateStatus.UpdateAvailable).updateInfo
        assertEquals("2.2", info.versionName)
        assertEquals(45200000L, info.apkSizeBytes)
        assertEquals(sha, info.sha256)
    }

    @Test fun parseReleaseJsonReturnsUpToDateForSameVersionOrDraft() {
        val sameVersion = """{"tag_name":"v2.1","draft":false,"prerelease":false,"assets":[]}"""
        val draft = """{"tag_name":"v3.0","draft":true,"prerelease":false,"assets":[]}"""
        assertTrue(AppUpdateManager.parseReleaseJson(sameVersion, "2.1") is UpdateStatus.UpToDate)
        assertTrue(AppUpdateManager.parseReleaseJson(draft, "2.1") is UpdateStatus.UpToDate)
    }

    @Test fun releaseAssetsFromRenamedRepositoryKeepLegacyCompatibility() {
        val name = "WavTape.apk"
        val url = "https://github.com/rsrathore2196/WavTape/releases/download/v2.2/$name"
        val result = AppUpdateManager.parseReleaseJson(releaseWithAssets(name), currentVersion = "2.1")
        assertEquals(url, (result as UpdateStatus.UpdateAvailable).updateInfo.apkDownloadUrl)
        assertTrue(AppUpdateManager.validAsset(url.replace("/WavTape/", "/Xtreme-Player/")))
        assertFalse(AppUpdateManager.validAsset(url.replace("/WavTape/", "/WavTape-other/")))
        assertFalse(AppUpdateManager.validAsset(url.replace("/rsrathore2196/", "/other-owner/")))
    }

    @Test fun namedArchitecturesRequireExactDeviceSupportEvenForSingleAsset() {
        assertThrows(IOException::class.java) {
            AppUpdateManager.parseReleaseJson(releaseWithAssets("WavTape-x86_64.apk"),
                currentVersion = "2.1", supportedAbis = arrayOf("x86"))
        }
        val result = AppUpdateManager.parseReleaseJson(
            releaseWithAssets("WavTape-x86_64.apk", "WavTape-x86.apk"),
            currentVersion = "2.1", supportedAbis = arrayOf("x86")) as UpdateStatus.UpdateAvailable
        assertEquals("WavTape-x86.apk", result.updateInfo.apkFileName)
    }

    private fun releaseWithAssets(vararg names: String): String {
        val assets = JSONArray()
        names.forEach { name -> assets.put(JSONObject().put("name", name).put("size", 4096L)
            .put("browser_download_url", "https://github.com/rsrathore2196/WavTape/releases/download/v2.2/$name")) }
        return JSONObject().put("tag_name", "v2.2").put("assets", assets).toString()
    }

    @Test fun downloadRedirectValidationAllowsOnlyTrustedHttpsGithubHosts() {
        assertTrue(AppUpdateManager.isTrustedDownloadRedirect("https://objects.githubusercontent.com/github-production-release-asset/foo.apk?X-Amz-Signature=abc"))
        assertTrue(AppUpdateManager.isTrustedDownloadRedirect("https://release-assets.githubusercontent.com/release/app-release.apk"))
        assertTrue(AppUpdateManager.isTrustedDownloadRedirect("https://github.com/rsrathore2196/Xtreme-Player/releases/download/v2.2/app-release.apk"))
        assertFalse(AppUpdateManager.isTrustedDownloadRedirect("http://objects.githubusercontent.com/app.apk"))
        assertFalse(AppUpdateManager.isTrustedDownloadRedirect("https://evil.example.com/app.apk"))
        assertFalse(AppUpdateManager.isTrustedDownloadRedirect("https://github.com.evil.com/app.apk"))
    }

    @Test fun changelogParsingAndByteFormattingCleanMarkdown() {
        val info = UpdateInfo(
            versionName = "2.2",
            releaseTitle = "v2.2",
            releaseNotes = "## What's Changed\n- **Cleaner** Liquid Glass UI\n* Fixed `search` cursor\nhttps://github.com/example/compare",
            apkDownloadUrl = "https://github.com/rsrathore2196/Xtreme-Player/releases/download/v2.2/app-release.apk",
            apkFileName = "app-release.apk",
            apkSizeBytes = 42_500_000L
        )
        val entries = parseUpdateChangelog(info)
        assertEquals(3, entries.size)
        assertTrue(entries[0].isHeading)
        assertEquals("What's Changed", entries[0].text)
        assertEquals("Cleaner Liquid Glass UI", entries[1].text)
        assertEquals("Fixed search cursor", entries[2].text)
        assertEquals("40.5 MB", formatUpdateBytes(42_500_000L))
    }

    @Test fun updatePopupRendersAvailableDownloadingAndReadyToInstallStates() {
        val releaseVersion = "${BuildConfig.VERSION_NAME}.1"
        val info = UpdateInfo(
            versionName = releaseVersion,
            releaseTitle = "WavTape v$releaseVersion",
            releaseNotes = "- Improved liquid glass header\n- Faster updates",
            apkDownloadUrl = "https://github.com/rsrathore2196/Xtreme-Player/releases/download/v2.2/app-release.apk",
            apkFileName = "app-release.apk",
            apkSizeBytes = 20L * 1024 * 1024
        )
        setUpdateState(UpdateStatus.UpdateAvailable(info), progress = 0, dismissed = false)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                    LocalDynamicGlassEnabled provides false
                ) {
                    UpdateNotificationBar()
                }
            }
        }
        compose.onNodeWithTag("auto_update_popup_dialog").assertIsDisplayed()
        compose.onNodeWithText("New Update Available").assertIsDisplayed()
        compose.onNodeWithText("v$releaseVersion").assertIsDisplayed()
        compose.onNodeWithText("20.0 MB").assertIsDisplayed()
        compose.onNodeWithText("Download Update").assertIsDisplayed()

        // Transition to Downloading with live MB progress + Cancel button
        compose.runOnIdle {
            setUpdateState(
                UpdateStatus.Downloading(
                    progressPercent = 45,
                    downloadId = 1L,
                    updateInfo = info,
                    message = "Downloading update...",
                    downloadedBytes = 9L * 1024 * 1024,
                    totalBytes = 20L * 1024 * 1024
                ),
                progress = 45
            )
        }
        compose.onNodeWithText("Downloading Update").assertIsDisplayed()
        compose.onNodeWithText("45%").assertIsDisplayed()
        compose.onNodeWithText("9.0 MB / 20.0 MB").assertIsDisplayed()
        compose.onNodeWithTag("cancel_update_download_button").performScrollTo().assertIsDisplayed()

        // Transition to ReadyToInstall with verification message
        compose.runOnIdle {
            setUpdateState(
                UpdateStatus.ReadyToInstall(
                    apkFile = File("/tmp/fake.apk"),
                    updateInfo = info,
                    message = "SHA-256, package and signing identity verified."
                ),
                progress = 100
            )
        }
        compose.onNodeWithText("Ready to Install").assertIsDisplayed()
        compose.onNodeWithText("SHA-256, package and signing identity verified.").assertIsDisplayed()
        compose.onNodeWithText("Install Update").assertIsDisplayed()

        // Dismissing hides the dialog; showUpdatePopup brings it back
        compose.onNodeWithTag("dismiss_update_bar_button").performClick()
        compose.onNodeWithTag("auto_update_popup_dialog").assertDoesNotExist()
        compose.runOnIdle { AppUpdateManager.showUpdatePopup() }
        compose.onNodeWithTag("auto_update_popup_dialog").assertIsDisplayed()
    }

    @Test fun streamResponseToFileReportsProgressAndRejectsTruncatedOrOversizedStreams() {
        val payload = ByteArray(150 * 1024) { (it % 251).toByte() }
        val target = File.createTempFile("wavtape-update-test-", ".part")
        try {
            val reportedPercents = mutableListOf<Int>()
            val written = kotlinx.coroutines.runBlocking {
                AppUpdateManager.streamResponseToFile(
                    input = java.io.ByteArrayInputStream(payload),
                    targetFile = target,
                    expectedSizeBytes = payload.size.toLong(),
                    reportedTotalBytes = payload.size.toLong(),
                    onProgress = { percent, _, _ -> reportedPercents.add(percent) }
                )
            }
            assertEquals(payload.size.toLong(), written)
            assertArrayEquals(payload, target.readBytes())
            assertTrue("Expected progress callbacks, got $reportedPercents", reportedPercents.isNotEmpty())
            assertTrue(reportedPercents.last() in 1..99)

            // Truncated stream must fail
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking {
                    AppUpdateManager.streamResponseToFile(
                        input = java.io.ByteArrayInputStream(payload.copyOf(1024)),
                        targetFile = target,
                        expectedSizeBytes = payload.size.toLong(),
                        reportedTotalBytes = -1L,
                        onProgress = { _, _, _ -> }
                    )
                }
            }

            // Oversized stream must fail
            assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking {
                    AppUpdateManager.streamResponseToFile(
                        input = java.io.ByteArrayInputStream(payload),
                        targetFile = target,
                        expectedSizeBytes = 1024L,
                        reportedTotalBytes = 1024L,
                        onProgress = { _, _, _ -> }
                    )
                }
            }
            assertEquals(0L, target.length())
        } finally {
            target.delete()
        }
    }

    @Test fun cancellingAnUpdateCannotLeaveItsRetryOrLateRedirectInTheWrongState() {
        val client = OkHttpClient()
        val request = Request.Builder().url("https://github.com/rsrathore2196/WavTape/releases/download/v2.2/WavTape.apk").build()
        val firstCall = client.newCall(request)
        val first = UpdateDownloadRequest()
        first.attach(firstCall)
        first.cancel()
        assertTrue(firstCall.isCanceled())

        val retryCall = client.newCall(request)
        val retry = UpdateDownloadRequest()
        retry.attach(retryCall)
        assertThrows(CancellationException::class.java) {
            first.attach(client.newCall(request))
        }
        first.cancel()
        assertFalse(retryCall.isCanceled())

        retry.cancel()
        assertTrue(retryCall.isCanceled())
    }

    @Test fun updaterFilesCannotConsumeCloudBackupQuotaOrRestoreAnOldInstallRequest() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        fun exclusions(resource: Int) = resources.getXml(resource).use { parser ->
            buildSet {
                val sections = mutableListOf<String>()
                var event = parser.next()
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        if (parser.name == "exclude") {
                            add(Triple(sections.last(), parser.getAttributeValue(null, "domain"),
                                parser.getAttributeValue(null, "path")))
                        }
                        sections.add(parser.name)
                    } else if (event == XmlPullParser.END_TAG) {
                        sections.removeAt(sections.lastIndex)
                    }
                    event = parser.next()
                }
            }
        }
        fun assertExcluded(entries: Set<Triple<String, String, String>>, section: String) {
            assertTrue(entries.contains(Triple(section, "file", "update_apks/")))
            assertTrue(entries.contains(Triple(section, "sharedpref", "apk_update_session_v2.xml")))
        }
        assertExcluded(exclusions(R.xml.backup_rules), "full-backup-content")
        val modern = exclusions(R.xml.data_extraction_rules)
        assertExcluded(modern, "cloud-backup")
        assertExcluded(modern, "device-transfer")
    }

    @Test fun validAssetRejectsPathTraversalAndAcceptsCaseInsensitiveRepoPath() {
        assertTrue(AppUpdateManager.validAsset("https://github.com/rsrathore2196/wavtape/releases/download/v2.2/WavTape.apk"))
        assertFalse(AppUpdateManager.validAsset("https://github.com/rsrathore2196/WavTape/releases/download/../other/WavTape.apk"))
        assertFalse(AppUpdateManager.validAsset("https://github.com/rsrathore2196/WavTape/releases/download/v2.2/WavTape.apk?foo=bar"))
    }

    @Test fun parseReleaseJsonHandlesNullGithubFieldsSafely() {
        val json = """
            {
              "tag_name": "v2.2",
              "name": null,
              "body": null,
              "draft": false,
              "prerelease": false,
              "published_at": null,
              "html_url": null,
              "assets": [
                {
                  "name": "app-release.apk",
                  "browser_download_url": "https://github.com/rsrathore2196/WavTape/releases/download/v2.2/app-release.apk",
                  "size": 52428800,
                  "state": null,
                  "content_type": null,
                  "digest": null
                }
              ]
            }
        """.trimIndent()
        val result = AppUpdateManager.parseReleaseJson(json, currentVersion = "2.1")
        assertTrue("Expected UpdateAvailable, got $result", result is UpdateStatus.UpdateAvailable)
        val info = (result as UpdateStatus.UpdateAvailable).updateInfo
        assertEquals("2.2", info.versionName)
        assertEquals("2.2", info.releaseTitle)
        assertEquals("", info.releaseNotes)
        assertNull(info.sha256)
    }
}
