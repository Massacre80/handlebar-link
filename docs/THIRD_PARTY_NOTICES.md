# Third-party notices

This document lists the third-party material in Handlebar Link and where its licence
text can be found. It accompanies [NOTICE.md](../NOTICE.md) at the repository root.

## Licence texts in this repository

| Material | Licence | Text |
|---|---|---|
| Handlebar Link (all original code) | GNU Affero General Public License v3.0 | [`LICENSE`](../LICENSE) |
| CarPlay modules (`:carplay:*`) and wire specs | GNU General Public License v3.0 | [`carplay/LICENSE`](../carplay/LICENSE) |
| Inter Display 4.1 | SIL Open Font License 1.1 | [`core/designsystem/licenses/Inter-OFL.txt`](../core/designsystem/licenses/Inter-OFL.txt) |

GPL v3 §13 permits GPL-3.0 and AGPL-3.0 code to be combined; the combined work is
distributed under the AGPL-3.0. The GPL-3.0 designation of the CarPlay modules is
retained so that DiPlay's and xcertplay's notices stay intact.

## Reference projects

These projects were read to implement the protocols. They are not bundled as source,
but their design, constants and test vectors appear throughout `:carplay:*` and
`docs/research/carplay-spec/`, with file:line citations in the code and specs.

| Project | Licence | Role |
|---|---|---|
| [DiPlay](https://github.com/shihabal3amri/DiPlay) | GPL-3.0 | Primary source for the CarPlay receiver port |
| [xcertplay](https://github.com/shilapi/xcertplay) | GPL-3.0 | DiPlay's upstream, carried through DiPlay |
| [CarLink](https://github.com/F-e-n-y-x/CarLink) | AGPL-3.0 | Reference receiver and four DiPlay hardening patches |
| [DiAuto](https://github.com/shihabal3amri/DiAuto) | AGPL-3.0 | Reference for audio routing and call handling |

## Fonts

| Font | Licence | Note |
|---|---|---|
| Inter Display 4.1 | SIL OFL 1.1 | Redistributable under the OFL; text in `core/designsystem/licenses/`. |
| Integral CF Regular | Commercial (Fontfabric) | The project owner holds a licence permitting its bundling and redistribution with this app. Included with that permission; not relicensed under AGPL-3.0. See `core/designsystem/licenses/README.md`. |

## build-time / runtime dependencies

Dependencies are resolved from Google Maven and Maven Central and are not copied into
this repository. Their licences travel with the artifacts; the notable ones are:

- AndroidX / Jetpack (Compose, Hilt, DataStore, WorkManager, Navigation 3, Lifecycle),
  Kotlin standard library and coroutines, Dagger/Hilt, Robolectric, detekt, Spotless:
  **Apache License 2.0**.
- Bouncy Castle `bcprov-jdk18on`: **Bouncy Castle License (MIT-style)**.
- JUnit 4: **Eclipse Public License 1.0** (test only).
- JaCoCo: **Eclipse Public License 2.0** (build/test only).

The full, authoritative list for a given build is in
`app/build/outputs/sdk-dependencies/release/sdkDependencies.txt`, produced by the
Android Gradle plugin.

## CarPlay identity

Release APKs bundle an experimental CarPlay accessory identity supplied by the builder
at compile time from a directory outside this repository (`carplay.mfi.dir`). It is not
source code and is not covered by this project's licence. Source builds without that
directory contain no identity and report `IdentityUnavailable`. Publishing a release
APK makes any bundled private key extractable; see the README's CarPlay notice.
