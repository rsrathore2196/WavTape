package com.example.recommendation

import com.example.data.model.MusicTrack
import com.example.performance.AppDispatchers
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Shared discovery work: two bounded optional slots and a reserved urgent lane. */
internal object DiscoveryRequestCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    private class Flight(val urgent: MutableStateFlow<Boolean>,
        val result: Deferred<List<MusicTrack>>, var readers: Int = 0, var essentialReaders: Int = 0)
    private class SharedPriority(val urgent: MutableStateFlow<Boolean>, val awaitFrameQuiet: Boolean = true) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<SharedPriority>
    }
    private class NetworkAdmission : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<NetworkAdmission>
    }
    private val flights = mutableMapOf<String, Flight>()
    private val lock = Any()
    private val changed = MutableStateFlow(0L)
    private val scrolling = MutableStateFlow(false)
    private val resolving = MutableStateFlow(0)
    val playbackResolving: Boolean get() = resolving.value > 0
    private var running = 0
    private var optionalRunning = 0
    private val spaces = Regex("\\s+")

    fun normalize(query: String): String = Normalizer.normalize(query, Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace(spaces, " ")

    fun homeScrolling(active: Boolean) { scrolling.value = active }

    /** Shared producers read promotion dynamically; no extra collector or coroutine. */
    suspend fun requestUrgency(): StateFlow<Boolean>? = currentCoroutineContext()[SharedPriority]?.urgent

    /** A retained Home request can be promoted without starting another transport. */
    suspend fun <T> withPriority(urgent: MutableStateFlow<Boolean>, awaitFrameQuiet: Boolean = true, block: suspend () -> T): T =
        withContext(SharedPriority(urgent, awaitFrameQuiet)) {
            BackgroundWorkCoordinator.optionalProcessing(urgent = { urgent.value }, awaitFrameQuiet = awaitFrameQuiet, block = block)
        }

    suspend fun <T> network(essential: Boolean = false, timeoutMillis: Long? = 12_000L, block: suspend () -> T): T {
        if (currentCoroutineContext()[NetworkAdmission] != null) return block()
        val priority = currentCoroutineContext()[SharedPriority]
        val urgent = priority?.urgent ?: MutableStateFlow(essential)
        if (essential) { urgent.value = true; BackgroundWorkCoordinator.admissionChanged() }
        return permit(urgent, awaitFrameQuiet = priority?.awaitFrameQuiet != false) {
            // Time only the admitted transport, then release its permit before
            // catalog processing. A null deadline preserves a caller's HTTP policy.
            BackgroundWorkCoordinator.traceWork(if (urgent.value) "Discovery urgent network" else "Discovery optional network") {
                if (timeoutMillis == null) block() else withTimeoutOrNull(timeoutMillis) { Result.success(block()) }?.getOrThrow()
                    ?: throw java.io.IOException("Discovery request timed out")
            }
        }
    }

    suspend fun <T> playback(block: suspend () -> T): T {
        resolving.update { it + 1 }
        BackgroundWorkCoordinator.admissionChanged()
        try { return BackgroundWorkCoordinator.traceWork("Playback source resolution") {
            withContext(com.example.performance.CriticalWork()) { BackgroundWorkCoordinator.finishBoundedBatch(block) }
        } }
        finally { resolving.update { (it - 1).coerceAtLeast(0) }; BackgroundWorkCoordinator.admissionChanged() }
    }

    suspend fun shared(key: String, essential: Boolean, network: Boolean = true, playbackMetadata: Boolean = false,
        awaitFrameQuiet: Boolean = true,
        essentialUpdates: StateFlow<Boolean>? = null,
        block: suspend () -> List<MusicTrack>): List<MusicTrack> {
        var essentialReader = essential || essentialUpdates?.value == true
        val flight = synchronized(flights) {
            flights.getOrPut(key) {
                val urgent = MutableStateFlow(essentialReader)
                Flight(urgent, scope.async(SharedPriority(urgent, awaitFrameQuiet), start = CoroutineStart.LAZY) {
                    suspend fun work(): List<MusicTrack> {
                        return if (network) permit(urgent, awaitFrameQuiet) {
                            BackgroundWorkCoordinator.traceWork(if (urgent.value) "Discovery urgent shared request" else "Discovery optional shared request") {
                                BackgroundWorkCoordinator.finishBoundedBatch { withTimeoutOrNull(12_000L) { block() }.orEmpty() }
                            }
                        }
                        else block()
                    }
                    if (playbackMetadata) BackgroundWorkCoordinator.localDataPreparation(urgent = { urgent.value }, awaitFrameQuiet = awaitFrameQuiet) { work() }
                    else BackgroundWorkCoordinator.optionalProcessing(urgent = { urgent.value }, awaitFrameQuiet = awaitFrameQuiet) { work() }
                })
            }.also {
                it.readers++
                if (essentialReader) it.essentialReaders++
                it.urgent.value = it.essentialReaders > 0
                BackgroundWorkCoordinator.admissionChanged()
            }
        }
        try {
            return coroutineScope {
                val promotion = essentialUpdates?.let { updates -> launch(start = CoroutineStart.UNDISPATCHED) {
                    updates.collect { active -> synchronized(flights) {
                        val next = essential || active
                        if (next != essentialReader) {
                            flight.essentialReaders += if (next) 1 else -1
                            essentialReader = next
                            flight.urgent.value = flight.essentialReaders > 0
                            BackgroundWorkCoordinator.admissionChanged()
                        }
                    } }
                } }
                try { flight.result.start(); flight.result.await() } finally { promotion?.cancel() }
            }
        }
        finally {
            synchronized(flights) {
                if (essentialReader) flight.essentialReaders--
                flight.urgent.value = flight.essentialReaders > 0
                BackgroundWorkCoordinator.admissionChanged()
                if (--flight.readers == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    if (!flight.result.isCompleted) flight.result.cancel()
                }
            }
        }
    }

    private suspend fun <T> permit(urgent: StateFlow<Boolean>, awaitFrameQuiet: Boolean = true, block: suspend () -> T): T {
        var acquired = false
        var optional = false
        fun release() {
            if (acquired) synchronized(lock) {
                running--
                if (optional) optionalRunning--
                acquired = false
                changed.update { it + 1 }
            }
        }
        try {
            // Do not admit optional discovery in the same frames as a tab/player transition.
            // No request is cancelled or restarted, and playback's urgent lane stays open.
            while (true) {
                if (!urgent.value) com.example.performance.BackgroundWorkCoordinator.yieldToInteraction(
                    awaitFrameQuiet = awaitFrameQuiet, bypass = { urgent.value })
                combine(changed, urgent, scrolling, resolving) { _, _, _, _ -> Unit }.first {
                    synchronized(lock) {
                        // Optional metadata shares two slots; a third remains reserved
                        // for urgent playback, which also takes priority over discovery.
                        val discoveryAllowed = resolving.value == 0 && optionalRunning < 2 && !scrolling.value
                        if (running < 3 && (urgent.value || discoveryAllowed)) {
                            optional = !urgent.value
                            running++
                            if (optional) optionalRunning++
                            acquired = true
                            true
                        } else false
                    }
                }
                currentCoroutineContext().ensureActive()
                // A navigation gesture may begin while queued for a slot. Release
                // it before waiting again; paused Home work must not reserve IO
                // capacity needed by other consumers. No request has started yet.
                if (urgent.value || com.example.performance.BackgroundWorkCoordinator.isInteractionIdle(awaitFrameQuiet = awaitFrameQuiet)) break
                release()
            }
            return withContext(NetworkAdmission()) { block() }
        } finally {
            release()
        }
    }
}
