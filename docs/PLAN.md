# Handlebar Link v2 — plan

Status: **approved, in progress** (2026-11-16). Done: M0, M1, M2 (engine, runtime, Android adapters), M3.1–M3.2 (app wiring, ride service), UI.1
(design tokens, Night and Bento themes) and the structure fixes of the
architecture review (`docs/reviews/2026-11-16-architecture-review.md`). M4: M4.1 (setup), M4.2
(presence observing and service) and M4.3 (update and restart recovery) built; the on-bike check is open. Mistakes and their rules:
`docs/LESSONS.md`.

## Goal

One app of our own on the Poco F1 (Android 17) mounted on the Honda CB350:

1. **Now:** a rock-solid link to the bike's meter. It starts by itself when the ignition turns on,
   survives the whole ride, and sends handlebar presses to CarPlay.
2. **Now too (owner, 2026-11-20):** our own CarPlay receiver inside the same app, which has replaced CarLink: no
   switch, no CarLink path left (see "Our CarPlay replaces CarLink"). CarLink/DiPlay is a reference only
   (`docs/research/05-carlink-reference.md`), never a dependency or a fork.

Fixed decisions: everything plays on the iPhone through CarPlay; the Poco never plays its own media
and has no internet connection of its own: everything online comes from the iPhone through CarPlay.
Voice = Siri through CarPlay ("… on YouTube Music"). Any permission CarPlay needs on the Poco is fine
(owner, 2026-11-19), including `INTERNET`: Android requires it for every socket, also the local ones to the iPhone.

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
  exit reasons are recorded; diagnostics can be shared. No third-party SDKs, and nothing is sent anywhere:
  the `INTERNET` permission exists only for CarPlay's local sockets to the iPhone (owner, 2026-11-19).
- UI: the owner's design (Paper file "CB350RS"). Colours, sizes, type and motion come only from
  `:core:designsystem` tokens (`design/*.css`, checked by `TokenDriftTest`); detekt rejects raw numbers
  elsewhere. Two themes the rider picks in settings, never switched automatically: **Night** (default:
  dark, bike photo, glass cards) and **Bento** (light, card grid); each has its own Home layout. A fully
  custom Compose theme, not Material (Android "Custom design systems in Compose"). Touch targets ≥ 48 dp.
  Data without a source yet (odometer, trip, service, intercom battery, CarPlay) shows an empty state,
  never a made-up number. (Replaces "Dark/Day/Sunlight, 76 dp", 2026-11-17.)
- Row and card text (owner, 2026-11-20: subtexts ran up to the trailing control and left one word alone): the text of a
  row with something after it sits in `HlRowText`, which keeps `HlSpacing.trailingGap` (16 dp) clear before the
  trailing element, and subtexts that may wrap use `TextStyle.balanced()` (the Compose guide's paragraph line break with
  the balanced strategy), in the design system's rows (`HlNavRow`, `HlValueRow`, `HlSwitchRow`, `HlOptionRow`,
  `SignalCard`) and the app's own (pairing search, setup search and problem rows, permission rows, the Handlebar card,
  the meter and iPhone-pairing cards).
- The Devices tab with nothing added (owner's design, Paper "Devices 1 — No intercom yet" and "Devices 1L"): the
  CarPlay tab's "No iPhone yet" hero with the headset (`NoIntercom`): the dial, "NOT SET UP", "Add your intercom", the
  balanced subtext, the brand Add device (still the container transform into Add device), then a glass group of three
  information rows without a chevron (`HlValueRow` with an icon well: Battery on Home, Calls and music in your helmet,
  Intercom button; the music note `HlIcons.Music` is new, path data from the artboard). No page title, as on the
  CarPlay tab. The hero's parts are shared in `ui/frame/TabHero.kt` (`HeroState`, `HeroColumn`, `HeroPanes`); on its
  side the hero | the button and the rows, as the CarPlay tab.
- Sheets dismiss with the secondary button (owner, 2026-11-20): Cancel is `HlSecondaryButton` (well fill, hairline edge,
  52 dp pill), full width as the sheet's last item, under the main action where there is one (Add device's "What is
  it?": Save, then Cancel), as in dialogs. Changed: the CarPlay screen picker, the handlebar action chooser (and the
  intercom's assistant button, which uses it), Calls, Music and the device sheet; the iPhone sheet has no Cancel (its
  Forget was already secondary). `HlQuietButton` stays for pages' lesser actions (Make visible again, Rename, Continue
  without).
- Security: nothing exported unless a system permission guards it; no secrets in git (the future
  CarPlay identity files are injected from `~/.carlink-private` at build time); R8 on.

Navigation (UI.3, from the Navigation 3 "multiple back stacks" recipe and the Navigation principles):
four tabs (Home, CarPlay, Garage, Devices; Rides was dropped, see below), each with its own saved back stack; Home is the fixed start destination and the app exits
through it (Back from another tab's root goes to Home); switching tabs replaces the other tab's entries;
each page gets saved UI state and its own ViewModel scope (entry decorators). Settings opens on Home's
stack as a full page without the tab bar. Tabs without a feature show an empty page.

### Rides dropped (owner, 2026-11-20: "remove the rides feature, not needed anymore")

- **Gone:** the Rides tab (`RidesRoute`, its tab, its empty page, `HlIcons.Rides` and its drawable, the strings
  `tab_rides`, `later_rides`, `no_rides`) and ride logging's location permission: the "Location" row on setup's
  "You're set" step (now the notifications only, `SetupAccess.rows`) and on Settings › Permissions, so Allow all no
  longer asks for it (`SettingsPermission`, tested in `SettingsPermissionTest` and `SetupAccessTest`, planted bugs
  caught), `rememberLocationAccess` and the background-recording note. Bento Home's distance card (shown only for a
  meter that reports speed and odometer) says "No data yet" instead of "No rides yet".
- **Kept:** the riding session (`RideService`, `RideCoordinator`, `RideStarter`, `RideAutoStart`, "ride" in
  auto-start and controls): that is the bike link and CarPlay running while riding, not ride logging.
