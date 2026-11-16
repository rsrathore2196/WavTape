package com.example.performance

import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/** Process-wide execution lanes. Never create another Default.limitedParallelism view.
 * Components accepting a CoroutineDispatcher can inject a test dispatcher instead.
 * Media3's audio renderer/loader threads deliberately do not use this CPU budget. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
object AppDispatchers {
    val Main: CoroutineDispatcher get() = Dispatchers.Main.immediate
    // Only tiny immutable player-state joins. Parsing/list transforms stay on CpuBounded.
    val Presentation: CoroutineDispatcher = Dispatchers.Default
    val CpuBounded: CoroutineDispatcher = BackgroundDispatcher(
        Dispatchers.Default.limitedParallelism(backgroundCpuParallelism(Runtime.getRuntime().availableProcessors())),
        preemptible = true
    )
    val Io: CoroutineDispatcher = BackgroundDispatcher(Dispatchers.IO)
    val Database: CoroutineDispatcher = BackgroundDispatcher(Dispatchers.IO.limitedParallelism(4))
    val HomeBookkeeping: CoroutineDispatcher = BackgroundDispatcher(Dispatchers.IO.limitedParallelism(1))
    val Imports: CoroutineDispatcher = BackgroundDispatcher(Dispatchers.IO.limitedParallelism(2))
    // Dedicated bounded lane for Coil image decoding/transformations without global CPU ticket contention
    // or interaction-gate stalling during smooth list flings.
    internal val Artwork: CoroutineDispatcher = BackgroundDispatcher(
        Dispatchers.Default.limitedParallelism(maxOf(2, backgroundCpuParallelism(Runtime.getRuntime().availableProcessors()))),
        preemptible = false
    )
}

/** Cooperative UI/optional CPU work carries this element. Critical playback does not. */
internal class NonEssentialWork(
    val owner: Job?,
    val canRun: () -> Boolean,
    val urgent: () -> Boolean = { false },
    val awaitAdmission: suspend () -> Unit
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NonEssentialWork>
}

/** Decorates the existing shared pools, without allocating another executor or CPU budget. */
internal class BackgroundDispatcher(
    private val delegate: CoroutineDispatcher,
    private val preemptible: Boolean = false
) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val ticket = if (preemptible) WorkStages.cpuTicket(context) else null
        fun schedule() {
            delegate.dispatch(context) {
                val optional = if (context[CriticalWork] != null) null else context[NonEssentialWork]
                val active = context[Job]?.isActive != false && optional?.owner?.isActive != false
                if (preemptible && active && (optional != null && !optional.canRun() || ticket != null && !ticket.tryEnter())) {
                    // Park a continuation on the shared gate scope, not a pool thread or fresh scope object.
                    val gateContext = context.minusKey(ContinuationInterceptor).minusKey(NonEssentialWork) + Dispatchers.IO
                    gateScope.launch(gateContext, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            optional?.awaitAdmission?.invoke()
                            ticket?.awaitTurn()
                        } finally { schedule() }
                    }
                } else try { atBackgroundPriority(block) } finally { ticket?.close() }
            }
        }
        schedule()
    }

    private fun atBackgroundPriority(block: Runnable) {
        val tid = Process.myTid()
        val previous = Process.getThreadPriority(tid)
        val lower = previous < Process.THREAD_PRIORITY_BACKGROUND
        try {
            if (lower) Process.setThreadPriority(tid, Process.THREAD_PRIORITY_BACKGROUND)
            block.run()
        } finally {
            // Default/IO workers are shared. Never leave a reused worker demoted,
            // and never change Main or Media3's decoder/renderer/loader priorities.
            if (lower) Process.setThreadPriority(tid, previous)
        }
    }

    private companion object {
        val gateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
