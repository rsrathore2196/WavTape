package com.example.data.repository

import com.example.performance.AppDispatchers

import android.content.Context
import com.example.data.local.AudioQualityPreferences
import com.example.data.local.MusicDao
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.data.remote.AudioRoutingService
import com.example.data.remote.InternetArchiveApiService
import com.example.data.remote.ArchiveFileEvidence
import com.example.data.remote.ArchiveFileKey
import com.example.data.remote.MusicDataSource
import com.example.data.local.MusicDatabase
import com.example.data.local.UserProfilePreferences
import com.example.recommendation.HomeRecommendationPolicy
import com.example.performance.BackgroundWorkCoordinator
import com.example.recommendation.DiscoveryRequestCoordinator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.remote.catalogRequest
import com.example.playback.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import com.example.data.local.UserProfile
import com.example.data.model.CountryData
import com.example.recommendation.AlbumArtDeduplicator
import com.example.recommendation.AlbumArtDeduplicator.distinctAlbumAndCover
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.util.Log
import com.example.playback.PlaybackMode
import com.example.playback.PlaybackSource
import com.example.playback.PlaybackSourceKind
import com.example.playback.PlaybackSourcePolicy
import com.example.playback.PlaybackStart
import com.example.playback.SourceTransitionOrigin
import com.example.playback.fastStartUpgradeSources
import com.example.playback.canLookUpFastStartQuality
import com.example.playback.startupSourcesFor

data class SearchResultCategory(
    val topResult: MusicTrack? = null,
    val exactMatches: List<MusicTrack> = emptyList(),
    val recommendedTracks: List<MusicTrack> = emptyList(),
    val jioMatches: List<MusicTrack> = emptyList(),
    val ytMatches: List<MusicTrack> = emptyList(),
    val artistSongs: List<MusicTrack> = emptyList(),
    val matchedArtistName: String = "",
    val similarTypeSongs: List<MusicTrack> = emptyList(),
    val matchedGenreOrType: String = "",
    val songs: List<MusicTrack> = emptyList(),
    val albums: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val catalogAlbums: List<CatalogAlbum> = emptyList(),
    val catalogPlaylists: List<CatalogPlaylist> = emptyList()
)

/** Cached catalog metadata never makes a saved favorite flag authoritative. */
private fun SearchResultCategory.withFavoriteIds(ids: Set<String>): SearchResultCategory {
    fun track(row: MusicTrack) = if (row.isLiked == (row.id in ids)) row else row.copy(isLiked = row.id in ids)
    fun tracks(rows: List<MusicTrack>) = if (rows.all { it.isLiked == (it.id in ids) }) rows else rows.map(::track)
    val top = topResult?.let(::track)
    val exact = tracks(exactMatches)
    val recommended = tracks(recommendedTracks)
    val jio = tracks(jioMatches)
    val youtube = tracks(ytMatches)
    val artist = tracks(artistSongs)
    val similar = tracks(similarTypeSongs)
    val all = tracks(songs)
    return if (top === topResult && exact === exactMatches && recommended === recommendedTracks &&
        jio === jioMatches && youtube === ytMatches && artist === artistSongs && similar === similarTypeSongs && all === songs) this
    else copy(topResult = top, exactMatches = exact, recommendedTracks = recommended, jioMatches = jio,
        ytMatches = youtube, artistSongs = artist, similarTypeSongs = similar, songs = all)
}

