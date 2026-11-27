package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.connections.ConnectionStore
import com.example.downloads.DownloadQuality
import com.example.downloads.OfflineDownloads
import com.example.performance.AppDispatchers
import com.example.playback.AudioEffectsManager
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Portable user preferences only. File grants, cache inventories and active deadlines belong to the device. */
internal object BackupSettings {
    private const val DOWNLOADS = "download_preferences"
    private const val LANGUAGE = "app_language"
    private const val SCROBBLING = "lastfm_preferences"
    // Earlier backups may contain these removed appearance options. Discard them
    // during import; they are neither exported nor restored as active settings.
    private val retiredThemeKeys = setOf("tonal_enabled", "tonal_seed_argb", "tonal_style",
        "tonal_system_colors", "tonal_album_art")

    private val fields = mapOf(
        AudioEffectsPreferences.STORE to mapOf(AudioEffectsPreferences.KEY to "string"),
        "xtreme_other_settings" to buildMap {
            listOf("proxy_enabled", "haptics_enabled", "dynamic_glass_enabled", "player_dynamic_album_glow",
                "player_high_contrast_typography", "player_full_screen_album_art", "playback_crossfade_enabled",
                "playback_spatial_audio", "playback_dolby_atmos", "playback_skip_silence", "playback_bit_perfect", "keep_lyrics_screen_awake",
                "streaming_addon_enabled", "high_performance_enabled", "mini_player_show_heart",
                "mini_player_show_next").forEach { put(it, "boolean") }
            listOf("proxy_host", "proxy_type", "lyrics_provider", "lyrics_translation_language",
                "playback_output_precision", "player_backdrop_effect", "streaming_addon_url", "streaming_addon_name",
                "liquid_glass_profile", "app_font_style").forEach { put(it, "string") }
            listOf("proxy_port", "text_size_index", "ui_size_index", "lyrics_text_size_sp",
                "total_played_count", "total_listening_minutes", "performance_refresh_rate_hz").forEach { put(it, "int") }
        },
        DOWNLOADS to mapOf("wifi_only" to "boolean", "quality" to "string"),
        LANGUAGE to mapOf("language_tag" to "string"),
        SCROBBLING to mapOf("enabled" to "boolean", "now_playing" to "boolean", "primary_artist_only" to "boolean",
            "min_duration_seconds" to "int", "threshold_percent" to "int", "threshold_seconds" to "int"),
        "xtreme_audio_quality_prefs" to mapOf("wifi_quality_ceiling" to "string", "cellular_quality_ceiling" to "string",
            "treat_metered_wifi_as_cellular" to "boolean", "prefer_youtube_opus" to "boolean",
            "enhancer_return_to_youtube" to "boolean", "playback_mode" to "string"),
        "xtreme_playback_sources_prefs" to mapOf("sources_v2" to "string", "sources_order" to "string"),
        "xtreme_smart_cache_preferences" to mapOf("key_max_quota_bytes" to "long", "key_smart_auto_trim" to "boolean",
            "key_auto_precache" to "boolean", "key_wifi_only" to "boolean"),
        "xtreme_recent_searches_prefs" to mapOf("recent_search_queries" to "string"),
        "xtreme_backup_preferences" to mapOf("auto_backup_enabled" to "boolean"),
        "lyrics_offset_preferences" to mapOf("global_lyrics_offset_ms" to "int"),
        "xtreme_theme_prefs" to buildMap {
            put("selected_theme_mode", "string")
            for (prefix in listOf("", "saved_dark_", "saved_light_")) {
                listOf("custom_preset_is_dark", "custom_is_amoled").forEach { put(prefix + it, "boolean") }
                put(prefix + "palette_version", "int")
                listOf("custom_preset_id", "custom_accent_name", "custom_accent_shade", "custom_accent_hex",
                    "custom_secondary_accent_hex", "custom_canvas_name", "custom_canvas_hex", "custom_card_name",
                    "custom_card_hex", "custom_card_border_hex", "custom_text_primary_hex", "custom_text_secondary_hex",
                    "custom_text_muted_hex", "custom_bg_gradient_colors", "custom_card_gradient_colors",
                    "custom_bs_gradient_colors", "custom_bg_gradient", "custom_card_gradient", "custom_bs_gradient",
                    "custom_theme_name").forEach { put(prefix + it, "string") }
            }
        }
    )

    private fun type(store: String, key: String): String? = fields[store]?.get(key) ?: when {
        store == "lyrics_offset_preferences" && key.startsWith("track_lyrics_offset_") -> "int"
        store == "xtreme_playback_sources_prefs" && key.startsWith("source_enabled_") -> "boolean"
        else -> null
    }

    suspend fun read(context: Context, stores: Set<String> = fields.keys): Map<String, Map<String, Any>> = stores.associateWith { store ->
        when (store) {
            DOWNLOADS -> OfflineDownloads.get(context).readPreferences().let {
                mapOf("wifi_only" to it.wifiOnly, "quality" to it.quality.name)
            }
            LANGUAGE -> mapOf("language_tag" to AppLanguage.selection(context))
            SCROBBLING -> ConnectionStore.get(context).savedLastFmPreferences().let {
                mapOf("enabled" to it.enabled, "now_playing" to it.nowPlaying, "primary_artist_only" to it.primaryArtistOnly,
                    "min_duration_seconds" to it.minDurationSeconds, "threshold_percent" to it.thresholdPercent,
                    "threshold_seconds" to it.thresholdSeconds)
            }
            else -> ReactiveSettingsRepository.preferences(context, store).all.entries.mapNotNull { (key, value) ->
                if (value != null && type(store, key) != null) key to value else null
            }.toMap()
        }
    }

