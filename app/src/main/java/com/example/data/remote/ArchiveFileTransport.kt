package com.example.data.remote

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.example.playback.NetworkQualityMonitor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A single Archive file transport shared by metadata probes and the Media3 cache upstream. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object ArchiveFileTransport {
    internal const val PREFIX_BYTES = 256 * 1024
    private const val UNKNOWN = -1L
    private val byteRange = Regex("bytes ([0-9]+)-([0-9]+)/(\\*|[0-9]+)", RegexOption.IGNORE_CASE)
    private val unsatisfiedRange = Regex("bytes \\*/([0-9]+)", RegexOption.IGNORE_CASE)
    private val redirectStatuses = setOf(301, 302, 303, 307, 308)
    private val replyLimit = java.util.concurrent.atomic.AtomicLong()

    internal class Failure(val outcome: String, val status: Int? = null) : IOException("Archive $outcome")
    private class Access(val key: ArchiveFileKey, val allowed: () -> Boolean)
    private val rangeAvailability = android.util.LruCache<ArchiveFileKey, Pair<Long, Boolean>>(128)

    private fun rangeSupport(key: ArchiveFileKey): Boolean? = rangeAvailability.get(key)?.let {
        it.second.takeIf { _ -> SystemClock.elapsedRealtime() - it.first in 0 until 300_000L }
    }

    fun isSequentialOnly(key: ArchiveFileKey): Boolean = rangeSupport(key) == false
    fun supportsRanges(key: ArchiveFileKey): Boolean = rangeSupport(key) == true

    private fun recordRange(key: ArchiveFileKey, code: Int) {
        if (code == 206 || code == 200)
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
                if (isRateLimited()) throw Failure("rate_limited", 429)
                val response = chain.proceed(request)
                if (response.code !in redirectStatuses) {
                    if (response.code == 429 || response.code == 503) noteBackoff(response.header("Retry-After"), response.code)
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

    fun noteBackoff(value: String?, status: Int): Long? {
        val seconds = value?.trim()?.toLongOrNull() ?: value?.let {
            runCatching {
                val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                    .apply { isLenient = false }.parse(it)
                (requireNotNull(date).time - System.currentTimeMillis() + 999L) / 1000L
            }.getOrNull()
        } ?: 30L.takeIf { status == 429 }
        if (seconds != null && seconds > 0L) {
            val until = SystemClock.elapsedRealtime() + seconds.coerceAtMost(3600L) * 1000L
            replyLimit.accumulateAndGet(until, ::maxOf)
        }
        return seconds?.coerceAtLeast(0L)
    }

    private fun request(key: ArchiveFileKey, position: Long, length: Long, validator: String?,
        allowed: () -> Boolean): Request {
        val last = if (length == UNKNOWN) "" else {
            if (length <= 0 || position > Long.MAX_VALUE - length) throw Failure("range_unavailable")
            (position + length - 1).toString()
        }
        return Request.Builder().url(key.url).tag(Access::class.java, Access(key, allowed))
            .header("User-Agent", "WavTape (Android; Archive Audio)")
            .header("Accept-Encoding", "identity").header("Range", "bytes=$position-$last")
            .apply { if (validator != null) header("If-Range", validator) }.build()
    }

    internal data class Span(val length: Long, val total: Long)

    /** A 206 describes one response body, whose length may be smaller than the requested span. */
    internal fun responseSpan(code: Int, contentRange: String?, contentLength: Long,
        position: Long, wanted: Long): Span {
        fun invalid(): Nothing = throw Failure("range_unavailable", code)
        if (code == 200) {
            if (position != 0L || contentRange != null) invalid()
            val length = if (wanted < 0L || contentLength < 0L) contentLength else minOf(wanted, contentLength)
            return Span(length, contentLength)
        }
        if (code == 416) {
            val size = contentRange?.let(unsatisfiedRange::matchEntire)?.groupValues?.get(1)?.toLongOrNull()
            if (size == null || position != size) invalid()
            return Span(0L, size)
        }
        if (code != 206) throw Failure(when (code) {
            401, 403, 451 -> "access_denied"
            429, 503 -> "rate_limited"
            else -> "http_unavailable"
        }, code)
        val (first, last, total) = (contentRange?.let(byteRange::matchEntire) ?: invalid()).destructured
        val start = first.toLongOrNull() ?: invalid()
        val end = last.toLongOrNull() ?: invalid()
        val size = if (total == "*") UNKNOWN else total.toLongOrNull() ?: invalid()
        if (start != position || end < start || end == Long.MAX_VALUE || size >= 0L && end >= size) invalid()
        val length = end - start + 1
        if (wanted >= 0L && length > wanted || contentLength >= 0L && contentLength != length) invalid()
        return Span(length, size)
    }

    private class RangeCursor(val start: Long, private val wanted: Long) {
        var position = start
            private set
        var total = UNKNOWN
            private set
        var chunkRemaining = UNKNOWN
            private set
        var validator: String? = null
            private set
        var partial = false
            private set

        val remaining: Long
            get() {
                val requested = if (wanted < 0) UNKNOWN else wanted - (position - start)
                val available = if (total < 0) UNKNOWN else total - position
                return if (available < 0) requested else if (requested < 0) available else minOf(available, requested)
            }

        fun accept(key: ArchiveFileKey, response: Response) {
            if (response.code == 200 && position > 0L) {
                if (validator != null) throw Failure("range_changed", response.code)
                recordRange(key, response.code)
            }
            val span = responseSpan(response.code, response.header("Content-Range"),
                response.body.contentLength(), position, remaining)
            val tag = response.header("ETag")
            if (response.code != 416 &&
                response.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false)
                throw Failure("range_unavailable", response.code)
            if (span.total >= 0 && total >= 0 && span.total != total ||
                total >= 0 && span.length > total - position ||
                response.code != 416 && validator != null && tag != null && tag != validator)
                throw Failure("range_changed", response.code)
            if (span.total >= 0) total = span.total
            if (validator == null && tag?.startsWith('"') == true && tag.endsWith('"')) validator = tag
            chunkRemaining = span.length
            partial = response.code == 206
            recordRange(key, response.code)
        }

        fun consume(count: Long) {
            position += count
            if (chunkRemaining >= 0) chunkRemaining -= count
        }

        fun limit(length: Long): Long = if (chunkRemaining < 0) length else minOf(length, chunkRemaining)

        fun endOfBody() {
            if (chunkRemaining > 0L) throw EOFException("Archive response truncated")
            if (!partial) total = position
        }
    }

    private val probes = client.newBuilder().callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    suspend fun probe(key: ArchiveFileKey, offset: Long = 0, maxBytes: Int = PREFIX_BYTES,
        allowed: () -> Boolean): ByteArray = probe(key, offset, maxBytes, allowed, probes)

    internal suspend fun probe(key: ArchiveFileKey, offset: Long, maxBytes: Int,
        allowed: () -> Boolean, http: OkHttpClient): ByteArray = withTimeoutOrNull(10_000L) {
        require(offset >= 0 && maxBytes in 1..PREFIX_BYTES)
        if (offset > 0 && isSequentialOnly(key)) throw Failure("range_unavailable")
        val cursor = RangeCursor(offset, maxBytes.toLong())
        val buffer = okio.Buffer()
        repeat(16) {
            readProbe(key, cursor, buffer, allowed, http)
            if (cursor.remaining == 0L) return@withTimeoutOrNull buffer.readByteArray()
        }
        throw Failure("range_probe_limit")
    } ?: throw SocketTimeoutException("Archive file probe timed out")

    private suspend fun readProbe(key: ArchiveFileKey, cursor: RangeCursor, buffer: okio.Buffer,
        allowed: () -> Boolean, http: OkHttpClient): Unit = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request(key, cursor.position, cursor.remaining, cursor.validator, allowed))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        cursor.accept(key, it)
                        val input = it.body.source()
                        while (cursor.remaining > 0L && cursor.chunkRemaining != 0L) {
                            val count = input.read(buffer, cursor.limit(minOf(8192L, cursor.remaining)))
                            if (count < 0L) {
                                cursor.endOfBody()
                                break
                            }
                            cursor.consume(count)
                        }
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    fun source(context: Context, key: ArchiveFileKey, connectMs: Int, readMs: Int,
        resume: ArchiveCacheResume? = null, replayLimit: Long = Long.MAX_VALUE): BaseDataSource =
        FileSource(key, client.newBuilder()
            .connectTimeout(connectMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readMs.toLong(), TimeUnit.MILLISECONDS).build(), resume, replaySequential = true, replayLimit = replayLimit) {
            val policy = NetworkQualityMonitor.getInstance(context.applicationContext).sourcePolicySnapshot()
            if (!policy.online) throw IOException("Archive network unavailable")
            policy.allowsArchive
        }

    internal class FileSource(private val key: ArchiveFileKey, private val http: OkHttpClient,
        private val resume: ArchiveCacheResume? = null, private val replaySequential: Boolean = false,
        private val replayLimit: Long = Long.MAX_VALUE, private val allowed: () -> Boolean) : BaseDataSource(true) {
        private var call: Call? = null
        private var response: Response? = null
        private var cursor: RangeCursor? = null
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            val started = SystemClock.elapsedRealtime()
            try {
                if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) throw Failure("http_unavailable")
                val range = openInitial(dataSpec)
                cursor = range
                transferStarted(dataSpec)
                opened = true
                ArchivePlaybackEvents.record("load", if (dataSpec.position == 0L) "opened" else "range_opened",
                    key, SystemClock.elapsedRealtime() - started, status = response?.code)
                return range.remaining
            } catch (error: IOException) {
                close()
                report(error, started)
                throw error
            }
        }

        private fun execute(position: Long, length: Long, validator: String?): Response {
            response?.close()
            response = null
            if (!allowed()) throw Failure("policy_blocked")
            return http.newCall(request(key, position, length, validator, allowed)).also { call = it }
                .execute().also { response = it }
        }

        private fun openInitial(spec: DataSpec): RangeCursor {
            val expectedTag = resume?.validator
            val offset = spec.position
            val range = RangeCursor(offset, spec.length)
            if (offset > 0 && replaySequential && expectedTag == null && resume?.hasCachedBytes() == true &&
                supportsRanges(key)) {
                // An expired or restarted untagged representation must agree with persisted bytes.
                val reading = replayRange(spec)
                return replay(spec, execute(0, reading.remaining, null), expectedTag)
            }
            if (offset > 0 && replaySequential && !supportsRanges(key)) {
                // Establish capability at zero after restart, expiration or a previous 200.
                val head = execute(0, 1, expectedTag)
                if (head.code == 200) return replay(spec, head, expectedTag)
                val probe = RangeCursor(0, 1)
                probe.accept(key, head)
                resume?.verifyHeaders(strongTag(head), probe.total, conditionalRange = head.code == 206 && expectedTag != null)
                if (probe.total == 0L) throw Failure("range_unavailable", head.code)
                if (expectedTag == null && resume?.hasCachedBytes() == true) {
                    val reading = replayRange(spec)
                    return replay(spec, execute(0, reading.remaining, null), expectedTag)
                }
            }
            val reply = execute(offset, spec.length, expectedTag.takeIf { offset > 0 })
            if (reply.code == 200 && offset > 0 && replaySequential) return replay(spec, reply, expectedTag)
            range.accept(key, reply)
            val tag = strongTag(reply)
            resume?.verifyHeaders(tag, range.total, conditionalRange = offset > 0 && reply.code in setOf(206, 416) &&
                expectedTag != null)
            resume?.remember(tag, provenPrefix = false)
            return range
        }

        private fun replayRange(spec: DataSpec): RangeCursor {
            val offset = spec.position
            if (offset > replayLimit) throw Failure("range_unavailable")
            val length = if (spec.length == UNKNOWN) UNKNOWN else {
                if (offset > Long.MAX_VALUE - spec.length) throw Failure("range_unavailable")
                offset + spec.length
            }
            return RangeCursor(0, length)
        }

        private fun replay(spec: DataSpec, response: Response, expectedTag: String?): RangeCursor {
            val offset = spec.position
            val range = replayRange(spec)
            range.accept(key, response)
            if (range.total >= 0 && offset > range.total) throw Failure("range_unavailable", response.code)
            val tag = strongTag(response)
            // If a mirror omits its tag, require matching cached bytes before joining.
            val compare = expectedTag == null || tag == null
            resume?.verifyHeaders(tag, range.total, conditionalRange = tag == null)
            var nextPolicyCheck = SystemClock.elapsedRealtime()
            fun checkpoint() {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                val now = SystemClock.elapsedRealtime()
                if (now >= nextPolicyCheck) {
                    if (!allowed()) throw Failure("policy_blocked")
                    nextPolicyCheck = now + 500L
                }
            }
            val reader = DataReader { buffer, start, length -> readChunk(range, buffer, start, length) }
            if (resume != null) resume.verifyPrefix(reader, offset, compare, ::checkpoint)
            else ArchiveCacheResume.discard(reader, offset, ::checkpoint)
            resume?.remember(range.validator, provenPrefix = compare)
            return range
        }

        private fun openChunk(range: RangeCursor) {
            val validator = range.validator ?: resume?.validator
            val reply = execute(range.position, range.remaining, validator)
            range.accept(key, reply)
            resume?.verifyHeaders(strongTag(reply), range.total, conditionalRange = validator != null && (reply.code == 206 || reply.code == 416))
        }

        private fun strongTag(response: Response): String? = response.header("ETag")?.takeIf {
            it.length in 2..256 && it.first() == '"' && it.last() == '"'
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val range = checkNotNull(cursor)
            if (range.remaining == 0L) return C.RESULT_END_OF_INPUT
            val started = SystemClock.elapsedRealtime()
            try {
                return readChunk(range, buffer, offset, length).also { if (it > 0) bytesTransferred(it) }
            } catch (error: IOException) {
                report(error, started)
                throw error
            }
        }

        private fun readChunk(range: RangeCursor, buffer: ByteArray, offset: Int, length: Int): Int {
            if (range.remaining == 0L) return C.RESULT_END_OF_INPUT
            if (range.chunkRemaining == 0L) {
                check(range.partial)
                openChunk(range)
                if (range.remaining == 0L) return C.RESULT_END_OF_INPUT
            }
            val count = checkNotNull(response).body.source().read(buffer, offset,
                range.limit(if (range.remaining < 0) length.toLong() else minOf(length.toLong(), range.remaining)).toInt())
            if (count < 0) range.endOfBody()
            if (count > 0) range.consume(count.toLong())
            return count
        }

        private fun report(error: IOException, started: Long) {
            ArchivePlaybackEvents.record("load", (error as? Failure)?.outcome ?: "network_error",
                key, SystemClock.elapsedRealtime() - started, status = (error as? Failure)?.status)
        }

        override fun getUri(): Uri? = if (opened) Uri.parse(key.url.toString()) else null
        override fun getResponseHeaders(): Map<String, List<String>> = response?.headers?.toMultimap().orEmpty()
        override fun close() {
            try { call?.cancel(); response?.close() } finally {
                call = null
                response = null
                cursor = null
                if (opened) {
                    opened = false
                    transferEnded()
                }
            }
        }
    }
}
