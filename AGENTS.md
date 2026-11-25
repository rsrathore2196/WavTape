# Project branding

The public app name is **WavTape** (renamed on 2026-11-18). Use WavTape in all
new user-facing UI, notification, sharing and export copy. The supplied cyan/blue
W tape-ribbon mark (`assets/branding/wavtape-logo.png`) is the brand logo, and
the transparent gradient wordmark (`assets/branding/wavtape-wordmark.png`) is
the official app name graphic; preserve their colors and transparent cutouts.

Existing application ID, preference/database keys, playback URI schemes and
working repository/community URLs are compatibility identifiers, not display
branding. Preserve them unless the user explicitly requests a migration. Older
Xtreme Player backups must remain readable.

Run checks relevant to the change; do not repeatedly run the full test suite.

## WavTape release APKs

- Keep `main` as the default development branch for Google AI Studio's direct
  pushes and normal reviewed Codex changes. Keep the release workflow file on
  `main` so GitHub shows the manual run control.
- Promote `main` to `release` through a manually reviewed pull request. Keep
  the `release` ruleset active with PRs required, zero mandatory approvals,
  force pushes and deletions blocked, and no bypass actors.
- Run the permanent [WavTape Release workflow](.github/workflows/wavtape-release.yml)
  manually on `release`. Restrict `release-signing` to that branch and keep
  signing secrets out of development and Gradle jobs.
- Preserve the application ID and increment `versionCode` when preparing an
  actual new release.
- Download `WavTape-release-apk` from the successful workflow run. Verify its
  certificate fingerprint, application ID, and non-debuggable status before
  distributing it. See [Release signing](docs/RELEASE_SIGNING.md).
- Preserve the permanent signing identity for all releases. Never generate a
  replacement keystore or sign a distribution build with AI Studio's debug key.
- Never request passwords, keystores, or private signing files in chat. Keep all
  signing credentials out of Git.
- Report signing and verification errors. Never fall back to another signing method.