    fun encode(settings: Map<String, Map<String, Any>>): JSONObject = JSONObject().apply {
        settings.forEach { (store, values) -> put(store, JSONObject().apply {
            values.forEach { (key, value) -> put(key, JSONObject().put("type", type(store, key)).put("value", value)) }
        }) }
    }

    suspend fun capture(context: Context): Map<String, Map<String, Any>> = withContext(AppDispatchers.Io) {
        AudioEffectsManager.initializePreferences(context)
        read(context) + (AudioEffectsPreferences.STORE to mapOf(AudioEffectsPreferences.KEY to
            AudioEffectsPreferences.encode(AudioEffectsManager.effectsState.value)))
    }

    fun publish(context: Context, settings: Map<String, Map<String, Any>>) {
        val raw = settings[AudioEffectsPreferences.STORE]?.get(AudioEffectsPreferences.KEY) as? String
        if (raw != null) {
            val configuration = AudioEffectsPreferences.decode(raw, com.example.playback.AudioEffectsManager.effectsState.value)
            com.example.playback.AudioEffectsManager.restoreConfiguration(configuration)
            // A durable barrier after the in-memory revision supersedes any queued pre-restore edits.
            AudioEffectsPreferences.write(context, configuration)
        }
        if ("xtreme_backup_preferences" in settings) {
            com.example.performance.MaintenanceScheduler.updateAutoBackup(context)
        }
        if ("xtreme_smart_cache_preferences" in settings) {
            com.example.data.cache.SmartCacheManager.applyRestoredPreferences(context)
        }
        (settings[LANGUAGE]?.get("language_tag") as? String)?.let { AppLanguage.restoreSelection(context, it) }
    }

    /** Validate the entire section, including native preference types, before any restore writes. */
    fun decode(json: JSONObject): Map<String, Map<String, Any>> {
        require(json.length() <= fields.size) { "Invalid settings section" }
        return json.keys().asSequence().associateWith { store ->
            require(store in fields) { "Unknown settings group" }
            val values = json.getJSONObject(store)
            require(values.length() <= 100_000) { "Too many settings" }
            values.keys().asSequence()
                .filterNot { store == "xtreme_theme_prefs" && it in retiredThemeKeys }
                .associateWith { key ->
                    val expected = type(store, key) ?: error("Unknown setting: $key")
                    val entry = values.getJSONObject(key)
                    require(entry.getString("type") == expected) { "Invalid setting type: $key" }
                    val raw = entry.get("value")
                    when (expected) {
                        "boolean" -> { require(raw is Boolean) { "Invalid setting: $key" }; raw }
                        "string" -> {
                            require(raw is String) { "Invalid setting: $key" }
                            if (store == DOWNLOADS) require(DownloadQuality.entries.any { it.name == raw }) { "Invalid download quality" }
                            if (store == LANGUAGE) require(raw.isEmpty() || raw in AppLanguage.tags) { "Invalid app language" }
                            if (store == AudioEffectsPreferences.STORE) AudioEffectsPreferences.decode(raw,
                                com.example.playback.AudioEffectsManager.effectsState.value)
                            raw
                        }
                        "int" -> {
                            require(raw is Int || raw is Long) { "Invalid setting: $key" }
                            val number = (raw as Number).toLong()
                            require(number in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid setting: $key" }
                            number.toInt()
                        }
                        "long" -> { require(raw is Int || raw is Long) { "Invalid setting: $key" }; (raw as Number).toLong() }
                        else -> error("Invalid setting type")
                    }
                }
        }
    }

    /** Called on IO under the restore mutex. Older archives preserve settings they do not contain. */
    suspend fun write(context: Context, settings: Map<String, Map<String, Any>>, replaceMissing: Boolean = true) {
        settings.forEach { (store, values) ->
            when (store) {
                LANGUAGE -> return@forEach // Locale publication can recreate the activity, so wait for commit.
                DOWNLOADS -> {
                    if (values.isNotEmpty()) OfflineDownloads.get(context).updatePreferences { current ->
                        current.copy(wifiOnly = values["wifi_only"] as? Boolean ?: current.wifiOnly,
                            quality = (values["quality"] as? String)?.let(DownloadQuality::valueOf) ?: current.quality)
                    }
                    return@forEach
                }
                SCROBBLING -> {
                    if (values.isNotEmpty()) {
                        val connections = ConnectionStore.get(context)
                        val current = connections.savedLastFmPreferences()
                        fun restoreOptions(value: com.example.connections.LastFmConnection) = value.copy(
                            enabled = values["enabled"] as? Boolean ?: value.enabled,
                            nowPlaying = values["now_playing"] as? Boolean ?: value.nowPlaying,
                            primaryArtistOnly = values["primary_artist_only"] as? Boolean ?: value.primaryArtistOnly,
                            minDurationSeconds = values["min_duration_seconds"] as? Int ?: value.minDurationSeconds,
                            thresholdPercent = values["threshold_percent"] as? Int ?: value.thresholdPercent,
                            thresholdSeconds = values["threshold_seconds"] as? Int ?: value.thresholdSeconds)
                        if (restoreOptions(current) != current) connections.change { it.copy(lastFm = restoreOptions(it.lastFm)) }
                    }
                    return@forEach
                }
            }
            val preferences = ReactiveSettingsRepository.preferences(context, store)
            val editor = preferences.edit()
            if (replaceMissing) preferences.all.keys.filter { type(store, it) != null }.forEach(editor::remove)
            values.forEach { (key, value) -> put(editor, key, value) }
            check(editor.commit()) { "Could not save restored settings" }
        }
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            else -> error("Invalid preference value")
        }
    }
}
