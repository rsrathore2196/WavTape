package com.example

import android.content.Context
import com.example.data.local.AddonCredentialStore
import com.example.data.local.PlaybackSourcesPreferences
import java.io.File
import javax.crypto.KeyGenerator

/** The JVM lacks AndroidKeyStore. Keep real AES-GCM and substitute only the test key. */
internal class AddonStorageFixture(private val context: Context) : AutoCloseable {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    init {
        PlaybackSourcesPreferences.openCredentials = { AddonCredentialStore(it.noBackupFilesDir) { key } }
    }
    override fun close() {
        File(context.noBackupFilesDir, AddonCredentialStore.FILE_NAME).delete()
        PlaybackSourcesPreferences.openCredentials = AddonCredentialStore::open
    }
}
