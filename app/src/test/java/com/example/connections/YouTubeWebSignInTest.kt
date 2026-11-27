package com.example.connections

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class YouTubeWebSignInTest {
    private val music = "https://music.youtube.com/"
    private val identity = """{"loggedIn":true,"name":"Music profile","sync":"profile||account","user":"2"}"""

    @Test fun firstConnectionWaitsForThePageAndCookiesWithoutReopening() = runTest {
        var evaluations = 0
        val session = awaitYouTubeWebSession(
            currentUrl = { music },
            readIdentity = { if (++evaluations == 1) "null" else identity },
            readCookie = { if (evaluations == 2) "PREF=anonymous" else "__Secure-3PAPISID=fixture" }
        )
        assertEquals(3, evaluations)
        assertEquals("Music profile", session?.name)
        assertEquals("profile", session?.profile)
        assertEquals("2", session?.user)
        assertFalse(session.toString().contains("fixture"))
    }

    @Test fun discardsAReplyAfterNavigationLeavesTheMusicOrigin() = runTest {
        var url = music
        val session = awaitYouTubeWebSession({ url }, readIdentity = {
            url = "https://accounts.google.com/ServiceLogin"
            identity
        }, readCookie = { error("Cookies must only be read on YouTube Music") }, timeoutMs = 1_000L)
        assertNull(session)
    }

    @Test fun doesNotPairThePreviousProfilesIdentityWithCookiesAfterSameOriginNavigation() = runTest {
        var url = "${music}?authuser=1"
        var evaluations = 0
        val session = awaitYouTubeWebSession({ url }, readIdentity = {
            if (++evaluations == 1) {
                url = "${music}?authuser=2"
                """{"loggedIn":true,"name":"Previous profile","user":"1"}"""
            } else identity
        }, readCookie = {
            assertEquals(2, evaluations)
            "SAPISID=fixture"
        })
        assertEquals("2", session?.user)
        assertEquals(url, session?.pageUrl)
    }

    @Test fun retriesAfterTheDocumentReloadsAtTheSameUrl() = runTest {
        var navigation = 5
        var evaluations = 0
        val session = awaitYouTubeWebSession({ music }, readIdentity = {
            if (++evaluations == 1) navigation++
            identity
        }, readCookie = {
            assertEquals(2, evaluations)
            "SAPISID=fixture"
        }, currentNavigation = { navigation })
        assertEquals(6, session?.navigation)
    }

    @Test fun disconnectCleanupContinuesWhenTheSettingsPageCloses() = runTest {
        val credentialsRemoved = CompletableDeferred<Unit>()
        val completeRemoval = CompletableDeferred<Unit>()
        val browserCleared = CompletableDeferred<String>()
        val disconnect = launch {
            disconnectBrowserAccount("youtube", clearBrowserData = { browserCleared.complete(it) }) {
                credentialsRemoved.complete(Unit)
                completeRemoval.await()
            }
        }
        credentialsRemoved.await()
        disconnect.cancel()
        completeRemoval.complete(Unit)
        disconnect.join()
        assertTrue(browserCleared.isCompleted)
        assertEquals("youtube", browserCleared.await())
    }

    @Test fun aStalledScriptCallbackTimesOutAndLeavesThePageRetryable() = runTest {
        assertNull(awaitYouTubeWebSession({ music }, { awaitCancellation() }, { error("No identity") }, timeoutMs = 700))
        assertNotNull(awaitYouTubeWebSession({ music }, { identity }, { "SAPISID=fixture" }))
    }

    @Test fun aDroppedJavascriptReplyCanRecoverDuringTheFirstConnection() = runTest {
        var evaluations = 0
        val session = awaitYouTubeWebSession({ music }, {
            if (++evaluations == 1) awaitCancellation() else identity
        }, { "SAPISID=fixture" })
        assertEquals(2, evaluations)
        assertNotNull(session)
    }

    @Test fun anAnonymousPageDoesNotReuseAnOldCookie() = runTest {
        assertNull(awaitYouTubeWebSession({ music }, { """{"loggedIn":false}""" },
            { error("A signed-out page must never use a previous session") }, timeoutMs = 700))
    }
}
