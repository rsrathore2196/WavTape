# Startup work scheduling

Baseline: `e9582f0afa5cae1f480cedadeab2caa9445973e4`.

## Production integration

The existing coordinator now admits classified CPU continuations and disk stages,
instead of relying only on whole-job startup admission. Home processing, image
decoding, library snapshot conversion and cache encoding share one automatic CPU
stage. The existing overall CPU dispatcher still permits one worker on devices
with up to three processors and two on other devices. No additional executor is
created. Small player-presentation joins use Default independently of bulk work;
Main retains UI binding and player commands.

Tickets release CPU capacity when a continuation suspends or finishes. Visible
content and promoted requests precede enrichment, persistence and maintenance.
Cancellation removes queued tickets. Playback source resolution uses a critical
context outside automatic admission; Media3 renderer/loader threads retain their
existing behavior. Resource releases notify resource waiters rather than waking
every startup collector.

Home checkpoints, recommendation-metadata transactions and artwork-index writes
share the disk stage. User-required metadata checkpoints have foreground priority.
The small runtime-profile installation remains independent of Home readiness and
still waits for its own first-draw/input/playback conditions. Headless workers do
not require an Activity or a Home frame. Outbox delivery yields between submitted
scrobbles while retaining their original durable timestamps.

## Startup and artwork

The application settles encrypted account restoration before Home selects its
account-scoped cache or starts a browse request. The appearance/settings snapshot
continues to own the shell's first draw independently. Restoration failure settles
the gate and preserves the existing anonymous fallback. Account-store access uses
background-priority IO, and its loaded flag is published after discovery identity.

A collapsed player prepares a small decode using the same source file as the full
player. Full-size decoding and palette preparation wait for optional admission;
opening the player promotes existing work. An explicit track selection can promote
cover lookup without promoting the collapsed player's full-size prewarm. Lookup
sharing, recording validation, square-cover selection, full resolution and palette
algorithms are retained. Visibility changes cancel obsolete work through the
existing owner scopes.

## Processing and persistence

Home transports release their network slot before parsing. Profile-search deadlines
cover transport rather than time spent waiting for CPU admission. Other searches
retain their HTTP client's deadline policy; ordinary discovery keeps its existing
12-second transport limit. Nested transport admission does not reserve another slot.

Renderer walks, source/aggregate shelf projection, library conversion and queue
reconstruction cooperate at intervals of 32 elements or four milliseconds. Shelf
signatures are reused in a ViewModel-owned identity index capped at 512 entries.
Existing duplicate-matching rules, ordering, stable identities and continuations
are preserved. Already canonical Home generations skip redundant persistence-time
deduplication.

Home cache submissions enqueue typed checkpoints before encoding. A replaced
checkpoint can therefore be discarded before projection/JSON serialization.
Requested reads can materialize and temporarily promote the current proposal;
cancellation releases that promotion. Atomic replacement, version-aware
acknowledgement, bounded retries and existing cache formats remain in use. Queue
restoration releases its file lock before CPU or interaction waits; service-owned
restoration remains independent of UI readiness. No storage migration is required.

## Verification and measurement limits

- Selected JVM/Robolectric regressions: **253 tests, 38 suites, zero failures,
  errors or skipped tests**. Coverage includes admission, cancellation, headless
  restoration, profile installation, account readiness, prewarm promotion,
  checkpoint coalescing/recovery, Home projection, artwork reuse, library snapshots,
  transport deadlines, queue checkpoints, crossfade and playback routing.
- Additional provider/playback regressions: **53 tests, five suites, zero failures,
  errors or skipped tests**, covering streaming add-ons, source policies, YouTube
  streaming policy, resolver caches and Media3 crossfade preparation.
- Debug app, Android test APK, minified benchmark app and benchmark test APK:
  assembled successfully. Release compilation and R8 shrinking succeeded; final
  release packaging is blocked by the environment's missing configured
  `my-upload-key.jks`. Signing configuration was retained.
- Release lint: zero errors, 285 warnings across the project. No dependency or
  tooling-version changes were made to address existing lint suggestions.
- No Android device was connected for this change. On-device scrolling, startup,
  CPU/GPU, memory and energy measurements remain unverified. No FPS, latency or
  power improvement is claimed from unit-test execution.

The existing startup benchmark records account restoration, CPU/disk admission and
execution, checkpoint encoding, mini/full artwork preparation and JSON-decoding
sections alongside frame timing, startup and memory metrics. Compare the baseline
and updated profileable builds on the recording phone, with matching settings,
account state, cache state and refresh rate, using five iterations of cold/warm
opening, immediate interaction, rapid tab changes and optional-work wakeup. Cover
empty, paused-restored and actively playing states.

Individual bitmap decodes and JSON DOM tokenization remain indivisible. The new
JSON traces expose their cost; the plan's conditional streaming-tokenizer rewrite
requires evidence from those device traces before changing parser semantics.
Cooperative slice intervals are scheduling checkpoints, not a hard native-decoder
or GPU execution-time guarantee. Animation specifications, layouts, glass shaders,
audio quality, explicit downloads and streamed-audio storage policies are unchanged.
