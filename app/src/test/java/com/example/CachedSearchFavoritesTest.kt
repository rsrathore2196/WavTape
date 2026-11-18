package com.example

import android.app.Application
import android.util.LruCache
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.MusicDao
import com.example.data.local.MusicDatabase
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.data.repository.MusicRepository
import com.example.data.repository.SearchResultCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CachedSearchFavoritesTest {
    private val song = MusicTrack("yt_favorite", "Song", "Artist", "Album", 180_000, "cover", "audio")

    @Suppress("UNCHECKED_CAST")
    private fun cache(repository: MusicRepository) = MusicRepository::class.java.getDeclaredField("searchCache")
        .apply { isAccessible = true }.get(repository) as LruCache<String, Pair<Long, SearchResultCategory>>

    private suspend fun close(repository: MusicRepository) {
        val scope = MusicRepository::class.java.getDeclaredField("initialCatalogScope").apply { isAccessible = true }
            .get(repository) as CoroutineScope
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    private fun category(track: MusicTrack) = SearchResultCategory(topResult = track,
        exactMatches = listOf(track), recommendedTracks = listOf(track), jioMatches = listOf(track),
        ytMatches = listOf(track), artistSongs = listOf(track), similarTypeSongs = listOf(track), songs = listOf(track))

    private fun assertFavorite(result: SearchResultCategory, expected: Boolean) {
        assertEquals(expected, result.topResult!!.isLiked)
        for (rows in listOf(result.exactMatches, result.recommendedTracks, result.jioMatches, result.ytMatches,
            result.artistSongs, result.similarTypeSongs, result.songs)) assertEquals(expected, rows.single().isLiked)
    }

    @Test fun likingAndUnlikingElsewhereUpdatesEveryCachedSearchRoleWithoutRefetchingCatalogMetadata() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext())
        val repository = MusicRepository(db.musicDao())
        val cached = category(song)
        cache(repository).put("song", System.currentTimeMillis() to cached)
        try {
            assertFavorite(repository.search("Song"), false)
            assertTrue(repository.toggleFavorite(song))
            assertFavorite(repository.search(" Song "), true)
            assertFalse(repository.toggleFavorite(song))
            assertFavorite(repository.search("SONG"), false)
            assertSame("Only favorite presentation changes; catalog metadata stays cached", cached, cache(repository).get("song").second)
            assertFalse(cached.songs.single().isLiked)
        } finally { close(repository); db.close() }
    }

    @Test fun cachedFavoriteFlagsAreReconciledWithTheDatabaseUsingOnlyTheIdProjection() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext())
        val dao = object : MusicDao by db.musicDao() {
            override fun getFavoriteTracks() = error("Search needs only favorite IDs, not full library metadata")
        }
        val repository = MusicRepository(dao)
        val cached = category(song.copy(isLiked = true))
        cache(repository).put("song", System.currentTimeMillis() to cached)
        try {
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song).copy(isLiked = false))
            assertFavorite(repository.search("Song"), false)
            db.musicDao().updateFavorite(song.id, true)
            assertFavorite(repository.search("Song"), true)
        } finally { close(repository); db.close() }
    }

    @Test fun unchangedFavoriteFlagsReuseTheCachedPreparedResult() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext())
        val repository = MusicRepository(db.musicDao())
        val cached = category(song.copy(isLiked = true))
        cache(repository).put("song", System.currentTimeMillis() to cached)
        try {
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song).copy(isLiked = true))
            assertSame(cached, repository.search("Song"))
        } finally { close(repository); db.close() }
    }
}
