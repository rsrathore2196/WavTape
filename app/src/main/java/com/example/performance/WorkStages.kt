package com.example.performance

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Classification of automatic work, not of playback renderers or user commands. */
internal enum class WorkClass { VISIBLE_CONTENT, ENRICHMENT, PERSISTENCE, MAINTENANCE }

internal class WorkClassification(val kind: WorkClass) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkClassification>
}

/** Explicit playback preparation keeps the engine's existing execution path. */
internal class CriticalWork : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CriticalWork>
}

/** Tickets cover one runnable/atomic write, never a suspended coroutine or network wait. */
internal object WorkStages {
    private val lock = Any()
    private val cpu = mutableListOf<Ticket>()
    private val disk = mutableListOf<Ticket>()
    private var cpuActive = false
    private var diskActive = false
    // CPU slices wake resource waiters, not every startup/lifecycle collector.
    private val changes = MutableStateFlow(0L)

    internal class Ticket internal constructor(val kind: WorkClass, private val cpuStage: Boolean,
        private val urgent: () -> Boolean, private val eligible: () -> Boolean) : AutoCloseable {
        private var acquired = false
        private var removed = false
        private val waitTrace = if (androidx.tracing.Trace.isEnabled())
            BackgroundWorkCoordinator.trace("${if (cpuStage) "CPU" else "Disk"} admission $kind") else null
        private var runTrace: AutoCloseable? = null
        private fun queue() = if (cpuStage) cpu else disk
        private fun available() = if (cpuStage) !cpuActive else !diskActive
        private fun priority() = if (urgent()) WorkClass.VISIBLE_CONTENT.ordinal else kind.ordinal
        fun tryEnter(): Boolean = synchronized(lock) {
            if (removed || !eligible() || !available()) return@synchronized false
            val next = queue().asSequence().filter { it.eligible() }.minByOrNull { it.priority() }
            if (next !== this) return@synchronized false
            queue().remove(this)
            acquired = true
            if (cpuStage) cpuActive = true else diskActive = true
            waitTrace?.close()
            runTrace = if (androidx.tracing.Trace.isEnabled())
                BackgroundWorkCoordinator.trace("${if (cpuStage) "CPU" else "Disk"} stage $kind") else null
            true
        }
        suspend fun awaitTurn() {
            BackgroundWorkCoordinator.awaitStageChange(changes) {
                synchronized(lock) {
                    removed || eligible() && available() &&
                        queue().asSequence().filter { it.eligible() }.minByOrNull { it.priority() } === this
                }
            }
        }
        override fun close() {
            synchronized(lock) {
                if (removed) return
                removed = true
                queue().remove(this)
                if (acquired) { if (cpuStage) cpuActive = false else diskActive = false }
            }
            waitTrace?.close(); runTrace?.close()
            changes.update { it + 1 }
        }
    }

    fun cpuTicket(context: CoroutineContext): Ticket? {
        if (context[CriticalWork] != null) return null
        val kind = context[WorkClassification]?.kind ?: return null
        return ticket(kind, true, context[NonEssentialWork]?.urgent ?: { false }) {
            context[kotlinx.coroutines.Job]?.isActive != false &&
                (context[NonEssentialWork]?.canRun?.invoke() ?: BackgroundWorkCoordinator.stageMayRun(kind))
        }
    }
    private fun ticket(kind: WorkClass, cpuStage: Boolean, urgent: () -> Boolean = { false }, eligible: () -> Boolean): Ticket =
        Ticket(kind, cpuStage, urgent, eligible).also {
            synchronized(lock) { (if (cpuStage) cpu else disk).add(it) }
            changes.update { it + 1 }
        }

    suspend fun <T> disk(kind: WorkClass = WorkClass.PERSISTENCE, block: suspend () -> T): T = withContext(AppDispatchers.Io) {
        val ticket = ticket(kind, false) { BackgroundWorkCoordinator.stageMayRun(kind) }
        try {
            while (!ticket.tryEnter()) { currentCoroutineContext().ensureActive(); ticket.awaitTurn() }
            block()
        } finally { ticket.close() }
    }
}

/** Bounded Kotlin loops yield without holding a network, file or database permit. */
internal class ProcessingSlice {
    private var count = 0
    private var started = android.os.SystemClock.elapsedRealtimeNanos()
    suspend fun checkpoint() {
        if (++count % 32 == 0 || android.os.SystemClock.elapsedRealtimeNanos() - started >= 4_000_000L) {
            BackgroundWorkCoordinator.awaitOptionalBatch()
            started = android.os.SystemClock.elapsedRealtimeNanos()
        }
    }
}
