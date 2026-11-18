# Material Color Utilities (Google LLC)

Source: https://github.com/material-foundation/material-color-utilities
Pinned revision: `dd62982dfdefdfc9232ce8d76e122fb1c6d188ae`.
License: Apache-2.0; full license is in `LICENSE`. Copyright notices are retained in every file.

Only HCT/CAM16, tonal palettes, temperature, dislike correction, math, and Blend are vendored.
Changes: Java packages/imports are namespaced under `com.example.ui.theme.colorutilities`;
an attribution header is added. Algorithms are otherwise unmodified.

The Kotlin engine's four accent palette rules match Google's `ColorSpec2021` rules
at this revision. Role tones and seed-hue neutral chroma 6/8 follow the app's explicit
contract, rather than DynamicColor's contrast-dependent/Fidelity container tones.

There is no resolvable `com.google.material.color:material-color-utilities` artifact
on Google Maven or Maven Central. Vendoring this small pure-math subset avoids
pulling the XML Material Components library into a Compose app or depending on
its restricted internal APIs. Do not edit the numerics without upstream tests.
