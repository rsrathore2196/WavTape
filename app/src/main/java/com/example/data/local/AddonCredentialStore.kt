package com.example.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local, authenticated storage. An existing ciphertext always requires its original key. */
internal class AddonCredentialStore(
    directory: File,
    private val key: (create: Boolean) -> SecretKey = ::deviceKey
) {
    private val file = AtomicFile(File(directory, FILE_NAME))

    fun read(): String? {
        val bytes = try {
            file.openRead().use { input ->
                require(input.channel.size() in 1..MAX_BYTES.toLong()) { "Invalid add-on credential file size" }
                input.readBytes()
            }
        } catch (failure: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw failure else return null
        }
        require(bytes.size > HEADER.size + NONCE_SIZE + TAG_BITS / 8 && bytes.size <= MAX_BYTES &&
            bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER)) { "Invalid add-on credential file" }
        val offset = HEADER.size + NONCE_SIZE
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(TAG_BITS, bytes, HEADER.size, NONCE_SIZE))
        cipher.updateAAD(HEADER)
        return String(cipher.doFinal(bytes, offset, bytes.size - offset), Charsets.UTF_8)
    }

    fun write(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(!file.baseFile.exists()))
        cipher.updateAAD(HEADER)
        check(cipher.iv.size == NONCE_SIZE)
        val bytes = HEADER + cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        require(bytes.size <= MAX_BYTES) { "Add-on configuration is too large" }
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
        check(read() == value) { "Could not verify saved add-on configuration" }
    }

    companion object {
        const val FILE_NAME = "playback_addons_v1.bin"
        private const val KEY_ALIAS = "wavtape.playback.addons.v1"
        private const val NONCE_SIZE = 12
        private const val TAG_BITS = 128
        private const val MAX_BYTES = 1024 * 1024
        private val HEADER = "WVADDON1".toByteArray(Charsets.US_ASCII)

        fun open(context: Context) = AddonCredentialStore(context.noBackupFilesDir)

        private fun deviceKey(create: Boolean): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            if (!create) throw IOException("Add-on encryption key is unavailable")
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
            }.generateKey()
        }
    }
}
