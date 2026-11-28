package com.example

import android.content.Context
import android.content.ContextWrapper
import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.AddonCredentialStore
import com.example.data.local.PlaybackSourcesPreferences
import com.example.playback.PlaybackSourceKind
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddonKeystoreTest {
    @Test fun migratesWithTheAndroidKeystoreAndOpensAfterRecreatingStorage() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val directory = File(app.noBackupFilesDir, id).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$id-$name", mode)
        }
        val legacy = context.getSharedPreferences(PlaybackSourcesPreferences.OTHER_PREFS, 0)
        val url = "https://addons.example/instrumentation-private-path"
        try {
            legacy.edit().putString("streaming_addon_url", url).putBoolean("streaming_addon_enabled", true).commit()
            val restored = PlaybackSourcesPreferences.load(context).single { it.kind == PlaybackSourceKind.ADDON }
            assertEquals(url, restored.baseUrl)
            assertTrue(restored.enabled)
            assertFalse(legacy.contains("streaming_addon_url"))
            val stored = File(directory, AddonCredentialStore.FILE_NAME)
            assertFalse(stored.readText(Charsets.ISO_8859_1).contains("instrumentation-private-path"))
            val decrypted = requireNotNull(AddonCredentialStore.open(context).read())
            assertEquals(url, JSONArray(decrypted).getJSONObject(0).getString("url"))
            assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
        } finally {
            app.deleteSharedPreferences("$id-${PlaybackSourcesPreferences.OTHER_PREFS}")
            app.deleteSharedPreferences("$id-${PlaybackSourcesPreferences.PREFS_NAME}")
            directory.deleteRecursively()
        }
    }
}
