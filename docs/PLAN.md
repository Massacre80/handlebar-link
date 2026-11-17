# Handlebar Link v2 — plan

Status: **approved, in progress** (2026-11-16). Done: M0, M1, M2 (engine, runtime, Android adapters), M3.1–M3.2 (app wiring, ride service), UI.1
(design tokens, Night and Bento themes) and the structure fixes of the
architecture review (`docs/reviews/2026-11-16-architecture-review.md`). Mistakes and their rules:
`docs/LESSONS.md`.

## Goal

One app of our own on the Poco F1 (Android 17) mounted on the Honda CB350:

1. **Now:** a rock-solid link to the bike's meter. It starts by itself when the ignition turns on,
   survives the whole ride, and sends handlebar presses to CarPlay.
2. **Later:** our own CarPlay receiver inside the same app, replacing CarLink. CarLink/DiPlay is a
   reference only (`docs/research/05-carlink-reference.md`), never a dependency or a fork.

Fixed decisions: everything plays on the iPhone through CarPlay; the Poco never plays its own media
and has no internet. Voice = Siri through CarPlay ("… on YouTube Music").

**Supported: Android 12 (API 31) and newer** (decided 2026-11-16). The current API is used where it
exists, with a version branch to the older API only where a supported release needs it (e.g. the
API 33 GATT methods, the API 37 `connectGatt(BluetoothGattConnectionSettings, …)`, the API 36 CDM
presence request). Retries and recovery that the link needs are part of the design. **No workaround
code that is known to be fragile:** no hidden APIs (the research's `BluetoothGatt.refresh()` is
dropped), no reflection, and no extra wake-up paths unless an on-bike test shows they are needed
(the PendingIntent scan of 02 §3.5 is not built by default).

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

Modules plus `build-logic` (convention plugins), named after Now in Android's `core:*` convention
(architecture review MUST-4). Engines never depend on each other; only `:app` wires them together.
Every module's dependencies are checked from its real Gradle configurations against
`build-logic/.../ModuleRules.kt` (`checkModuleDependencies`); a module without a rule fails.

```
:core:common       pure Kotlin    Logger, logfmt, TimeProvider
:core:testing      pure Kotlin    test-only: TestTimeProvider (virtual time), RecordingLogSink
:core:designsystem Android lib    tokens + ~20 owned Compose components (no Bluetooth, no app logic)
:bike:protocol     pure Kotlin    SAB codec: UUIDs, frames, replies, CTS encoding
:bike:link         pure Kotlin    the link engine: BikeLink API, state machine, setup session,
                                  runtime, and `spi` (what the platform must provide). Cannot use
                                  Android. Public types via api(:core:common, :bike:protocol, coroutines)
:bike:link-android Android lib    (M2.5) implements `spi` with BluetoothGatt, the CTS GATT server,
                                  system broadcasts and a wake lock; public Android factory
:bike:link-test    pure Kotlin    test-only: FakeBikeLink for :app's tests
:app               application    the only Hilt module: ride service, auto-start, pairing, button
                                  routing, screens, diagnostics (package-by-feature: di/, ride/, …)
(later) :carplay:protocol, :carplay:engine, :carplay:engine-test — same pattern
```

Key rules (details in 01 §5, 03 §7):

- DI: **Hilt** (with KSP), as Google's `testing-setup` skill prescribes for a project without a DI
  framework. Only `:app` applies it (`hl.hilt`); engines stay framework-free and expose `create(...)`
  factories that `:app`'s Hilt modules call. Tests swap engines for fakes with `@TestInstallIn`.
- `explicitApi()`: everything in a library is `internal` unless it is documented API.
- Each engine has one sealed error type that also says how to recover. Android exceptions and status
  codes are translated in exactly one adapter. `CancellationException` is never swallowed.
- Connection lifecycle = a pure reducer `(State, Event) → (State, Effects)`, tested as a table.
- All BLE work runs on one thread. A CarPlay failure (later) can never take down the bike link.
- Logs go through our own `Logger`: logcat, an on-screen ring, a rotating file. Crashes and Android's
  exit reasons are recorded; diagnostics can be shared. No third-party SDKs, no `INTERNET` permission.