@OptIn(ExperimentalCoroutinesApi::class)
class MusicRepository private constructor(
    private val daoProvider: () -> MusicDao,
    private val context: Context?,
    private val losslessPageSearch: (suspend (String, Int) -> InternetArchiveApiService.LosslessSearchPage)? = null
) {
    constructor(musicDao: MusicDao, context: Context? = null) : this({ musicDao }, context)
    constructor(context: Context) : this({ MusicDatabase.getDatabase(context.applicationContext).musicDao() }, context.applicationContext)
    internal constructor(musicDao: MusicDao, context: Context,
        losslessPageSearch: suspend (String, Int) -> InternetArchiveApiService.LosslessSearchPage
    ) : this({ musicDao }, context, losslessPageSearch)
    // Constructing the UI's repository never opens Room. Cold flows acquire it on IO.
    private val musicDao: MusicDao by lazy(daoProvider)
    private val initialCatalogMutex = Mutex()
    private val initialCatalogScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + AppDispatchers.Io)
    private class InitialCatalogFlight(val result: kotlinx.coroutines.Deferred<List<MusicTrack>>,
        val urgent: java.util.concurrent.atomic.AtomicBoolean)
    private var initialCatalogFlight: InitialCatalogFlight? = null
    private data class PreparedCatalog(val tracks: List<MusicTrack>, val liked: Set<String>, val result: List<MusicTrack>)
    private var preparedCatalog: PreparedCatalog? = null
    // App-owned snapshots outlive an Activity/ViewModel. Warm recreation can
    // compose real local rows immediately; the next Room emission remains authoritative.
    @Volatile var retainedFavoriteTracks: List<MusicTrack> = emptyList()
        private set
    @Volatile var retainedRecentlyPlayed: List<MusicTrack> = emptyList()
        private set
    @Volatile var retainedPlaylists: List<PlaylistEntity> = emptyList()
        private set

    class TrackAvailabilityTracker {
        private val _unavailable = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
        val unavailable: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> = _unavailable
        fun markUnavailable(track: MusicTrack) {
            val now = android.os.SystemClock.elapsedRealtime()
            _unavailable.value = _unavailable.value + (track.id to (now + 600_000L))
        }
    }
    val recommendations = TrackAvailabilityTracker()

    val catalogBrowser = CatalogRepository(context, likedTracksSource = { getFavoriteTracks() })
    // Home's owners and workers share this cap, including discovery ranking.
    internal val homeDispatcher = BackgroundWorkCoordinator.optionalCpuDispatcher
    internal suspend fun cacheRecommendationTracks(tracks: List<MusicTrack>) = withContext(homeDispatcher) {
        val rows = tracks.filter { it.id.startsWith("yt_") && it.title.isNotBlank() }
            .distinctBy { it.id }.map { TrackEntity.fromMusicTrack(it).copy(isCached = true) }
        if (rows.isNotEmpty()) withContext(AppDispatchers.Database) { musicDao.mergeTracksMetadata(rows) }
    }

    // LRU / in-memory cache to avoid duplicate network calls and prevent rate limiting
    private val searchCache = android.util.LruCache<String, Pair<Long, SearchResultCategory>>(80)
    private val losslessMisses = android.util.LruCache<String, Long>(128)
    private val rejectedLosslessUrls = android.util.LruCache<String, Long>(64)
    private val streamResolver = StreamResolver(lookupScope = initialCatalogScope, archiveAllowed = { policy ->
        context?.let { com.example.playback.NetworkQualityMonitor.getInstance(it).sourcePolicySnapshot().key == policy.key } ?: true
    }, resolveAddon = { source, track, policy, rejected ->
        context?.let { com.example.data.remote.StreamingAddons.resolve(it, track, policy, rejected, source) }
    })
    internal suspend fun searchArchive(query: String, page: Int,
        policy: PlaybackSourcePolicy, recordings: List<MusicTrack>): InternetArchiveApiService.LosslessSearchPage =
        withContext(AppDispatchers.Io) {
            fun failed(reason: com.example.playback.LosslessDiagnostics.Reason) =
                InternetArchiveApiService.LosslessSearchPage(isSuccessful = false, failure = reason, exhaustive = false)
            val app = context ?: return@withContext failed(com.example.playback.LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED)
            val monitor = com.example.playback.NetworkQualityMonitor.getInstance(app)
            fun allowed() = com.example.data.remote.archiveCatalogPolicyAllows(policy, monitor.sourcePolicySnapshot())
            if (!allowed()) {
                com.example.data.remote.ArchivePlaybackEvents.record("search", "policy_blocked")
                return@withContext failed(com.example.playback.LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED)
            }
            var result = InternetArchiveApiService.LosslessSearchPage(isSuccessful = false, exhaustive = false)
            val complete = kotlinx.coroutines.withTimeoutOrNull(40_000L) {
                result = InternetArchiveApiService.searchLosslessPage(query, page, pageSize = 8, allowed = ::allowed)
                if (page == 1 && result.tracks.isEmpty() && com.example.data.remote.ArchiveFileKey.itemLink(query) == null) {
                    val anchor = recordings.take(2).firstOrNull {
                        CatalogNames.score(query, it.title, listOf(it.artist, it.album)) >= 55
                    }
                    if (anchor != null && allowed()) {
                        val match = kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                            InternetArchiveApiService.findLosslessRecording(anchor, allowed = ::allowed).tracks.firstOrNull()
                        }
                        if (match != null) result = result.copy(tracks = listOf(match), failure = null)
                    }
                    val next = result.nextPage
                    if (result.tracks.isEmpty() && next != null && allowed())
                        result = InternetArchiveApiService.searchLosslessPage(query, next, pageSize = 8, allowed = ::allowed)
                }
                true
            }
            if (!allowed()) return@withContext failed(com.example.playback.LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED)
            if (complete == null) result.copy(isSuccessful = false, exhaustive = false,
                failure = com.example.playback.LosslessDiagnostics.Reason.NETWORK_TIMEOUT) else result
        }
    private val chartClient by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build() }

    // Static memory cache for enriched catalog to persist across fast navigations
    companion object {
        @Volatile private var memoryCachedCatalog: List<MusicTrack>? = null
    }

    private suspend fun getLikedTrackIds(): Set<String> = withContext(homeDispatcher) {
        observeFavoriteTrackIds().first()
    }

    suspend fun getInitialCatalog(profile: UserProfile? = null,
        priority: BackgroundWorkCoordinator.StartupPriority = BackgroundWorkCoordinator.StartupPriority.CRITICAL): List<MusicTrack> {
        // Home and Player join one repository-owned load; disposing one UI owner
        // cannot cancel the database work still needed by another caller.
        val flight = initialCatalogMutex.withLock {
            initialCatalogFlight?.takeUnless { it.result.isCompleted }?.also {
                if (priority == BackgroundWorkCoordinator.StartupPriority.CRITICAL) it.urgent.set(true)
            } ?: run {
                val urgent = java.util.concurrent.atomic.AtomicBoolean(priority == BackgroundWorkCoordinator.StartupPriority.CRITICAL)
                InitialCatalogFlight(initialCatalogScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    // The shared flight owns the admission. A caller must not hold
                    // an optional slot while a detached child does unadmitted CPU work.
                    BackgroundWorkCoordinator.startup(priority, urgent = { urgent.get() },
                        label = "Initial catalog preparation") { prepareInitialCatalog() }
                }, urgent).also { initialCatalogFlight = it }
            }
        }
        try {
            flight.result.start()
            return flight.result.await()
        } finally {
            if (flight.result.isCompleted) initialCatalogMutex.withLock {
                if (initialCatalogFlight === flight) initialCatalogFlight = null
            }
        }
    }

    private suspend fun prepareInitialCatalog(): List<MusicTrack> = withContext(homeDispatcher) {
        val liked = getLikedTrackIds()
        val cached = memoryCachedCatalog.orEmpty().ifEmpty {
            withContext(AppDispatchers.Database) { musicDao.getCachedTracksSync(250) }.map { it.toMusicTrack() }
                .also { if (it.isNotEmpty() && memoryCachedCatalog.isNullOrEmpty()) memoryCachedCatalog = it }
        }.ifEmpty {
            MusicDataSource.curatedTracks
        }
        preparedCatalog?.takeIf { it.tracks == cached && it.liked == liked }?.let { return@withContext it.result }
        // Discard legacy fabricated YouTube defaults when reading old recommendation caches.
        val corrected = cached.map { track ->
            if (track.id.startsWith("yt_") && track.genre == "Pop" && track.language == "Hindi")
                track.copy(genre = "Unknown", language = "Unknown") else track
        }
        HomeRecommendationPolicy.originals(corrected).map { it.copy(isLiked = it.id in liked) }
            .also { preparedCatalog = PreparedCatalog(cached, liked, it) }
    }

    private suspend fun homeProfile(profile: UserProfile? = null): UserProfile = profile ?: withContext(AppDispatchers.Io) {
        context?.let { UserProfilePreferences.getUserProfile(it) } ?: UserProfile()
    }

    private fun browseLanguage(): String = com.example.data.local.AppLanguage.catalogTag

    /** Direct YouTube Music server radio recommendation. Preserves server recommendation ordering. */
    suspend fun fetchYouTubeRadio(
        seed: MusicTrack,
        excludedIds: Set<String> = emptySet(),
        limit: Int = 10,
        countryCode: String? = null
    ): List<MusicTrack> = withContext(AppDispatchers.Io) {
        val resolvedVideoId = com.example.data.remote.YouTubeMusicApiService.resolveSeedVideoId(seed) ?: return@withContext emptyList()
        val profile = homeProfile()
        val region = countryCode ?: profile.countryCode
        com.example.data.remote.YouTubeMusicApiService.getRadioRecommendations(
            videoId = resolvedVideoId,
            limit = limit,
            countryCode = region,
            excludedTrackIds = excludedIds + seed.id
        )
    }

    suspend fun syncOnlineCatalog(profile: UserProfile? = null): List<MusicTrack> = getInitialCatalog(profile)

    internal suspend fun searchArtistRadioSongs(name: String, continuation: String?) =
        com.example.data.remote.YouTubeMusicApiService.searchSongsPage(name, continuation, useInnerTubeX = true,
            countryCode = homeProfile().countryCode, transportTimeoutMs = 4_000L)

    suspend fun getPunjabiHits(limit: Int = 15): List<MusicTrack> = getInitialCatalog().take(limit)
    suspend fun getEraHits(limit: Int = 15, @Suppress("UNUSED_PARAMETER") forceRefresh: Boolean = false,
        @Suppress("UNUSED_PARAMETER") deferRoomMergeUntilIdle: Boolean = false): List<MusicTrack> = getInitialCatalog().take(limit)
    suspend fun getYtmQuickPicks(limit: Int = 15, @Suppress("UNUSED_PARAMETER") forceRefresh: Boolean = false,
        @Suppress("UNUSED_PARAMETER") deferRoomMergeUntilIdle: Boolean = false): List<MusicTrack> = getInitialCatalog().take(limit)
    suspend fun getCountryAndLanguageHits(profile: UserProfile?, limit: Int = 15, @Suppress("UNUSED_PARAMETER") forceRefresh: Boolean = false): List<MusicTrack> =
        getInitialCatalog(profile).take(limit)

    suspend fun recommendationRadio(seed: MusicTrack, alreadyQueued: List<MusicTrack>, limit: Int,
        @Suppress("UNUSED_PARAMETER") taste: Any? = null): List<MusicTrack> =
        fetchYouTubeRadio(seed, alreadyQueued.map { it.id }.toSet(), limit)

    suspend fun cachedRadioCandidates(seed: MusicTrack, queued: List<MusicTrack>, @Suppress("UNUSED_PARAMETER") tracks: List<MusicTrack>,
        @Suppress("UNUSED_PARAMETER") taste: Any? = null, limit: Int = 10): List<MusicTrack> =
        fetchYouTubeRadio(seed, queued.map { it.id }.toSet(), limit)

    fun observeFavoriteTrackIds(): Flow<Set<String>> = flow { emitAll(musicDao.observeFavoriteTrackIds()) }
        .flowOn(AppDispatchers.Database).map { it.toSet() }.distinctUntilChanged().flowOn(homeDispatcher)

    // Replay authoritative snapshots; multiple screens share both Room reads and
    // entity conversion. No initial empty value is mistaken for a loaded library.
    private val libraryRetry = MutableStateFlow(0L)
    private val _libraryFailures = MutableStateFlow<Map<String, String>>(emptyMap())
    val libraryFailures = _libraryFailures.asStateFlow()

    init {
        initialCatalogScope.launch {
            BackgroundWorkCoordinator.uiForeground.collect { if (it) retryLocalLibrary() }
        }
    }

    /** Retry only failed observations. Healthy, shared Room collectors are never restarted. */
    fun retryLocalLibrary() {
        if (_libraryFailures.value.isNotEmpty()) libraryRetry.update { it + 1L }
    }

    private fun <T> sharedLibrarySnapshots(name: String, rows: Flow<T>, retain: (T) -> Unit) =
        libraryRetry.map { revision -> if (revision == 0L || name in _libraryFailures.value) revision else -1L }
            .let { triggers -> kotlinx.coroutines.flow.flow {
                var accepted = 0L
                triggers.collect { revision ->
                    if (revision >= 0L) accepted = revision
                    emit(accepted)
                }
            } }.distinctUntilChanged().flatMapLatest {
            rows.distinctUntilChanged().onEach { value ->
                retain(value)
                _libraryFailures.update { failures -> failures - name }
            }.retryWhen { error, attempt ->
                if (error is CancellationException) throw error
                if (attempt >= 2) false else { kotlinx.coroutines.delay(250L * (attempt + 1)); true }
            }.catch { error ->
                if (error is CancellationException) throw error
                _libraryFailures.update { failures -> failures + (name to "Local library unavailable. Please retry.") }
            }
        }.shareIn(initialCatalogScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0), replay = 1)

    private val favoriteSnapshots by lazy {
        sharedTrackSnapshots("favorites", flow { emitAll(musicDao.getFavoriteTracks()) }) { retainedFavoriteTracks = it }
    }
    private val recentSnapshots by lazy {
        sharedTrackSnapshots("recents", flow { emitAll(musicDao.getRecentlyPlayedTracks()) }) { retainedRecentlyPlayed = it }
    }
    private fun sharedTrackSnapshots(name: String, rows: Flow<List<TrackEntity>>, retain: (List<MusicTrack>) -> Unit): Flow<List<MusicTrack>> {
        // Keep only the current snapshot across WhileSubscribed restarts. Room still
        // checks for edits on reentry, but unchanged rows need no JSON/track conversion.
        // The shared upstream is the sole owner of this CPU-side memoization.
        var previousRows = emptyList<TrackEntity>()
        var previousTracks = emptyList<MusicTrack>()
        val prepared = rows.flowOn(AppDispatchers.Database).distinctUntilChanged().map { list ->
            BackgroundWorkCoordinator.localDataPreparation(awaitFrameQuiet = false) {
                if (list != previousRows) {
                    val slice = com.example.performance.ProcessingSlice()
                    val previous = previousRows.indices.associate { index ->
                        slice.checkpoint()
                        previousRows[index].id to (previousRows[index] to previousTracks[index])
                    }
                    val tracks = list.map { row ->
                        slice.checkpoint()
                        previous[row.id]?.takeIf { it.first == row }?.second ?: row.toMusicTrack()
                    }
                    previousRows = list
                    previousTracks = tracks
                }
                previousTracks
            }
        }.distinctUntilChanged().flowOn(AppDispatchers.CpuBounded + com.example.performance.WorkClassification(
            com.example.performance.WorkClass.VISIBLE_CONTENT))
        return sharedLibrarySnapshots(name, prepared, retain)
    }

    fun getFavoriteTracks(): Flow<List<MusicTrack>> = favoriteSnapshots
    fun observeFavoriteCount(): Flow<Int> = flow { emitAll(musicDao.observeFavoriteCount()) }
        .distinctUntilChanged().flowOn(AppDispatchers.Database)
    /** Optional ranking must not wait indefinitely for unavailable local storage. */
    suspend fun favoriteSnapshotOrRetained(): List<MusicTrack> =
        if ("favorites" in libraryFailures.value) retainedFavoriteTracks
        else kotlinx.coroutines.withTimeoutOrNull(1_500L) { getFavoriteTracks().first() } ?: retainedFavoriteTracks

    fun getRecentlyPlayedTracks(): Flow<List<MusicTrack>> = recentSnapshots

    private val playlistSnapshots by lazy {
        sharedLibrarySnapshots("playlists", flow { emitAll(musicDao.getAllPlaylists()) }
            .flowOn(AppDispatchers.Database)) { retainedPlaylists = it }
    }
    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistSnapshots

    fun getPlaylistWithTracks(playlistId: Long): Flow<PlaylistWithTracks?> {
        return flow { emitAll(musicDao.getPlaylistWithTracks(playlistId)) }
            .flowOn(AppDispatchers.Database).distinctUntilChanged()
            .map { content -> content?.also { it.musicTracks } }.flowOn(AppDispatchers.CpuBounded)
    }

    suspend fun toggleFavorite(track: MusicTrack): Boolean =
        musicDao.toggleTrackFavorite(TrackEntity.fromMusicTrack(track))

    suspend fun markTrackPlayed(track: MusicTrack) {
        musicDao.recordTrackPlayed(TrackEntity.fromMusicTrack(track), System.currentTimeMillis())
    }

    suspend fun createPlaylist(title: String, description: String, coverUrl: String = ""): Long {
        val defaultCover = coverUrl.ifEmpty {
            "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80"
        }
        val entity = PlaylistEntity(
            title = title,
            description = description,
            coverUrl = defaultCover
        )
        return musicDao.insertPlaylist(entity)
    }

    suspend fun createPlaylistWithTracks(title: String, description: String, coverUrl: String = "", tracks: List<MusicTrack>): Long {
        val rows = withContext(AppDispatchers.CpuBounded) { tracks.map { TrackEntity.fromMusicTrack(it) } }
        return withContext(AppDispatchers.Database) {
            musicDao.createPlaylistWithEntries(PlaylistEntity(title = title, description = description, coverUrl = coverUrl), rows)
        }
    }

    suspend fun deletePlaylist(playlistId: Long) {
        musicDao.deletePlaylist(playlistId)
    }

    suspend fun renamePlaylist(playlistId: Long, newTitle: String) {
        musicDao.updatePlaylistTitle(playlistId, newTitle.trim())
    }

    suspend fun addTrackToPlaylist(playlistId: Long, track: MusicTrack) {
        musicDao.appendPlaylistTrack(playlistId, TrackEntity.fromMusicTrack(track))
    }

    suspend fun removePlaylistEntry(playlistId: Long, entryId: Long) = musicDao.removePlaylistEntry(playlistId, entryId)

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String) {
        musicDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    /** User search returns matching catalog entities; discovery remains in its own flows. */
    suspend fun search(query: String, context: android.content.Context? = null): SearchResultCategory = withContext(AppDispatchers.CpuBounded) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext SearchResultCategory()
        val cacheKey = trimmed.lowercase(java.util.Locale.ROOT)
        suspend fun favorites(result: SearchResultCategory): SearchResultCategory {
            val ids = try {
                withContext(AppDispatchers.Database) { musicDao.observeFavoriteTrackIds().first() }.toSet()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptySet<String>() }
            return result.withFavoriteIds(ids)
        }
        searchCache.get(cacheKey)?.takeIf { System.currentTimeMillis() - it.first in 0 until 300_000L }
            ?.let { return@withContext favorites(it.second) }
        val tracks = AudioRoutingService.resolveSearchTracks(context ?: this@MusicRepository.context,
            trimmed, limit = 25).tracks
        val exact = tracks.filter { CatalogNames.score(trimmed,
            StreamResolver.extractCleanTitle(it.title, it.artist)) >= 55 }.ifEmpty { tracks.take(10) }
        val result = SearchResultCategory(topResult = tracks.firstOrNull(), exactMatches = exact,
            jioMatches = tracks.filter { it.id.startsWith("online_") },
            ytMatches = tracks.filter { it.id.startsWith("yt_") }, songs = tracks,
            albums = tracks.map { it.album }.filter { CatalogNames.normalize(it) !in
                setOf("", "unknown", "single", "online stream", "extended stream", "youtube music") }.distinct(),
            artists = tracks.map { it.artist }.filter { CatalogNames.normalize(it) !in
                setOf("", "unknown", "unknown artist", "various artists", "online artist", "youtube artist") }.distinct())
        searchCache.put(cacheKey, System.currentTimeMillis() to result)
        favorites(result)
    }

    /** Startup policy is separate from retries, downloads and explicit source changes. */
    internal suspend fun resolvePlaybackStart(track: MusicTrack, preferredSourceId: String? = null,
        jioDeadlineMs: Long = 0L, onAddonLookupFinished: (suspend (Long) -> Unit)? = null): PlaybackStart =
        withContext(AppDispatchers.Io) {
            val mode = context?.let(com.example.data.local.AudioQualityPreferences::playbackMode) ?: PlaybackMode.BEST_QUALITY
            val online = context?.let { com.example.playback.NetworkQualityMonitor.getInstance(it).snapshot() }
                ?.connectionType != com.example.playback.NetworkConnectionType.OFFLINE
            var attemptedArchive = false
            val explicitArchive = ArchiveFileKey.explicitSelection(track)
            if (mode == PlaybackMode.FAST_START && online && preferredSourceId == null && explicitArchive != null) {
                val policy = context?.let {
                    com.example.data.local.OtherSettingsPreferences.loadPlaybackSources(it)
                    com.example.playback.NetworkQualityMonitor.getInstance(it).sourcePolicySnapshot()
                }
                val archive = policy?.sourceId(PlaybackSourceKind.INTERNET_ARCHIVE)
                    ?.takeIf { policy.allowsArchive && !policy.returnsToYouTube(track.id) }
                if (archive != null) {
                    attemptedArchive = true
                    try {
                        val exact = resolvePlayableTrack(track, preferredSourceId = archive, retainLosslessLookup = false)
                        return@withContext PlaybackStart(exact, mode)
                    } catch (e: TrackUnavailableException) {
                        if (ArchiveFileEvidence.isLiteralSelection(track)) throw e
                    }
                }
            }
            if (mode == PlaybackMode.FAST_START && online && preferredSourceId == null &&
                !ArchiveFileEvidence.isLiteralSelection(track)) {
                try {
                    val initial = resolvePlayableTrack(track, preferredSourceId = PlaybackSource.youtube.id, retainLosslessLookup = false)
                    return@withContext PlaybackStart(initial, mode)
                } catch (_: TrackUnavailableException) {
                    // Failure of a single provider does not make the recording unavailable.
                }
            }
            PlaybackStart(resolveBestQualityTrack(track, preferredSourceId, jioDeadlineMs, onAddonLookupFinished,
                skipExactArchive = attemptedArchive), mode)
        }

    private suspend fun resolveBestQualityTrack(track: MusicTrack, preferredSourceId: String? = null,
        jioDeadlineMs: Long = 0L, onAddonLookupFinished: (suspend (Long) -> Unit)? = null,
        audibleFallback: MusicTrack? = null, skipExactArchive: Boolean = false,
        rejectedArchiveUrls: Set<String> = emptySet()): MusicTrack =
        resolvePlayableTrack(track, preferredSourceId = preferredSourceId, jioDeadlineMs = jioDeadlineMs,
            onAddonLookupFinished = onAddonLookupFinished, retainLosslessLookup = false, bestQualityFirst = true,
            audibleFallback = audibleFallback, skipExactArchive = skipExactArchive, rejectedUrls = rejectedArchiveUrls)

    internal suspend fun resolveFastStartUpgrade(
        track: MusicTrack, policy: PlaybackSourcePolicy, manual: Boolean = false,
        stillCurrent: () -> Boolean = { true }, prepare: suspend (MusicTrack) -> Boolean = { true }
    ): MusicTrack? = kotlinx.coroutines.withTimeoutOrNull(60_000L) {
        suspend fun prepareCandidate(candidate: MusicTrack, source: PlaybackSource): Boolean {
            if (!stillCurrent() || candidate.id != track.id || candidate.audioUrl == track.audioUrl ||
                !policy.acceptsRemoteTrack(candidate)) return false
            // Leave the original stream playing while the chosen transport warms at the live position.
            val preparationBudget = when (source.kind) {
                PlaybackSourceKind.ADDON, PlaybackSourceKind.INTERNET_ARCHIVE -> 24_000L
                else -> 10_000L
            }
            return kotlinx.coroutines.withTimeoutOrNull(preparationBudget) { prepare(candidate) } == true
        }
        if (!manual) {
            if (!policy.canLookUpFastStartQuality(track.id) || !stillCurrent()) return@withTimeoutOrNull null
            val activeUri = android.net.Uri.parse(track.audioUrl)
            val currentSource = SourceTransitionOrigin.sourceId(track.id, activeUri, policy)
            val higherPriority = policy.startupSourcesFor(track.id).takeWhile { it.id != currentSource }
            if (higherPriority.isEmpty()) return@withTimeoutOrNull null
            // The startup transport's Opus pin must not filter out cached lossless candidates.
            val lookupTrack = if (com.example.data.remote.YouTubeAudioStreamService.isPlaybackUri(track.audioUrl) &&
                activeUri.getQueryParameter("codec") == "opus") {
                val query = activeUri.encodedQuery.orEmpty().split('&').filterNot { it == "codec=opus" }.joinToString("&")
                track.copy(audioUrl = activeUri.buildUpon().encodedQuery(query.ifEmpty { null }).build().toString())
            } else track
            // The audible stream is already a working fallback. Do not extract it again
            // while Best Quality verifies the providers that outrank it.
            val candidate = try { resolveBestQualityTrack(lookupTrack, audibleFallback = track) }
                catch (_: TrackUnavailableException) { return@withTimeoutOrNull null }
            val candidateSource = SourceTransitionOrigin.sourceId(candidate.id, android.net.Uri.parse(candidate.audioUrl), policy)
            // Keep an already playing source when Best Quality lands on that provider or a lower fallback.
            val candidatesInOrder = higherPriority.dropWhile { it.id != candidateSource }
            val replacement = candidatesInOrder.firstOrNull() ?: return@withTimeoutOrNull null
            if (prepareCandidate(candidate, replacement) && stillCurrent()) return@withTimeoutOrNull candidate
            if (replacement.kind == PlaybackSourceKind.INTERNET_ARCHIVE && stillCurrent()) {
                val alternate = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                    try {
                        resolveBestQualityTrack(lookupTrack, preferredSourceId = replacement.id,
                            rejectedArchiveUrls = setOf(candidate.audioUrl))
                    } catch (_: TrackUnavailableException) { null }
                }
                if (alternate != null && alternate.audioUrl != candidate.audioUrl &&
                    SourceTransitionOrigin.sourceId(alternate.id, android.net.Uri.parse(alternate.audioUrl), policy) == replacement.id &&
                    prepareCandidate(alternate, replacement) && stillCurrent()) return@withTimeoutOrNull alternate
            }

            // A provider may resolve a valid URL that cannot resume at the live playhead.
            // Keep playing while trying the remaining providers ranked above the active one.
            for (source in candidatesInOrder.drop(1)) {
                if (!stillCurrent()) return@withTimeoutOrNull null
                val fallback = try { resolveBestQualityTrack(lookupTrack, preferredSourceId = source.id) }
                    catch (_: TrackUnavailableException) { continue }
                if (SourceTransitionOrigin.sourceId(fallback.id, android.net.Uri.parse(fallback.audioUrl), policy) == source.id &&
                    prepareCandidate(fallback, source) && stillCurrent()) return@withTimeoutOrNull fallback
            }
            return@withTimeoutOrNull null
        }

        // Explicit Upgrade Audio retains its existing provider-by-provider preparation.
        for (source in policy.fastStartUpgradeSources(track)) {
            if (!stillCurrent()) return@withTimeoutOrNull null
            val candidate = kotlinx.coroutines.withTimeoutOrNull(16_000L) {
                try {
                    resolvePlayableTrack(track, preferredSourceId = source.id, manualUpgrade = true,
                        retainLosslessLookup = false)
                } catch (_: TrackUnavailableException) { null }
            } ?: continue
            if (SourceTransitionOrigin.sourceId(candidate.id, android.net.Uri.parse(candidate.audioUrl), policy) == source.id &&
                prepareCandidate(candidate, source)) return@withTimeoutOrNull candidate
        }
        null
    }

    /** Apply the active quality tier consistently to every playback entry point. */
    suspend fun resolvePlayableTrack(
        track: MusicTrack,
        forceRefresh: Boolean = false,
        failedUrl: String? = null,
        preferYouTube: Boolean = false,
        jioDeadlineMs: Long = 0L,
        currentSourceOnly: Boolean = false,
        rejectedUrls: Set<String> = emptySet(),
        onAddonLookupFinished: (suspend (Long) -> Unit)? = null,
        preferredSourceId: String? = null,
        manualUpgrade: Boolean = false,
        retainLosslessLookup: Boolean = true,
        bestQualityFirst: Boolean = false,
        audibleFallback: MusicTrack? = null,
        skipExactArchive: Boolean = false
    ): MusicTrack = withContext(AppDispatchers.Io) {
        if (android.net.Uri.parse(track.audioUrl).scheme == com.example.downloads.OfflineDownloads.SCHEME) return@withContext track
        context?.let { ctx ->
            try { com.example.downloads.OfflineDownloads.get(ctx).localTrack(track) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // An unavailable download index must not prevent an unrelated file
                // or normal source resolution. Explicit offline URIs stay offline above.
                Log.w("MusicRepository", "Saved download lookup unavailable", error)
                null
            }
        }?.let { return@withContext it }
        repeat(3) {
            context?.let { com.example.data.local.OtherSettingsPreferences.loadPlaybackSources(it) }
            val monitor = context?.let { com.example.playback.NetworkQualityMonitor.getInstance(it) }
            val sourcePolicy = monitor?.sourcePolicySnapshot()
            var cachedFallback = audibleFallback?.takeIf { candidate ->
                sourcePolicy != null && candidate.id == track.id && candidate.audioUrl.isNotBlank() &&
                    sourcePolicy.acceptsRemoteTrack(candidate)
            }
            val exactArchive = sourcePolicy?.let { policy -> policy.online && policy.allowsArchive &&
                !policy.returnsToYouTube(track.id) && ArchiveFileKey.explicitSelection(track) != null &&
                (preferredSourceId == null || preferredSourceId == policy.sourceId(PlaybackSourceKind.INTERNET_ARCHIVE)) } == true
            if (!exactArchive && !forceRefresh && !manualUpgrade && failedUrl == null && rejectedUrls.isEmpty() && sourcePolicy != null) {
                context?.let { ctx ->
                    try {
                        com.example.service.StreamedAudioRepresentations.lookup(ctx, track, sourcePolicy,
                            preferredSourceId = preferredSourceId, currentSourceOnly = currentSourceOnly,
                            bestQualityFirst = bestQualityFirst)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // A broken/unavailable optional cache must not hide the normal
                        // resolver. Offline resolution still fails locally on a cache miss.
                        android.util.Log.w("MusicRepository", "Stream cache lookup unavailable", error)
                        null
                    }
                }?.let { cached ->
                    if (monitor.sourcePolicySnapshot().key != sourcePolicy.key) return@repeat
                    val first = sourcePolicy.startupSourcesFor(track.id).firstOrNull()?.id
                    if (!bestQualityFirst || !sourcePolicy.online || preferredSourceId != null ||
                        android.net.Uri.parse(cached.audioUrl).getQueryParameter("provider") == first) return@withContext cached
                    cachedFallback = cached
                }
            }
            val losslessVersion = sourcePolicy?.takeIf { it.allowsArchive }?.let { streamResolver.losslessDiscoveryVersion(track.id) }
            val quality = sourcePolicy?.ceiling?.audioQuality ?: AudioQuality.ULTRA_HD_320
            try {
                val identity = withContext(AppDispatchers.CpuBounded) { RecordingIdentity.key(track) }
                val requestKey = "playback-source:${track.id}:$identity:${sourcePolicy?.key}:$forceRefresh:$currentSourceOnly:$preferYouTube:$preferredSourceId:$manualUpgrade:$retainLosslessLookup:$bestQualityFirst:$losslessVersion:$skipExactArchive:${cachedFallback?.audioUrl}:${failedUrl.orEmpty()}:${rejectedUrls.sorted().joinToString()}"
                val result = com.example.recommendation.DiscoveryRequestCoordinator.shared(requestKey, essential = true, network = false) {
                    com.example.recommendation.DiscoveryRequestCoordinator.playback {
                        streamResolver.resolve(track, quality, forceRefresh, failedUrl, preferYouTube = preferYouTube, jioDeadlineMs = jioDeadlineMs,
                            currentSourceOnly = currentSourceOnly, rejectedUrls = rejectedUrls,
                            maxBitrateBps = sourcePolicy?.ceiling?.maxBitrateBps ?: Int.MAX_VALUE, sourcePolicy = sourcePolicy,
                            preferredSourceId = preferredSourceId, manualUpgrade = manualUpgrade, retainLosslessLookup = retainLosslessLookup,
                            losslessVersion = losslessVersion, bestQualityFirst = bestQualityFirst, cachedFallback = cachedFallback,
                            skipExactArchive = skipExactArchive)
                    }.let(::listOf)
                }.firstOrNull() ?: throw TrackUnavailableException(track.title)
                currentCoroutineContext().ensureActive()
                if (sourcePolicy != null && monitor?.sourcePolicySnapshot()?.key != sourcePolicy.key) return@repeat
                if (sourcePolicy != null && !sourcePolicy.acceptsRemoteTrack(result)) {
                    com.example.playback.PlaybackDiagnostics.record(track.id, sourcePolicy, null,
                        com.example.playback.PlaybackDiagnostics.Stage.VALIDATION, com.example.playback.PlaybackDiagnostics.Reason.POLICY)
                    throw TrackUnavailableException(track.title, com.example.playback.PlaybackDiagnostics.unavailable(track.id, track.title, sourcePolicy))
                }
                // Sharing transports must never roll back newer cover/playlist metadata.
                val selected = track.copy(audioUrl = result.audioUrl, source = result.source, bitrateKbps = result.bitrateKbps,
                    qualityBadge = result.qualityBadge, isLossless = result.isLossless,
                    primaryArtists = track.primaryArtists.ifEmpty { result.primaryArtists })
                sourcePolicy?.let { policy ->
                    // Cache bookkeeping must never prevent a verified stream from playing.
                    try {
                        com.example.service.StreamedAudioRepresentations.remember(selected, policy)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        android.util.Log.w("MusicRepository", "Stream cache bookkeeping unavailable", error)
                    }
                }
                return@withContext selected
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (sourcePolicy != null && monitor?.sourcePolicySnapshot()?.key != sourcePolicy.key) return@repeat
                throw cancelled
            } catch (e: TrackUnavailableException) {
                if (sourcePolicy != null && monitor?.sourcePolicySnapshot()?.key != sourcePolicy.key) return@repeat
                cachedFallback?.let { return@withContext it }
                if (track.recommendationOnly && !currentSourceOnly && preferredSourceId == null && !e.temporary) recommendations.markUnavailable(track)
                throw e
            }
        }
        throw TrackUnavailableException(track.title, "Streaming settings changed during resolution. Please try again.")
    }

    /** Downloads pin their own quality without changing the active stream's policy. */
    internal suspend fun resolveDownloadTrack(track: MusicTrack, quality: com.example.downloads.DownloadQuality): MusicTrack = withContext(AppDispatchers.Io) {
        val lossless = quality == com.example.downloads.DownloadQuality.LOSSLESS
        val cap = if (lossless) Int.MAX_VALUE else quality.kbps * 1000
        val ceiling = if (lossless) com.example.playback.AudioQualityCeiling.MAX else com.example.playback.AudioQualityCeiling.HIGH
        val policy = context?.let { com.example.playback.NetworkQualityMonitor.getInstance(it).sourcePolicySnapshot() }
            ?.copy(ceiling = ceiling, online = true, returnToYouTube = false, requestTrackId = track.id)
            ?: com.example.playback.PlaybackSourcePolicy(ceiling)
        streamResolver.resolve(track, if (lossless) AudioQuality.HI_RES_LOSSLESS else AudioQuality.ULTRA_HD_320,
            forceRefresh = true, maxBitrateBps = cap, sourcePolicy = policy, retainLosslessLookup = false)
    }

    suspend fun resolveYouTubeFallback(
        track: MusicTrack,
        quality: AudioQuality = AudioQuality.ULTRA_HD_320
    ): MusicTrack? = withContext(AppDispatchers.Io) {
        if (context != null) try { resolvePlayableTrack(track, forceRefresh = true, failedUrl = track.audioUrl, preferYouTube = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        else streamResolver.resolveYouTubeFallback(track, quality)
    }

    suspend fun cachedExploreCategories(profile: UserProfile? = null): List<ExploreCategory> = withContext(AppDispatchers.Io) {
        val preferences = homeProfile(profile)
        catalogBrowser.cachedExploreCategories(preferences.countryCode.ifBlank { "US" }, browseLanguage())
    }

    suspend fun prepareStartupTabs() {
        try { kotlinx.coroutines.withTimeoutOrNull(3_000L) { cachedExploreCategories() } }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
        try { kotlinx.coroutines.withTimeoutOrNull(3_000L) { getAllPlaylists().first() } }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
    }

    /** Public category browsing stays metadata-only and uses the active profile's region. */
    suspend fun getExploreCategories(profile: UserProfile? = null): List<ExploreCategory> = withContext(AppDispatchers.Io) {
        val preferences = homeProfile(profile)
        catalogBrowser.exploreCategories(preferences.countryCode.ifBlank { "US" },
            browseLanguage())
    }

    suspend fun getExploreCategoryResults(category: ExploreCategory, profile: UserProfile? = null,
        forceRefresh: Boolean = false): SearchResultCategory = withContext(AppDispatchers.Io) {
        val preferences = homeProfile(profile)
        val page = catalogBrowser.exploreCategory(category, preferences.countryCode.ifBlank { "US" },
            browseLanguage(), forceRefresh = forceRefresh, fillMissingShelves = true)
        // Provider identity, browse parameters and continuation belong to each collection.
        SearchResultCategory(matchedGenreOrType = category.title, songs = page.tracks,
            catalogAlbums = page.albums, catalogPlaylists = page.playlists)
    }

    internal suspend fun hasRecentLosslessAttempt(track: MusicTrack) = streamResolver.hasRecentLosslessAttempt(track)
    internal fun losslessDiscoveryVersion(trackId: String): Long = streamResolver.losslessDiscoveryVersion(trackId)
    internal fun cancelLosslessDiscovery(trackId: String, expectedVersion: Long? = null) =
        streamResolver.cancelLosslessDiscovery(trackId, expectedVersion)
    internal fun cancelObsoleteLosslessDiscovery(trackId: String, policyKey: String) =
        streamResolver.cancelObsoleteLosslessDiscovery(trackId, policyKey)

    suspend fun findLosslessUpgrade(track: MusicTrack, automatic: Boolean = false): MusicTrack? = withContext(AppDispatchers.Io) {
        val monitor = context?.let(com.example.playback.NetworkQualityMonitor::getInstance)
        context?.let { com.example.data.local.OtherSettingsPreferences.loadPlaybackSources(it) }
        val policy = monitor?.sourcePolicySnapshot()
        if (policy?.allowsArchive != true) {
            val reason = when {
                policy == null || !policy.online -> com.example.playback.LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED
                !policy.maximum -> com.example.playback.LosslessDiagnostics.Reason.QUALITY_INELIGIBLE
                else -> com.example.playback.LosslessDiagnostics.Reason.SOURCE_DISABLED
            }
            com.example.playback.LosslessDiagnostics.record(track.id, reason, "discovery-eligibility",
                "quality=${policy?.ceiling}, online=${policy?.online}")
            return@withContext null
        }
        val generation = streamResolver.losslessDiscoveryVersion(track.id)
        fun allowed() = streamResolver.isLosslessDiscoveryCurrent(track.id, generation) &&
            monitor?.sourcePolicySnapshot()?.let { it.allowsArchive && it.key == policy.key } == true
        val key = withContext(AppDispatchers.CpuBounded) { RecordingIdentity.key(track) }
        if (track.id.startsWith("ia_")) {
            val originalUrl = InternetArchiveApiService.originalFileUrl(track)
            if (originalUrl != null) InternetArchiveApiService.verifyDirectRecording(track.copy(audioUrl = originalUrl)) {
                allowed()
            }?.takeUnless { StreamResolver.isLosslessUrlRejected(it.audioUrl) ||
                (rejectedLosslessUrls.get(it.audioUrl) ?: 0L) > android.os.SystemClock.elapsedRealtime() }
                ?.let { return@withContext it }
        }
        if (ArchiveFileEvidence.isLiteralSelection(track)) return@withContext null
        fun missedRecently() = losslessMisses.get(key)?.let { android.os.SystemClock.elapsedRealtime() < it } == true
        fun pending() = streamResolver.hasPendingLosslessDiscovery(track.id, policy.key)
        if (missedRecently() && !pending()) return@withContext null
        if (losslessPageSearch == null) {
            val cached = StreamResolver.getCachedLosslessMatch(track)
            if (cached != null && (rejectedLosslessUrls.get(cached.audioUrl) ?: 0L) <= android.os.SystemClock.elapsedRealtime() &&
                !StreamResolver.isLosslessUrlRejected(cached.audioUrl)) {
                return@withContext cached.takeIf { allowed() }
            }
        }
        // Reuse the process-wide reference-counted request sharing. Cancelling one
        // consumer cannot cancel a lookup another consumer still needs.
        val found = com.example.recommendation.DiscoveryRequestCoordinator.shared("archive-recording:$key:${policy.key}:$generation",
            essential = !automatic, network = false, playbackMetadata = true) {
            run lookup@{
                if (missedRecently() && !pending() || !allowed()) return@lookup emptyList()
                val rejected = (rejectedLosslessUrls.snapshot().filterValues {
                    it > android.os.SystemClock.elapsedRealtime()
                }.keys + StreamResolver.getActiveRejectedUrls()).toSet()
                val matches = if (losslessPageSearch == null) {
                    val match = streamResolver.discoverLosslessBackground(track, rejected, ::allowed, policy.key, requestedVersion = generation)
                    if (match == null) {
                        val reason = com.example.playback.LosslessDiagnostics.last(track.id)?.reason
                        val ttl = if (reason == com.example.playback.LosslessDiagnostics.Reason.NO_RESULTS) 90_000L else 10_000L
                        losslessMisses.put(key, android.os.SystemClock.elapsedRealtime() + ttl)
                        emptyList()
                    } else {
                        listOf(match)
                    }
                } else {
                    val artist = RecordingIdentity.artistNames(track).firstOrNull()
                        ?: StreamResolver.extractCleanArtist(track.artist)
                    val title = StreamResolver.extractCleanTitle(track.title, track.artist)
                    val tracks = mutableListOf<MusicTrack>()
                    var successful = true
                    var failure: com.example.playback.LosslessDiagnostics.Reason? = null
                    var exhaustive = true
                    for ((index, query) in listOf("$title $artist".trim(), artist)
                        .filter(String::isNotBlank).distinct().withIndex()) {
                        if (!allowed()) break
                        val page = losslessPageSearch.invoke(query, if (index == 0) 4 else 6)
                        successful = successful && page.isSuccessful
                        failure = page.failure ?: failure
                        exhaustive = exhaustive && page.exhaustive && page.nextPage == null
                        tracks.addAll(page.tracks)
                        if (page.tracks.any { StreamResolver.matchesLosslessUpgrade(track, it) }) break
                    }
                    val result = InternetArchiveApiService.LosslessSearchPage(tracks, isSuccessful = successful,
                        failure = failure, exhaustive = exhaustive)
                    if (!allowed()) {
                        com.example.playback.LosslessDiagnostics.record(track.id,
                            com.example.playback.LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED, "discovery-policy")
                        return@lookup emptyList()
                    }
                    val candidates = withContext(AppDispatchers.CpuBounded) {
                        result.tracks.mapNotNull { candidate ->
                            if (candidate.audioUrl == track.audioUrl ||
                                candidate.audioUrl in rejected ||
                                (rejectedLosslessUrls.get(candidate.audioUrl) ?: 0L) > android.os.SystemClock.elapsedRealtime() ||
                                StreamResolver.isLosslessUrlRejected(candidate.audioUrl) ||
                                !StreamResolver.matchesLosslessUpgrade(track, candidate)) {
                                if (candidate.audioUrl != track.audioUrl && !StreamResolver.matchesLosslessUpgrade(track, candidate)) {
                                    com.example.playback.LosslessDiagnostics.record(track.id,
                                        com.example.playback.LosslessDiagnostics.Reason.SOURCE_VERIFICATION_FAILED, "candidate-verification")
                                }
                                null
                            } else RecordingIdentity.losslessConfidence(track, candidate)?.let { candidate to it }
                        }.sortedByDescending { it.second }.map { it.first }
                    }
                    if (candidates.isEmpty()) {
                        val reason = when {
                            result.failure != null -> result.failure
                            result.tracks.isNotEmpty() -> com.example.playback.LosslessDiagnostics.Reason.NO_MATCH
                            else -> com.example.playback.LosslessDiagnostics.Reason.NO_RESULTS
                        }
                        com.example.playback.LosslessDiagnostics.record(track.id, reason, "discovery")
                        // Never persist timeouts/partial searches as confirmed absence.
                        val ttl = if (result.isSuccessful && result.exhaustive && result.nextPage == null &&
                            reason == com.example.playback.LosslessDiagnostics.Reason.NO_RESULTS) 90_000L else 10_000L
                        losslessMisses.put(key, android.os.SystemClock.elapsedRealtime() + ttl)
                    }
                    candidates
                }
                matches
            }
        }.firstOrNull()
        if (!allowed()) return@withContext null
        if (found != null && losslessPageSearch == null) {
            StreamResolver.cacheVerifiedLosslessMatch(track, found)
        }
        found
    }

    internal suspend fun rejectLosslessUpgrade(track: MusicTrack, candidateUrl: String? = null) = withContext(AppDispatchers.CpuBounded) {
        val reason = com.example.playback.LosslessDiagnostics.last(track.id)?.reason
        if (reason in setOf(com.example.playback.LosslessDiagnostics.Reason.SEEK_UNAVAILABLE,
                com.example.playback.LosslessDiagnostics.Reason.BUFFER_COVERAGE)) {
            // Optional warmup failed to prove a seamless switch, not recording
            // validity. Keep the verified file usable for an initial start/retry.
            losslessMisses.put(RecordingIdentity.key(track), android.os.SystemClock.elapsedRealtime() + 5_000L)
            return@withContext
        }
        // Reject the failed representation, not every authorized copy of this recording.
        val ttl = com.example.playback.LosslessDiagnostics.rejectionTtlMs(
            reason)
        if (candidateUrl != null) {
            rejectedLosslessUrls.put(candidateUrl, android.os.SystemClock.elapsedRealtime() + ttl)
            StreamResolver.rejectLosslessUrl(candidateUrl, ttl)
        }
        StreamResolver.invalidateLosslessMatch(track, candidateUrl)
        losslessMisses.put(RecordingIdentity.key(track), android.os.SystemClock.elapsedRealtime() + 5_000L)
        Unit
    }

    fun clearSearchCache() {
        searchCache.evictAll()
        streamResolver.clear()
    }

    fun getSearchCacheEntryCount(): Int {
        return searchCache.size() + streamResolver.cacheSize
    }
}
