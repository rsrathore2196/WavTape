# Persistent streamed-audio cache

## Integration

The streaming cache moves from `cacheDir/xtreme_audio_cache` to
`noBackupFilesDir/xtreme_streamed_audio_cache`. Android excludes the new location
from Auto Backup and ordinary disposable-cache cleanup. `MusicCache` retains one
lazy owner, opened on a loader/IO thread. Explicit downloads retain their separate
`noBackupFilesDir/offline_audio` owners, index and `NoOpCacheEvictor`.

Playback selects explicit downloads first, then a complete compatible streamed
representation, then the existing online resolver. Cache-only selections use
`xtreme-cached` addresses: the player, queue, session persistence, lazy source
factory and source policies recognize them. Restored or differently configured
items re-enter lazy resolution to select a compatible representation. Cache-only
reads have no network upstream and validate recording identity and current policy.

Generic progressive representations record exact catalog/recording identity,
provider, bitrate, codec, MIME, lossless status and available length evidence.
Keys hash representation identity; journal descriptors exclude transport URLs,
headers and credentials. Existing YouTube itag/length and SABR key families remain
separate. Unknown-length generic partial streams are not joined across fresh
resolutions. Full span coverage, positive verified length and intact physical
span files are necessary for offline eligibility. A complete cache hit is an
availability observation, not a promise that subsequent LRU eviction cannot remove it.

Adaptive manifest bodies bypass the streaming cache so signed segment addresses
are not persisted as playlists. HLS/DASH and unsupported optional transports keep
Media3's existing imminent preparation. Offline adaptive playback continues to
use explicit downloads; this upgrade does not infer a whole adaptive recording
from independent segment files. DRM/license acquisition retains its existing path.

## Migration and clearing

`PersistentAudioCacheMigration` runs before opening the destination. Whole-directory
rename preserves cache UID, spans and Media3 database identity. A failed rename
uses a separate staging directory, sufficient-space check, SHA-256 verification,
fsynced files and an atomic commit. A durable verified marker distinguishes a
committed cache that has since changed from an unverified interrupted commit.
Failures preserve the legacy directory for retry; a temporary failure can keep
using it for the current process. An independently populated destination wins
without merging or overwriting either directory. Preserved legacy data may remain
outside the active cache budget until migration cleanup or explicit clearing.

Clear Audio/All removes streamed spans through the live owner, revokes optional
writers and cleans migration leftovers. Leftover copies are removed without
Media3 database deletion because copied directories can share a UID. Downloads,
likes, playlists, listening history, custom lyrics, account credentials and
preferences are not targets. Active playback remains allowed to refill its cache.
Network failures retain audio; a parser-confirmed malformed cache representation
can be invalidated independently.

## Limits and priorities

The existing shared media quota remains 512 MiB by default and configurable to
8 GiB, with 60 MiB reserved for artwork (452 MiB default streamed audio).
`HeadAwareAudioCacheEvictor` evicts ordinary LRU spans first, then protected heads.
Spans beginning within the first 4 MiB get a bounded second chance, charged at
whole-span size against `min(96 MiB, audio quota / 4)`. Heads are not complete songs.
Quota changes resize the live owner without reopening it.

`NextAudioPrefetcher` waits eight stable seconds and prepares only the immediate
next item. It yields to unhealthy current buffering, pause, seeking, queue/source/
network changes and the imminent 20-second transition window. Media3 retains
three seconds of imminent period preloading for gapless/crossfade preparation.
Optional bytes use 1 MiB opening reads followed by at most 2 MiB chunks. Unmetered
full fetching is capped at 64 MiB per recording and needs verified range support;
metered use is conservative and respects Wi-Fi-only/data-saver preferences.
Optional work reserves 256 MiB free storage and declines low-battery operation.

`StrictAudioCacheWriter` acquires nonblocking cache holes before creating upstream
readers. Allocation failures and contention cannot fall through to uncached
fetches. Cancellation/preemption closes upstream and releases holes. Only verified
progressive ranges can be committed; unsupported manifests and changed lengths
stop optional work. Local file/content/resource/data sources bypass streamed
storage entirely.

## Changed production paths

- `MusicCache`, `PersistentAudioCacheMigration`, `HeadAwareAudioCacheEvictor`:
  storage ownership, migration, local bypass, eviction and clearing.
- `StreamedAudioRepresentations`, `MusicRepository`, `AudioQualityCeiling`,
  `SourceTransitionOrigin`: representation evidence, offline selection and policy.
- `MusicService`, `PlaybackManager`, `PlaybackSessionStore`: production player,
  source factory, error handling, queue admission and restart integration.
- `NextAudioPrefetcher`, `StreamedAudioPrefetch`, `StrictAudioCacheWriter`:
  lifecycle, preferences, network admission and bounded optional writes.
- `SmartCacheManager`, `OfflineDownloads`, `OtherSettingsPage`: separate actual
  audio/download statistics, complete-recording counts, metadata versus memory
  accounting and existing cache controls.

## Verification scope

See the delivery report for the exact executed suites and results. The regression
sources cover real Media3 migration/index restoration, incomplete representations,
source/quality restrictions, writer contention/cancellation, eviction/resizing,
cache statistics and local/content reads. Instrumentation additionally exercises
cache-only decoding/seeking, player/session handoff and disk queue restoration.
Software-emulator timing is not a physical-device performance benchmark. Live
provider/account integration, device storage-pressure behavior and older-device
CPU/GPU/startup measurements require separate hardware verification.

## Verified delivery

Target investigation began at `6fc4c936edc6184e06767fd6a20c087969223341`;
implementation was integrated with `62b1fcf` before final verification.
The external reference was inspected at tag v1.9.1
(`7f45c768a461fd7e68bf4613f71e8a767e643d4c`) and
`acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc`. Its cache strategies were independently
implemented; its unsafe rename-failure deletion behavior was not adopted.

- Debug application APK, Android test APK and JVM test compilation: passed.
- Android lint: passed with zero errors (284 warnings across the existing project).
- Final focused Robolectric/JVM run: 18 suites, 140 tests, zero failures/errors.
  Coverage includes migration, eviction, cache representation safety, local/content
  bypass, strict prefetch writing/cancellation, quotas/statistics, offline-cache
  checkpoints, crossfade, source policies, YouTube ranges and streaming add-ons.
- Standalone eviction/prefetch JVM checks: 10 passed.
- The prior Android run passed 38 of 41 cases. Three test-fixture/integration checks
  were corrected afterward; the final Android run could not complete because the
  replacement software emulator restarted during first boot. The user explicitly
  requested ending emulator/watchdog testing and proceeding with the passing checks.
  Device cache-only decoding, the new MediaController selection/session tests, and
  final download/device regressions therefore remain unverified at delivery.
- No physical-device CPU/GPU, startup/next-song latency or live-provider network
  benchmark results are claimed. A 30 MiB local data-source read and content-provider
  seeking passed without streamed-cache growth; this is a storage assertion, not
  a physical-device codec-performance benchmark.

Robolectric's directory-open shadow does not support directory fsync. The JVM-only
`DirectorySyncOsShadow` performs real host directory fsync for the migration tests;
production Android migration continues to use Android's `Os.open/fsync/close`.