- UI: the owner's design (Paper file "CB350RS"). Colours, sizes, type and motion come only from
  `:core:designsystem` tokens (`design/*.css`, checked by `TokenDriftTest`); detekt rejects raw numbers
  elsewhere. Two themes the rider picks in settings, never switched automatically: **Night** (default:
  dark, bike photo, glass cards) and **Bento** (light, card grid); each has its own Home layout. A fully
  custom Compose theme, not Material (Android "Custom design systems in Compose"). Touch targets ≥ 48 dp.
  Data without a source yet (odometer, trip, service, intercom battery, CarPlay) shows an empty state,
  never a made-up number. (Replaces "Dark/Day/Sunlight, 76 dp", 2026-11-17.)
- Security: nothing exported unless a system permission guards it; no secrets in git (the future
  CarPlay identity files are injected from `~/.carlink-private` at build time); R8 on.

Navigation (UI.3, from the Navigation 3 "multiple back stacks" recipe and the Navigation principles):
five tabs, each with its own saved back stack; Home is the fixed start destination and the app exits
through it (Back from another tab's root goes to Home); switching tabs replaces the other tab's entries;
each page gets saved UI state and its own ViewModel scope (entry decorators). Settings opens on Home's
stack as a full page without the tab bar. Tabs without a feature show an empty page.

### First-time setup (M4.1, started 2026-11-17 at the owner's request)

Design: Paper "CB350RS", page "Setup" (6 screens). Every rule below is from the official docs (research
2026-11-17, checked against the sources) or the protocol spec; nothing is assumed.

- **Gate, not a start destination** (Navigation principles; Navigation 3 conditional recipe): while no bike
  is linked the app shows setup instead of the tabs; Home stays the start destination.
- **Meter steps** are RoadSync's for the CB350 (SAB_PROTOCOL.md "Putting the meter into pairing mode"):
  hold both left meter buttons until ECO flashes, select switch right to "Bluetooth reset", down to Yes,
  right; the Bluetooth mark flashes.
- **Permissions**: `BLUETOOTH_SCAN` with `usesPermissionFlags="neverForLocation"` (bt-permissions: some
  beacons are filtered; irrelevant for the meter) for our own scan; `BLUETOOTH_CONNECT` to bond and read
  names. Asked from "Find my bike", after the screen that explains why (permissions skill §6: rationale
  first, never loop, settings page on permanent denial; never cache, catch `SecurityException`).
  Companion pairing needs Location Services on (companion-device-pairing).
- **Our scan** (find-ble-devices; ScanSettings): no hardware filter (service-filtered scans on the bike
  returned nothing, cause not established; SAB_PROTOCOL.md §1.1). Names cleaned (the meter's ends in two NUL
  characters); Honda meters (Vehicle Information service or "HONDA BTU" name) listed first,
  other named devices after as a fallback, in the order first seen (sorting by signal made the list jump).
  `SCAN_MODE_LOW_LATENCY` only while on screen, a time limit, RSSI shown; stop the scan before associating
  (`associate()` doc).
- **Association** (CompanionDeviceManager): the tapped device only (`setNamePattern` on its cleaned name
  then anything, `setSingleDevice(true)`; no service filter, so a fallback device works; no address filter:
  its handling is not documented). One branch:
  API 33+ `associate(request, Executor, Callback)` with `EXTRA_ASSOCIATION`; API 31–32
  `associate(request, Callback, Handler)` with `onDeviceFound` and `EXTRA_DEVICE` (a BLE `ScanResult`).
  Android 16+: a discovery timeout comes back as user-rejected.
- **Bond** within 10 minutes of consent (the system may skip its confirmation): `createBond()` (`createBond
  (TRANSPORT_LE)` from API 37), `ACTION_BOND_STATE_CHANGED` receiver registered `RECEIVER_EXPORTED` (sent by
  a privileged app, not the system), filtered to our address, 30 s limit as RoadSync; skip when bonded.
- **Store** the meter's address (and the association id); read the device back with `getRemoteDevice`,
  never `AssociationInfo.getAssociatedDevice()` (not persisted).
- **Later (M4.2)**: presence observing (`ObservingDevicePresenceRequest` API 36, older variants below),
  `CompanionDeviceService`, `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`.

Tooling (re-verified 2026-11-16 against Maven Central, Google Maven and the official AGP 9.5 release
notes via `android docs`): Gradle 9.9.1 (AGP 9.5 needs ≥ 9.6), AGP 9.5.1 with built-in Kotlin,
Kotlin 2.4.21 (latest stable; 2.5.0 is still RC), compile/target SDK 37, minSdk 31, JDK 21
(AGP needs ≥ 17), coroutines 1.11.0, Compose BOM 2026.09.00, Navigation 3 1.2.1,
Lifecycle 2.11.0, DataStore 1.3.0, Hilt 2.60.1 + KSP 2.3.14 (Hilt ≥ 2.59 is required for AGP 9),
Spotless 8.11.0 + ktlint 1.8.0, detekt 1.23.8, Android Lint as errors, JaCoCo 0.8.16 (coverage, per
the testing-setup skill), Turbine 1.2.1, AssertK 0.28.1, Robolectric 4.18.
Screenshot tests: Google's **Compose Preview Screenshot Testing as an AGP 9.5 test suite**
(`testOptions { screenshotTests.create(...) }`), as Google's official `testing-setup` skill
prescribes for AGP ≥ 9.5.0-alpha03. It replaces Roborazzi from 03 §8; no third-party dependency.
The project layout follows Google's official AGP 9 template (`android create empty-activity`):
built-in Kotlin, version catalog, repository content filters, foojay toolchain resolver,
configuration and build cache on, `src/*/kotlin` source folders.

Agent tooling in this repo: Google's Android CLI (`android docs` searches the official Android
Knowledge Base) and the official Android skills in `.claude/skills/` (android-cli, navigation-3,
testing-setup, android-intent-security, android-permissions-security, edge-to-edge, r8-analyzer,
jetpack-compose-performance, anr-audit-and-fix). Update them with `android skills update --all`.
CI: one GitHub Actions job running
`./gradlew check assembleRelease`, plus gitleaks and Dependabot.

