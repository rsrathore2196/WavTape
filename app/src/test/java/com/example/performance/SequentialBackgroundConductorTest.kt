package com.example.performance

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SequentialBackgroundConductorTest {
    @Before
    fun resetCoordinator() {
        BackgroundWorkCoordinator.resetForTesting()
    }

    @Test
    fun queuedTasksExecuteStrictlyOneAtATimeInPriorityOrder() = runBlocking {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val executionOrder = mutableListOf<String>()

        val head = async {
            SequentialBackgroundConductor.enqueue(
                priority = SequentialBackgroundConductor.Priority.MAINTENANCE,
                yieldToScroll = false,
                label = "head"
            ) {
                val now = active.incrementAndGet()
                maxActive.accumulateAndGet(now, ::maxOf)
                synchronized(executionOrder) { executionOrder.add("head") }
                firstEntered.complete(Unit)
                try {
                    releaseFirst.await()
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        withTimeout(2_000) { firstEntered.await() }

        val maintenance = async {
            SequentialBackgroundConductor.enqueue(
                priority = SequentialBackgroundConductor.Priority.MAINTENANCE,
                yieldToScroll = false,
                label = "maintenance"
            ) {
                val now = active.incrementAndGet()
                maxActive.accumulateAndGet(now, ::maxOf)
                synchronized(executionOrder) { executionOrder.add("maintenance") }
                delay(10)
                active.decrementAndGet()
            }
        }
        val warmup = async {
            SequentialBackgroundConductor.enqueue(
                priority = SequentialBackgroundConductor.Priority.TAB_WARMUP,
                yieldToScroll = false,
                label = "warmup"
            ) {
                val now = active.incrementAndGet()
                maxActive.accumulateAndGet(now, ::maxOf)
                synchronized(executionOrder) { executionOrder.add("warmup") }
                delay(10)
                active.decrementAndGet()
            }
        }
        val visible = async {
            SequentialBackgroundConductor.enqueue(
                priority = SequentialBackgroundConductor.Priority.VISIBLE_HOME,
                yieldToScroll = false,
                label = "visible"
            ) {
                val now = active.incrementAndGet()
                maxActive.accumulateAndGet(now, ::maxOf)
                synchronized(executionOrder) { executionOrder.add("visible") }
                delay(10)
                active.decrementAndGet()
            }
        }

        delay(50)
        assertEquals(1, maxActive.get())
        releaseFirst.complete(Unit)

        withTimeout(3_000) {
            head.await()
            visible.await()
            warmup.await()
            maintenance.await()
        }

        assertEquals(1, maxActive.get())
        assertEquals(listOf("head", "visible", "warmup", "maintenance"), executionOrder)
    }

    @Test
    fun cancellingQueuedTaskNeverStallsSubsequentWork() = runBlocking {
        val blockerEntered = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val blocker = async {
            SequentialBackgroundConductor.enqueue(
                priority = SequentialBackgroundConductor.Priority.VISIBLE_HOME,
                yieldToScroll = false,
                label = "blocker"
            ) {
                blockerEntered.complete(Unit)
                releaseBlocker.await()
            }
        }
        withTimeout(2_000) { blockerEntered.await() }

        var cancelledRan = false
        val doomed = async {
            SequentialBackgroundConductor.enqueue(
                priority = SequentialBackgroundConductor.Priority.ENRICHMENT,
                yieldToScroll = false,
                label = "doomed"
            ) {
                cancelledRan = true
            }
        }
        delay(30)
        doomed.cancelAndJoin()
        releaseBlocker.complete(Unit)
        blocker.await()

        val successorResult = withTimeout(2_000) {
            SequentialBackgroundConductor.enqueue(
                priority = SequentialBackgroundConductor.Priority.ENRICHMENT,
                yieldToScroll = false,
                label = "successor"
            ) { "completed" }
        }
        assertEquals("completed", successorResult)
        assertTrue(!cancelledRan)
    }

    @Test
    fun artworkDispatcherRunsIndependentlyWhenCpuBoundedTicketIsHeld() = runBlocking {
        val cpuEntered = CompletableDeferred<Unit>()
        val releaseCpu = CompletableDeferred<Unit>()
        val cpuHolder = async(AppDispatchers.Io) {
            BackgroundWorkCoordinator.localDataPreparation(awaitFrameQuiet = false) {
                withContext(AppDispatchers.CpuBounded) {
                    cpuEntered.complete(Unit)
                    releaseCpu.await()
                }
            }
        }
        try {
            withTimeout(2_000) { cpuEntered.await() }
            val decoded = withTimeout(1_500) {
                withContext(AppDispatchers.Artwork) { "decoded" }
            }
            assertEquals("decoded", decoded)
        } finally {
            releaseCpu.complete(Unit)
            cpuHolder.cancelAndJoin()
        }
    }
}
