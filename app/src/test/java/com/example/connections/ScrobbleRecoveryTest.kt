package com.example.connections

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ListeningSnapshot
import com.example.data.model.MusicTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScrobbleRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var state: MutableStateFlow<ConnectionState>
    private var previousStore: Any? = null
    private val instance = ConnectionStore::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val track = MusicTrack("audit-song", "Song", "Artist", "Album", 180_000, "", "")
    private fun snapshot() = ListeningSnapshot("one-play", track, emptyMap(), 1_000, false, playing = true)

    @Before fun installStore() {
        previousStore = instance.get(null)
        val store = ConnectionStore(context)
        ConnectionStore::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(store, true)
        @Suppress("UNCHECKED_CAST")
        state = ConnectionStore::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(store) as MutableStateFlow<ConnectionState>
        state.value = ConnectionState(lastFm = LastFmConnection("key", "secret", "first-session", "listener"))
        instance.set(null, store)
    }

    @After fun restoreStore() { instance.set(null, previousStore) }

    private suspend fun awaitCount(count: AtomicInteger, expected: Int) {
        withTimeout(3_000) { while (count.get() < expected) delay(5) }
    }

    @Test fun failedNowPlayingCanRetryAtALaterListeningCheckpoint() = runBlocking {
        val count = AtomicInteger()
        val scrobbler = LastFmScrobbler(context) { _, _ ->
            if (count.incrementAndGet() == 1) throw IOException("Temporary network loss")
        }
        try {
            scrobbler.accept(snapshot()); awaitCount(count, 1)
            delay(50)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
            scrobbler.accept(snapshot()); awaitCount(count, 2)
        } finally { scrobbler.close() }
    }

    @Test fun reconnectingDuringTheSamePlayUpdatesTheNewSession() = runBlocking {
        val count = AtomicInteger()
        val sessions = java.util.concurrent.CopyOnWriteArrayList<String>()
        val scrobbler = LastFmScrobbler(context) { session, _ -> sessions += session.session; count.incrementAndGet() }
        try {
            scrobbler.accept(snapshot()); awaitCount(count, 1)
            state.value = state.value.copy(lastFm = state.value.lastFm.copy(session = "replacement-session"))
            scrobbler.accept(snapshot()); awaitCount(count, 2)
            assertEquals(listOf("first-session", "replacement-session"), sessions)
        } finally { scrobbler.close() }
    }

    @Test fun networkFailureDoesNotRetryForEveryMeasuredSnapshot() = runBlocking {
        val count = AtomicInteger()
        val scrobbler = LastFmScrobbler(context) { _, _ -> count.incrementAndGet(); throw IOException("Offline") }
        try {
            scrobbler.accept(snapshot()); awaitCount(count, 1)
            delay(50)
            repeat(100) { scrobbler.accept(snapshot()) }
            delay(100)
            assertEquals(1, count.get())
        } finally { scrobbler.close() }
    }

    @Test fun enablingNowPlayingDuringTheSamePlaySendsTheUpdate() = runBlocking {
        val count = AtomicInteger()
        state.value = state.value.copy(lastFm = state.value.lastFm.copy(nowPlaying = false))
        val scrobbler = LastFmScrobbler(context) { _, _ -> count.incrementAndGet() }
        try {
            scrobbler.accept(snapshot())
            delay(50)
            assertEquals(0, count.get())
            state.value = state.value.copy(lastFm = state.value.lastFm.copy(nowPlaying = true))
            scrobbler.accept(snapshot()); awaitCount(count, 1)
        } finally { scrobbler.close() }
    }

    @Test fun outboxReadsTheRecoverableAtomicBackupBeforeCreatingANewLedger() {
        val target = File(context.noBackupFilesDir, "scrobble_outbox.json")
        target.parentFile!!.mkdirs()
        val root = JSONObject().put("pending", JSONArray().put(JSONObject().put("id", "saved-play")))
            .put("sent", JSONArray())
        File(target.path + ".bak").writeText(root.toString())
        val constructor = ScrobbleOutbox::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val outbox = constructor.newInstance(context)
        val restored = ScrobbleOutbox::class.java.getDeclaredMethod("read").apply { isAccessible = true }
            .invoke(outbox) as JSONObject
        assertEquals("saved-play", restored.getJSONArray("pending").getJSONObject(0).getString("id"))
    }
}
