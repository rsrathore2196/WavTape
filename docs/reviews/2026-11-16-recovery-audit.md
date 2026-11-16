# App recovery audit — 16 November 2026

Reviewed from `a3bc16d` on `main`, after fast-forwarding the draft checkout to
include the already published playback, cache and audit fixes. Those earlier
changes were preserved and were not reapplied.

This pass reviewed playback/queue ownership, download/index/transfer recovery,
library persistence and imports, catalog browsing/search, cache accounting,
lyrics/timers, settings, backup/export and update handling. The full available
JVM/Robolectric suite also exercises UI layout, theme, navigation, animation,
glass rendering, DSP, crossfade, spatial audio and output precision behavior.
The scope is confirmed defects and shared recovery logic; no visual redesign,
feature removal, dependency change or environment publication is included.

## Changes in implementation order

1. **Reject missing or incomplete downloaded audio.** The previous check accepted
   any nonempty set of cache keys. Media3 can retain content metadata after all
   audio spans are gone, and an open cache index can retain the old length of a
   truncated file. Download validation now checks actual span files and lengths,
   the completed byte inventory and, for progressive audio, continuous coverage
   of the known resource length. Adaptive HLS/DASH byte-range downloads remain
   supported without incorrectly requiring every underlying resource in full.
   Invalid entries enter the existing failed/retry state; validation does not
   delete audio or start a replacement network download.

2. **Recover from rejected download foreground startup.** Foreground notification
   setup previously happened before the worker claimed its queued record. If
   Android rejected startup, the worker failed while the app still displayed
   an indefinitely queued download. The claim now precedes foreground setup,
   allowing the existing failure handler to persist a failed, retryable record.
   Transfer concurrency, notification design and download policy are unchanged.

3. **Make theme exports report and preserve real saved files.** A null public
   output stream previously reported a successful public export. Failed writes
   also left empty MediaStore rows. Public exports now remain pending until the
   complete JSON stream closes and MediaStore confirms publication. Failed or
   cancelled publication removes its pending row and ordinary mirror failures
   retain the existing private-copy fallback. Private writes use `AtomicFile`,
   exports serialize access to the shared filename, and unavailable external app
   storage falls back to internal app storage. The theme document, active theme
   and export UI are unchanged.

4. **Keep non-backup files out of backup discovery.** The physical-file scan
   previously listed every JSON file, including `customtheme.json`, unrelated
   JSON and truncated documents. Selected document folders also always reported
   zero songs/playlists. Physical files, MediaStore and document folders now use
   one bounded preview reader to recognize backup sections and obtain counts.
   MediaStore rows without names are skipped without stopping discovery of later
   backups. Renamed legacy backups remain discoverable. Preview checks do not
   replace the existing full validation and transaction safeguards during restore.

5. **Validate the entire Downloads playback queue.** The Downloads page previously
   validated the selected song but converted all later entries to offline URIs
   without checking their files. A missing later song was consequently queued
   and failed at its turn. Page playback and the Library quick-play button now
   share `playableQueue`, preserving order/shuffle while excluding invalid audio.
   The selected song is checked within that single pass rather than scanned
   twice. File validation runs on playback actions, not while rendering rows or
   reacting to favorite-state changes. No layout, artwork, animation or glass
   drawing code was changed.

6. **Isolate optional download lookup failures from normal playback.** Every
   ordinary selection queried the download index before source resolution. A
   malformed index or storage read failure could therefore prevent even an
   unrelated local recording from playing. The repository now logs ordinary
   lookup failures and continues its existing resolution path. Cancellation is
   still propagated, explicit offline URIs still go directly to the offline
   source, and the damaged index is not overwritten or silently cleared.

## Regression evidence

- The baseline ran **1,705 tests**: 1,704 passed and one existing output-precision
  UI test timed out. That test passed on its separate rerun without a product or
  fixture change.
- New failing regressions reproduced metadata-only/partial/truncated downloads,
  missing adaptive segments, foreground-start failure, invalid backup listings,
  incomplete theme export/publication, and a missing upcoming Downloads entry.
  The theme provider fixture was attached to an Android context before treating
  its export failures as product evidence.
- The initial post-fix selection passed **61 tests**, covering the new recovery
  cases and existing Downloads UI, storage, collection membership/progress and
  backup restoration tests.
- Additional tests cover failed public publication, document-folder counts and
  malformed/nameless MediaStore rows; all eight export/discovery cases passed.
- The complete suite passed **1,720 tests** before the last storage-isolation
  finding. Two additional reproductions then confirmed that malformed/unreadable
  download indexes blocked unrelated playback. After the correction, all **72**
  selected playback, source-routing, download-integrity and cache tests passed,
  including cancellation, explicit offline requests and unknown-length downloads.
- Final complete JVM/Robolectric suite: **1,725 tests across 281 suites**, with
  **zero failures, errors or skipped tests**. This includes 20 additional cases
  from this pass and the previously timing-sensitive output-precision UI test.

Final verification commands:

```text
bash gradlew :app:testDebugUnitTest
bash gradlew :app:assembleDebug :app:lintRelease
git diff --check
```

The debug APK built successfully. Release lint completed with **zero errors and
285 warnings**; it is not warning-free. The warnings in the modified production
files are KTX convenience suggestions on unchanged lines. Whitespace validation
also passed.

New suites: `OfflineDownloadIntegrityTest`, `DownloadWorkerRecoveryTest`,
`ThemeExportRecoveryTest`, `BackupDiscoveryTest`, `DownloadLookupIsolationTest`.
`LibraryDownloadsUiTest` adds the actual Downloads-page queue regression.

No physical Android device is attached. Host tests cannot certify live provider
availability, account authentication, hardware audio behavior or phone frame
pacing. This audit records reviewed paths and reproduced defects; it is not a
claim that every possible device/server interaction is bug-free.
