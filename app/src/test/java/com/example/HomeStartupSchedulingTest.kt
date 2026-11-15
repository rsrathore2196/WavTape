package com.example

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.*
import com.example.data.model.*
import com.example.data.remote.YouTubeHomeParser
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.repository.MusicRepository
import com.example.performance.BackgroundWorkCoordinator
import com.example.playback.PlaybackManager
import com.example.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HomeStartupSchedulingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun ready() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        BackgroundWorkCoordinator.resetForTesting()
    }
    @After fun stop() { BackgroundWorkCoordinator.foreground(false); Dispatchers.resetMain() }

    private fun shelf(id: String) = ServerHomeShelf(id, "Trending", tracks = listOf(
        MusicTrack("yt_abcdefghijk", id, "Artist", "Album", 1000, "", "")))

    @Test fun accountReadinessPreventsAnAnonymousFlightAndGenerationRestart() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val identity = MutableStateFlow("anonymous")
        val calls = AtomicInteger()
        val loaded = CompletableDeferred<Boolean>()
        val player = PlaybackManager(context)
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
                MusicRepository(context), player, context,
                homeLoader = { _, _, authenticated ->
                    calls.incrementAndGet(); loaded.complete(authenticated)
                    YouTubeHomeParser.ParsedHomeFeed(listOf(shelf("native")), null, accountIdentity = identity.value)
                }, profileLoader = { _, _, _ -> YouTubeMusicApiService.ProfileShelfResult(null) },
                accountIdentity = { identity.value }, accountUpdates = identity,
                awaitAccountIdentity = { ready.await() }, prepareCoreTabs = {},
                connectivityUpdates = MutableStateFlow(true)) as T
        }
        try {
            val home = ViewModelProvider(store, factory)[HomeViewModel::class.java]
            home.setHomeVisible(true)
            delay(100)
            assertEquals(0, calls.get())
            identity.value = "restored-account"
            ready.complete(Unit)
            assertTrue(withTimeout(5_000) { loaded.await() })
            delay(100)
            assertEquals(1, calls.get())
        } finally { store.clear(); player.release() }
    }

    @Test fun aSupersededCheckpointIsDiscardedBeforeItIsEncoded() = runBlocking {
        BackgroundWorkCoordinator.firstFrameRendered()
        BackgroundWorkCoordinator.foreground(true)
        val preparation = BackgroundWorkCoordinator.coreTabsPreparation()
        val oldReads = AtomicInteger()
        val oldReadStack = java.util.concurrent.atomic.AtomicReference<String>()
        val oldShelves = object : AbstractList<ServerHomeShelf>() {
            override val size = 1
            override fun get(index: Int): ServerHomeShelf { oldReads.incrementAndGet(); oldReadStack.set(Throwable().stackTraceToString()); return shelf("old") }
        }
        val key = "US|anonymous"
        val cache = HomeCardCache(context)
        try {
            cache.saveHome(SavedHomeCards(key, 1, serverShelves = oldShelves))
            cache.saveHome(SavedHomeCards(key, 2, serverShelves = listOf(shelf("latest"))))
            assertEquals(oldReadStack.get(), 0, oldReads.get())
            assertEquals("latest", withTimeout(5_000) { cache.readServer(key) }!!.serverShelves.single().id)
            assertEquals(oldReadStack.get(), 0, oldReads.get())
        } finally { preparation.close(); BackgroundWorkCoordinator.foreground(false) }
    }

    @Test fun cooperativeProjectionPreservesExistingDuplicateAndStableIdentityRules() = runBlocking {
        val initial = listOf(shelf("first"), shelf("duplicate"))
        val changed = listOf(shelf("new-id"), shelf("another-copy"))
        val index = HomeShelfIdentity.Index()
        assertEquals(HomeShelfIdentity.deduplicate(initial), index.deduplicate(initial))
        repeat(3) { assertEquals(HomeShelfIdentity.refresh(initial, changed), index.refresh(initial, changed)) }
    }
}
