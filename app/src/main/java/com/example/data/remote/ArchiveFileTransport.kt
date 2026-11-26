package com.example.data.remote

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.example.playback.NetworkQualityMonitor
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A single Archive file transport shared by metadata probes and the Media3 cache upstream. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object ArchiveFileTransport {
    internal const val PREFIX_BYTES = 256 * 1024
    private val byteRange = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    private val redirectStatuses = setOf(301, 302, 303, 307, 308)
    private val replyLimit = java.util.concurrent.atomic.AtomicLong()

    internal class Failure(val outcome: String, val status: Int? = null) : IOException("Archive $outcome")
    private class Access(val key: ArchiveFileKey, val allowed: () -> Boolean, val optional: Boolean)
    private val rangeAvailability = android.util.LruCache<ArchiveFileKey, Pair<Long, Boolean>>(128)

    fun isSequentialOnly(key: ArchiveFileKey): Boolean = rangeAvailability.get(key)?.let {
        SystemClock.elapsedRealtime() - it.first in 0 until 300_000L && !it.second
    } == true

    private fun recordRange(key: ArchiveFileKey, code: Int, position: Long, wanted: Long) {
        if (code == 206 || code == 200 && position == 0L && wanted > 0L)
            rangeAvailability.put(key, SystemClock.elapsedRealtime() to (code == 206))
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 2 })
        .addInterceptor { chain ->
            var request = chain.request()
            val access = checkNotNull(request.tag(Access::class.java))
            repeat(4) { hop ->
                if (!access.allowed()) throw Failure("policy_blocked")
                if (access.optional && isRateLimited()) throw Failure("rate_limited", 429)
                val response = chain.proceed(request)
                if (response.code !in redirectStatuses) {
                    if (response.code == 429 || response.code == 503) noteBackoff(response.header("Retry-After"))
                    return@addInterceptor response
                }
                response.use {
                    val target = redirectTarget(access.key, request.url, it.header("Location"))
                    if (hop == 3 || target == null) throw Failure("redirect_rejected", it.code)
                    request = request.newBuilder().url(target).build()
                }
            }
            throw Failure("redirect_rejected")
        }.build()

    internal fun redirectTarget(key: ArchiveFileKey, from: okhttp3.HttpUrl, location: String?): okhttp3.HttpUrl? =
        location?.let(from::resolve)?.takeIf { ArchiveFileKey.fromUrl(it) == key }

    fun failureOf(error: Throwable): Failure? =
        generateSequence(error) { it.cause }.take(8).filterIsInstance<Failure>().firstOrNull()

    fun loadPolicy(): androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy =
        object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                if (failureOf(info.exception) != null) C.TIME_UNSET else super.getRetryDelayMsFor(info)
        }

    fun isRateLimited(): Boolean = SystemClock.elapsedRealtime() < replyLimit.get()

    fun noteBackoff(value: String?): Long? {
        val seconds = value?.trim()?.toLongOrNull() ?: value?.let {
            runCatching {
                val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                    .apply { isLenient = false }.parse(it)
                (requireNotNull(date).time - System.currentTimeMillis() + 999L) / 1000L
            }.getOrNull()
        }
        if (seconds != null && seconds > 0L) {
            val until = SystemClock.elapsedRealtime() + seconds.coerceAtMost(3600L) * 1000L
            replyLimit.accumulateAndGet(until, ::maxOf)
        }
        return seconds?.coerceAtLeast(0L)
    }

    private fun request(key: ArchiveFileKey, position: Long, length: Long, optional: Boolean,
        allowed: () -> Boolean): Request {
        val last = if (length == C.LENGTH_UNSET.toLong()) "" else {
            if (length <= 0 || position > Long.MAX_VALUE - length) throw Failure("range_unavailable")
            (position + length - 1).toString()
        }
        return Request.Builder().url(key.url).tag(Access::class.java, Access(key, allowed, optional))
            .header("User-Agent", "WavTape (Android; Archive Audio)")
            .header("Accept-Encoding", "identity").header("Range", "bytes=$position-$last").build()
    }

    /** A server may serve a full 200 at zero. Never skip a 200 body to fake a later range. */
    internal fun responseLength(code: Int, contentRange: String?, contentLength: Long,
        position: Long, wanted: Long): Long {
        if (code == 200) {
            if (position != 0L) throw Failure("range_unavailable", code)
            return if (wanted < 0L) contentLength else if (contentLength < 0L) wanted else minOf(wanted, contentLength)
        }
        if (code != 206) throw Failure(when (code) {
            401, 403, 451 -> "license_denied"
            429, 503 -> "rate_limited"
            416 -> "range_unavailable"
            else -> "http_unavailable"
        }, code)
        val match = contentRange?.let(byteRange::matchEntire) ?: throw Failure("range_unavailable", code)
        val (first, last, total) = match.destructured
        val start = first.toLongOrNull() ?: throw Failure("range_unavailable", code)
        val end = last.toLongOrNull() ?: throw Failure("range_unavailable", code)
        val size = total.toLongOrNull() ?: throw Failure("range_unavailable", code)
        val expectedEnd = if (wanted < 0) size - 1 else if (wanted > Long.MAX_VALUE - position) -1L
            else minOf(position + wanted - 1, size - 1)
        if (start != position || end < start || end >= size || end != expectedEnd ||
            contentLength >= 0 && contentLength != end - start + 1) throw Failure("range_unavailable", code)
        return end - start + 1
    }

    private fun checkResponse(response: Response, position: Long, wanted: Long): Long {
        val length = responseLength(response.code, response.header("Content-Range"),
            response.body.contentLength(), position, wanted)
        if (response.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false)
            throw Failure("range_unavailable", response.code)
        return length
    }

    private val probes = client.newBuilder().callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    suspend fun probe(key: ArchiveFileKey, offset: Long = 0, maxBytes: Int = PREFIX_BYTES,
        allowed: () -> Boolean): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = probes.newCall(request(key, offset, maxBytes.toLong(), optional = true, allowed = allowed))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        checkResponse(it, offset, maxBytes.toLong())
                        recordRange(key, it.code, offset, maxBytes.toLong())
                        val input = it.body.source()
                        val buffer = okio.Buffer()
                        while (buffer.size < maxBytes) {
                            if (input.read(buffer, minOf(8192L, maxBytes - buffer.size)) == -1L) break
                        }
                        if (continuation.isActive) continuation.resume(buffer.readByteArray())
                    } catch (error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    /** Keep a sequential Archive file playable while exposing its genuine seek capability. */
    fun sequentialExtractors(): androidx.media3.extractor.ExtractorsFactory =
        androidx.media3.extractor.ExtractorsFactory {
            androidx.media3.extractor.DefaultExtractorsFactory().createExtractors().map { extractor ->
                object : androidx.media3.extractor.Extractor by extractor {
                    override fun init(output: androidx.media3.extractor.ExtractorOutput) {
                        extractor.init(object : androidx.media3.extractor.ExtractorOutput by output {
                            override fun seekMap(seekMap: androidx.media3.extractor.SeekMap) {
                                output.seekMap(androidx.media3.extractor.SeekMap.Unseekable(seekMap.durationUs))
                            }
                        })
                    }
                }
            }.toTypedArray()
        }

    fun source(context: Context, key: ArchiveFileKey, connectMs: Int, readMs: Int): BaseDataSource =
        FileSource(context.applicationContext, key, client.newBuilder()
            .connectTimeout(connectMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readMs.toLong(), TimeUnit.MILLISECONDS).build())

    private class FileSource(private val context: Context, private val key: ArchiveFileKey,
        private val http: OkHttpClient) : BaseDataSource(true) {
        private var call: Call? = null
        private var response: Response? = null
        private var remaining = C.LENGTH_UNSET.toLong()
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            val started = SystemClock.elapsedRealtime()
            try {
                if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) throw Failure("http_unavailable")
                val request = request(key, dataSpec.position, dataSpec.length, optional = false) {
                    NetworkQualityMonitor.getInstance(context).sourcePolicySnapshot().allowsArchive
                }
                val reply = http.newCall(request).also { call = it }.execute()
                response = reply
                remaining = checkResponse(reply, dataSpec.position, dataSpec.length)
                recordRange(key, reply.code, dataSpec.position, dataSpec.length)
                transferStarted(dataSpec)
                opened = true
                ArchivePlaybackEvents.record("load", if (dataSpec.position == 0L) "opened" else "range_opened",
                    key, SystemClock.elapsedRealtime() - started, status = reply.code)
                return remaining
            } catch (error: IOException) {
                close()
                ArchivePlaybackEvents.record("load", (error as? Failure)?.outcome ?: "network_error",
                    key, SystemClock.elapsedRealtime() - started, status = (error as? Failure)?.status)
                throw error
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val count = checkNotNull(response?.body).source().read(buffer, offset,
                if (remaining < 0L) length else minOf(length.toLong(), remaining).toInt())
            if (count < 0 && remaining > 0L) throw java.io.EOFException("Archive response truncated")
            if (count > 0) {
                if (remaining >= 0L) remaining -= count
                bytesTransferred(count)
            }
            return count
        }

        override fun getUri(): Uri? = response?.let { Uri.parse(key.url.toString()) }
        override fun getResponseHeaders(): Map<String, List<String>> = response?.headers?.toMultimap().orEmpty()
        override fun close() {
            try { call?.cancel(); response?.close() } finally {
                call = null
                response = null
                if (opened) {
                    opened = false
                    transferEnded()
                }
            }
        }
    }
}
