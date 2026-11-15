# Addon FLAC and licensed playback

The `fix/addon-playback` branch adds client handling for version 1 backend playback descriptors. It preserves existing legacy addon responses and the current lossless-only addon policy at MAX quality.

The matching backend changes are on `rsrathore2196/Addon_Service`, branch `fix/provider-readiness`. The backend's `docs/VERCEL_PLAYBACK_SETUP.md` describes deployment settings and the required credentials/key integration. Neither repository's main branch is changed by this work.

## Playback paths

- Qobuz progressive FLAC: the backend supplies a verified FLAC URL, and the app supplies an explicit FLAC MIME type to Media3.
- Tidal unprotected FLAC/DASH: the descriptor identifies the DASH container and FLAC codec; Media3 handles manifest/segment playback.
- Tidal protected FLAC/DASH: a valid Widevine descriptor is applied when the media source is created. Media3 generates the DRM challenge and posts it to the signed backend license route. The backend must be authorized by Tidal and the provider must issue a valid license. This does not bypass authorization or licenses.
- Deezer protected media: when the backend decoder/key integration is configured, it returns ordinary progressive FLAC from its byte-range relay. Raw `BF_CBC_STRIPE` URLs remain rejected by the app. The app contains no provider media key or shared decryption secret.

Only a supported Widevine descriptor with a valid HTTPS license URI is accepted. The app forces that configured license URI instead of silently using a different manifest URL, and does not forward provider account credentials to the license service. Unsupported DRM, unknown descriptor versions, raw encrypted media and missing required license descriptors are rejected.

The shared media-source factory applies registered addon MIME and DRM settings for the main player, crossfade deck and queued sources. Unrelated local/non-addon media items retain their existing settings. Expired responses remain subject to the existing resolution/cache freshness checks.

## Backend descriptor example

```json
{
  "codec": "flac",
  "format": "dash",
  "playback": {
    "version": 1,
    "type": "dash",
    "uri": "https://media.example/full.mpd",
    "mimeType": "application/dash+xml",
    "codec": "flac",
    "requiresLicense": true,
    "drm": {
      "scheme": "widevine",
      "licenseUrl": "https://backend.example/_playback/signed-session/license"
    }
  }
}
```

The URI values above are examples, not working media or license endpoints. A decoder relay instead uses `type: "progressive"`, `mimeType: "audio/flac"`, and no DRM configuration.

## Validation

`StreamingAddonIntegrationTest` covers progressive decoder-relay descriptors, correct Widevine configuration on actual Media3 items, and rejection of raw encrypted/unlicensed responses alongside existing addon tests. Run:

```sh
bash ./gradlew :app:testDebugUnitTest --tests com.example.StreamingAddonIntegrationTest
```

The repository requires its pinned Gradle 9.3.1/AGP 9.1.1 toolchain and Android SDK 37.0. Real licensed Widevine playback additionally requires a supported physical Android device and a Tidal account/application with the needed playback rights. Fixture tests cannot establish that account authorization, CDM behavior or decoded playback succeeds on every device.

The integration suite was executed for this change: 19 tests passed, zero failures, zero skipped. The app source and test source compilation completed successfully. The matching backend passed 83 tests and its decoder output was independently checked as valid FLAC with FFmpeg. Live provider licenses/keys and real-device playback remain separate acceptance checks.

Before release, test a full track and seeking for each source, signed-session expiry recovery, a denied license, unavailable quality/region, and the intended account-concurrency behavior. Provider tokens and per-track decoding keys must remain in secure backend settings, never the app build.
