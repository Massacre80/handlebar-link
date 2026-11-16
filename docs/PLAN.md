# Handlebar Link v2 — plan

Status: **proposed, awaiting approval** (2026-11-16). Nothing below is built yet.

## Goal

One app of our own on the Poco F1 (Android 17) mounted on the Honda CB350:

1. **Now:** a rock-solid link to the bike's meter. It starts by itself when the ignition turns on,
   survives the whole ride, and sends handlebar presses to CarPlay.
2. **Later:** our own CarPlay receiver inside the same app, replacing CarLink. CarLink/DiPlay is a
   reference only (`docs/research/05-carlink-reference.md`), never a dependency or a fork.

Fixed decisions: everything plays on the iPhone through CarPlay; the Poco never plays its own media
and has no internet. Voice = Siri through CarPlay ("… on YouTube Music").

## Sources

Every decision below comes from the research in `docs/research/`:

| Doc | Topic |
|---|---|
| `01-architecture.md` | modules, DI, errors, logging, security, tooling, tests, CI |
| `02-ble-connection.md` | GATT client, connection state machine, auto-start on ignition, pairing, 34 edge cases |
| `03-ui-design-system.md` | tokens, owned components, screens, motion, screenshot tests |
| `04-intercom-audio.md` | where each sound and the mic go; intercom setup; design for our own receiver |
| `05-carlink-reference.md` | how CarLink/DiPlay works (reference for the CarPlay phase) |
| `protocol/SAB_PROTOCOL.md` | the meter's BLE protocol, with the facts verified on the CB350 |

The working prototype stays on branch `prototype` as a reference.

## Structure

Five Gradle modules plus `build-logic` (convention plugins). Engines never depend on each other;
only `:app` wires them together, and a build check enforces it.

```
:core              pure Kotlin    Logger, time, small shared types
:bike:protocol     pure Kotlin    SAB codec: UUIDs, frames, replies, CTS encoding
:bike:link         Android lib    GATT transport, CTS server, meter session, connection supervisor
:designsystem      Android lib    tokens + ~20 owned Compose components (no Bluetooth, no app logic)
:app               application    ride service, auto-start, pairing, button routing, screens, diagnostics
(later) :carplay:protocol, :carplay:engine — added only when CarPlay work starts
```

Key rules (details in 01 §5, 03 §7):

- Manual DI: each engine has a `create(...)` factory; `:app` has one `AppGraph`. No Hilt/Koin.
- `explicitApi()`: everything in a library is `internal` unless it is documented API.
- Each engine has one sealed error type that also says how to recover. Android exceptions and status
  codes are translated in exactly one adapter. `CancellationException` is never swallowed.
- Connection lifecycle = a pure reducer `(State, Event) → (State, Effects)`, tested as a table.
- All BLE work runs on one thread. A CarPlay failure (later) can never take down the bike link.
- Logs go through our own `Logger`: logcat, an on-screen ring, a rotating file. Crashes and Android's
  exit reasons are recorded; diagnostics can be shared. No third-party SDKs, no `INTERNET` permission.
- UI: colours, sizes, type and motion come only from `:designsystem` tokens; the build rejects raw
  values elsewhere. Dark by default, plus Day and Sunlight themes; 76 dp glove-sized targets.
- Security: nothing exported unless a system permission guards it; no secrets in git (the future
  CarPlay identity files are injected from `~/.carlink-private` at build time); R8 on.

Tooling (versions verified 2026-11-16, see 01 §7): Gradle 9.8.2, AGP 9.5.1, Kotlin 2.4.21,
compile/target SDK 37, minSdk 33, JDK 21, coroutines 1.11.0, Compose BOM 2026.09.00,
Navigation 3, Spotless + ktlint, detekt, Android Lint as errors, Kover, Turbine, AssertK,
Robolectric (service shell only), Roborazzi (screenshots). CI: one GitHub Actions job running
`./gradlew check assembleRelease`, plus gitleaks and Dependabot.

One application id, `com.abid.handlebarlink`: v2 simply replaces the prototype on the phone. The
prototype stays available on branch `prototype`.

## Milestones

Each milestone is small, ends with a commit on `v2`, and has a check that proves it is done.
No milestone starts before the previous one is done.

| # | What | Done when |
|---|---|---|
| **M0** | Empty project, set up properly: `build-logic`, version catalog, the five modules, quality gates (format, detekt, lint, explicit API, module boundaries), CI, a blank screen. | `./gradlew check` passes locally and in CI; the blank app installs on the Poco. |
| **M1** | `:bike:protocol`: the meter codec, written from `SAB_PROTOCOL.md`. | Unit tests cover every frame, reply and code verified on the CB350. |
| **M2** | `:bike:link`: GATT transport seam, scripted fake meter, the connection state machine and its 16 required tests, the real Android transport, CTS server. | All tests pass on virtual time, incl. a randomised test with invariants. |
| **M3** | `:app` ride service: logging and diagnostics, button routing to CarPlay (interim: CarLink's media session, as in the prototype). | Same behaviour as the prototype on the bike, checked with the on-device checklist. |
| **M4** | Auto-start on ignition: pairing through the companion-device dialog, presence service, boot and update receivers. | Ignition on → connected with the phone screen off and the app not open, timed on the bike (02 §6.5 D4/D5). |
| **M5** | UI: tokens and components in `:designsystem`; setup, status, button mapping, diagnostics and settings screens. | Screenshot tests for every component and screen; glove and sunlight check on the bike. |
| **—** | CarPlay receiver: a separate plan once M0–M5 are done. | — |

## Things only the phone or bike can settle

Each one is a named test in the research docs; we run them in the milestone that needs them:

- How fast Android notices the meter with the screen off (02 §6.5 D4/D5): decides "presence-driven"
  vs "always armed" as the default.
- Whether the meter uses a fixed address (02 D2), and what force-stop does (02 D11).
- Volume changes from a background service on Android 17 (01 §4.4).
- The Poco's real screen density, glove use and sunlight readability (03 §9).
- Intercom: the 15-minute bench test of "iPhone owns all sound" (04 §7.1). This can be done any
  time with today's CarLink, independent of the milestones.
