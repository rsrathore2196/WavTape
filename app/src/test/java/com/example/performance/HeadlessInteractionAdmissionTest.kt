package com.example.performance

import android.app.Application
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class HeadlessInteractionAdmissionTest {
    private val coordinator = BackgroundWorkCoordinator

    @Before @After fun reset() { coordinator.resetForTesting() }

    @Test fun recentInteractionCannotLeaveHeadlessArtworkWaitingForANonexistentUiEvent() = runBlocking {
        coordinator.interaction()
        assertFalse(coordinator.uiForeground.value)
        assertTrue(coordinator.isInteractionIdle())
        withTimeout(2_000) { coordinator.yieldToInteraction() }
    }

    @Test fun headlessWorkRetainsExplicitMotionLeasesButNeedsNoQuietTimerAfterTheyClose() = runBlocking {
        val owner = Any()
        coordinator.foreground(true)
        coordinator.scrolling(owner, true)
        val navigation = coordinator.navigationBusy()
        try {
            assertFalse(coordinator.isInteractionIdle())
            coordinator.foreground(false)
            assertFalse(coordinator.isInteractionIdle())
            navigation.close()
            coordinator.scrolling(owner, false)
            assertTrue(coordinator.isInteractionIdle())
            assertFalse(coordinator.isInteractionIdle(requireForeground = true))
            withTimeout(2_000) { coordinator.yieldToInteraction() }
        } finally {
            navigation.close()
            coordinator.scrolling(owner, false)
        }
    }

    @Test fun foregroundQuietIntervalsAndFirstDrawProtectionRemainInEffect() {
        coordinator.foreground(true)
        coordinator.frame(2L, 1L)
        assertFalse(coordinator.isInteractionIdle())
        ShadowSystemClock.advanceBy(Duration.ofMillis(251))
        assertTrue(coordinator.isInteractionIdle())
        coordinator.expectForegroundDraw()
        assertFalse(coordinator.isInteractionIdle())
        coordinator.firstFrameRendered()
        assertTrue(coordinator.isInteractionIdle())
    }

    @Test fun deferredHeadlessBatchesDoNotWaitForAQuietDeadlineWithNoUiToSignalIt() = runBlocking {
        coordinator.interaction()
        withTimeout(2_000) {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED, requireForeground = false) {
                coordinator.yieldToInteraction()
            }
        }
    }
}
