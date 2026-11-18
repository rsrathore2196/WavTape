package com.example

import android.app.Application
import com.example.updater.UpdateCheckListeners
import com.example.updater.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateCheckListenersTest {
    @Test fun manualChecksJoiningAnAutomaticCheckReceiveItsResultWithoutAnotherRequest() {
        val listeners = UpdateCheckListeners()
        val automatic = mutableListOf<UpdateStatus>()
        val manual = mutableListOf<UpdateStatus>()
        listeners.add(automatic::add)
        listeners.add(manual::add)
        val result = UpdateStatus.UpToDate("2.1")
        listeners.complete(result)
        assertEquals(listOf(result), automatic)
        assertEquals(automatic, manual)
    }

    @Test fun aCallerJoiningDuringResultDeliveryIsNotLost() {
        val listeners = UpdateCheckListeners()
        val joined = mutableListOf<UpdateStatus>()
        listeners.add { listeners.add(joined::add) }
        val result = UpdateStatus.Error("Offline")
        listeners.complete(result)
        assertEquals(listOf(result), joined)
    }

    @Test fun aThrowingScreenCallbackDoesNotChangeTheResultOrSuppressOtherCallers() {
        val listeners = UpdateCheckListeners()
        val received = mutableListOf<UpdateStatus>()
        listeners.add { error("Screen callback failed") }
        listeners.add(received::add)
        val result = UpdateStatus.UpToDate("2.1")
        listeners.complete(result)
        listeners.complete(UpdateStatus.Error("Unexpected second result"))
        assertEquals(listOf(result), received)
        val late = mutableListOf<UpdateStatus>()
        listeners.add(late::add)
        assertEquals(listOf(result), late)
    }
}
