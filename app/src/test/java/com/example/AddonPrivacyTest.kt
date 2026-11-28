package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AddonCredentialStore
import com.example.data.local.BackupSettings
import com.example.data.local.PlaybackSourcesPreferences
import com.example.playback.PlaybackSource
import com.example.playback.PlaybackSourceKind
import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AddonPrivacyTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val sources get() = context.getSharedPreferences(PlaybackSourcesPreferences.PREFS_NAME, 0)
    private val other get() = context.getSharedPreferences(PlaybackSourcesPreferences.OTHER_PREFS, 0)
    private val ciphertext get() = File(context.noBackupFilesDir, AddonCredentialStore.FILE_NAME)
    private val key by lazy { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    private val secret = "fixture-private-token"
    private val https = "https://relay.example/a/$secret?accessKey=$secret"
    private val formerHttp = "http://legacy.example/$secret"

    private fun preferencesJson() = JSONArray().put(source("a", https, true))
        .put(source("b", formerHttp, false))
        .put(JSONObject().put("kind", "JIOSAAVN").put("enabled", true)).toString()

    private fun source(id: String, url: String, enabled: Boolean) =
        JSONObject().put("id", id).put("kind", "ADDON").put("name", id).put("enabled", enabled).put("url", url)

    private fun useKey() {
        PlaybackSourcesPreferences.openCredentials = { AddonCredentialStore(it.noBackupFilesDir) { key } }
    }

    @Before fun prepare() {
        sources.edit().clear().commit()
        other.edit().clear().commit()
        ciphertext.delete()
        useKey()
    }

    @After fun reset() {
        PlaybackSourcesPreferences.openCredentials = AddonCredentialStore::open
        ciphertext.delete()
    }

    @Test fun migrationPreservesOrderAndFlagsAndEncryptsBeforeRemovingPlaintext() = runBlocking {
        sources.edit().putString("sources_v2", preferencesJson()).commit()
        other.edit().putString("streaming_addon_url", "https://obsolete.example/$secret").commit()
        val first = PlaybackSourcesPreferences.load(context)
        val addons = first.filter { it.kind == PlaybackSourceKind.ADDON }
        assertEquals(listOf(https, formerHttp), addons.map { it.baseUrl })
        assertEquals(listOf(true, false), addons.map { it.enabled })
        assertTrue(first.any { it.id == PlaybackSource.jio.id && it.enabled })
        assertFalse(sources.contains("sources_v2"))
        assertFalse(other.contains("streaming_addon_url"))
        assertTrue(ciphertext.isFile)
        assertFalse(ciphertext.readText(Charsets.ISO_8859_1).contains(secret))

        // A crash can leave a legacy copy after the verified encrypted commit.
        sources.edit().putString("sources_v2", JSONArray().put(source("old", https, false)).toString()).commit()
        useKey()
        assertEquals(addons, PlaybackSourcesPreferences.load(context).filter { it.kind == PlaybackSourceKind.ADDON })
        assertFalse(sources.contains("sources_v2"))
    }

    @Test fun encryptionFailureKeepsMigrationRecoverableAndBlocksPlaintextPlaybackAndWrites() = runBlocking {
        sources.edit().putString("sources_v2", preferencesJson()).commit()
        PlaybackSourcesPreferences.openCredentials = {
            AddonCredentialStore(it.noBackupFilesDir) { throw IOException("Fixture Keystore unavailable") }
        }
        assertTrue(PlaybackSourcesPreferences.load(context).none { it.kind == PlaybackSourceKind.ADDON })
        assertTrue(PlaybackSourcesPreferences.snapshot(context).none { it.kind == PlaybackSourceKind.ADDON })
        assertTrue(PlaybackSourcesPreferences.addonStorageUnavailable.value)
        assertEquals(preferencesJson(), sources.getString("sources_v2", ""))
        val failure = runCatching { PlaybackSourcesPreferences.saveAddon(context, https, "Other") }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Secure add-on storage"))
        assertFalse(ciphertext.exists())
        useKey()
        assertEquals(2, PlaybackSourcesPreferences.load(context).count { it.kind == PlaybackSourceKind.ADDON })
        assertFalse(PlaybackSourcesPreferences.addonStorageUnavailable.value)
    }

    @Test fun legacyBackupsRestoreSafeSettingsAndNeverReplaceOnDeviceAddons() = runBlocking {
        PlaybackSourcesPreferences.saveAddon(context, https, "Saved")
        fun entry(type: String, value: Any) = JSONObject().put("type", type).put("value", value)
        val settings = JSONObject().put(PlaybackSourcesPreferences.PREFS_NAME, JSONObject()
            .put("sources_v2", entry("string", preferencesJson()))
            .put("source_enabled_a", entry("boolean", true)))
            .put(PlaybackSourcesPreferences.OTHER_PREFS, JSONObject()
                .put("streaming_addon_url", entry("string", formerHttp))
                .put("keep_lyrics_screen_awake", entry("boolean", true)))
        val restored = BackupSettings.decode(settings)
        withContext(Dispatchers.IO) { BackupSettings.write(context, restored) }
        val exported = BackupSettings.encode(BackupSettings.read(context, restored.keys)).toString()
        assertFalse(exported.contains(secret))
        assertFalse(exported.contains("sources_v2"))
        assertFalse(exported.contains("streaming_addon"))
        assertTrue(other.getBoolean("keep_lyrics_screen_awake", false))
        val current = PlaybackSourcesPreferences.load(context)
        assertTrue(current.any { it.id == PlaybackSource.jio.id && it.enabled })
        assertEquals(https, current.single { it.kind == PlaybackSourceKind.ADDON }.baseUrl)
    }

    @Test fun exportRedactsUnmigratedSourcePreferencesEvenWhenTheKeystoreIsUnavailable() = runBlocking {
        sources.edit().putString("sources_v2", preferencesJson()).commit()
        other.edit().putString("streaming_addon_url", https).commit()
        val raw = BackupSettings.encode(BackupSettings.read(context,
            setOf(PlaybackSourcesPreferences.PREFS_NAME, PlaybackSourcesPreferences.OTHER_PREFS))).toString()
        assertFalse(raw.contains(secret))
        assertTrue(raw.contains("source_enabled_jiosaavn"))
    }
}
