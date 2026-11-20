# Handlebar Link · BikePlay

[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue)](#license)
[![Android 12+](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#requirements)

Handlebar Link turns a spare Android phone into the dashboard for a Honda CB350 / CB350RS.
It talks to the bike's meter over Bluetooth, wakes up on its own when you switch the
ignition on, and maps the handlebar buttons to **BikePlay** — its own CarPlay receiver. So
the iPhone's CarPlay runs on the mounted phone: maps, music, calls, Siri. Nothing is wired
into the bike and there's no hardware to buy.

The mounted phone doesn't need its own data — everything online comes from the iPhone over
CarPlay.

> Independent, community-built project. Not certified by Apple, and not affiliated with
> Apple, Google or Honda. See the [CarPlay notice](#carplay-notice) before you build it.

## What it does

- **Meter link** — connects to the CB350/CB350RS cluster, holds the connection through the
  ride, and forwards the handlebar buttons. It starts itself the moment the ignition comes
  on, with the screen off and the app closed, through Android's companion-device presence.
- **BikePlay** — the CarPlay receiver. The iPhone's CarPlay picture, audio, touch and Siri,
  on the mounted phone. No second app, no switch to flip; it comes up with the ride.
- **Handlebar buttons** — up, down, left, right and their long-presses do different things
  depending on whether you're riding, the phone's ringing, or you're on a call: answer,
  decline, hang up, mute, volume, skip, Siri, screen brightness. A mute always clears itself
  when the call ends.
- **Garage** — service intervals, chain, insurance/PUC/RC and whatever else you want to be
  reminded about, with a daily nudge. The defaults come from the CB350RS manual; change any
  of them.
- **Audio** — calls and Siri move to a helmet intercom when one's connected; music and
  navigation stay on A2DP.
- **Sideways layout** — the phone rides on its side, so the screen is a two-pane landscape
  with a nav rail and big targets. Two looks: Night and Bento.
- **Diagnostics** — logs stay on the phone; you can export a report, but nothing is sent
  anywhere.

## Requirements

- A spare Android phone, **Android 12 (API 31) or newer**, ARM. Built and tested on a Poco
  F1 running Android 17. This is where the app goes — not on the iPhone.
- A Honda CB350 / CB350RS with the meter the protocol was reverse-engineered against (see
  `docs/protocol/SAB_PROTOCOL.md`). Other meters won't work.
- An iPhone for CarPlay, paired once from the BikePlay tab.
- For CarPlay to actually connect, an Apple accessory identity supplied at build time. A
  build without one still runs and reports `IdentityUnavailable`; see
  [Building from source](#building-from-source).

## Install

1. Grab `handlebar-link-2.0.0.apk` from the
   [latest release](https://github.com/Massacre80/handlebar-link/releases/latest).
2. Check it against the `.sha256` in the release:
   `shasum -a 256 -c handlebar-link-2.0.0.apk.sha256`.
3. Open the APK, allow installs from wherever you opened it, and tap **Install**.
4. Open the app and walk through setup: grant the permissions it asks for, link the meter
   through Android's companion-device prompt, and pair your iPhone.

Coming from a dev build? Those were signed with the Android debug key and release builds
aren't, so Android won't let you update over the top. Uninstall the dev build first — Garage
data lives on the phone and goes with it.

Every release is signed with this certificate:

```
18:89:F3:CC:1A:B0:8E:CB:83:10:57:F1:7B:69:62:E4:8E:3D:75:6A:B7:F5:10:4B:03:8A:F2:1A:65:1B:9D:1A
```

Confirm it with `apksigner verify --print-certs handlebar-link-2.0.0.apk`. A different
signer means it isn't an official build.

## Building from source

You need **JDK 21** and **Android SDK platform 37**.

```sh
./gradlew check              # format, detekt, lint, tests, coverage, module boundaries
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
./gradlew spotlessApply      # fix formatting
```

Settings go in `~/.gradle/gradle.properties` or the environment:

| Property | What it's for |
|---|---|
| `HL_KEYSTORE_PATH`, `HL_KEYSTORE_PASSWORD`, `HL_KEY_ALIAS`, `HL_KEY_PASSWORD` | Release signing. Without them the release APK is debug-signed — fine for local testing, not for sharing. |
| `carplay.mfi.dir` (in `local.properties`) | Folder holding your `identity.pk8` and `certificate.p7b`. Copied into the build's assets; never committed. |

Keep identity files, keystores and passwords out of git. The full release process is in
[`docs/RELEASING.md`](docs/RELEASING.md); the test setup is in
[`docs/testing.md`](docs/testing.md).

## Layout

| Module | What's in it |
|---|---|
| `:core:common`, `:core:testing` | pure Kotlin — logging, time, shared types, test doubles |
| `:core:designsystem` | Compose tokens and the app's own components |
| `:bike:protocol`, `:bike:link`, `:bike:link-android` | meter codec, link engine, Android transport |
| `:carplay:protocol`, `:carplay:engine`, `:carplay:engine-android` | the CarPlay receiver, ported from DiPlay (GPL-3.0) |
| `:app` | ride service, auto-start, pairing, the screens, and the only place Hilt wires it together |

The engines don't know about each other — only `:app` connects them.

## Contributing and security

[CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md) cover how to work on this
and how to report problems. The design and the running list of mistakes-and-rules are in
[`docs/PLAN.md`](docs/PLAN.md) and [`docs/LESSONS.md`](docs/LESSONS.md).

## Credits

This project is a port of, and leans on, other people's work:

- **[DiPlay](https://github.com/shihabal3amri/DiPlay)** by
  [shihabal3amri](https://github.com/shihabal3amri) (GPL-3.0) — the CarPlay receiver the
  `:carplay:*` modules are ported from. DiPlay is itself based on
  [xcertplay](https://github.com/shilapi/xcertplay) by **shilapi** (GPL-3.0).
- **[DiAuto](https://github.com/shihabal3amri/DiAuto)** (AGPL-3.0), also by shihabal3amri —
  the reference for how calls and audio get routed. It builds on
  [Open Headunit](https://github.com/andreknieriem/open-headunit) by **andreknieriem**,
  Headunit Revived, and Michael Reid's headunit.
- **[CarLink](https://github.com/F-e-n-y-x/CarLink)** by
  [F-e-n-y-x](https://github.com/F-e-n-y-x) (AGPL-3.0) — the receiver whose hardening fixes
  this port follows. No CarLink code is copied in.
- **[Inter](https://github.com/rsms/inter)** by Rasmus Andersson (SIL Open Font License 1.1).
- **Integral CF** by Fontfabric, bundled under the owner's commercial licence.
- The **Bouncy Castle**, **Google** and **AndroidX/Kotlin** libraries the app is built on.

Full licence texts and attribution are in [NOTICE.md](NOTICE.md) and
[`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md), and ship inside the APK under
`assets/licenses/`.

## CarPlay notice

This isn't an Apple-certified product. A CarPlay-enabled build bundles an experimental
accessory identity that the builder supplies, and that key can be pulled back out of the
APK. There's no promise CarPlay keeps working after an iOS update. Builds from source carry
no identity at all. Android Auto isn't implemented.

## License

Handlebar Link is **AGPL-3.0** ([LICENSE](LICENSE)). The CarPlay modules (`:carplay:*`) are
**GPL-3.0** ([carplay/LICENSE](carplay/LICENSE)), because that's what their DiPlay origin
requires — AGPL-3.0 and GPL-3.0 are allowed to combine under AGPL-3.0 §13. Attribution and
third-party notices are in [NOTICE.md](NOTICE.md) and
[`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md), and ship in the APK under
`assets/licenses/`.
