package com.example.service

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Session-local VOD manifests, reusable by the replacement without another network round trip. */
@UnstableApi
internal class FastStartManifests {
    private data class Manifest(val bytes: ByteArray, val resolvedUri: Uri)
    private val manifests = mutableMapOf<Uri, Manifest>()

    fun recording(upstream: DataSource.Factory): DataSource.Factory = factory(upstream, record = true)
    fun replaying(upstream: DataSource.Factory): DataSource.Factory = factory(upstream, record = false)

    private fun factory(upstream: DataSource.Factory, record: Boolean): DataSource.Factory = DataSource.Factory {
        object : DataSource {
            private val listeners = mutableListOf<TransferListener>()
            private var delegate: DataSource? = null

            override fun open(spec: DataSpec): Long {
                val eligible = spec.httpMethod == DataSpec.HTTP_METHOD_GET && spec.position == 0L &&
                    spec.length == C.LENGTH_UNSET.toLong() && MusicCache.isManifestRequest(spec)
                val manifest = if (eligible) synchronized(manifests) { manifests[spec.uri] } else null
                val available = manifest ?: if (eligible && record) readManifest(upstream, spec) else null
                val source = available?.let { ByteArrayDataSource(it.bytes) } ?: upstream.createDataSource()
                delegate = source
                listeners.forEach(source::addTransferListener)
                return source.open(if (available == null) spec else spec.withUri(available.resolvedUri))
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(delegate).read(buffer, offset, length)
            override fun getUri() = delegate?.uri
            override fun getResponseHeaders() = delegate?.responseHeaders.orEmpty()
            override fun addTransferListener(transferListener: TransferListener) { listeners.add(transferListener) }
            override fun close() {
                val source = delegate
                delegate = null
                source?.close()
            }
        }
    }

    private fun readManifest(upstream: DataSource.Factory, spec: DataSpec): Manifest {
        val source = upstream.createDataSource()
        val manifest = try {
            source.open(spec)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                if (output.size() + read > MAX_MANIFEST_BYTES) throw IOException("Fast Start manifest exceeds the read budget")
                output.write(buffer, 0, read)
            }
            Manifest(output.toByteArray(), source.uri ?: spec.uri)
        } finally { source.close() }
        synchronized(manifests) {
            if (spec.uri !in manifests && manifests.size >= MAX_MANIFESTS)
                throw IOException("Fast Start manifest count exceeds the read budget")
            manifests[spec.uri] = manifest
        }
        return manifest
    }

    fun forHandoff(item: MediaItem): MediaItem = synchronized(Companion) {
        val uri = checkNotNull(item.localConfiguration).uri
        val id = ++nextId
        pending = Handoff(id, item.mediaId, uri, SystemClock.elapsedRealtime(), this)
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply { putLong(HANDOFF, id) }
        item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 512 * 1024
        private const val MAX_MANIFESTS = 6
        private const val HANDOFF = "fast_start_manifest_handoff"
        private data class Handoff(val id: Long, val mediaId: String, val uri: Uri, val publishedAt: Long, val manifests: FastStartManifests)
        private var nextId = 0L
        private var pending: Handoff? = null

        @Synchronized
        fun takeFor(item: MediaItem): FastStartManifests? {
            val handoff = pending ?: return null
            if (SystemClock.elapsedRealtime() - handoff.publishedAt > 5_000L) {
                pending = null
                return null
            }
            if (item.mediaMetadata.extras?.getLong(HANDOFF) != handoff.id || item.mediaId != handoff.mediaId ||
                item.localConfiguration?.uri != handoff.uri) return null
            pending = null
            return handoff.manifests
        }
    }
}
