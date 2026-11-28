package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.example.performance.AppDispatchers
import com.example.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One persistent source list. Legacy single-server settings are read only for migration. */
object PlaybackSourcesPreferences {
    internal const val PREFS_NAME = "xtreme_playback_sources_prefs"
    internal const val LEGACY_CONFIG = "sources_v2"
    internal const val LEGACY_ORDER = "sources_order"
    internal const val OTHER_PREFS = "xtreme_other_settings"
    internal val legacyAddonKeys = setOf("streaming_addon_url", "streaming_addon_name", "streaming_addon_enabled")
    const val ID_ADDON = "addon"
    const val ID_JIOSAAVN = "jiosaavn"
    const val ID_INTERNET_ARCHIVE = "internet_archive"
    const val ID_YOUTUBE = "youtube"
    val DEFAULT_ORDER = listOf(ID_INTERNET_ARCHIVE, ID_JIOSAAVN, ID_YOUTUBE)
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    private val lock = Mutex()
    private val revision = MutableStateFlow(0L)
    private val _addonStorageUnavailable = MutableStateFlow(false)
    internal val addonStorageUnavailable = _addonStorageUnavailable.asStateFlow()
    @Volatile private var saved: SavedAddons? = null

    private class SavedAddons(val app: Context, val addons: List<PlaybackSource>)
    @androidx.annotation.VisibleForTesting
    internal var openCredentials: (Context) -> AddonCredentialStore = AddonCredentialStore::open
        set(value) { field = value; saved = null }

    private fun prefs(context: Context) = ReactiveSettingsRepository.preferences(context, PREFS_NAME)
    internal fun flag(id: String) = "source_enabled_$id"
    internal val portableFlags = PlaybackSource.builtIns.map { flag(it.id) }.toSet()

    fun snapshot(context: Context): List<PlaybackSource> {
        val addons = saved?.takeIf { it.app === context.applicationContext }?.addons.orEmpty()
        val preferences = prefs(context)
        return configuredSources(preferences, addons)
    }

    private fun configuredSources(preferences: SharedPreferences, addons: List<PlaybackSource>): List<PlaybackSource> =
        if (addons.isEmpty() && portableFlags.none(preferences::contains) &&
            !preferences.contains(LEGACY_CONFIG) && !preferences.contains(LEGACY_ORDER)) emptyList()
        else PlaybackSource.ordered(addons + builtins(preferences))

    private fun builtins(preferences: SharedPreferences): List<PlaybackSource> {
        val known = preferences.getString(LEGACY_CONFIG, null)?.let(::legacyBuiltinFlags).orEmpty()
        val legacyOrder = preferences.getString(LEGACY_ORDER, null)?.split(',')?.map(String::trim).orEmpty()
        return PlaybackSource.newInstallDefaults.map { source ->
            source.copy(enabled = preferences.getBoolean(flag(source.id),
                known[flag(source.id)] ?: (source.enabled || source.id in legacyOrder)))
        }
    }

