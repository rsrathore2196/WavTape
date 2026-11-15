package com.example

import android.app.Application
import com.example.data.remote.YouTubeHomeParser
import com.example.performance.*
import com.example.recommendation.DiscoveryRequestCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CooperativeHomeProcessingTest {
    @Before fun headless() { BackgroundWorkCoordinator.resetForTesting() }

    @Test fun batchedRendererWalkPreservesMixedItemsOrderingAndContinuations() = runBlocking {
        val root = JSONObject(NativeFeedFixtures.home(
            NativeFeedFixtures.shelf("Quick picks", *(1..100).toList().toIntArray()),
            NativeFeedFixtures.collectionShelf("Albums", NativeFeedFixtures.album(101)),
            NativeFeedFixtures.collectionShelf("Playlists", NativeFeedFixtures.playlist(102)),
            continuation = "next-page"))
        val expected = YouTubeHomeParser.parseHomeFeed(root, countryCode = "IN")
        assertEquals(expected, YouTubeHomeParser.parseHomeFeedCooperatively(root, countryCode = "IN"))
    }

    @Test fun aLongRendererWalkIsCancellableAtItsProcessingBoundaries() = runBlocking {
        val root = JSONObject(NativeFeedFixtures.home(
            NativeFeedFixtures.shelf("Quick picks", *(1..1000).toList().toIntArray())))
        val work = launch(start = CoroutineStart.UNDISPATCHED) {
            YouTubeHomeParser.parseHomeFeedCooperatively(root)
            fail("A cancelled feed must not publish a complete result")
        }
        work.cancelAndJoin()
    }

    @Test fun raisingThePlayerPromotesItsExistingMetadataFlightDuringInteraction() = runBlocking {
        BackgroundWorkCoordinator.firstFrameRendered()
        BackgroundWorkCoordinator.foreground(true)
        BackgroundWorkCoordinator.touch(true)
        val demand = MutableStateFlow(false)
        val calls = AtomicInteger()
        val request = async {
            DiscoveryRequestCoordinator.shared("demand-promotion", false, network = false,
                playbackMetadata = true, essentialUpdates = demand) {
                withContext(AppDispatchers.CpuBounded) { calls.incrementAndGet() }
                emptyList()
            }
        }
        try {
            delay(100)
            assertEquals(0, calls.get())
            demand.value = true
            withTimeout(3_000) { request.await() }
            assertEquals(1, calls.get())
        } finally {
            request.cancelAndJoin()
            BackgroundWorkCoordinator.touch(false)
            BackgroundWorkCoordinator.foreground(false)
        }
    }

    @Test fun nestedTransportAdmissionDoesNotAcquireAnotherNetworkSlot() = runBlocking {
        val ready = List(3) { CompletableDeferred<Unit>() }
        val release = CompletableDeferred<Unit>()
        val calls = ready.map { entered -> async {
            DiscoveryRequestCoordinator.network(essential = true) {
                DiscoveryRequestCoordinator.network(essential = true) { entered.complete(Unit); release.await() }
            }
        } }
        try { withTimeout(3_000) { ready.forEach { it.await() } } }
        finally { release.complete(Unit); calls.forEach { it.cancelAndJoin() } }
    }

    @Test fun anExistingClientDeadlineIsPreservedWhileDefaultDiscoveryRemainsBounded() = kotlinx.coroutines.test.runTest {
        assertEquals("client response", DiscoveryRequestCoordinator.network(essential = true, timeoutMillis = null) {
            delay(13_000L); "client response"
        })
        try {
            DiscoveryRequestCoordinator.network(essential = true) { delay(13_000L); "late" }
            fail("The default discovery timeout must remain in force")
        } catch (_: java.io.IOException) { }
    }
}
