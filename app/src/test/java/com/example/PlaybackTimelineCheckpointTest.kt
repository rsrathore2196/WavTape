package com.example

import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.test.core.app.ApplicationProvider
import com.example.playback.ARTIST_RADIO_EXTRA
import com.example.service.PlaybackSessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackTimelineCheckpointTest {
    private class QueueTimeline(val items: List<MediaItem>, val beforeRead: () -> Unit = {}) : Timeline() {
        val reads = AtomicInteger()
        val readOnMain = AtomicBoolean()
        override fun getWindowCount() = items.size
        override fun getWindow(index: Int, window: Window, projectionUs: Long): Window {
            if (Looper.myLooper() == Looper.getMainLooper()) readOnMain.set(true)
            reads.incrementAndGet()
            beforeRead()
            return window.apply { mediaItem = items[index] }
        }
        override fun getPeriodCount() = items.size
        override fun getPeriod(index: Int, period: Period, setIds: Boolean): Period =
            period.set(if (setIds) index else null, if (setIds) index else null, index, 180_000_000, 0)
        override fun getIndexOfPeriod(uid: Any) = (uid as? Int)?.takeIf { it in items.indices } ?: C.INDEX_UNSET
        override fun getUidOfPeriod(index: Int): Any = index
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun item(id: String, uri: String = "https://audio.example/$id.m4a") = MediaItem.Builder()
        .setMediaId(id).setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(id).setArtist("Artist").build()).build()
    private suspend fun awaitPosition(position: Long): PlaybackSessionStore.Snapshot = withTimeout(5_000) {
        var result: PlaybackSessionStore.Snapshot?
        do {
            result = PlaybackSessionStore.restore(context)
            if (result?.positionMs != position) delay(10)
        } while (result?.positionMs != position)
        requireNotNull(result)
    }

    @Test fun largeTimelinePreparationRunsOffMainAndPositionOnlyCheckpointsReuseIt() = runBlocking {
        val timeline = QueueTimeline(List(10_000) { item("large_$it") })
        val prepared = requireNotNull(PlaybackSessionStore.validatedTimelineItems(timeline))
        assertFalse(timeline.readOnMain.get())
        assertEquals(10_000, timeline.reads.get())
        PlaybackSessionStore.checkpoint(context, timeline, 42, 111_000)
        val first = awaitPosition(111_000)
        PlaybackSessionStore.checkpoint(context, timeline, 43, 112_000)
        val second = awaitPosition(112_000)
        assertSame(prepared, first.items)
        assertSame(first.items, second.items)
        assertEquals(43, second.index)
        assertEquals("large_43", second.items[second.index].mediaId)
        assertEquals("Position updates must not reread the queue", 10_000, timeline.reads.get())
        assertFalse(timeline.readOnMain.get())
    }

    @Test fun aNewTimelineInvalidatesTheCacheEvenForTheSameRecordingId() = runBlocking {
        val original = QueueTimeline(listOf(item("selected")))
        val upgraded = QueueTimeline(listOf(item("selected", "https://archive.org/download/verified/selected.flac")))
        val before = requireNotNull(PlaybackSessionStore.validatedTimelineItems(original))
        val after = requireNotNull(PlaybackSessionStore.validatedTimelineItems(upgraded))
        assertNotSame(before, after)
        assertEquals(before.single().mediaId, after.single().mediaId)
        assertTrue(after.single().localConfiguration!!.uri.toString().endsWith(".flac"))
        assertFalse(upgraded.readOnMain.get())
    }

    @Test fun anOlderSlowCheckpointCannotOverwriteANewerQueueAndPosition() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = QueueTimeline(listOf(item("old"))) {
            entered.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
        }
        try {
            PlaybackSessionStore.checkpoint(context, old, 0, 121_000)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val current = QueueTimeline(listOf(item("new_first"), item("new_selected")))
            PlaybackSessionStore.checkpoint(context, current, 1, 122_000)
            release.countDown()
            val saved = awaitPosition(122_000)
            assertEquals(1, saved.index)
            assertEquals(listOf("new_first", "new_selected"), saved.items.map { it.mediaId })
            assertFalse(old.readOnMain.get())
            assertFalse(current.readOnMain.get())
        } finally { release.countDown() }
    }

    @Test fun unsupportedOrDemoUrisAreStillRejected() = runBlocking {
        assertNull(PlaybackSessionStore.validatedTimelineItems(QueueTimeline(listOf(item("fake", "https://soundhelix.com/demo.mp3")))))
        assertNull(PlaybackSessionStore.validatedTimelineItems(QueueTimeline(listOf(item("bad", "unknown://song")))))
    }

    @Test fun artistRadioScopeSurvivesDiskRestoration() = runBlocking {
        val original = item("artist-song")
        val scoped = original.buildUpon().setMediaMetadata(original.mediaMetadata.buildUpon()
            .setExtras(Bundle().apply { putString(ARTIST_RADIO_EXTRA, "Selected Artist") }).build()).build()
        val regular = item("regular-song")
        PlaybackSessionStore.checkpoint(context, QueueTimeline(listOf(scoped, regular)), 0, 130_000)
        val saved = File(context.filesDir, "real_playback_session.json")
        withTimeout(5_000L) {
            while (runCatching { "\"artistRadio\":\"Selected Artist\"" !in saved.readText() }.getOrDefault(true)) delay(10)
        }
        // Clear the in-process snapshot so restore must decode the session file.
        PlaybackSessionStore::class.java.getDeclaredField("latest").apply { isAccessible = true }
            .set(PlaybackSessionStore, null)
        val restored = requireNotNull(PlaybackSessionStore.restore(context))
        assertEquals("Selected Artist", restored.items[0].mediaMetadata.extras?.getString(ARTIST_RADIO_EXTRA))
        assertNull(restored.items[1].mediaMetadata.extras?.getString(ARTIST_RADIO_EXTRA))
    }

    @Test fun queueEncodingTracksArtistRadioChangesForTheSameMediaItem() {
        val seed = item("same-seed")
        val ordinary = seed.buildUpon().setMediaMetadata(seed.mediaMetadata.buildUpon()
            .setExtras(Bundle()).build()).build()
        val station = seed.buildUpon().setMediaMetadata(seed.mediaMetadata.buildUpon()
            .setExtras(Bundle().apply { putString(ARTIST_RADIO_EXTRA, "Selected Artist") }).build()).build()
        // The position writer must not equate the two queues just because Media3 does.
        assertEquals(ordinary, station)
        fun storedEntry(item: MediaItem) = JSONArray(PlaybackSessionStore.encodedQueue(listOf(item))
            .toString(Charsets.UTF_8)).getJSONObject(0)
        assertFalse(storedEntry(ordinary).has("artistRadio"))
        assertEquals("Selected Artist", storedEntry(station).getString("artistRadio"))
        assertFalse(storedEntry(ordinary).has("artistRadio"))
    }
}