One application id, `com.abid.handlebarlink`: v2 simply replaces the prototype on the phone. The
prototype stays available on branch `prototype`.

## Milestones

Each milestone is small, ends with a commit on `v2`, and has a check that proves it is done.
No milestone starts before the previous one is done.

| # | What | Done when |
|---|---|---|
| **M0** ✅ | Empty project, set up properly: `build-logic`, version catalog, the five modules, quality gates (format, detekt, lint, explicit API, module boundaries), CI, a blank screen. | `./gradlew check` passes locally and in CI; the blank app installs on the Poco. |
| **M1** ✅ | `:bike:protocol`: the meter codec, written from `SAB_PROTOCOL.md`. | Unit tests cover every frame, reply and code verified on the CB350. |
| **M2** | `:bike:link` (pure Kotlin engine): GATT transport seam, scripted fake meter, the connection state machine and its 16 required tests; `:bike:link-android`: the real Android transport and CTS server. | All tests pass on virtual time, incl. a randomised test with invariants. |
| **M3** | `:app` ride service: logging and diagnostics, button routing to CarPlay (interim: CarLink's media session, as in the prototype). | Same behaviour as the prototype on the bike, checked with the on-device checklist. |
| **M4** | Auto-start on ignition: pairing through the companion-device dialog, presence service, boot and update receivers. | Ignition on → connected with the phone screen off and the app not open, timed on the bike (02 §6.5 D4/D5). |
| **M5** | UI: tokens and components in `:core:designsystem`; setup, status, button mapping, diagnostics and settings screens. **Moved before M3.3** at the owner's request (2026-11-17), because the design is ready: M3.3's screens are built from it. Steps: UI.1 tokens and themes ✅, UI.2 components ✅, UI.3 pages and navigation ✅, UI.4 screenshot tests ✅. | Screenshot tests for every component and screen in both themes; glove and sunlight check on the bike. |
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
