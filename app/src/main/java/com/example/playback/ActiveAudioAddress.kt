package com.example.playback

import android.net.Uri
import java.util.UUID

/** Session extras cross UID boundaries. The in-process controller can match an opaque source ID. */
internal object ActiveAudioAddress {
    private data class Publication(val uri: Uri, val id: String)
    @Volatile private var current: Publication? = null

    @Synchronized
    fun publish(uri: Uri?): String? {
        if (uri == null) {
            current = null
            return null
        }
        return current?.takeIf { it.uri == uri }?.id
            ?: Publication(uri, UUID.randomUUID().toString()).also { current = it }.id
    }

    fun matches(uri: Uri?, id: String?): Boolean =
        uri != null && id != null && current?.let { it.uri == uri && it.id == id } == true
}
