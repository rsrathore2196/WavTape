package com.example.performance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-consumer sequential queue that prevents non-critical background stages
 * (Home enrichment, supplemental browses, tab pre-warming, and maintenance)
 * from competing with each other or saturating CPU/IO pools after startup.
 *
 * Playback and user-triggered commands do not wait in this queue.
 */
object SequentialBackgroundConductor {
    enum class Priority {
        VISIBLE_HOME,
        TAB_WARMUP,
        ENRICHMENT,
        MAINTENANCE
    }

    private class Task<T>(
        val priority: Priority,
        val yieldToScroll: Boolean,
        val label: String,
        val result: CompletableDeferred<T>,
        val block: suspend () -> T
    )

    private val queueLock = Any()
    private val pending = mutableListOf<Task<*>>()
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)

    init {
        scope.launch {
            for (ignored in wakeSignal) {
                while (true) {
                    val next = synchronized(queueLock) {
                        val candidate = pending.minByOrNull { it.priority.ordinal }
                        if (candidate != null) pending.remove(candidate)
                        candidate
                    } ?: break
                    if (!next.result.isActive) continue
                    runTask(next)
                }
            }
        }
    }

    private suspend fun <T> runTask(task: Task<T>) {
        kotlinx.coroutines.coroutineScope {
            val worker = launch {
                try {
                    if (task.yieldToScroll && BackgroundWorkCoordinator.uiForeground.value) {
                        BackgroundWorkCoordinator.awaitNavigationIdle(requireForeground = false)
                        // Let active fling frames settle briefly without stalling indefinitely.
                        var waited = 0L
                        while (waited < 400L && !BackgroundWorkCoordinator.isInteractionIdle(awaitFrameQuiet = false)) {
                            delay(40L)
                            waited += 40L
                        }
                    }
                    val value = BackgroundWorkCoordinator.traceWork("Sequential/${task.label}") {
                        task.block()
                    }
                    task.result.complete(value)
                } catch (cancelled: CancellationException) {
                    task.result.cancel(cancelled)
                } catch (error: Throwable) {
                    task.result.completeExceptionally(error)
                }
            }
            val handle = task.result.invokeOnCompletion { cause ->
                if (cause is CancellationException) worker.cancel(cause)
            }
            try {
                worker.join()
            } finally {
                handle.dispose()
            }
        }
    }

    suspend fun <T> enqueue(
        priority: Priority = Priority.ENRICHMENT,
        yieldToScroll: Boolean = priority != Priority.VISIBLE_HOME,
        label: String = priority.name,
        block: suspend () -> T
    ): T {
        currentCoroutineContext().ensureActive()
        val deferred = CompletableDeferred<T>()
        val task = Task(priority, yieldToScroll, label, deferred, block)
        synchronized(queueLock) { pending.add(task) }
        wakeSignal.trySend(Unit)
        try {
            return deferred.await()
        } catch (cancelled: CancellationException) {
            synchronized(queueLock) { pending.remove(task) }
            deferred.cancel(cancelled)
            throw cancelled
        }
    }
}
