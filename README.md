# Handlebar Link · BikePlay

[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue)](#license)
[![Android 12+](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#requirements)

An Android app for a phone mounted on a **Honda CB350 / CB350RS**: it links to the bike's
meter over Bluetooth, starts by itself when the ignition comes on, and turns handlebar
presses into controls for **BikePlay** — the app's own CarPlay receiver. Nothing needs to
be plugged into the bike and no extra hardware is sold with the project.

The phone runs the display and the receiver. Music, navigation and calls come from the
iPhone through CarPlay; the mounted phone does not need its own internet connection.

> This is an independent, community-built project. It is **not** certified by Apple and
> is **not** affiliated with Apple, Google or Honda. See the [CarPlay notice](#carplay-notice).

## Features

- **Meter link.** Connects to the CB350/CB350RS instrument cluster, survives the ride, and
  reports handlebar presses. Auto-starts on ignition through Android companion-device
  presence, with the screen off and the app not open.
- **BikePlay.** The app's own CarPlay receiver (wired bootstrap, wireless Wi-Fi Direct
  session) replaces CarLink: no switch, no second app. iPhone → CarPlay picture, audio,
  touch and Siri on the mounted phone.
- **Smart buttons.** Up / down / left / right and their long presses are mapped per
  situation — riding, ringing or on a call. Answer, decline, end, mute, volume, media,
  Siri and brightness are handled in the ride, with a persisted safety unmute on restart.
- **Garage.** Service intervals, chain, insurance/PUC/RC and your own reminders, with a
  daily notification. Defaults come from the CB350RS owner's manual; everything is editable.
- **Audio routes.** Calls and Siri follow a connected helmet intercom; music and navigation
  keep playing over A2DP. The microphone mute is undone when a call ends or the app restarts.
- **Landscape first.** The phone rides on its side: a two-pane layout with a navigation
  rail, built for glove-sized targets and sunlight legibility. Two themes: Night and Bento.
- **Diagnostics.** On-device logs with a shareable report; nothing is sent anywhere.

## Requirements

- **The display:** an ARM Android device with **Android 12 (API 31) or newer**, tested on a
  Poco F1 running Android 17. Install the app here, not on your iPhone.
- **The bike:** a Honda CB350 / CB350RS with the meter this project's protocol was verified
  against (see `docs/protocol/SAB_PROTOCOL.md`). Other meters are unsupported.
- **The phone:** an iPhone for CarPlay. Pair it once from the BikePlay tab.
- **For a CarPlay-enabled build:** an Apple accessory identity supplied at build time.
  Source builds without one work but report `IdentityUnavailable`; see
  [Building](#building-from-source).

## Install

1. Open [Releases](https://github.com/Devsed-official/handlebar-link/releases/latest) and download `handlebar-link-2.0.0.apk`.
2. Verify it: compare its SHA-256 with the `.sha256` file in the release
   (`shasum -a 256 -c handlebar-link-2.0.0.apk.sha256`).
3. Open the APK and allow installation from the source you used, then tap **Install**.
4. Open the app and follow first-time setup: allow the requested permissions, link the
   meter through Android's companion-device consent, and pair your iPhone.

**Upgrading from a development build:** development builds were signed with the Android
debug key; release builds use a different key. Android will refuse to update over the top —
uninstall the development build first (Garage data lives on the phone and will be lost).

The release signing certificate fingerprint is:

```
18:89:F3:CC:1A:B0:8E:CB:83:10:57:F1:7B:69:62:E4:8E:3D:75:6A:B7:F5:10:4B:03:8A:F2:1A:65:1B:9D:1A
```

Check it with `apksigner verify --print-certs handlebar-link-2.0.0.apk`. An APK signed by
another key is not an official build.

## Building from source

Requires **JDK 21** and the **Android SDK platform 37**.

```sh
./gradlew check              # format, detekt, lint, tests, coverage, module boundaries
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
./gradlew spotlessApply      # fix formatting
```

Gradle properties (in `~/.gradle/gradle.properties` or as environment variables):

| Property | Purpose |
|---|---|
| `HL_KEYSTORE_PATH`, `HL_KEYSTORE_PASSWORD`, `HL_KEY_ALIAS`, `HL_KEY_PASSWORD` | Release signing. Without them the release APK is debug-signed, for local testing only. |
| `carplay.mfi.dir` (in `local.properties`) | Directory with `identity.pk8` and `certificate.p7b`. Copied into generated assets at build time; never committed. |

Never put identity files, keystores or passwords in git. See
[`docs/RELEASING.md`](docs/RELEASING.md) for the full release process and
[`docs/testing.md`](docs/testing.md) for the test setup.

## Layout

| Module | What |
|---|---|
| `:core:common`, `:core:testing` | pure Kotlin: logging, time, shared types and test doubles |
| `:core:designsystem` | Compose tokens and owned components |
| `:bike:protocol`, `:bike:link`, `:bike:link-android` | meter codec, link engine, Android transport |
| `:carplay:protocol`, `:carplay:engine`, `:carplay:engine-android` | CarPlay receiver, ported from DiPlay (GPL-3.0) |
| `:app` | ride service, auto-start, pairing, screens and the only Hilt module |

Engines never depend on each other; only `:app` wires them.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md). The engineering
plan and the mistakes this project already learned from are in
[`docs/PLAN.md`](docs/PLAN.md) and [`docs/LESSONS.md`](docs/LESSONS.md).

## CarPlay notice

This is **not an Apple-certified product**. A CarPlay-enabled build bundles an
experimental accessory identity supplied by the builder; a bundled private key is
extractable from the APK, and there is no guarantee that CarPlay keeps working after an
iOS update. Source builds contain no identity. Android Auto is not implemented here.

## License

Handlebar Link is distributed under the **GNU Affero General Public License v3.0**
([LICENSE](LICENSE)). The CarPlay modules are GPL-3.0 ([carplay/LICENSE](carplay/LICENSE)),
as required by their DiPlay origin; GPL v3 §13 allows the combination. Attributions and
third-party notices are in [NOTICE.md](NOTICE.md) and
[`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md). These texts are also shipped
inside the APK under `assets/licenses/`.
