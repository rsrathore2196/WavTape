package com.example.performance

import android.app.Application
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WorkStagesTest {
    @Before fun headless() { BackgroundWorkCoordinator.resetForTesting() }

    @Test fun pendingFirstDrawParksCacheCpuAndMaintenanceBeforeForegroundCallback() = runBlocking {
        BackgroundWorkCoordinator.expectForegroundDraw()
        val cache = async(AppDispatchers.Io) {
            BackgroundWorkCoordinator.localDataPreparation(awaitFrameQuiet = false) {
                withContext(AppDispatchers.CpuBounded) { "restored" }
            }
        }
        val maintenance = async(AppDispatchers.Io) {
            BackgroundWorkCoordinator.awaitMaintenanceIdle()
            "maintained"
        }
        val startup = async(AppDispatchers.Io) {
            BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.MAINTENANCE,
                requireForeground = false) { "scheduled" }
        }
        try {
            delay(150)
            assertFalse("A starting Activity is not a headless process", cache.isCompleted)
            assertFalse(maintenance.isCompleted)
            assertFalse(startup.isCompleted)
            BackgroundWorkCoordinator.firstFrameRendered()
            assertEquals("restored", withTimeout(2_000) { cache.await() })
            assertEquals("maintained", withTimeout(2_000) { maintenance.await() })
            assertEquals("scheduled", withTimeout(2_000) { startup.await() })
        } finally {
            BackgroundWorkCoordinator.firstFrameRendered()
            cache.cancelAndJoin(); maintenance.cancelAndJoin(); startup.cancelAndJoin()
        }
    }

    @Test fun actualHeadlessMaintenanceStillRunsWithoutAnActivity() = runBlocking {
        withTimeout(2_000) { BackgroundWorkCoordinator.awaitMaintenanceIdle() }
        assertEquals("ready", withTimeout(2_000) {
            BackgroundWorkCoordinator.localDataPreparation {
                withContext(AppDispatchers.CpuBounded) { "ready" }
            }
        })
    }

    @Test fun visibleContentGetsTheNextStageBeforeAnOlderPersistenceTicket() = runBlocking {
        val job = Job()
        fun ticket(kind: WorkClass) = checkNotNull(WorkStages.cpuTicket(job + WorkClassification(kind)))
        val running = ticket(WorkClass.ENRICHMENT)
        val persistence = ticket(WorkClass.PERSISTENCE)
        val visible = ticket(WorkClass.VISIBLE_CONTENT)
        try {
            // Visible has priority even though it arrived last.
            assertFalse(running.tryEnter())
            assertTrue(visible.tryEnter())
            assertFalse(persistence.tryEnter())
            visible.close()
            assertTrue(running.tryEnter())
            running.close()
            assertTrue(persistence.tryEnter())
        } finally { visible.close(); persistence.close(); running.close(); job.cancel() }
    }

    @Test fun helperConsumersShareOneAutomaticCpuStageWhileCriticalWorkCanRun() = runBlocking {
        // Inject two workers so the stage-admission assertion is independent of
        // the host/CI processor count. Production's overall CPU ceiling is tested separately.
        java.util.concurrent.Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { pool ->
            val cpu = BackgroundDispatcher(pool, preemptible = true)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val secondEntered = AtomicInteger()
            val first = async(AppDispatchers.Io) {
                BackgroundWorkCoordinator.localDataPreparation {
                    withContext(cpu) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            val second = async(AppDispatchers.Io) {
                check(entered.await(5, TimeUnit.SECONDS))
                BackgroundWorkCoordinator.optionalProcessing {
                    withContext(cpu) { secondEntered.incrementAndGet() }
                }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { com.example.recommendation.DiscoveryRequestCoordinator.playback {
                    withContext(cpu) { assertEquals(0, secondEntered.get()) }
                } }
            } finally { release.countDown() }
            first.await(); second.await()
            assertEquals(1, secondEntered.get())
        }
    }

    @Test fun cancellingAQueuedStageCannotStrandItsSuccessor() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch { WorkStages.disk(WorkClass.VISIBLE_CONTENT) { entered.complete(Unit); release.await() } }
        entered.await()
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { WorkStages.disk { fail("Cancelled writer ran") } }
        cancelled.cancelAndJoin()
        release.complete(Unit); first.join()
        withTimeout(2_000) { WorkStages.disk { /* no leaked permit */ } }
    }

    @Test fun suspensionReleasesCpuCapacityAndPresentationDoesNotQueueBehindBulkWork() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val parked = async(AppDispatchers.Io + WorkClassification(WorkClass.ENRICHMENT)) {
            withContext(AppDispatchers.CpuBounded) { entered.complete(Unit); release.await(); "resumed" }
        }
        try {
            entered.await()
            assertEquals("visible", withTimeout(2_000) {
                withContext(AppDispatchers.CpuBounded + WorkClassification(WorkClass.VISIBLE_CONTENT)) { "visible" }
            })
            assertEquals("presented", withTimeout(2_000) { withContext(AppDispatchers.Presentation) { "presented" } })
        } finally { release.complete(Unit) }
        assertEquals("resumed", parked.await())
    }
}
