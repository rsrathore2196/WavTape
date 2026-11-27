package com.example.data.remote

import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.example.data.model.MusicTrack
import com.example.data.repository.RecordingIdentity
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One Archive item and one decoded filename. The CDN location is never the file identity. */
internal data class ArchiveFileKey private constructor(val item: String, val file: String) {
    val id: String get() = "ia_$item:$file"
    val url: HttpUrl get() = HttpUrl.Builder().scheme("https").host("archive.org")
        .addPathSegment("download").addPathSegment(item).apply {
            file.split('/').forEach(::addPathSegment)
        }.build()

    val extension: String get() = file.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
    val playableContainer: Boolean get() = extension in setOf("flac", "wav", "m4a")

    companion object {
        private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}")
        private val storageDirectory = Regex("[0-9]+")

        fun create(item: String, file: String): ArchiveFileKey? {
            if (!identifier.matches(item) || file.length !in 1..1024 || file.split('/').any {
                it.isEmpty() || it == "." || it == ".." || it.any { char -> char.isISOControl() || char == '\\' }
            }) return null
            return ArchiveFileKey(item, file)
        }

        fun fromId(id: String): ArchiveFileKey? {
            if (!id.startsWith("ia_") || ':' !in id) return null
            return create(id.substringAfter("ia_").substringBefore(':'), id.substringAfter(':'))
        }

        fun fromUrl(value: String): ArchiveFileKey? = value.toHttpUrlOrNull()?.let(::fromUrl)

        fun fromUrl(url: HttpUrl): ArchiveFileKey? {
            if (!archiveHttps(url)) return null
            val path = url.pathSegments
            val itemIndex = when {
                url.host == "archive.org" && path.firstOrNull() == "download" -> 1
                path.firstOrNull() == "items" -> 1
                path.firstOrNull()?.matches(storageDirectory) == true && path.getOrNull(1) == "items" -> 2
                else -> return null
            }
            val file = path.drop(itemIndex + 1)
            if (file.any { '/' in it }) return null
            return create(path.getOrNull(itemIndex) ?: return null, file.joinToString("/"))
        }

        fun itemLink(value: String): String? {
            val url = value.trim().toHttpUrlOrNull() ?: return null
            return fromUrl(url)?.item ?: url.takeIf {
                archiveHttps(it) && it.host == "archive.org" && it.pathSegments.firstOrNull() == "details" &&
                    (it.pathSize == 2 || it.pathSize == 3 && it.pathSegments.last().isEmpty())
            }?.pathSegments?.getOrNull(1)?.takeIf(identifier::matches)
        }

        fun archiveHttps(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() &&
            (url.host == "archive.org" || url.host.endsWith(".archive.org"))

        fun nativeTransport(value: String): ArchiveFileKey? = fromUrl(value)?.takeUnless {
            StreamingAddons.hasRegisteredTransport(value)
        }

        fun explicitSelection(track: MusicTrack): ArchiveFileKey? = fromId(track.id)
            ?: nativeTransport(track.audioUrl)?.takeIf { track.source.equals("INTERNET_ARCHIVE", true) }
    }
}

internal fun archiveCatalogPolicyAllows(request: com.example.playback.PlaybackSourcePolicy,
    active: com.example.playback.PlaybackSourcePolicy): Boolean =
    request.allowsArchive && active.allowsArchive &&
        request.copy(dolbyAtmos = false).key == active.copy(dolbyAtmos = false).key

internal object ArchiveFileEvidence {
    private const val FRESH_MS = 5 * 60_000L
    private data class Proof(val verifiedAt: Long, val track: MusicTrack)
    private val proofs = LruCache<ArchiveFileKey, Proof>(128)

    fun remember(key: ArchiveFileKey, track: MusicTrack) {
        proofs.put(key, Proof(SystemClock.elapsedRealtime(), track))
    }

    fun forget(key: ArchiveFileKey) { proofs.remove(key) }

    fun fresh(key: ArchiveFileKey): MusicTrack? = proofs.get(key)?.takeIf {
        SystemClock.elapsedRealtime() - it.verifiedAt in 0 until FRESH_MS
    }?.track

    fun isLiteralSelection(track: MusicTrack): Boolean =
        ArchiveFileKey.fromId(track.id)?.let { track.title == it.file } == true

    fun matchesSelection(requested: MusicTrack, candidate: MusicTrack): Boolean {
        val selected = ArchiveFileKey.explicitSelection(requested) ?: return false
        if (selected != ArchiveFileKey.fromUrl(candidate.audioUrl)) return false
        if (!isLiteralSelection(requested)) return ArchiveRecordingIdentity.confidence(requested, candidate) != null
        // An explicitly selected filename identifies just that file. It never supplies
        // title evidence for a catalog match or overrides contradictory metadata.
        val expected = RecordingIdentity.artistNames(requested)
        val actual = RecordingIdentity.artistNames(candidate)
        return !ArchiveRecordingIdentity.isrcConflict(RecordingIdentity.isrc(requested.isrc), RecordingIdentity.isrc(candidate.isrc)) &&
            (expected.isEmpty() || ArchiveRecordingIdentity.artistsAgree(expected, actual))
    }

    /** The replacement keeps the original catalog ISRC in MusicTrack. Only file proof can verify it. */
    fun verifiedReplacementIsrc(track: MusicTrack): String? {
        if (track.source.startsWith("Streaming Addon:")) return RecordingIdentity.isrc(track.isrc)
        val archive = ArchiveFileKey.nativeTransport(track.audioUrl)
        return RecordingIdentity.isrc(if (archive == null) track.isrc else fresh(archive)?.isrc.orEmpty())
    }
}

internal object ArchivePlaybackEvents {
    private val requests = java.util.concurrent.atomic.AtomicLong()
    fun nextRequestId(): Long = requests.incrementAndGet()

    fun record(stage: String, outcome: String, key: ArchiveFileKey? = null,
        elapsedMs: Long? = null, count: Int? = null, status: Int? = null, requestId: Long? = null) {
        // Only fixed stage/outcome values reach this logger. Paths, queries and headers stay out.
        Log.d("ArchivePlayback", "stage=$stage outcome=$outcome file=${key?.hashCode()?.toUInt()?.toString(16)} " +
            "request=$requestId elapsedMs=$elapsedMs count=$count http=$status")
    }
}
