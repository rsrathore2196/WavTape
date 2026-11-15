package com.example

import com.example.playback.AudioQualityCeiling
import com.example.playback.NetworkConnectionType
import com.example.service.NextAudioPrefetcher
import com.example.service.NextPrefetchPolicy
import com.example.service.NextPrefetchWork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NextAudioPrefetchTest {
    @Test fun offlineDisabledWifiOnlyAndDataSaverDoNotAdmitRemotePrefetch() {
        for (connection in NetworkConnectionType.entries) {
            assertFalse(NextPrefetchPolicy.allowed(false, false, connection, true, AudioQualityCeiling.HIGH))
            assertFalse(NextPrefetchPolicy.allowed(true, false, connection, false, AudioQualityCeiling.HIGH))
        }
        assertFalse(NextPrefetchPolicy.allowed(true, true, NetworkConnectionType.CELLULAR, true, AudioQualityCeiling.HIGH))
        assertFalse(NextPrefetchPolicy.allowed(true, true, NetworkConnectionType.METERED_WIFI, true, AudioQualityCeiling.HIGH))
        assertFalse(NextPrefetchPolicy.allowed(true, false, NetworkConnectionType.OFFLINE, true, AudioQualityCeiling.HIGH))
        assertFalse(NextPrefetchPolicy.allowed(true, false, NetworkConnectionType.CELLULAR, true, AudioQualityCeiling.DATA_SAVER))
        assertTrue(NextPrefetchPolicy.allowed(true, true, NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET, true, AudioQualityCeiling.HIGH))
        assertTrue(NextPrefetchPolicy.allowed(true, false, NetworkConnectionType.CELLULAR, true, AudioQualityCeiling.NORMAL))
    }

    @Test fun onlyUnmeteredConnectionsCanFetchTheCompleteNextSong() {
        for (connection in NetworkConnectionType.entries) {
            assertEquals(connection == NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET,
                NextPrefetchPolicy.fetchFull(connection, AudioQualityCeiling.HIGH))
            assertFalse(NextPrefetchPolicy.fetchFull(connection, AudioQualityCeiling.DATA_SAVER))
        }
    }

    @Test fun stableNextTrackWaitsEightSecondsAndCannotStartDuplicateRequests() = runTest {
        val work = NextPrefetchWork(this)
        val started = mutableListOf<String>()
        repeat(10) { work.submit("next-a") { started += "a" } }
        runCurrent(); advanceTimeBy(NextAudioPrefetcher.GRACE_MS - 1); runCurrent()
        assertTrue(started.isEmpty())
        advanceTimeBy(1); runCurrent()
        repeat(10) { work.submit("next-a") { started += "duplicate" } }
        advanceTimeBy(20_000); runCurrent()
        assertEquals(listOf("a"), started)
        work.cancel()
    }

    @Test fun rapidQueueChangesAndPauseCancelGraceAndOnlyTheImmediateNextTrackStarts() = runTest {
        val work = NextPrefetchWork(this)
        val started = mutableListOf<String>()
        work.submit("current-one:next-two") { started += "two" }
        advanceTimeBy(4_000); runCurrent()
        work.submit("shuffled:next-five") { started += "five" }
        advanceTimeBy(4_000); runCurrent()
        work.cancel() // paused/stopped
        advanceTimeBy(20_000); runCurrent()
        assertTrue(started.isEmpty())
        work.submit("current-five:next-seven") { started += "seven" }
        advanceTimeBy(8_000); runCurrent()
        assertEquals(listOf("seven"), started)
        work.cancel()
    }

    @Test fun policyOrQueueReplacementRevokesWriterAdmissionBeforeCancellingTheOldCoroutine() = runTest {
        val work = NextPrefetchWork(this)
        var oldAllowed: (() -> Boolean)? = null
        var cancellationObserved = false
        work.submit("next-a:wifi:high") { allowed ->
            oldAllowed = allowed
            try { awaitCancellation() } finally { cancellationObserved = !allowed() }
        }
        advanceTimeBy(8_000); runCurrent()
        assertTrue(oldAllowed!!.invoke())
        work.submit("next-b:cellular:normal") { }
        assertFalse(oldAllowed!!.invoke())
        runCurrent()
        assertTrue(cancellationObserved)
        work.cancel()
    }
}