    /** Read only the known provider flags; never copy URLs or add-on identifiers into portable settings. */
    internal fun legacyBuiltinFlags(raw: String): Map<String, Boolean> = runCatching {
        val rows = JSONArray(raw)
        (0 until minOf(rows.length(), 19)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val source = PlaybackSource.builtIns.find { it.kind.name == row.optString("kind") } ?: return@mapNotNull null
            flag(source.id) to (source.id == ID_YOUTUBE || row.optBoolean("enabled"))
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun decodeAddons(raw: String): List<PlaybackSource> {
        val rows = JSONArray(raw)
        return (0 until minOf(rows.length(), 19)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            if (row.optString("kind") != "ADDON") return@mapNotNull null
            val url = row.optString("url").takeIf(String::isNotBlank) ?: return@mapNotNull null
            // Keep existing unsupported addresses editable, with their original identity and enabled flag.
            PlaybackSource(row.optString("id").ifBlank { PlaybackSource.addonId(url) }, PlaybackSourceKind.ADDON,
                row.optString("name").take(100).ifBlank { "Custom server" }, row.optBoolean("enabled"), url)
        }.distinctBy { it.id }.take(16)
    }

    private fun encodeAddons(addons: List<PlaybackSource>) = JSONArray().apply {
        addons.forEach { put(JSONObject().put("kind", "ADDON").put("id", it.id).put("name", it.name)
            .put("enabled", it.enabled).put("url", it.baseUrl)) }
    }.toString()

    private fun readAddons(app: Context, preferences: SharedPreferences): List<PlaybackSource> {
        saved?.takeIf { it.app === app }?.let { return it.addons }
        val former = ReactiveSettingsRepository.preferences(app, OTHER_PREFS)
        val raw = preferences.getString(LEGACY_CONFIG, null)
        val order = preferences.getString(LEGACY_ORDER, null)
        val url = former.getString("streaming_addon_url", null)
        val secure = openCredentials(app)
        val encrypted = secure.read()
        val addons = when {
            encrypted != null -> decodeAddons(encrypted)
            raw != null -> decodeAddons(raw)
            !url.isNullOrBlank() -> listOf(PlaybackSource(PlaybackSource.addonId(url), PlaybackSourceKind.ADDON,
                former.getString("streaming_addon_name", null)?.take(100)?.ifBlank { null } ?: "Custom server",
                preferences.getBoolean(flag(ID_ADDON), former.getBoolean("streaming_addon_enabled", false)), url))
            else -> emptyList()
        }
        // A completed encrypted write wins if the process died before removing the old copies.
        // An empty v2 list can also be authoritative. Keep that decision durable
        // if a crash separates removal of v2 from removal of the old single URL.
        if (encrypted == null && (addons.isNotEmpty() || raw != null && !url.isNullOrBlank())) {
            secure.write(encodeAddons(addons))
        }
        if (raw != null || order != null || preferences.contains(flag(ID_ADDON))) {
            val flags = if (raw != null) legacyBuiltinFlags(raw) else order.orEmpty().split(',').map(String::trim)
                .filter { it in DEFAULT_ORDER }.associate { flag(it) to preferences.getBoolean(flag(it), true) }
            val editor = preferences.edit().remove(LEGACY_CONFIG).remove(LEGACY_ORDER)
            preferences.all.keys.filter { it.startsWith("source_enabled_") && it !in portableFlags }.forEach(editor::remove)
            // Safe flags already restored on this device take precedence over stale migration input.
            flags.filterKeys { !preferences.contains(it) }.forEach(editor::putBoolean)
            check(editor.commit()) { "Could not remove legacy playback settings" }
        }
        if (legacyAddonKeys.any(former::contains)) {
            val editor = former.edit()
            legacyAddonKeys.forEach(editor::remove)
            check(editor.commit()) { "Could not remove legacy add-on settings" }
        }
        saved = SavedAddons(app, addons)
        return addons
    }

    private fun readSources(app: Context): List<PlaybackSource> {
        val preferences = prefs(app)
        val addons = try {
            readAddons(app, preferences).also { _addonStorageUnavailable.value = false }
        } catch (_: Exception) {
            // Keep old data recoverable and off the network until the Keystore can be used.
            saved = null
            _addonStorageUnavailable.value = true
            emptyList()
        }
        return configuredSources(preferences, addons)
    }

    suspend fun load(context: Context): List<PlaybackSource> =
        com.example.performance.BackgroundWorkCoordinator.finishBoundedBatch {
            withContext(AppDispatchers.Io) {
                lock.withLock { readSources(context.applicationContext) }
            }
        }

    private suspend fun update(context: Context, change: (List<PlaybackSource>) -> List<PlaybackSource>) =
        withContext(AppDispatchers.Io) {
            val app = context.applicationContext
            lock.withLock {
                val before = readSources(app).ifEmpty { PlaybackSource.newInstallDefaults }
                val updated = PlaybackSource.ordered(change(before))
                val addons = updated.filter { it.kind == PlaybackSourceKind.ADDON }.take(16)
                if (addons != before.filter { it.kind == PlaybackSourceKind.ADDON }) {
                    check(!_addonStorageUnavailable.value) { "Secure add-on storage is unavailable. Try again after unlocking your device." }
                    openCredentials(app).write(encodeAddons(addons))
                    saved = SavedAddons(app, addons)
                    revision.update { it + 1 }
                }
                val preferences = prefs(app)
                val editor = preferences.edit()
                for (source in updated.filter { it.kind != PlaybackSourceKind.ADDON }) {
                    val key = flag(source.id)
                    if (key in portableFlags) editor.putBoolean(key, source.enabled)
                }
                check(editor.commit()) { "Could not save playback sources" }
            }
        }

    fun save(context: Context, sources: List<PlaybackSource>) = mutate(context) {
        val previous = it.filter { source -> source.kind == PlaybackSourceKind.ADDON }.map { source -> source.baseUrl }.toSet()
        sources.filter { source -> source.kind != PlaybackSourceKind.ADDON || source.baseUrl in previous ||
            com.example.data.remote.AddonHttps.isAllowed(source.baseUrl) }
    }

    private fun mutate(context: Context, change: (List<PlaybackSource>) -> List<PlaybackSource>) {
        scope.launch { try { update(context, change) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _addonStorageUnavailable.value = true }
        }
    }

    fun setSourceEnabled(context: Context, sourceId: String, enabled: Boolean) = mutate(context) { sources ->
        sources.map { if (it.id == sourceId && it.kind != PlaybackSourceKind.YOUTUBE) it.copy(enabled = enabled) else it }
    }
    fun swapSources(context: Context, fromIndex: Int, toIndex: Int) = mutate(context) { sources ->
        sources.toMutableList().apply {
            if (fromIndex in indices && toIndex in indices &&
                this[fromIndex].kind == PlaybackSourceKind.ADDON && this[toIndex].kind == PlaybackSourceKind.ADDON)
                add(toIndex, removeAt(fromIndex))
        }
    }
    fun reorderAddons(context: Context, orderedIds: List<String>) = mutate(context) { sources ->
        val addons = sources.filter { it.kind == PlaybackSourceKind.ADDON }
        val byId = addons.associateBy { it.id }
        val moved = orderedIds.distinct().mapNotNull(byId::get)
        moved + addons.filterNot { it.id in orderedIds } + sources.filterNot { it.kind == PlaybackSourceKind.ADDON }
    }
    suspend fun saveAddon(context: Context, url: String, name: String, previousId: String? = null) = update(context) { sources ->
        val normalized = com.example.data.remote.StreamingAddons.normalize(url)
        val source = PlaybackSource(PlaybackSource.addonId(normalized), PlaybackSourceKind.ADDON, name.take(100), false, normalized)
        if (sources.any { it.id == source.id && it.id != previousId }) sources
        else if (previousId != null) sources.map { if (it.id == previousId) source.copy(enabled = it.enabled) else it }
        else if (sources.count { it.kind == PlaybackSourceKind.ADDON } < 16) sources + source else sources
    }
    fun remove(context: Context, id: String) = mutate(context) { sources -> sources.filterNot { it.id == id && it.kind == PlaybackSourceKind.ADDON } }

    internal fun changes(context: Context): Flow<Unit> = merge(revision.map { Unit }, callbackFlow {
        val preferences = prefs(context.applicationContext)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }).conflate().flowOn(AppDispatchers.Io)

    @Immutable
    data class SourceItem(val id: String, val name: String, val description: String,
        val isLossless: Boolean, val iconType: String, val isEnabled: Boolean,
        val isQualityAllowed: Boolean, val isAlwaysOn: Boolean = false,
        val qualityRequirementText: String? = null, val url: String = "") {
        val isEffectiveActive: Boolean get() = isEnabled && isQualityAllowed
        val isReorderable: Boolean get() = iconType == ID_ADDON
    }
    fun isQualityAllowed(id: String, ceiling: AudioQualityCeiling): Boolean = when (id) {
        ID_YOUTUBE -> true
        ID_JIOSAAVN -> ceiling in setOf(AudioQualityCeiling.HIGH, AudioQualityCeiling.MAX)
        else -> ceiling == AudioQualityCeiling.MAX
    }
    fun getSources(context: Context): List<SourceItem> {
        val ceiling = NetworkQualityMonitor.getInstance(context).activeQualityCeiling.value.effectiveCeiling
        return OtherSettingsPreferences.playbackIntegrations(context).sourceList(ceiling).map { source ->
            val type = when (source.kind) {
                PlaybackSourceKind.YOUTUBE -> ID_YOUTUBE
                PlaybackSourceKind.JIOSAAVN -> ID_JIOSAAVN
                PlaybackSourceKind.INTERNET_ARCHIVE -> ID_INTERNET_ARCHIVE
                PlaybackSourceKind.ADDON -> ID_ADDON
            }
            val secure = source.kind != PlaybackSourceKind.ADDON || com.example.data.remote.AddonHttps.isAllowed(source.baseUrl)
            val allowed = secure && source.available(ceiling)
            SourceItem(source.id, source.name, when (source.kind) {
                PlaybackSourceKind.YOUTUBE -> "Lossy • Full catalogue • Radio"
                PlaybackSourceKind.JIOSAAVN -> "Verified recording matches • AAC up to 320 kbps"
                PlaybackSourceKind.INTERNET_ARCHIVE -> "Verified, authorized lossless recordings"
                PlaybackSourceKind.ADDON -> if (secure) "Custom streaming server"
                    else "HTTPS required. Edit this add-on to update its URL."
            }, source.kind in setOf(PlaybackSourceKind.ADDON, PlaybackSourceKind.INTERNET_ARCHIVE), type,
                source.enabled, allowed, isAlwaysOn = source.kind == PlaybackSourceKind.YOUTUBE,
                qualityRequirementText = if (allowed || !secure) null else
                    if (source.kind == PlaybackSourceKind.JIOSAAVN) "Requires High or Maximum/Lossless"
                    else "Requires Maximum/Lossless", url = source.baseUrl)
        }
    }
    fun getActiveSourceName(context: Context): String = NetworkQualityMonitor.getInstance(context).sourcePolicy.value
        .playbackSources.firstOrNull()?.name ?: "No enabled source"
    fun observeSources(context: Context): Flow<List<SourceItem>> = combine(
        OtherSettingsPreferences.observePlaybackIntegrations(context), NetworkQualityMonitor.getInstance(context).activeQualityCeiling
    ) { _, _ -> getSources(context) }.distinctUntilChanged().flowOn(AppDispatchers.Io)
}
