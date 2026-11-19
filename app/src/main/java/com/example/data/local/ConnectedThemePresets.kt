package com.example.data.local

import com.example.ui.theme.TonalThemeColors

/** One catalog of ten identities; brightness is a variant of the selected identity. */
object ThemePresets {
    const val PALETTE_VERSION = 8
    val allPresets: List<AppThemePreset> by lazy { MaterialThemePresets.all(true) }
    fun default(dark: Boolean): AppThemePreset = MaterialThemePresets.default(dark)
    fun forMode(preset: AppThemePreset, dark: Boolean): AppThemePreset =
        MaterialThemePresets.findById(preset.id, dark) ?: default(dark)
    fun forMode(state: CustomThemeState, dark: Boolean): CustomThemeState =
        if (isBuiltIn(state)) checkNotNull(MaterialThemePresets.findById(state.presetId, dark)).toCustomThemeState()
        else if (state.isPresetDark == dark) state else default(dark).toCustomThemeState()

    fun findById(id: String): AppThemePreset? = MaterialThemePresets.findById(id, true)
    fun findByName(name: String): AppThemePreset? = allPresets.find { it.name.equals(name, true) }

    internal fun isBuiltIn(state: CustomThemeState): Boolean =
        MaterialThemePresets.findById(state.presetId, state.isPresetDark)?.toCustomThemeState() == state

    internal fun tonalPalette(state: CustomThemeState): TonalThemeColors? =
        if (isBuiltIn(state)) MaterialThemePresets.roles(state.presetId, state.isPresetDark) else null

    /** Old palettes are retained only as read-only migration fixtures, never selectable choices. */
    internal fun legacyPreset(id: String, name: String, version: Int = 1): AppThemePreset? =
        if (version >= HistoricalThemePresets.PALETTE_VERSION)
            HistoricalThemePresets.findById(id) ?: HistoricalThemePresets.findByName(name)
        else HistoricalThemePresets.legacyPreset(id, name, version)

    internal fun upgradeLegacy(state: CustomThemeState): CustomThemeState {
        if (isBuiltIn(state)) return state
        val old = HistoricalThemePresets.upgradeLegacy(state)
        if (!HistoricalThemePresets.isBuiltIn(old)) return state
        return (if (old.isAmoled) checkNotNull(MaterialThemePresets.findById(MaterialThemePresets.AMOLED_ID, old.isPresetDark))
            else default(old.isPresetDark)).toCustomThemeState()
    }

    // Source compatibility for existing internal consumers/fixtures. These aliases resolve
    // to the new identities; none exposes a retired preset or creates another UI choice.
    internal val darkPresets get() = MaterialThemePresets.all(true)
    internal val lightPresets get() = MaterialThemePresets.all(false)
    internal val StudioNight get() = default(true)
    internal val CleanDay get() = default(false)
    internal val CleanFrost get() = CleanDay
    internal val AmoledPureBlack get() = checkNotNull(MaterialThemePresets.findById(MaterialThemePresets.AMOLED_ID, true))
    internal val EmeraldNebula get() = darkPresets[1]
    internal val CyberpunkNeon get() = darkPresets[4]
    internal val SunsetHorizon get() = darkPresets[5]
    internal val OceanicGlass get() = darkPresets[6]
    internal val NebulaGlass get() = darkPresets[4]
    internal val MatrixGlass get() = darkPresets[7]
    internal val SolarGlass get() = darkPresets[2]
    internal val VelvetNoir get() = darkPresets[8]
    internal val MinimalIvory get() = lightPresets[9]
    internal val CherryBlossom get() = lightPresets[3]
    internal val ElectricMint get() = lightPresets[1]
    internal val SunsetPeach get() = lightPresets[5]
    internal val GlacierGlass get() = lightPresets[6]
    internal val SakuraGlass get() = lightPresets[3]
    internal val PorcelainBlue get() = lightPresets[0]
    internal val LavenderLinen get() = lightPresets[4]
    internal val SageMist get() = lightPresets[7]
}