- **Location that remains:** `ACCESS_FINE_LOCATION` with `ACCESS_COARSE_LOCATION`, now `maxSdkVersion="32"`, only for
  CarPlay's Wi-Fi Direct group on Android 12/12L (`carPlayPermissions`; API 33+ uses `NEARBY_WIFI_DEVICES` with
  `neverForLocation`). The wifi-permissions guide allows the cap when nothing else relies on precise location, and
  nothing does: our BLE scan and the pairing page's classic discovery use `BLUETOOTH_SCAN` with `neverForLocation`,
  companion-device pairing "doesn't require location permissions" (bt-permissions; it needs Location *services* on,
  `SetupProblem.LOCATION_OFF`, which reads a setting and needs no permission), and nothing calls `discoverPeers`,
  `requestPeers`, Wi-Fi scans or a location provider (CarPlay's GPS forwarding to the iPhone is not built).
- The tab bar and rail hold four tabs; on the Poco and the emulator on their side each rail tab is now the bar's tab
  turned on its side (74 dp, `RailGapTest`).

### Landscape layout (owner, 2026-11-20: "the width overall is very bad, fix it properly")

The Poco rides on its side (2246 x 1080, about 817 x 393 dp, notch on one short edge). Portrait stays exactly as
approved; landscape gets its own arrangement, decided once in `AppRoot` and read by pages from `LocalWindowLayout`.

- **When:** `ui/frame/WindowLayout.kt`: `WindowLayout.PANES` when the window's height is compact (< 480 dp) and its
  width at least medium (≥ 600 dp), from `currentWindowAdaptiveInfoV2().windowSizeClass` (Android docs "Use window
  size classes"; `androidx.compose.material3.adaptive:adaptive`, added with `addLibrary`). Phones upright, tablets and
  small split-screen windows keep `COLUMN`. Previews and screenshot tests default to `COLUMN`, so the portrait
  references hold; the landscape ones provide `PANES` (`LandscapeScreenshots.kt`, `@Landscape`: 914 x 411 emulator,
  817 x 393 Poco, and the Poco at font 1.5).
- **One frame, centred (owner: "not centred"):** on its side the cutout (or a three-button navigation bar) sits on one
  short edge only. Pages, the rail and full pages pad both sides by the larger side (`MirroredSides`,
  `WindowInsets.pageTop`/`pageAll`), so everything is centred on the screen, never pushed by the cutout on one side.
  Upright the sides are zero: no change.
- **Navigation:** the floating tab bar becomes `HlTabRail` (design system, next to `HlTabBar`: the same glass pill,
  tabs, labels, chip spring and backdrop blur, stacked; `size-tab-rail` 86 dp = the chip and the bar's 6 dp inset each
  side) on the **end edge**, where CarPlay keeps its dock (owner, 2026-11-20), 16 dp in from the mirrored side. It
  fills the height between the system bars less 8 dp, the same band as Home's panes, on every tab page, its tabs
  spread evenly (`railGap`); the chip and the tabs are the rail's inner width, so the chip is 6 dp from every edge as
  in the bar. Tab pages get the rail's room as end padding (`railSpace`): the content's start margin, its gap to the
  rail and the rail's end margin are one width, the mirrored side + 16 dp (owner: "the right side padding is totally
  wrong"; measured 168 / 168 / 168 px on the emulator), and the navigation bar as bottom padding. The room reaches the
  pages as one `LivePadding` that reads the current value: Navigation 3 keeps a page's entry content from its first
  show, and a plain `PaddingValues` captured there missed the insets that arrive after the first frame. Tab pages'
  second pane is clipped below the status bar and a status-bar protection is drawn over tab pages, so nothing runs
  under the clock.
- **Two panes** (equal width, Home's cards pane 1.6 x the bike's since the BikePlay card, 16 dp gutter, each scrolling on its own when it runs out of height): Home Night (the
  wordmark, pill and settings in their own band over the whole bike on its ground, `FittedBike`; the cards: the BikePlay card,
  device tile and Add device side by side, Handlebar), Home Bento (pill and settings over the bike card filling the pane,
  its bike sized to the room under the name; the cards in portrait order), setup and in-app iPhone pairing (bike, ring
  or heading in the first pane, the step's words and actions in the second; Find my bike and Make visible stay under
  the heading so they are on screen), the CarPlay tab (dial, tag, title, detail | actions and the status rows), the
  Handlebar page (`SubPagePanes`: the switch as large as the pane's height allows | situations, keys, reset).
  The tab pages (Devices, Garage) fill the frame like Home (the same margins as Home's cards); Settings and
  its pages and Add device keep the 600 dp column, centred in the space left by the mirrored sides and the rail.
  Sheets keep their centred 600 dp width. A hero's centred subtext keeps to `size-hero-text` (260 dp, `heroTextMax`;
  Paper "Tab 1L — No iPhone yet, landscape (Night)": "Pair it once here. CarPlay then" over "connects by itself on
  every ride." (the text is BikePlay's two sentences since 2026-11-20, see "BikePlay on screen"), balanced by
  `centred()`): the CarPlay tab's hero, the Devices tab's empty hero, the waiting screens'
  centred lines (setup and iPhone pairing) and the empty Garage tab; upright the column sets the width as before. Setup, pairing and Handlebar share one margin (`setupSides`: 24 dp on its
  side) and their panes scroll to the screen edge. The switch photo is never drawn past its own 1024 px
  (`diagramSide`), and its callouts frost it (`glassBlur`, the cards' glass).
- **The bike never overlaps the header:** the header has its own band and the bike fits the space left (whole, not
  cropped); the Handlebar card is no longer laid over the bike (it showed the bike through its unblurred glass), and the
  switch photo on the Handlebar page is no longer blown up to 570 dp (1024 px source).

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
- **Then (M4.2)**: presence observing and the presence service, below.

### Auto-start on ignition (M4.2–M4.3, 2026-11-18)

Sources: the official docs (companion-device-pairing "Keep companion apps awake"; BLE "Communicate in the
background"; FGS "Restrictions on starting from the background"; the CompanionDeviceManager,
CompanionDeviceService and DevicePresenceEvent references) and 02 §3. Code: `app/.../autostart/`.

- **Observing** (`PresenceArming`, at every process start and whenever the selected bike changes): the
  selected bike's association is observed, every other association of the app is released (`planPresence`).
  One version branch (`AndroidCompanionPresence`): API 36+ `startObservingDevicePresence(
  ObservingDevicePresenceRequest.Builder().setAssociationId(id))`, which deprecates the address overload;
  API 31–35 `startObservingDevicePresence(address)`. Associations are listed with `getMyAssociations()` (33+)
  or `getAssociations()` (Android 12, addresses only). FEATURE_COMPANION_DEVICE_SETUP is checked first, as the
  reference requires. "Forgotten" here means the selection is cleared: observation stops; the association
  itself is kept (disassociating is part of "Forget this bike", not built yet; 02 §4.2).
- **Presence service** (`BikePresenceService`, a `CompanionDeviceService`, exported and guarded by
  `BIND_COMPANION_DEVICE_SERVICE`): only the callback family of the running release is trusted
  (`presenceCallbacks`: `onDevicePresenceEvent` on 36+, `onDeviceAppeared/Disappeared(AssociationInfo)` on
  33–35, the address overloads on 31–32), because on 36+ the system also calls the deprecated ones. BLE
  appeared / BT connected = present; BLE disappeared / BT disconnected = gone; other events are ignored.
- **Appeared** (`PresenceEvents`): the selected bike's link gets `onPresence(true)` and the ride service is
  started with `startForegroundService` from the background, allowed for a CDM app holding
  `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` (FGS exemptions). Without Nearby devices the
  start is skipped: a `connectedDevice` service could not call `startForeground`, which that start requires.
  A running ride re-posts its foreground notification for the duplicate start. **Gone**: only
  `onPresence(false)`; the service is never stopped by a disappear event (02 §3.2: the meter stops
  advertising while connected).
- **Policy per bike** (`LinkPolicy`): a bike Android watches is **presence-driven** (10 min linger, 02 §2.5,
  then the ride service ends until the next appearance); a bike without an association stays **always
  armed** (as before M4), because nothing would start it again. A bike chosen from "Paired with this phone"
  in setup has no association (that path skips Android's consent): it does not auto-start; logged as
  `presence.not_linked`. See the open question below.
- Also requested: `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` (required by the observe calls) and
  `REQUEST_COMPANION_RUN_IN_BACKGROUND` (the BLE background guide's way to stay connected while present).
- **Presence-driven vs always armed (decision pending D4/D5):** presence-driven is the default for watched
  bikes, as the research recommends; the on-bike timing below decides whether it stays. If CDM presence is
  slow or unreliable with the screen off, the default becomes always armed (`linkConfig`, one place).
- **Open question:** should setup also link (associate) a meter picked from "Paired with this phone"? Today
  such a meter is connected and not advertising, which is why that path skips the consent sheet; Android's
  consent needs it advertising (ignition on, link to the app closed).
- **M4.3, after an update** (`AppUpdated`, a non-exported `MY_PACKAGE_REPLACED` receiver): the update kills
  the process and a running ride, and Android does not report a bike that stays present, so with a bike
  selected the ride starts again without a presence hint (it reconnects if the bike is there; presence-driven
  lingers out if not). `MY_PACKAGE_REPLACED` is an FGS background-start exemption. Observation is re-asserted
  by the process start itself (`PresenceArming` in `Application.onCreate`), so the receiver adds nothing there.
- **M4.3, process killed mid-ride**: `RideService` is now `START_STICKY`; the system's restart becomes a
  foreground service through the companion-device exemption and resumes without a hint (02 §3.4, edge case 6).
  Without an association the restart is refused and the service stops, so it is not restarted again.
- **M4.3, no boot receiver**: the CDM reference says the app "doesn't need to remain running" to receive
  presence callbacks, and nothing in the docs says observation must be renewed after a reboot (the research
  found it persisted by the system, 02 §3.4 "Reboot"). Presence-driven mode starts nothing at boot by design.
  So no `BOOT_COMPLETED` receiver (and no `RECEIVE_BOOT_COMPLETED`) until D8 shows otherwise; if it fails, the
  fix is a non-exported receiver whose process start runs `PresenceArming` (and, for always armed, starts the
  ride: `connectedDevice` is allowed from `BOOT_COMPLETED` on Android 15+).

#### On-bike check for M4 (owner; release build, logs captured to a file from the start, lessons 36, 40)

Log tags: `autostart` (`presence.observe`, `presence.event`/`presence.device`, `presence.appeared`/`gone`,
`presence.ride_start`, `presence.policy`, `presence.not_linked`, `update.ride_start`), `service`
(`service.restarted`, `service.background_refused`, `service.background_no_permission`), `ride`, `bike`.
`adb shell dumpsys companiondevice` shows the association and whether it is observed and present.

1. **Setup through the consent sheet** (not "Paired with this phone"), then open the app once.
   `dumpsys companiondevice`: our association, observing on; log `presence.observe` and
   `presence.policy watched=true`.
2. **D3 (no bike needed):** `am kill com.abid.handlebarlink`, screen off, then
   `adb shell cmd companiondevice simulate-device-appeared <id>` (or `simulate-device-event <id> 0`): the ride
   notification appears, no `ForegroundServiceStartNotAllowedException`; `presence.ride_start started=true`.
3. **D4, 20 ignition cycles:** app not open, process dead, phone idle ≥ 30 min, screen off. Note the time of
   ignition on; read `presence.appeared` and the link's `Ready` from the log. Record ignition→appeared and
   appeared→Ready, median and p95. Target: Ready within the 02 §3.4 target (under 5 s after the event).
4. **Ignition off:** the link drops, `presence.gone`; after 10 min without the bike the ride notification
   goes (presence-driven linger). Ignition on within 10 min reconnects without a new start (D6).
5. **D5, always armed:** the same timing with the link always armed and CDM observation stopped. This needs a
   build with `linkConfig` returning always armed for watched bikes and `PresenceArming` not started (one
   place each); record the same numbers.
6. **D8:** reboot with the ignition on, and reboot then ignition on: Ready without opening the app (note
   whether an unlock was needed). If not, add the boot receiver described above.
7. **D9:** `adb install -r` of the release build with the ignition on: `update.ride_start started=true`, then
   Ready. **D10:** `am kill` while Ready: `service.restarted`, Ready within about 10 s.
8. **D16:** `dumpsys companiondevice` mid-ride: the association counts as present while connected.

**Decision after 3 and 5:** keep presence-driven as the default for watched bikes if D4's p95 is acceptable
and every cycle connected; otherwise switch `linkConfig` to always armed and record the numbers here.

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

### Smart buttons (handlebar actions per call situation, 2026-11-19)

Code: `app/.../controls/` (`CallSituation.kt`, `CarPlayControls.kt`, `HandlebarMapping.kt`, `RiderActions.kt`).
Each press does the action the rider set for the **situation** it happens in: `RIDING` (no call), `RINGING`
(a call rings) or `ON_CALL` (active, held or dialling). `MappedButtonPresses` reads `CallSituations.current`
and picks that layer of `HandlebarMapping`.

- **Defaults.** Riding: up previous, down next, right play/pause, hold down play/pause, left nothing, hold
  left Siri. Calls live on the left key, which has a phone symbol on the CB350 (owner's UX review): ringing,
  left answers and hold left declines; on a call, left mutes the microphone, hold left ends the call, up and
  down set the call's volume. Every other press does nothing during calls.
- **Choices per situation** (`allowedActions`; `assign` refuses the rest): riding offers everything except
  the call actions; ringing offers answer and decline, on a call end and mute microphone, both plus volume,
  brightness, screens and nothing. Music keys, spoken battery/time, Mute (the call volume cannot reach zero)
  and Siri (a stray hold would start it over the conversation) are not offered during calls.
- **Storage.** Riding keeps the keys from before (`handlebar_<SWITCH>`), so riders keep their settings;
  `handlebar_ringing_<SWITCH>` and `handlebar_call_<SWITCH>` for the others. Unknown or disallowed stored
  names fall back to the default.
- **Routing (2026-11-20, our CarPlay replaced CarLink):** every press goes to our own CarPlay
  (`OurCarPlayControlTarget`); while it is not `Connected` a press does nothing, is logged
  (`control.carplay.not_connected`) and the banner says "CarPlay isn't connected" (`NO_CARPLAY_SESSION`). The
  situation comes from our CarPlay's iAP2 calls while connected and is riding otherwise (`CarPlayCallSituations`).
  The CarLink route (`RoutedControlTarget`, `MediaSessionControlTarget`, `AudioCallSituations` and its
  `VoipCallWatcher`, `NEEDS_OUR_CARPLAY`, "Soon") is gone.
- **With our CarPlay connected** (`OurCarPlayControlTarget`, `situationOfCalls`): the situation from iAP2
  CallStateUpdate 0x4155 (0 disconnected, 1 sending, 2 ringing, 3 connecting, 4 active, 5 held,
  6 disconnecting; DiPlay `BydCarPlayCall.kt:22-24`; a ringing call wins), so ringing is seen; answer = telephony
  HID hook switch, decline/end = HID drop (DiPlay `CarPlayController.kt:750-753`); Siri = AirPlay `requestSiri`
  (siriAction 2 then 3, DiPlay `AirPlaySession.kt:390-393`); the music keys as media HID keys. Nothing is "Soon".
- **Mute microphone** = `AudioManager.setMicrophoneMute` (MODIFY_AUDIO_SETTINGS, a normal permission; AOSP
  `AudioService.setMicrophoneMute` has no API-level gate and refuses only unmuting under the OP_MUTE_MICROPHONE
  app op; not part of Android 17's background audio hardening). The mute is global and outlives our process. Safety: it works only on a call, is read back after each change
  (`MIC_REFUSED` if Android ignored it), and if we muted it, it is unmuted as soon as the situation leaves
  `ON_CALL` (`control.mic.unmuted_after_call`). A "we muted it" flag (`mic_muted_by_app` in the app's
  DataStore) is stored before muting and cleared once unmuted; at every process start
  (`HandlebarLinkApp` → `CarPlayControls.start`) a set flag with no call unmutes
  (`control.mic.unmuted_after_restart`), and a set flag mid-call keeps the mute until that call ends. Since
  2026-11-20 the only call source is our CarPlay, which is not connected right after a process start, so a restart
  always unmutes (the safe side); the CarLink-era seeding from the audio state is gone. A mic we did not mute is never
  touched. A flag that cannot be stored stops the mute; a failed flag read or write is
  logged and never ends the watch (docs/LESSONS.md 59). **Our CarPlay's uplink (2026-11-19):** whether the audio HAL
  also silences an app's own `AudioRecord` on every input is not promised (`AudioFlinger::setMicMute` is per HAL), so
  `MicrophoneCapture` sends silence of the same length while `AudioManager.isMicrophoneMute()` is on (read at the
  capture's start and on each `ACTION_MICROPHONE_MUTE_CHANGED`, `carplay.audio.microphone_muted`); the flag and its
  safety rules above are unchanged. Our own CarPlay's calls hold no Telecom call (see "C5 audio", "Routes"), and the
  Telecom call held for CarLink's calls is gone with CarLink.
- **Handlebar page** (`ui/handlebar/`, Paper "Handlebar — Smart buttons", artboards 2–5): a Riding / Ringing /
  On a call switch (`HlSegmentedControl`) chooses the layer shown; under it a line on the situation (its
  defaults while the layer is unchanged, else just what it is). The CarLink-era notices ("ringing needs our
  CarPlay", "hanging up does") and "Soon" tags are gone (2026-11-20). The switch's callouts follow the
  situation: riding calls out every key; ringing and on a call only the keys that do something, the call key as
  a brand chip with its hold on a second line ("Answer · Hold to decline"). A key whose action can never work here
  (the microphone Android does not let the app change) gets a footnote saying so.
  The chooser (`HandlebarPressRoute(switch, situation)`) lists `allowedActions(situation)` under Call, Music,
  Sound, Voice and Screen headings, Nothing last, the chosen one in brand, with a line on what each situation
  leaves out. The intercom's assistant button offers Siri too (through our CarPlay, as a press). Reset to default (`HlSecondaryButton`) only
  opens a "Reset all buttons?" dialog (`HandlebarResetRoute`, Paper "Handlebar 7"); its Reset puts all three
  layers back.

## Milestones

Each milestone is small, ends with a commit on `v2`, and has a check that proves it is done.
No milestone starts before the previous one is done.

| # | What | Done when |
|---|---|---|
| **M0** ✅ | Empty project, set up properly: `build-logic`, version catalog, the five modules, quality gates (format, detekt, lint, explicit API, module boundaries), CI, a blank screen. | `./gradlew check` passes locally and in CI; the blank app installs on the Poco. |
| **M1** ✅ | `:bike:protocol`: the meter codec, written from `SAB_PROTOCOL.md`. | Unit tests cover every frame, reply and code verified on the CB350. |
| **M2** | `:bike:link` (pure Kotlin engine): GATT transport seam, scripted fake meter, the connection state machine and its 16 required tests; `:bike:link-android`: the real Android transport and CTS server. | All tests pass on virtual time, incl. a randomised test with invariants. |
| **M3** | `:app` ride service: logging and diagnostics, button routing to CarPlay (interim: CarLink's media session, as in the prototype). | Same behaviour as the prototype on the bike, checked with the on-device checklist. |
| **M4** | Auto-start on ignition: pairing through the companion-device dialog, presence service, boot and update receivers. Steps: M4.1 setup ✅, M4.2 presence observing and service ✅, M4.3 update and restart recovery ✅ (built; the on-bike check in "Auto-start on ignition" is open). | Ignition on → connected with the phone screen off and the app not open, timed on the bike (02 §6.5 D4/D5). |
| **M5** | UI: tokens and components in `:core:designsystem`; setup, status, button mapping, diagnostics and settings screens. **Moved before M3.3** at the owner's request (2026-11-17), because the design is ready: M3.3's screens are built from it. Steps: UI.1 tokens and themes ✅, UI.2 components ✅, UI.3 pages and navigation ✅, UI.4 screenshot tests ✅. | Screenshot tests for every component and screen in both themes; glove and sunlight check on the bike. |
| **C** | CarPlay receiver, started 2026-11-18 at the owner's request: see "CarPlay receiver" below. | Per step below. |

## CarPlay receiver

Our own wireless CarPlay receiver in the app (replacing CarLink), started 2026-11-18 at the owner's request.
The owner's MFi identity comes from their old CarPlay display (`~/.carlink-private/offline-mfi`, never in git).

### BikePlay on screen (owner decision, 2026-11-20: "rename CarPlay to BikePlay, properly so it's clearer")

- **On screen our feature is BikePlay:** the tab, Home's card, the hero's titles and statuses ("BikePlay is running",
  "Starting BikePlay", "BikePlay needs your OK"), the buttons (Open BikePlay, Start BikePlay), the "BikePlay screen"
  row, sheet and display, the iPhone rows and sheet, pairing's "BikePlay connects to it…", the ride notification's
  "Handlebar controls BikePlay", setup's welcome, Settings › Permissions' "BikePlay" row and the overlay's reason, the
  handlebar actions (Open BikePlay, Skips in BikePlay…) and the Devices tab's "BikePlay plays through the intercom".
  The tab's empty hero introduces it: "BikePlay shows your iPhone's CarPlay on the bike." over "Pair it once here; it
  then connects by itself on every ride."
- **CarPlay stays only for Apple's side on the iPhone,** so the words match what the rider sees there and what the
  iPhone checks: "Checking CarPlay" (the iPhone's CarPlay check, step 2 of 4), "Loading the CarPlay identity", "Proving
  it is a CarPlay receiver", "CarPlay identity files are missing", "CarPlay authentication failed", "The iPhone didn't
  start CarPlay", and "your iPhone's CarPlay" in the introduction.
- **Text only:** code identifiers, packages, modules, routes, string resource names, log keys and the engine keep
  CarPlay; these docs keep CarPlay for the receiver and the protocol.

### Home's BikePlay card, design B "Instrument" (owner's choice, 2026-11-20; branch `polish`)

Paper page "BikePlay card": the owner chose design B "Instrument" with **a button only where the rider must or can
act**, and **the status shown once** (the meter, its state word and one line are the whole status: no tag, pill or
second line repeating it).
- **Design system:** `HlMeter` (`component/Instrument.kt`), the reusable meter the BikePlay tab's hero will use next: a
  value arc over a tick arc in three geometries from the artboards (`MeterGeometry` in `theme/HlInstrument.kt`: COMPACT
  196 dp beside a button, WIDE 316 dp without one, RETRY 236 dp while a retry counts down), values `Empty` (dim),
  `Full`, `Segments` (link quality) and `Progress` (a step, or a draining countdown moving linearly between the
  once-a-second updates) with the knob cut out of the card, tones GOOD (lime) and DANGER. `HlInstrumentCard`: the
  corner mark and "BikePlay", the chevron, the meter with the state word (24 sp, 30 sp without a button) and the detail
  (14 sp, 15 sp), the readings on its base (Connected), at most one 52 dp full-width button, lime for everything the rider
  must or can do (owner, 2026-11-20: the fixes and Try now too; only dismissing is a secondary button); 208 dp tall in every state (a minimum: larger text grows it downwards), full width; Night's glass
  (`instrument` rgb(18 20 14 / 78%)), Bento's ink card; the edge danger at 32 % in danger states. New tokens:
  `color-instrument`, `color-instrument-solid` (the knob's ring), `color-instrument-shadow`, `shadow-instrument`,
  `size-instrument`, `size-meter`, `size-meter-wide`, `size-meter-retry`, `size-meter-foot` (TokenDriftTest); icons
  `BikePlayMark`, `Play`, `PlusBold` (path data from the artboards). Previews and screenshot tests for the nine states
  in both looks (`InstrumentSamples`). Night's backdrop blur is not drawn: the card sits on the plain ground, never over
  the bike.
- **States** (`ui/carplay/BikePlayCard.kt`, built from the tab's `carPlayHero`, so the two never disagree): 1 Connected
  (all segments lit by `linkQuality`: Strong 3, Fair 2, Weak 1, all three before a verdict; the iPhone's name; "Strong"
  over "Link" and "5 GHz" over "Ch 36"; Open BikePlay) · 2 Starting (step 1 of 4: the arc at a quarter, "Calling your
  iPhone") · 3 Connecting (steps 2–4, the step's words; the card says "Waiting for the iPhone to join" for step 3 as
  Paper does) · 4 Not set up (empty arc, "BikePlay shows your iPhone's CarPlay on the bike. Pair it once to start." in two
  balanced lines up to 300 dp, Add iPhone) · 5–7 Needs you (danger arc, the hero's reason, the hero's fix: Turn on
  Wi-Fi, Turn on Bluetooth, Allow or Open settings, Choose iPhone) · 8 Couldn't connect (a retry the engine runs by itself: the danger arc draining with the pause,
  `pauseLeft` from `CarPlayState.Recovering.failedAt`, the status line's "<what failed> · Retrying in N s", e.g.
  "iPhone didn't answer · Retrying in 8 s", Try now) ·
  9 BikePlay off (no ride: "Starts again when you ride"; stopped by the rider: "Stopped until you start it", since a
  hold outlives rides; Start BikePlay). A tap outside the button opens the BikePlay tab.
- **Buttons are the tab's** (`rememberTabActions`, `ui/carplay/TabActions.kt`, now shared by the tab and Home through
  `BikePlayCommands`): pairing and the iPhone sheet open over Home; Open BikePlay opens the display on the BikePlay tab.
- **Try now** is a new engine command, `CarPlayLink.retryNow()` (non-suspending, queued to the runtime's dispatcher):
  while started and pausing before a timed retry it cancels the pause and starts the next attempt at once; it does
  nothing before a start, after a stop, while an attempt runs or while waiting for Wi-Fi (CarPlayRetryTest, every
  planted bug caught). `Recovering` gained `failedAt` for the countdown's whole length.
- **Placement:** Home Night upright: the tiles, the card 10 dp under them, the Handlebar card 10 dp under it (Paper
  "Home (Night)"); Bento: where the CarPlay card was ("Home (Bento)"); on its side first in the cards pane, the pane 1.6
  x the bike's ("Landscape Home (Night)"). `SignalCard` is no longer on Home.

### Our CarPlay replaces CarLink (owner decision, 2026-11-20; worktree `carplay-only-app`)

The owner: "Remove the text beta instead of carlink, we are replacing carlink, that's the goal, so no need of toggle
etc, it should be used." **Make our CarPlay the default instead of CarLink: done ✅ (2026-11-20).**
- **No switch.** The "Handlebar Link CarPlay · Beta · instead of CarLink" row, its DataStore key `carplay_beta`,
  `CarPlayStatus.Off`, the CarLink hero (`TabPhase.CARLINK`, `TabAction.TURN_ON_BETA`) and "Runs in CarLink for now"
  are gone. `CarPlayCoordinator` runs the engine in every ride while CarPlay's permissions are granted and the rider
  has not stopped it (Cancel, Disconnect). Settings › Permissions always lists CarPlay's row ("CarPlay"), and Allow all
  asks for it. A stored `carplay_beta` from an older build is ignored.
- **When the ride starts** (`RideAutoStart`, `startsRideOnResume`): as before when the bike appears (companion-device
  presence, from the background) or the app comes to the front with a bike set up; and now also when the app comes to
  the front with an iPhone paired, all of CarPlay's permissions granted and CarPlay not stopped by the rider, bike or
  not (started from the visible activity, so the foreground-service start is allowed; a stopped CarPlay would end such
  a ride at once, so none is started for it). This replaces the switch's own `RideService.start`.
- **Start CarPlay** is the hero's action for Ready (no ride), Stopped by the rider and, new, "CarPlay stopped" after an
  error (`CarPlayCoordinator.start`: lifts the hold and, after a failure, starts the engine again in the running ride).
- **CarLink paths removed:** `MediaSessionControlTarget` and `CARLINK_PACKAGES` (and the manifest `<queries>` for
  `com.carlink.app(.hudtest)`), the notification listener `MediaAccessListener` (its manifest entry, the
  `BIND_NOTIFICATION_LISTENER_SERVICE` guard and Settings' "Notification read, reply & control" row, which existed only
  to reach CarLink's media session), `RoutedControlTarget`'s CarLink branch (presses go to our CarPlay; not connected,
  a press does nothing and is logged), `RoutedCallSituations`' audio source (`AudioCallSituations`), `NEEDS_OUR_CARPLAY`
  and `NO_MEDIA_ACCESS` with the Handlebar page's "Soon" tags and CarLink notices, and the CarLink call route:
  `VoipCallWatcher`, `IntercomCallRoute` (the held self-managed Telecom call), its call notification and the
  `phoneCall` service type, `MANAGE_OWN_CALLS`, `FOREGROUND_SERVICE_PHONE_CALL` and the `core-telecom` dependency use.
  Each was used only for CarLink (callers checked one by one); our CarPlay's calls route themselves through
  `CallAudio` (`setCommunicationDevice`, :carplay:engine-android) and never used them. `MODIFY_AUDIO_SETTINGS` stays
  (the handlebar's microphone mute).
- **Open CarPlay and Switch screen** from the handlebar (`ScreenSwitcher`) show our CarPlay display: they start
  `MainActivity` with `EXTRA_SCREEN` (and the ride's lock-screen flags), and the tabs' navigation opens
  `CarPlayDisplayRoute` on the CarPlay tab (`Navigator.openCarPlayDisplay`; Switch screen goes back from it when it is
  shown, `Navigator.switchScreen`); with the app not in front, Switch screen brings the app as before.
- **Test hooks:** MainActivity's `skip_setup` and `start_ride` extras act only in debug builds (`BuildConfig.DEBUG`;
  the launcher activity is exported), and `onNewIntent` calls `setIntent` (android-intent-security).

**Source of truth for bytes:** DiPlay's working receiver (github.com/shihabal3amri/DiPlay, read through
CarLink's `vendor/diplay` on `upstream/main`, which carries four DiPlay hardening PRs). The specs with
file:line citations are in `docs/research/carplay-spec/` (README there: provenance and the PRs we follow).
The earlier summaries `research/06-*.md` are background only: the first WIP engine was written from them and
was wrong in almost every byte (docs/LESSONS.md 46).

**Performance rules (owner: "native, GPU, fast"):**
- Video: MediaCodec hardware decoder rendering straight into a `SurfaceView` surface (hardware composer
  overlay, no GPU copy as with `TextureView`), `KEY_LOW_LATENCY` (API 30+, always on our minSdk 31), H.264 and
  HEVC; no software decoding, no frame copies between socket and decoder beyond the decrypt.
- Crypto per frame: ChaCha20-Poly1305 through the platform JCA (Conscrypt/BoringSSL, native from API 28),
  never BouncyCastle; decrypt into reused buffers. BouncyCastle only for X25519/Ed25519 at pairing (Android
  has them from API 33, we support 31).
- No NDK code unless a measured bottleneck needs it: decode, crypto and composition are already native.

**Permissions** (owner, 2026-11-19: whatever CarPlay needs; checked against the official docs
`kb://android/privacy-and-security/local-network-permission`, `…/connectivity/wifi/wifi-permissions` and
`…/wifi/wifi-direct`). Declared in `:app`'s manifest (C2 app part; `:carplay:engine-android` may declare its own, the
merged manifest keeps one of each), requested in the app's existing permission flow:

| Permission | Why | Kind |
|---|---|---|
| `INTERNET` | Any socket: the AirPlay listener, the iPhone's streams. The Poco has no internet connection; nothing leaves the bike. | normal |
| `ACCESS_LOCAL_NETWORK` | Android 17 blocks the local network for apps targeting SDK 37 (ours) unless granted; covers inbound connections from the iPhone and mDNS. | runtime (API 37+), *Nearby devices* group |
| `NEARBY_WIFI_DEVICES` with `neverForLocation` | `WifiP2pManager.createGroup()` and group info on API 33+. | runtime, *Nearby devices* group |
| `ACCESS_FINE_LOCATION` with `ACCESS_COARSE_LOCATION` (declared by the app, `maxSdkVersion="32"` since ride logging was dropped 2026-11-20) | The same Wi-Fi Direct calls on Android 12/12L (API 31-32). Asked together (2026-11-20): "If you try to request only ACCESS_FINE_LOCATION, the system ignores the request on some releases of Android 12" (location permissions guide); precise must be chosen. Location *services* need not be on: the Wi-Fi Direct guide asks for Location Mode only for `discoverPeers`, `discoverServices` and `requestPeers`, which the port never calls (it only creates and reads its own group). **Limit (12/12L only):** the group is created only while the app is in the foreground (review of AOSP android12, 2026-11-20: Wi-Fi Direct checks the fine-location app op, which a background `connectedDevice` service does not hold; unverified on a device), so a ride woken on ignition with the screen off retries until the rider opens the app. A while-visible `location` service type would lift it; not added (Android 12 only, the Poco runs 17). | runtime |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Wi-Fi Direct (required by the Wi-Fi Direct guide). | normal |
| ~~`ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`~~ | Dropped in C2: mDNS goes through `NsdManager`, which needs only `INTERNET` (AOSP `NsdService.connect`, Connectivity main :2310) and advertises on the group interface itself; no socket binds to a `Network`. | — |
| `BLUETOOTH_SCAN` (`neverForLocation`, already declared by the app) | `cancelDiscovery()` before the RFCOMM dial when granted ("Connect Bluetooth devices"); the app's pairing flow runs a classic discovery. Not required for CarPlay. | runtime |
| `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` (already declared) | RFCOMM dial to the iPhone; CarPlay runs in RideService. | runtime / normal |
| `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE` | Siri's microphone (C5). | runtime / normal |

The *Nearby devices* group already holds Bluetooth, which the app requests; Android then grants local network and
nearby Wi-Fi without another prompt (local-network-permission guide, "Pre-granted scenario").

| Step | What | Done when |
|---|---|---|
| **C1** ✅ (2026-11-18, uncommitted in worktree `carplay-base`) | `:carplay:protocol`: iAP2 link (marker, SYN, checksums, ACK/EAK, retransmission) and control messages; TLV8, SRP-6a pair-setup, pair-verify, control cipher, MFi auth-setup; binary plist; RTSP. `:carplay:engine`: runtime (generations, jittered backoff, cleanup), Bluetooth bootstrap, AirPlay control connection to pair-verify and auth-setup. | 104 protocol + 19 engine tests against RFC vectors, DiPlay test literals, plistlib, BouncyCastle's SRP client and Apple's HomeKitADK SRP proof; independent review 2026-11-19 (docs/LESSONS.md 53-55), every planted bug caught; `check` green. |
| **C2** | `:carplay:engine-android`: RFCOMM dial (secure, UUID `00000000-deca-fade-deca-deafdecacafe`), Wi-Fi Direct group (5 GHz, stable name/passphrase), AirPlay `ServerSocket`, mDNS on the group interface, identity and pairing storage, MFi files injected at build time; RideService runs CarPlay next to the bike link. Permissions (decided 2026-11-19, see below) in its manifest. Connection design and device checks: `research/carplay-spec/E-connection.md` (engine items E1-E9 built 2026-11-19: StartSession nudge, Bonjour off the critical path, Bluetooth-on retry, quick dial retries, IPv4 first, fail-fast link watch; E6 deferred to P4/P5). | On the Poco with the owner's iPhone: state reaches Connected (pair-verify and auth-setup pass) and the log shows each step. App part: see "CarPlay in the app" below. |
| **C3** | Complete `/info` (audio formats and latencies, HID devices with descriptors, icons), session SETUP (event channel, NTP timing, keep-alive), stream SETUP, RECORD, `/command`, `/feedback`. | The iPhone keeps the session and sends video. |
| **C4** | Video and touch: screen stream (128-byte header, per-frame ChaCha20), MediaCodec → SurfaceView, touch HID over the event channel. | CarPlay on the Poco, measured latency and frame rate on a release build. |
| **C5** | Audio (AudioTrack, AAC via MediaCodec), Siri microphone, handlebar keys as CarPlay HID (replaces CarLink's media session route), iAP2 tunnel hand-over (type 130). | Ride test: music, navigation prompts, Siri, handlebar controls. |

**C2 `:carplay:engine-android`** (2026-11-19; design and evidence in `docs/research/carplay-spec/E-connection.md`, its
device checks P1–P12 list the log lines this module writes):
- Factory `createAndroidCarPlayPorts(context, time, logger)`; it only constructs objects (main-thread safe, lesson 60).
  The app binds `CarPlayPortsSource { createAndroidCarPlayPorts(context, time, logger.child("carplay")) }` and asks
  for `CarPlayPermissions.runtime()`. `CarPlayIphone.choose(context, address)` stores the rider's choice of iPhone.
- **MFi files at build time:** the Gradle property or `local.properties` key `carplay.mfi.dir` names a directory with
  `identity.pk8` and `certificate.p7b` (the owner's: `carplay.mfi.dir=~/.carlink-private/offline-mfi`; `~/` is
  expanded). `hl.carplay.mfi` copies them into generated assets under
  `carplay/engine-android/build/generated/carplay-mfi/assets/offline-mfi/` (never `src/`, never git; `*.pk8`/`*.p7b`
  stay ignored). Without the key the build works and CarPlay reports `IdentityUnavailable`; a key pointing at a
  directory without both files fails the build.
- Stored: the AirPlay identity and up to 16 pairings (oldest evicted) in `noBackupFilesDir/carplay/`; the rider's iPhone,
  one Wi-Fi Direct name/passphrase per iPhone and the channel that last reached Connected in the `carplay_android`
  preferences (the app sets `allowBackup=false`).
- Ids: Android hides this phone's Bluetooth and interface MACs from apps, so identification and `deviceID` use the id
  DiPlay derives from the AirPlay identity (`DiPlayBootstrap.deviceId`), which is what CarLink sends on the Poco.

### CarPlay in the app (C2, app part, 2026-11-19)

Code: `app/.../carplay/` (coordinator, settings, permissions, seam), `ui/carplay/` (page, status line), `di/CarPlayModule`.

- **Seam to the Android ports:** `fun interface CarPlayPortsSource { fun create(): CarPlayPorts? }`
  (`carplay/CarPlayPortsSource.kt`), bound in `di/CarPlayModule.carPlayPortsSource()` to a placeholder returning null
  until `:carplay:engine-android` exists; merging it changes only that binding. `create()` is called once when Hilt
  first builds the link, possibly on the main thread (a view model builds the coordinators), so it only constructs
  objects (docs/LESSONS.md 60). Null: CarPlay shows "Not in this version of the app yet" and never runs.
- **Engine:** `CarPlayLink?` is a `@Singleton` from `createCarPlayLink(...)` on its own single-threaded dispatcher
  (`@CarPlayDispatcher`, `limitedParallelism(1, "carplay")`, separate from the bike link's), the app's
  `TimeProvider` and `Logger` (`child("carplay")`), `Random.Default` (as the bike engine), and `CarPlayConfig()`.
- ~~**Beta switch:** "Handlebar Link CarPlay (beta)", key `carplay_beta`, default off.~~ Removed 2026-11-20 (see "Our
  CarPlay replaces CarLink"): CarPlay runs in every ride.
- **Coordinator** (`CarPlayCoordinator`, a `RideCompanion`): `RideService` passes it to `RideCoordinator.ride(presentHint,
  beside)`, which runs it in its own child job beside the bike link (a parameter, not a seventh constructor argument: detekt LongParameterList).
  While the permissions are granted, ports exist and the rider has not stopped it, it runs `CarPlayLink.run()` in a
  child job and calls `start()`; a permission withdrawn or the rider's Cancel/Disconnect (`hold`) calls `stop()` and
  cancels that job, which releases the radio, Bluetooth and sockets. Its `run()` never throws: any failure (also a
  `CancellationException` that is not our own cancellation, docs/LESSONS.md 54) is logged as `carplay.failed` and
  shown as "Stopped after an error" until Start CarPlay (`start()`, which starts the engine again in the same ride);
  the bike link is untouched. Ending a ride (`RideCoordinator.stop`) stops CarPlay too.
- **keepAlive:** the ride (and so `RideService`: `connectedDevice`, whose prerequisite `CHANGE_WIFI_STATE` and
  `BLUETOOTH_CONNECT` both meet, plus `microphone` while CarPlay runs, see "C5 audio") lasts while the bike link **or**
  CarPlay needs it. CarPlay
  counts from the start of its run until it knows it will not start, while it is meant to run, and until the
  engine's own `keepAlive` ends after a stop.
- **Permissions:** `carPlayPermissions(sdk)`: `BLUETOOTH_CONNECT`; `NEARBY_WIFI_DEVICES` on API 33+ (else
  `ACCESS_FINE_LOCATION` with `ACCESS_COARSE_LOCATION`); `ACCESS_LOCAL_NETWORK` on API 37+; `RECORD_AUDIO`. The engine has no permission error on this base, so a
  missing one is app-side (`CarPlayStatus.NeedsPermission`), checked fresh on every resume and permission answer. The
  line names what is missing (`permissionLine`): "Needs Nearby devices: allow it in Settings › Permissions", "Allow
  the microphone for Siri and calls" (RECORD_AUDIO only), or both; the hero likewise ("Allow the microphone"). Asked through the existing flow: a "CarPlay"
  row on the Permissions page and in Allow all (always shown since 2026-11-20), and the dialog before Start CarPlay.
  `ACCESS_NETWORK_STATE` and `CHANGE_WIFI_MULTICAST_STATE` are left to the
  Android ports' NSD-or-raw-mDNS choice.
- **UI:** the CarPlay tab (was an empty page; Paper "CarPlay — flow v2" without the switch row since 2026-11-20): the
  hero (its state tag is the design system's `HlTag` with a tone and its 6 dp dot; the title is a polite TalkBack live
  region, so the hero's changes are announced, not the ticking detail), then the status rows with `HlRowIcon` tiles
  (iPhone, Link, CarPlay screen). Home shows the BikePlay card (design B, see "Home's BikePlay card"). Status line: Starts with the next ride (no
  ride); Starting; "Connecting · <step>"; Connected; "<error> · Retrying in N s" (ticking each second); the error alone
  when only the rider can fix it. While CarPlay starts or runs, the hero follows it even when the app's own choice of
  iPhone names none (two paired, none chosen: the engine dials the one with the live link), so Open CarPlay stays.
  Bluetooth unavailable offers Turn on Bluetooth only while Bluetooth is off; with it on, the engine found no iPhone it
  may use, so the hero says "Choose your iPhone" with Choose iPhone (read only then, `SetupConditions.bluetoothOn`).

### C4 video: the screen decoder (2026-11-19, worktree `carplay-c4-video`)

Code: `carplay/engine-android/.../video/`. Standalone behind `ScreenDecoder` (`createScreenDecoder(logger, listener,
recycler, options)`; `configure(width, height, fps)`, `attach(surface)`, `detach()`, `submit(frame, offset, length,
ptsMicros, isConfig)`, `release()`; `ScreenSurface.bind(surfaceView, decoder)`). The coordinator feeds it C3's frames
(`avcC` config, then length-prefixed pictures) and wires `Listener.onKeyFrameNeeded` to the session's keyframe request.
- **Decoder choice:** hardware only, never an alias or a secure-only decoder, those advertising `FEATURE_LowLatency`
  first, then `MediaCodecList` order (DiPlay `lowLatencyDecoderName`). Tuned format: `KEY_LOW_LATENCY` where advertised,
  `KEY_PRIORITY` 0, `KEY_OPERATING_RATE` = fps, Qualcomm's `vendor.qti-ext-dec-low-latency.enable` and
  `…picture-order.enable` only when `getSupportedVendorParameters` lists them; a refused format falls back to CSD and
  input size only (DiPlay `videoDecoderAttempts`). DiPlay turns those keys on only with its experimental smooth video;
  here they are always on (the Qualcomm decoder otherwise holds about two frames, DiPlay SMOOTH_WIRELESS.md §3).
- **Render at once:** async `MediaCodec` on a `HandlerThread` at display priority; each output is released with
  `releaseOutputBuffer(index, now)`, so the `SurfaceView` shows it at the next VSYNC and drops older ones on the same
  VSYNC. No pacing on the iPhone's timestamps (DiPlay measured smoother cadence with it, at a display delay of about
  100 ms; not worth the touch latency on a bike; revisit with device numbers).
- **Prewarm:** the decoder is created, configured and started at `configure()` (on a decoder with
  `FEATURE_DetachedSurface`, configured detached, `CONFIGURE_FLAG_DETACHED_SURFACE`), otherwise at `attach()`, without
  CSD; the first config then goes in as one `BUFFER_FLAG_CODEC_CONFIG` buffer with SPS and PPS concatenated (MediaCodec
  reference, "Codec-specific Data"). A refused detached start waits for the surface without counting as a failure.
  Why: on the Poco (2026-11-19) a cold start held the decoder thread about 200 ms (list 100 ms, create and configure
  94 ms), the queue limit dropped the first IDR and the screen stayed black for a second. `carplay.video.decoder` logs
  `start_ms`, `prewarmed` and `surface`; `carplay.video.candidates` logs `list_ms`.
- **Backpressure:** the latency limit is the age of the oldest waiting picture, not a count: 250 ms while the decoder
  runs, 1.5 s while it starts and drains what waited meanwhile (until the queue is down to one picture); 60 pictures and
  8 MiB are memory guards (DiPlay `VideoBacklogRecovery` soft 250 ms / hard 1.5 s; `VideoDecodeQueue` 60 frames, 8 MiB,
  "short delayed bursts keep their reference chain"). Over a limit, all waiting pictures are dropped and the decoder waits
  for a keyframe; a keyframe drops the pictures waiting before it; a burst after a Wi-Fi stall is young and is kept.
  Device run 2 (2026-11-19) showed why: a 6-picture limit applied at the first output broke the chain while 14 pictures
  from the start drained. `submit` never waits. Keyframes are asked for at most once a second.
- **Recovery:** any error releases the decoder and starts a new one from the configuration; two failures before a first
  output move to the next candidate; four failures in 10 s, or none left, wait 1 s. Surface loss releases the decoder,
  except on decoders with `FEATURE_DetachedSurface` (API 35), which keep decoding; `SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT`
  (API 34) keeps the surface while the view is hidden.
- **Metrics:** `carplay.video.stats` every 5 s (received, decoded, shown, rendered, fps, drops by reason, decode and
  render p50/p90 ms from submit, queue_max, recoveries, keyframe_requests), plus `carplay.video.decoder`,
  `…vendor_parameters`, `…first_output`, `…first_frame`, `…decoder_error`, `…keyframe_request`, `…surface`.
- **Device check (coordinator):** `ScreenDecoderSmokeTest` (instrumented) plays an ffmpeg clip at 60 fps on the Poco's
  decoder; test data and commands in `carplay/engine-android/src/test/resources/video/README.md`.

### C4 picture and touch (2026-11-19, worktree `carplay-picture`)

The iPhone's CarPlay picture on the Poco, full screen, touchable. Code: `spi/Ports.kt` (`ScreenPort`),
`session/MediaReceivers.kt` (`ScreenReceiver`), `session/TouchInput.kt`, `session/HidDevices.kt`;
`:carplay:engine-android` `CarPlayScreen.kt`, `video/AndroidScreenPort.kt`, `touch/CarPlayTouch.kt`; `:app`
`carplay/CarPlayCanvas.kt`, `ui/carplay/CarPlayDisplay*.kt`.
- **Engine to decoder: a port.** `ScreenPort` in `CarPlayPorts` (`screen`, default `ScreenPort.NONE`): `configure(w, h,
  fps)` at the main screen's stream SETUP (the canvas `/info` reported, so the decoder prewarms before the first frame),
  `frame(isConfig, senderNanos, payload)` for each message of stream 110 in order (never blocks: `ScreenDecoder.submit`
  only queues), and `keyFrameRequests: Flow<Unit>`, collected while the screen stream runs; each emission sends
  `{type: forceKeyFrame}` (DiPlay CarPlayMediaEngine.kt:146-157, no params for the main screen). Frames of another screen
  are read and dropped (`/info` declares none).
- **Framing.** C3's reader hands over the `avcC` as sent and pictures already in Annex B. `AndroidScreenPort` turns an
  `avcC` with 4-byte lengths into its SPS and PPS behind start codes, so the decoder reads the pictures as the Annex B
  they are instead of guessing whether lengths tile them (docs/LESSONS.md 71); any other record goes in unchanged.
- **Decoder lifetime.** `createCarPlayScreen(logger)` (Hilt `@Singleton`) owns the one `ScreenDecoder`; the ports get its
  port, the display calls `CarPlayScreen.show(surfaceView)` (`ScreenSurface`). Leaving the display detaches the surface
  (decoders with `FEATURE_DetachedSurface` keep decoding); reattaching asks for a key frame (`carplay.video.surface`,
  `carplay.video.keyframe_request reason=surface attached`).
- **Canvas (superseded 2026-11-19 by "CarPlay picture: DiPlay's negotiation" below).** `CarPlayCanvas` measures the default
  display's largest window through a window context (`createWindowContext(display, TYPE_APPLICATION)`,
  `maximumWindowMetrics`; Media projection guide), `DisplayMetrics.xdpi`, the refresh rate and the cutout, once, when
  the engine first asks: `createCarPlayLink` takes `config: () -> CarPlayConfig`, read at the start of each attempt on
  the engine's dispatcher (no Binder call while Hilt builds, docs/LESSONS.md 60). Landscape: width = long side (less
  the cutout strip), height = short side; portrait: width = short side, height = long side; each even (DiPlay
  `evenDisplaySize`), scaled down above 3840 x 2160 (DiPlay's canvas limit); millimetres = width / xdpi x 25.4,
  clamped 1..2000 (a density outside 100..1000 dpi falls back to `densityDpi`); fps = refresh rate in 30..60.
- **Touch.** `CarPlayLink.touch(contacts)` (non-suspending, any thread) queues `TouchContact(pointerId, x, y, down)` with
  x and y as 0..1 fractions of the picture; `CarPlayTouch.contacts(event, w, h)` builds them from a `MotionEvent` (DiPlay
  CarPlayTouchMapper.kt:14-33). The session with the event channel sends each as `{type: hidSendReport, uuid: 2a2a2a2a,
  hidReport}` (AirPlaySession.kt:351-370, 444-451): two slots kept per finger (AirPlayTouchSlots.kt:14-34), positions
  times the canvas, rounded half up, u16 little-endian (AirPlayHid.kt:44-55). The queue holds 128 touches and drops the
  oldest; touches made before an event channel connects are dropped (`carplay.touch.stale_dropped`), never sent late.
  Logs: `carplay.touch.first`, `carplay.touch.dropped` (once), `carplay.touch.ended sent= failed=`.
- **App.** "Open CarPlay" (the hero's primary, a full-width brand `HlButton`, 52 dp) on the CarPlay tab while connected
  pushes `CarPlayDisplayRoute` on the tab's
  stack (a full page, no tab bar). The display hides the system bars (immersive, transient by swipe), locks the screen
  to the canvas's orientation (see "CarPlay screen"), runs in the cutout (set explicitly below API 35; a landscape
  picture pads itself out of it), keeps the screen on, shows the status line over the last picture while not
  connected, and the first time only the "‹ Swipe back for Handlebar Link" hint for 3 s (`CarPlayDisplayHint`, DataStore
  key `carplay_display_hint_shown`). Back pops to the tab; CarPlay keeps running.
- **Screenshots:** the beta note changed ("shows CarPlay full screen"), so `CarPlayConnectingNight/Bento` need new
  references from Linux CI (`[record screenshots]`); no new screenshot tests (references are recorded on CI only).
- **Device check (coordinator, release build):** open the app (with an iPhone paired the ride starts by itself;
  `--ez start_ride true` on a debug build away from the bike), connect the iPhone, wait for "Connected", tap "Open CarPlay". Expect in the log: `carplay.config width=2246
  height=1080 width_mm=141 fps=60` (landscape, less any cutout strip; portrait: 1080 x 2246, 68 mm) and `carplay.canvas`, then per session `carplay.screen.configure`,
  `carplay.video.display`, `carplay.video.decoder … prewarmed=true`, `carplay.screen.receiving`,
  `carplay.video.first_output`, `carplay.video.first_frame`, `carplay.screen.force_key_frame sent=true` after each
  `carplay.video.keyframe_request`, `carplay.video.stats` every 5 s; on the first tap `carplay.touch.first contacts=1`.
  Back to the tab and in again: `carplay.video.surface state=detached decoder=kept`, then `state=attached` and a key
  frame request. Measure frame rate and latency from `carplay.video.stats` (C4's "done when").


### CarPlay screen and the microphone (2026-11-19, owner decisions, worktree `carplay-c5`)

- **CarPlay screen: Landscape (default) / Portrait.** `CarPlaySettings.orientation` (DataStore key
  `carplay_orientation`, stored by name, an unknown name reads Landscape), a row on the CarPlay
  tab ("CarPlay screen", the phone icon turned the way the picture stands) opening a sheet with the design system's
  option rows (`CanvasSheets.kt`, laid out as the Handlebar picker) and the footnote "If CarPlay is running, it
  reconnects to apply it."
- **Canvas (superseded by "CarPlay picture: DiPlay's negotiation" below; the orientation setting and the notch rule
  stay).** `carPlayCanvas(…, orientation, cutout)`: landscape reports the long side as width. The Poco
  (1080 x 2246, 403.4 dpi) reports **2246 x 1080 at 141 mm** without a cutout report; with one, the strip is taken off
  the width (an 89 px notch would give 2156 x 1080 at 136 mm). The real cutout depth is measured on the phone
  (`carplay.screen cutout=`), UNVERIFIED until a device run. Portrait stays 1080 x 2246 at 68 mm.
- **Reconnect on change.** The coordinator gives the canvas the orientation (`CanvasOrientation.use`) before each start;
  a new orientation while CarPlay runs stops the engine, joins its run and starts it again (`carplay.restart
  orientation=`), keeping the ride's keep-alive held across it. Off or held, it applies at the next start.
- **Display.** `CarPlayDisplay` sets `Activity.requestedOrientation` to `userLandscape`/`userPortrait` while shown
  (adaptive cookbook "App orientation restricted on phones"; Android 17 ignores it only on screens 600 dp and wider) and
  restores the app's own value on leaving, remembered across the re-creation the turn causes and never restored while
  only re-creating (`isChangingConfigurations`). The picture keeps the reported canvas's aspect ratio (`DisplayShape`);
  touches are fractions of that view (`CarPlayTouch`), which the engine scales to the canvas in either orientation.
- **The notch, decided:** a landscape picture stays out of the cutout. Evidence: the cutout guide ("Support display
  cutouts", Compose) says the cutout must not obscure controls and touch sensitivity may be lower there; in landscape
  the notch sits on a short edge, where CarPlay puts its dock and status bar; DiAuto's projection offers "Immersive
  with notch" to keep the picture clear of it (`SystemUI.kt`, `fullscreen_immersive_avoid_notch_desc`). So the canvas
  leaves the strip out and the display pads by `WindowInsets.displayCutout`; `userLandscape` lets a phone mounted
  either way up show the picture upright, the padding following the notch. Portrait keeps C4's edge-to-edge picture.
- **The microphone is required (owner).** CarPlay does not run while any CarPlay permission is missing,
  `RECORD_AUDIO` included (`carPlayPermissions`, the coordinator's `missing.isEmpty()`). Start CarPlay opens "CarPlay
  needs your OK" (`CarPlayAccessRoute`): one line per missing permission ("Microphone: Siri and calls use
  the microphone."), then Android's dialog on Continue. Refused, the hero says NEEDS YOU with the permission's title
  ("Allow the microphone", "Allow Nearby devices", "Allow Local network", or "Allow CarPlay's permissions" naming them
  all) and one action: Allow while Android still asks, Open settings (`ACTION_APPLICATION_DETAILS_SETTINGS`) once it
  no longer does (`CarPlayHero.withAccess`). Whether it still asks is "asked before and no rationale"
  (android-permissions-security §6), decided per permission (`accessOf`, fixed 2026-11-20: a list counted as asked when
  any one of its permissions was, so CarPlay showed Open settings after the pairing had asked only for Bluetooth, before
  Android ever asked for the microphone); the asked set is shared in the process (`AskedPermissions`) so asking on one
  screen updates the others. The status line and Home's card name the missing permission too. The Permissions page's
  "CarPlay" row and Allow all ask `carPlayPermissions()`, which holds `RECORD_AUDIO` on every release.
- **Screenshots** (record on Linux CI, docs/LESSONS.md 26): changed `CarPlayNotSetUp*`, `CarPlayConnecting*`,
  `CarPlayConnected*`, `CarPlayWifiOff*` (Night and Bento: the new row; again 2026-11-20 without the switch row);
  `CarPlayOff*` removed 2026-11-20 for `CarPlayReady*` and `CarPlayFailed*`; new `CarPlayMicrophoneNight`,
  `CarPlayMicrophoneBento`, `CarPlayScreenSheetNight`, `CarPlayScreenSheetBento`.

### CarPlay size and dock side (owner, 2026-11-20)

- **Why.** The dock icons were small: iOS sizes CarPlay's controls physically from the screen size it is told
  (DiPlay CarPlaySize.kt:3-6) and we told it DiPlay's 300 mm while the Poco's landscape window is 136 mm, so every
  control showed at about 45 %. iOS 26's Smart Display Zoom (CarPlay Settings → Display) does not help here: off made
  them smaller still. No CarPlay setting or receiver key sizes the dock alone.
- **Measured on the Poco** (landscape, same iPhone): 300 mm — thin dock along the bottom, icons ~80 px; 200 mm — bigger
  home icons, the same dock; 150 and 136 mm — iOS switches layout: a tall dock on a side, icons ~120 px, map and widgets,
  clean; 120 mm — the dock overlapped the clock (too small). iOS changes layout at a few sizes
  (AirPlayDisplaySettings.kt:22-23).
- **Rule:** the widest window's real width (`realWidthMm`): its pixels over the lower of `DisplayMetrics` xdpi/ydpi
  (the bucket if either is more than twice off it), in 135..400 mm — 135 laid out cleanly, 120 did not; 400 is DiPlay's
  largest. It replaces DiPlay's fixed 300 mm reference, so CarPlay's controls come out at their real size on any phone.
  The Poco: `realMm=136` (2160 px at 403 ppi). Logged in `carplay.screen … dpi= realMm=`.
- **Dock on the right:** `/info` `rightHandDrive` true (DiPlay AirPlayInfoPlist.kt:46, `CarPlayConfig.rightHandDrive`):
  CarPlay docks on the driver's side. The owner wants it on the right (as cars in India are right-hand drive).
- **Open:** portrait at the real size (68 x 141 mm on the Poco) still needs a look on the device; if iOS lays it out
  badly, portrait gets its own floor.

### Review fixes, engine (2026-11-20, branch `carplay-engine-fixes`)

A seven-dimension review of 424a79a (each finding checked by an independent skeptic) found 51 issues; the engine's
are fixed with a test each, and every new test was proven by a planted bug (45 plants, all caught; LESSONS 111-115).
What changed in behaviour:
- **StartSession (0x4301) offers one address**, the group's IPv4, as DiPlay's Wi-Fi P2P path (CarPlayController.kt:1753,
  WifiP2pGroupManager.kt:676-688); it used to add the IPv6 link-local one, which DiPlay #1344/#242 tie to slow or broken
  iPhone networking. The listener still binds every address. The identity-derived deviceID is upper case, as
  DiPlayBootstrap writes it.
- **Wi-Fi off:** the engine waits for Wi-Fi to come on (`WifiDirectPort.wifiOn`) instead of retrying every ≤30 s and
  paging Bluetooth each time; it retries at once when it does.
- **Ends and cleanup:** an attempt's end is reported from its job's completion, after every earlier attempt has closed,
  so a quick start/stop can no longer leave the ride running; a cancelled write no longer closes a shared stream; each
  microphone and socket resource is released on its own; a failing accept on one address no longer ends the listener;
  a superseded connection's end no longer tears down the live session; a Wi-Fi Direct group still forming at cancel is
  removed.
- **Hand-over:** `disableBluetooth` counts only from the active (RECORDed) session; tunnel identification and
  authentication have DiPlay's 5-minute limits; a tunnel lost after Bluetooth is gone ends the attempt instead of
  falling back to a dead link.
- **Call audio:** a headset route counts only once Android reports it as the active communication device; an
  unconfirmed or lost route falls back to the loudspeaker; call microphones are counted per caller.
- **Performance:** pictures are decrypted into buffers the decoder returns (`PictureBuffers`, FrameRecycler); the screen
  reader runs at DISPLAY priority, the audio data socket at AUDIO priority with DiPlay's 512 KiB receive buffer; fewer
  copies in audio receive, the microphone uplink and the tunnel buffer (`ByteQueue`).
- The engine no longer publishes `CarPlayPermissions`; the app's list is the only one.
- Device checks still open: the iPhone's internet with the single address, the headset route confirmation
  (`carplay.audio.headset_confirmed`), the audio sockets' logged `rcvbuf`.

### CarPlay picture: DiPlay's negotiation (2026-11-19, worktree `carplay-c5`)

- **Why.** A/B on the Poco (same iPhone, Wi-Fi Direct and swipes): the iPhone sent us 2–5x the video data it sent
  CarLink (ours about 11 Mbit/s, peaks 30; CarLink 1.7–5.8 Mbit/s), slowing the iPhone's internet. CarLink declared
  DiPlay's turning-screen square (1920 x 1920 at 350 mm, six view areas, starting on 790 x 1644); we declared the raw
  panel at its real size (1080 x 2246 at 68 mm), so the iPhone laid CarPlay out for a 68 mm phone screen on 2.4 Mpx.
  DiPlay never measures the screen: it reports a reference width (300 mm, "CarPlay size" Medium).
- **What we declare now** (`carPlayCanvas`, each step cited in its KDoc; transcribed independently in
  `carplay/engine/src/test/resources/display/diplay_display.py`): DiPlay's square as CarLink turns it on for a phone
  (carlink-core CarPlayAdapter.kt:269-272). The Poco, no cutout: **1920 x 1920 at 300 x 300 mm, 60 fps, view areas
  [1920 x 922, 922 x 1920], initial 0 (landscape) or 1 (portrait), `viewAreaTransitionControl`**. The square side is the
  long side capped at 1920 (picture "Smoother"), the first of DiPlay's sides the screen decoder takes
  (`screenDecoderTakes`); none: DiPlay's plain canvas (the window, 300 mm across the widest window, one area).
- **Deviations from DiPlay, on purpose.** (1) The other orientation's window is known (the notch rule: landscape is the
  long side less the cutout strip, portrait the whole screen) instead of DiPlay's estimate for system bars that keep
  their edges (`turnedWindows`); identical without a cutout. (2) No CarLink size variants (its live interface-size
  switcher; we have none), so 2 areas, not CarLink's 6, and CarPlay starts on the full-size area: the same physical
  layout as CarLink's start area (144 x 300 mm portrait) at 922 x 1920 instead of 790 x 1644 pixels. (3) DiPlay's extra
  decoder-configure probe for squares a decoder does not list is left out: our decoder only takes listed sizes. (4) The
  frame rate stays the refresh rate (CarLink asks 60 of a decoder that carries it, PicturePolicy.kt:131); DiPlay's own
  default is 30 (AirPlayPersistence.loadFps). `maxFPS` is now snapped to the nearest 5 as DiPlay's `sanitizeFps` (it was
  floored, LESSONS 108).
- **View areas.** The iPhone never moves CarPlay between areas by itself; only the car does, with `updateViewArea` on the
  event channel (DiPlay docs/VIEW_AREAS.md). We do not send it: a change of the CarPlay screen setting still reconnects
  (a live turn would be `updateViewArea` plus the area change on the display; not built, not tested on a device).
- **Display.** The decoder is configured for the square (`screen.configure` at the stream SETUP). The `SurfaceView` is
  laid out as DiPlay's `contentRect` (`CarPlayPictureLayout`): the start area fills the window, scaled per axis, and the
  rest of the square lies right of or below the window, which crops it (DiPlay's SurfaceView bounds). Touches map
  through the same rectangle into canvas fractions (`CarPlayTouch.contacts(event, layout)`, CarPlayTouchMapper), which
  the engine scales to 1920 x 1920.
- **Safe areas (ours; owner 2026-11-20).** DiPlay's turning square drops the safe area (CarPlayHostActivity.kt:5124-5145),
  so CarLink and our first build drew the dock into the phone's rounded corners and under the swipe handle, and the
  top under the notch. Each area now declares a `safeArea` (with `drawUIOutsideSafeArea`, so wallpaper and maps still
  fill the screen) measured from the phone, never fixed numbers, so it suits any phone: `screenEdges` reads the largest
  window's insets (rounded corners, the cutout turned back to the upright phone, the navigation bar's strip ignoring
  visibility) and `safeInsets` keeps every edge r(1 - 1/sqrt 2) clear of the corners and, upright, the bottom clear of
  the navigation bar (CarPlay docks along it) and the top and bottom clear of the cutout; on its side CarPlay docks on a
  side edge, so the bottom clears only the corners (the owner found the navigation strip too much there); scaled to each area per axis, rounded up, under a
  quarter of a side. The navigation bar's strip, not the wider `mandatorySystemGestures` strip: the owner found that
  one too much room under the dock. The Poco: corners 104, cutout 86, navigation bar 63 → portrait 27,74,27,54 and
  landscape 28,28,28,28 (left, top, right, bottom, canvas pixels). Verified on the Poco with the iPhone: the dock and
  icons move in, the wallpaper still reaches the edges. Logged in `carplay.screen … corner= cutoutEnds= navigationBar=`
  and `carplay.picture … safe=`.
- **Log.** Each attempt: `carplay.picture display=2246x1080 negotiated=1920x1920 viewArea=0:1920x922+0+0
  areas=1920x922,922x1920 mm=300x300 fps=60 orientation=LANDSCAPE` (portrait: `display=1080x2246 … viewArea=1:922x1920+0+0`),
  and `carplay.config … view_areas=2 view_area=…`.
- **Device check (coordinator).** Release build, the same swipes for about 50 s on CarLink and on ours, both in
  portrait (CarLink's A/B ran portrait) and then ours in landscape; compare kbps from `DiPlay-VideoStats` (CarLink) and
  the bytes in `carplay.screen.frames` (ours, delta bytes x 8 / delta seconds), and the picture: filling the screen,
  undistorted, touches landing under the finger at all four edges, nothing of the other area visible.

### C5 controls: handlebar keys and calls through our CarPlay (2026-11-19, worktree `carplay-c5-controls`)

- **Engine API** (`CarPlayLink`): `press(key: CarPlayKey)`, `requestSiri()`, `calls: StateFlow<List<CarPlayCall>>`
  (`CarPlayCall(status: CarPlayCallStatus)`: SENDING 1, RINGING 2, CONNECTING 3, ACTIVE 4, HELD 5). Commands only
  queue (`ControlInput`, 16 deep); the session holding the event channel sends them, and requests made before the
  event channel connected are dropped (`carplay.hid.stale_dropped`), never sent to a later session (a stale answer).
- **HID bytes** (DiPlay `AirPlayHid.kt:70-72, 117-133`, `AirPlaySession.kt:380-388, 444-451`): one press is two
  `hidSendReport` commands, the one-byte report (the index into the device's usage array) then 0 (release). Media
  device `2a2a2a2c`: PLAY 1, PAUSE 2, PLAY_PAUSE 3, NEXT 4, PREVIOUS 5. Telephony device `2a2a2a2d`: HOOK_SWITCH 1,
  DROP 3 (`CarPlayController.kt:3723-3724`). Not sent, as in DiPlay: Phone Mute (telephony 4; declared, never sent,
  so MUTE_MIC stays `AudioManager.setMicrophoneMute`), flash, redial, keypad, AC Search. Volume: the media descriptor
  has no volume usage and DiPlay sends none, so the volume actions stay on the phone's own audio.
- **Siri:** `{type: requestSiri, params: {siriAction: 2}}` then the same with 3 (`AirPlaySession.kt:390-393`).
- **Call state:** the bootstrap already subscribes (0x4154, params 0,1,2,3,4,11, `Iap2Messages.kt:305-312`) and
  declares 0x4155; `BootstrapControl.serve` hands each 0x4155 to `CallStates` (`CallStateUpdate.parse` in
  `:carplay:protocol`: status u8 param 2, call key = UUID param 4, else trimmed remote id param 0; an update without a
  one-byte status is ignored, as DiPlay `BydCarPlayCall.kt:34-51`). Disconnected and disconnecting remove a call, an
  unknown status too (DiPlay keeps but never shows it). The calls are cleared when the Bluetooth link or the attempt
  ends. Logs never carry the number or the name.
- **App:** `OurCarPlay` (connected = `CarPlayState.Connected`), `OurCarPlayControlTarget` (the only target since
  2026-11-20; not connected, a press does nothing and is logged), `CarPlayCallSituations` (`situationOfCalls` of the
  iAP2 calls while connected, else riding). Until 2026-11-20 `RoutedControlTarget`/`RoutedCallSituations` fell back to
  CarLink's media session and the phone's audio, and `ActionAvailability.changes` refreshed the Handlebar page's "Soon"
  marks; all gone with CarLink. MUTE_MIC and its safety rules are unchanged: the persisted flag, unmute when the
  situation leaves ON_CALL (also when our CarPlay drops mid-call, or a waiting call rings over a muted one: unmuting is
  the safe side), never touch a mute we did not make.
- **Logs:** `carplay.hid.key {key, sent}`, `carplay.siri.requested {sent}`, `carplay.hid.stale_dropped {requests}`,
  `carplay.calls.update {status, calls, changed}`, `carplay.calls.ignored`, `carplay.calls.unknown_status`,
  `carplay.calls.cleared {reason}`, `control.carplay {action, key}`, `control.carplay.not_connected {action}`,
  `control.situation.routed {situation, source}`.
- **Open (device):** whether the iPhone keeps sending CallStateUpdates on Bluetooth while we decline the type-130
  iAP2 tunnel (DiPlay forwards them from both links; `CarPlayController.kt:1796, 1927`). If it moves them to the
  tunnel, the tunnel's iAP2 must feed the same `CallStates`. Device steps: see the C5 controls report (2026-11-19).
  Superseded by "C5 tunnel" below: the tunnel is now taken and feeds the same `CallStates`.

### C5 tunnel: the iAP2 hand-over from Bluetooth to Wi-Fi (2026-11-19, worktree `carplay-c5`)

Why: on our CarPlay the owner's iPhone lost its internet, on CarLink on the same Poco it did not. The one protocol
difference left: CarLink takes the iPhone's type-130 iAP2 tunnel, re-identifies over it and closes RFCOMM 0.5 s after
the iPhone's `/command disableBluetooth`; we declined the tunnel and kept iAP2 and Bluetooth up all session (DiPlay's
`WirelessActiveFallback`). Code: `:carplay:protocol` `stream/IapTunnelReader.kt`, `Iap2Link(…, initiate)`,
`Iap2LinkParameters.TUNNEL`; `:carplay:engine` `session/IapTunnel.kt`, `session/WirelessHandoff.kt`, `MediaStreams.data`.
- **SETUP** (CarPlayMediaEngine.kt:379-443): a type-130 stream with `clientTypeUUID` `E9459FD0-BCAD-4C45-820F-1E72447EF2F2`
  (any case) and a `seed` gets `{type 130, streamID 1, dataPort[, streamConnectionID as received]}` and a TCP listener on
  the group's addresses; other data streams (video in car) and one without a seed are declined. A new tunnel SETUP
  replaces the open one; TEARDOWN of 130 (streamID 1 or none) closes it.
- **Wire** (IapTunnel.kt, AirPlayIapTunnelStream.kt): iPhone to us on the data connection, accepted only from the
  control connection's address within 15 s: ChaCha20-Poly1305 frames (`u16 LE length` as AAD, nonce 4 zero bytes + LE64
  counter from 0, key `HKDF-SHA512(shared, "DataStream-Salt" + seed, "DataStream-Output-Encryption-Key")`) holding
  32-byte APTransportPackage records (size u32 BE at 0, type at 16); only `comm` bodies are iAP2. Us to the iPhone:
  `POST /command {type: "iAPSendMessage", params: {data}}` on the event channel (waits up to 10 s for it). The iPhone
  closing the data connection is not the end: its next connection carries on with the counter at 0.
- **iAP2 over it:** the same `Iap2Channel` and `BootstrapControl` as Bluetooth, no second implementation. The link
  initiates (marker and SYN in one write) with DiPlay's zero-acknowledgement SYN `01 04 FFFF 0000 0000 00 00 …`
  (Iap2LinkChannel.openTunnel); then the same identification, MFi authentication, five subscriptions and answers
  (0x5702/0x4E0E → 0x5703, 0x4300 → 0x4301); its CallStateUpdates go to the same `CallStates`. HID never used iAP2 (it
  goes as `hidSendReport` on the event channel), so nothing else moves.
- **Hand-over** (`WirelessHandoff`, CarPlayController.kt:506-525, 2064-2208): tunnel ready (subscriptions sent) and
  `disableBluetooth` (also `disable-bluetooth`, any case), in either order, close the RFCOMM bootstrap (its coroutine,
  then the socket). Calls are forgotten only when the last link carrying them ends.
- **Fallback:** a request with no ready tunnel after 45 s keeps Bluetooth (`carplay.handoff.fallback`); a tunnel that
  becomes ready later still takes over. Deliberate difference (owner, 2026-11-19): DiPlay fails the whole connection
  when the tunnel fails; here a tunnel failing while Bluetooth is open only falls back to Bluetooth. After we closed
  Bluetooth nothing else carries iAP2, so the tunnel ending then ends the attempt and the runtime reconnects (DiPlay).
- **Logs** (device check): `carplay.iap2.tunnel.setup {dataPort}`, `carplay.iap2.tunnel.connected {peer}`,
  `carplay.iap2.connected {link=tunnel}`, `carplay.iap2.identified {link=tunnel}`, `carplay.iap2.tunnel.ready
  {handoffRequested}`, `carplay.handoff.requested {tunnel}`, `carplay.handoff.bluetooth_closed {after_request_ms,
  was_open, from}`; on trouble `carplay.handoff.fallback {reason}`, `carplay.iap2.tunnel.ended {reason}`,
  `carplay.iap2.tunnel.peer_eof`, `carplay.handoff.tunnel_lost`.
- **Unverified on the device:** that the iPhone completes identification and authentication over our tunnel, and
  whether the iPhone's internet then behaves as on CarLink.

### C5 audio: sound and the microphone (2026-11-19, worktree `carplay-c5-audio`)

CarPlay's music, directions, calls and Siri play on the Poco, and Siri and calls hear the Poco's microphone, as DiPlay
does. Code: `spi/Ports.kt` (`AudioPort`, `AudioPlayer`, `AudioStreamInfo`, `MicrophonePort`, `MicrophoneFormat`,
`MicrophoneOffer`), `session/AudioReceiver.kt`, `session/MicrophoneUplink.kt`; `:carplay:protocol`
`audio/AudioDecoderConfig.kt`; `:carplay:engine-android` `audio/`.
- **Engine to platform: two ports.** `AudioPort.open(AudioStreamInfo(type, audioType, format, latencyMillis))` at a
  stream's first decrypted packet (DiPlay `onAudioStarted`, CarPlayMediaEngine.kt:224-228); each packet then goes to
  `AudioPlayer.packet(sequence, timestamp, payload)` in arrival order, payload as on the wire (AAC-LC access unit, Opus
  packet, big-endian PCM); `close()` at TEARDOWN, a replacing SETUP or the session's end. `duck(true/false)` for the
  iPhone's `duckAudio`/`unduckAudio` (AirPlaySession.kt:810-811); a session that ends ducked is unducked
  (CarPlayMediaEngine.kt:551-553). A player that throws is closed and the stream goes on silent; the session never ends
  for a dead audio device. Calls never block: decoding and the track live on the player's own thread.
- **Microphone.** `MicrophonePort.offer()` (permission and an Opus encoder, asked beside the group's start, 2 s limit)
  decides `/info`'s `audioInputFormats` (DiPlay AirPlayInfoPlist.kt:155-162: `0x70004154` with Opus, `0x4154` without,
  none without a microphone). A stream records when DiPlay's would (CarPlayMediaEngine.kt:624-697): main audio (100),
  telephony and speechRecognition always, default/compatibility only with `input` or an uplink `dataPort`, never AAC;
  20 ms Opus at 48 kHz (bitrate 96/64/48 kbit/s by the format's rate) or PCM of `framesPerPacket` or what fits one MTU
  (`pcmFrameMillis`). It starts at the first downlink packet, or 500 ms after SETUP except for calls (MicrophoneStart.kt).
  The engine seals each body with `MicrophonePacketSealer` (input key, payload type 100, the RTP clock stepped by the
  format's rate: 480 per packet at Opus 24 kHz), PCM made big-endian, and sends it from a socket of its own to the
  control connection's peer at the stream's `dataPort` (MicrophoneUplink.kt:149-153, 559-578).
- **Android playback** (`AudioRenderer`, `TrackFeed`, after DiPlay's AudioRenderer, AndroidMediaSink.kt:1829-2864):
  one thread per stream at `THREAD_PRIORITY_AUDIO`; a 192-packet `RtpReorderQueue`; MediaCodec for AAC-LC (as ADTS,
  with its AudioSpecificConfig) and Opus (OpusHead and delays), PCM byte-swapped; an AudioTrack per stream with DiPlay's
  mapping (`AudioRoute`): telephony `USAGE_VOICE_COMMUNICATION`, speechRecognition `USAGE_ASSISTANT`, media
  `USAGE_MEDIA`, default/alert/compatibility `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`.
- **Latency choices.** Speech (directions, Siri, calls) asks for `PERFORMANCE_MODE_LOW_LATENCY` when its rate is the
  output's own (`PROPERTY_OUTPUT_SAMPLE_RATE`; the AudioTrack.Builder reference requires it): wireless iPhones send
  speech as Opus, decoded at 48 kHz, the Poco's rate. It keeps DiPlay's small buffer (capacity max(4 x minimum, 16 KiB),
  start after max(minimum, 4 KiB), about 43 ms mono) with a working size (`setBufferSizeInFrames`) of the start plus one
  write in whole bursts (`PROPERTY_OUTPUT_FRAMES_PER_BUFFER`); `LatencyTuner` adds one burst for each underrun the
  device reports while sound was waiting (never for a network gap), up to the capacity ("use a fairly large buffer,
  then trim", AudioTrack#setBufferSizeInFrames, #getUnderrunCount). Music keeps the normal path and DiPlay's wireless
  jitter buffer: 300 ms queued before play in a 500 ms track, paused and requeued when it runs dry (rebuffer); its
  latency does not matter and the normal path costs less power on a ride of hours. Short bursts play 500 ms after their
  last write; a 10 ms fade-in.
- **Focus and ducking** (`AudioFocus`, DiPlay AudioFocusCoordinator with CarLink's prompt duck #83, always on here):
  one request for the playing tracks, following music (`GAIN`), then a call (`GAIN_TRANSIENT`), then Siri
  (`GAIN_TRANSIENT_MAY_DUCK`); directions take none and play over the rest. A duckable loss or the iPhone's `duckAudio`
  lowers music's own track to 0.2, a transient loss mutes it, a gain restores it, a permanent loss is ignored.
- **Routes (2026-11-19, the bike, not a car).** Per stream:
  - *Calls and Siri* (telephony, speechRecognition): sound **and** microphone on the rider's Bluetooth headset when it
    is connected. `CallAudio.voiceStarted` (track or recorder of a call or Siri) picks it among
    `getAvailableCommunicationDevices()` (`chooseHeadset`: type `TYPE_BLUETOOTH_SCO` or `TYPE_BLE_HEADSET`; the rider's
    device by address, then Bluetooth name, then any headset) and makes it the communication device
    (`setCommunicationDevice`, the API 31 replacement for `startBluetoothSco`: "Audio routing API updates in Android 14
    for VoIP apps", "Managing BLE Audio for Communication Applications"); the call's or Siri's `AudioTrack` and
    `AudioRecord` also `setPreferredDevice` the headset's output and input (Siri's `USAGE_ASSISTANT` track and
    `VOICE_RECOGNITION` source do not follow the communication route by themselves). The last of them to end calls
    `clearCommunicationDevice`. The rider's choice comes from the Intercom page (`RiderHeadsets`: calls on PHONE = no
    headset; else the chosen or primary device), passed to `createAndroidCarPlayPorts(…, headsets)`.
  - *Without a headset* (none connected, calls on the phone, or the route refused): DiPlay's behaviour, a call on the
    earpiece moves to the loudspeaker once. A headset that disconnects mid-call drops Android's route; the earpiece
    report then finds it gone (`carplay.audio.headset_lost`) and the loudspeaker fallback applies. An earpiece report
    while the headset is still listed is ignored (passing).
  - *Music and directions*: plain `USAGE_MEDIA` / `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` tracks with no preferred device
    and no communication device: Android plays them over A2DP to the connected headset. Nothing in the app overrides
    that (no `setSpeakerphoneOn`, no preferred device for them; the loudspeaker fallback is for calls only).
  - ~~*The held Telecom call* (callaudio/IntercomCallRoute) for CarLink's calls~~: removed with CarLink (2026-11-20).
    A Telecom call would have taken over the same communication route and the audio mode our calls use.
- **Calls** (`CallAudio`, AndroidMediaSink.kt:581-678): the call's microphone enters `MODE_IN_COMMUNICATION` before
  it records and leaves with `MODE_NORMAL` (Siri keeps the normal mode); see "Routes" for where it plays. AudioService
  drops the mode and the communication device when the process dies, so no persisted undo flag
  (docs/LESSONS.md 57). Capture (`MicrophoneCapture`, MicrophoneUplink.kt:77-408): calls `VOICE_COMMUNICATION` with the
  platform's AEC and NS, Siri `VOICE_RECOGNITION` (`VOICE_COMMUNICATION` where refused), else `MIC`; one restart after
  a read error, then the microphone ends (only it). Bodies wait in a 25-entry buffer that drops the oldest.
- **Permissions:** `RECORD_AUDIO` joins `carPlayPermissions` (app) and `CarPlayPermissions.runtime` (engine-android),
  declared in both manifests; engine-android also declares `MODIFY_AUDIO_SETTINGS`. The Permissions page's "CarPlay"
  row asks for it with the rest and says so ("…and the microphone for Siri and calls"). The app requests only its own
  list; the engine's `CarPlayPermissions.runtime` is used by nothing but its own test (left for the engine side to
  drop or align; it still lacks `ACCESS_COARSE_LOCATION` on 12/12L).
- **Microphone in the background (2026-11-19).** `RECORD_AUDIO` is a while-in-use permission: a `microphone` service
  type may only be started or added while the app is visible (Android 14+ throws a SecurityException otherwise; no
  listed exemption covers a companion-device app; "Restrictions on starting foreground services that need while-in-use
  permissions"), and types are added by calling `startForeground` again with all of them ("Launch a foreground
  service"). `RideService` declares `connectedDevice|microphone` (`phoneCall` went with CarLink's held call,
  2026-11-20) and `FOREGROUND_SERVICE_MICROPHONE`, and
  `keepsMicrophone` decides: added when our CarPlay is Connected, RECORD_AUDIO is granted and the app is visible
  (checked at every CarPlay state change with `ActivityManager.getMyMemoryState`, and at every resume, when the app
  starts the ride service again); kept across screen-off and CarPlay's drops and retries; given back when CarPlay
  stops (Idle/Stopped) or the permission goes (`service.microphone_type`). A refused upgrade is logged
  (`service.foreground_denied`) and retried at the next change. **Limit:** a ride that wakes on ignition with the
  screen off gets no microphone until the rider opens the app once; nothing safe can add a while-in-use type from the
  background. **Android 17's background audio hardening (2026-11-20)** goes further for apps targeting API 37: in the
  background, playback and volume changes "fail silently" and audio focus returns `AUDIOFOCUS_REQUEST_FAILED` unless
  the app runs a foreground service with while-in-use capabilities, which a service gets when "started ... while the
  app is visible" ("Background audio hardening"). So the same limit covers CarPlay's **sound, audio focus and volume**:
  a ride started only from the background (ignition, screen off, the app never shown) plays nothing until the app is
  opened once. What the doc asks is done: every start of the ride from the visible app (`RideAutoStart` on every
  resume, the CarPlay tab's Start CarPlay) posts the foreground state again even when nothing changed, so a ride begun
  in the background is re-promoted while visible; at ignition `RideStarter.showOnScreen` brings the app over the lock
  screen, which makes it visible. Still open for a device run: `adb shell cmd audio set-enable-hardening throw` and the
  `AudioHardening` lines in `dumpsys audio` (`level: full` = an FGS without WIU) for a ride started with the screen
  off; and a rider-facing hint when focus is refused in the background (it is logged by the engine's `AudioFocus`; the
  app has no signal for it yet).
- **Link row (2026-11-19).** `CarPlayState.Connected.wifiChannel` (the group's channel, as offered over iAP2) and
  `CarPlayScreen.stats` (each `carplay.video.stats` window as `CarPlayVideoStats`, reset at a new screen stream) give
  "Wi-Fi Direct · 5 GHz ch 36 · Strong". `linkQuality`: Weak at 5 % pictures lost on the way to the decoder (queue
  drops, bad lengths), a decoder recovery, or decode p90 above 100 ms; Fair with any loss or decode p90 above 34 ms
  (two frames at 60 fps); Strong otherwise; no word before a window with pictures.
- **Logs** (`carplay.audio.*`): engine `microphone_offer offer=`, `receiving type audioType codec sampleRate`, `duck`,
  `microphone_start … frameMillis rtpStep`, `microphone_silent_start`, `microphone_ended packets sendFailures`,
  `microphone_failed`, `player_failed`, `ended packets dropped`; Android `track type audioType codec sampleRate channels
  channel usage performance requested nativeRate burst capacityFrames bufferFrames startMs decoder`, `playing queuedMs`,
  `buffer bufferFrames bufferMs underruns`, `rebuffer`, `stats` every 5 s (rx, reordered, late, queueFull,
  decoderDropped, skipped, writeErrors, underruns, queue, playing, trackMs, bufferFrames, rebuffers), `focus`,
  `focus_change`, `prompt_duck`, `mode`, `call_route`, `microphone_capture source codec`, `microphone_effect`,
  `microphone_stats`, `renderer_failed`, `track_unavailable`. Never key material or audio content.
- **Device check (coordinator, release build, CarPlay display open):** allow the microphone when asked; connect the
  iPhone. Expect `carplay.audio.microphone_offer offer=PCM_AND_OPUS`. Play music: `carplay.airplay.stream type=100
  audioType=media` (or 102), `carplay.audio.receiving`, `carplay.audio.track … channel=MEDIA performance=none
  startMs=300`, `carplay.audio.playing`, then `carplay.audio.stats` every 5 s with `underruns=0` and `rebuffers=0` on a
  steady link. Start a route: `carplay.audio.duck ducked=true`, `carplay.audio.prompt_duck ducked=true music=0.2`,
  `carplay.audio.track … audioType=default channel=NAVIGATION performance=low_latency nativeRate=48000`, a
  `carplay.audio.buffer` line only if the Poco underruns, then `ducked=false`. Hold Siri: `audioType=speechrecognition`,
  `microphone_start codec=OPUS … rtpStep=480` (Opus 24 kHz), `microphone_capture source=voice_recognition`, Siri
  answers what was said. A call: `microphone_start audioType=telephony`, `mode to=3`, possibly `call_route
  loudspeaker=true`, the far end hears the rider; hang up: `mode to=0`. Each stream ends with `carplay.audio.ended`.
  **With the helmet (2026-11-19):** intercom paired and connected (calls and music). Hold Siri: `carplay.audio.headset_route
  device=<intercom> type=7 channel=ASSISTANT routed=true`, `track_device … set=true`, `microphone_device … set=true`,
  `microphone_capture … headset=<intercom>`; Siri is heard in the helmet and hears the helmet's microphone; after it,
  `headset_route … cleared=true` and music comes back over A2DP. A call: the same with `channel=PHONE`, `mode to=3`, no
  `call_route loudspeaker`. Switch the intercom off mid-call:
  `headset_lost`, then `call_route loudspeaker=true`. Mute the microphone from the handlebar mid-call:
  `microphone_muted muted=true`, the far end hears nothing; unmute: `muted=false`. Background: open the CarPlay display
  once after Connected (`service.microphone_type on=true visible=true applied=true`), turn the screen off, hold Siri:
  no "Foreground service started from background can not have location/camera/microphone access" in logcat, and
  `dumpsys activity services com.abid.handlebarlink` lists `types=0x…` including microphone (0x80).

## Things only the phone or bike can settle

Each one is a named test in the research docs; we run them in the milestone that needs them:

- How fast Android notices the meter with the screen off (02 §6.5 D4/D5): decides "presence-driven"
  vs "always armed" as the default (checklist under "Auto-start on ignition").
- Whether the meter uses a fixed address (02 D2), and what force-stop does (02 D11).
- Volume changes, playback and audio focus from a background service on Android 17 (01 §4.4; "Microphone in the
  background" above: a ride started only from the background needs the app opened once).
- The Poco's real screen density, glove use and sunlight readability (03 §9).
- Intercom: the 15-minute bench test of "iPhone owns all sound" (04 §7.1), now with our own CarPlay (CarLink is no
  longer used, 2026-11-20), independent of the milestones.
