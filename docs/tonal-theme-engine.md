# Runtime Material 3 tonal themes

The app uses Jetpack Compose Material 3 1.3.0 and minSdk 24. Existing saved themes,
AMOLED presets, custom colors, typography, glass effects and navigation remain the default.
The runtime engine is opt-in; it does not migrate or overwrite the existing 20 presets.

## In-app access

Open **Settings → Appearance → Tonal Themes**, directly below Custom Theme Colors.
This replaces the previous Current Theme / Save Theme card. Choose colors/style,
light or dark mode, wallpaper colors, or the current track's album artwork and tap
**Apply theme**. The app applies and saves the selection, including across restarts
and app backups. Existing preset/custom editors remain available; applying one
switches back to its palette. The regular light/dark/System controls preserve an
active tonal selection. The artwork option uses a cached, small software sample of
the currently playing cover, and does no artwork work when disabled or overridden
by System wallpaper colors.

## Dependencies

`app/build.gradle.kts` already includes the Compose BOM, Material 3 and
`implementation(libs.androidx.palette.ktx)` (`androidx.palette:palette-ktx:1.0.0`).
No extra repository is required. The requested
`com.google.material.color:material-color-utilities` coordinate is not published in
Google Maven/Maven Central. Google's HCT/Blend math is included directly, namespaced,
at a pinned upstream revision; see `ui/theme/colorutilities/README.md` and its Apache
license. The license and attribution are also packaged in APK assets.

## App integration

```kotlin
var options by remember { mutableStateOf(TonalThemeOptions(
    seedColor = TonalThemePreset.EMERALD_PULSE.seedColor,
    style = PaletteStyle.VIBRANT,
    useSystemDynamicColor = false,
    useAlbumArtwork = false
)) }

MyApplicationTheme(
    darkTheme = isSystemInDarkTheme(),
    tonalTheme = options,
    artworkBitmap = albumCover // optional, caller-owned decoded Bitmap
) {
    AppContent()
}
```

For a standalone Compose host, use `TonalMaterialTheme(options, darkTheme, bitmap) { ... }`.
It supplies both `MaterialTheme.colorScheme` and `LocalAppColors`, while leaving window
styling and density to the host. Passing `tonalTheme = null` to `MyApplicationTheme`
keeps the saved theme path; its existing `dynamicColor = true` parameter now also works.

`ThemePreviewScreen(artworkBitmap = cover, onApply = { options, dark -> ... })` offers all
four presets/styles, light/dark switches, wallpaper/album controls, 36 labeled hex swatches,
the full surface hierarchy and live Material buttons, switch and slider. The optional Apply
callback lets the host own/persist the choice; preview changes alone do not change settings.
There are light/dark Android Studio previews. Appearance uses the same screen with
the app's saved selection and an Apply callback to its theme owner.

## Mapping contract

| Role family | Light tones | Dark tones |
| --- | --- | --- |
| primary / onPrimary | 40 / 100 | 80 / 20 |
| primaryContainer / onPrimaryContainer | 90 / 10 | 30 / 90 |
| secondary, tertiary, error + their content/container roles | same as primary | same as primary |
| background, surface / onSurface | 98 / 10 | 6 / 90 |
| surfaceContainerLowest → Highest | 100, 96, 94, 92, 90 | 4, 10, 12, 17, 22 |
| surfaceDim / surfaceBright | 87 / 98 | 6 / 24 |
| surfaceVariant / onSurfaceVariant | 90 / 30 | 30 / 80 |
| outline / outlineVariant | 50 / 80 | 60 / 30 |

The four accent recipes follow Google's ColorSpec2021 rules. Fidelity preserves the
seed hue/chroma palette and temperature complement; its container tones still follow
the explicit 90/30 contract rather than upstream Fidelity's variable seed-tone containers.
Neutral palettes use the seed hue with chroma 6 (surfaces) and 8 (variant/outlines) in every
style. HCT gamut mapping naturally lowers achievable chroma near black/white.

All 36 roles are set explicitly. Every text/content pair is checked at ≥4.5:1, including
onPrimary, onSurface and onSurfaceVariant. Foregrounds are guarded again during the
350ms color animations so light/dark transitions do not fade text through an unreadable
midpoint. Disabled controls retain Material's normal disabled-opacity behavior. The
host must still choose the corresponding content role for each surface.

## Artwork and runtime behavior

`ThemePaletteGenerator.extractHarmonizedSeed(bitmap, baseSeed)` is a suspend helper.
It chooses a vibrant/dominant Palette swatch, harmonizes its hue with Google's
`Blend.harmonize`, and returns the base seed if the image is empty/unusable. Work uses
the bounded CPU dispatcher, sampling up to 128px with at most 16 swatches. Keep the
caller-owned Bitmap alive until it returns; the helper never recycles the caller's image.
Hardware images are scaled/read into a small software sample where supported; unreadable
images safely keep the base theme. No network request or image decoding is started here.

Turning album theming off restores the base seed. New requests cancel obsolete extraction
results. Image quantization runs only when the base seed/bitmap changes; style and
light/dark switches reuse its extracted seed. Generated schemes
are cached in a bounded 64-entry cache with no Bitmaps or Contexts. No HCT generation or
quantization runs per animation frame. Android's animator-duration scale is respected.

Android 12+ System Dynamic Color uses Google's wallpaper schemes and takes precedence
over artwork/style choices. API 24–30 falls back to the selected seed. Resource configuration
changes refresh wallpaper colors; the wrapper does not retain an Activity Context globally.
