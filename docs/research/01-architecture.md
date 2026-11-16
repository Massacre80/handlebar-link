# 01 — Architecture & project setup for Handlebar Link v2

Status: recommendation, 2026-11-16. Scope: how to structure, build, test and ship the v2 app (branch `v2`
of `/Volumes/My files/pocof1/handlebar-link`). Product direction: Handlebar Link grows into **our own
app**. Today it is a BLE bridge from the CB350 meter's handlebar switches. Later it will also contain our
**own wireless CarPlay receiver**: Wi-Fi Direct, AirPlay/iAP2 session, H.264 video surface, audio streams
and mic uplink. That receiver will be written cleanly, using CarLink (`/Volumes/My files/pocof1/carlink-src`)
only as a reference. BLE/GATT mechanics (autoConnect vs. CDM presence, bonding, MTU) are covered here only
as far as they shape the architecture.

Sources studied (shallow clones, treated as data, nothing executed), all under
`/private/tmp/claude-501/-Volumes-My-files-pocof1/338e22e3-6e55-4ad9-85b5-2c4986af3681/scratchpad/research/`:

| Repo | Commit | Why it matters |
|---|---|---|
| `nowinandroid` (android/nowinandroid) | `2982e63` (2026-10-20) | Google's reference: convention plugins, catalog, fakes instead of mocks, CI, module-graph tooling |
| `bitwarden-android` | `8b2f6e2` (2026-11-16) | Security-critical production app: error-handling rules, crash logging without a vendor (F-Droid flavor), detekt, CI hardening. Most current versions (AGP 9.5.0, Kotlin 2.4.21) |
| `thunderbird-android` | `d2ecc5c` (2026-11-14) | Large OSS app: **Koin**, **precompiled-script** convention plugins, Logger → sinks logging, fakes-only testing guide, ADRs, api/internal split |
| CarLink (`/Volumes/My files/pocof1/carlink-src`) + `notes/CARLINK_NOTES.md` | branch `bike` | Reference only: how a CarPlay host is wired (process/FGS types, threading, MFi asset handling) |
| Prototype `/Volumes/My files/pocof1/handlebar-link-prototype` | — | Domain reference (SAB protocol, CTS server, media-session path) |

Official docs read: [Architecture recommendations](https://developer.android.com/topic/architecture/recommendations),
[Modularization](https://developer.android.com/topic/modularization),
[Coroutines best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices),
[Android 17 changes (targeting 37)](https://developer.android.com/about/versions/17/behavior-changes-17),
[Android 17 changes (all apps)](https://developer.android.com/about/versions/17/behavior-changes-all),
[FGS background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
[Companion device pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing),
[Enable app optimization (R8)](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization),
[Built-in Kotlin in AGP 9](https://developer.android.com/build/migrate-to-built-in-kotlin),
[AGP 9.5 release notes](https://developer.android.com/build/releases/gradle-plugin),
[setup-gradle docs](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md),
[Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
Versions were cross-checked against Maven Central, Google Maven and GitHub releases on 2026-11-16.

---

## 0. TL;DR

1. **Two engines behind small APIs, one app that composes them.** Day one has four Gradle modules plus an
   included `build-logic`:
   - `:core`: pure Kotlin. Logger API, time, small shared types.
   - `:bike:protocol`: pure Kotlin. The SAB codec.
   - `:bike:link`: Android library. GATT transport, meter session, connection supervisor.
   - `:app`: services, UI, settings, button routing, diagnostics.

   The CarPlay receiver arrives later as `:carplay:engine`, plus `:carplay:protocol` if needed. Both are
   leaves with a small API. **Engines never depend on each other**; only `:app` wires bike buttons into
   CarPlay input. No empty placeholder modules, no `:feature:*:api/impl`, no domain layer.
2. **Manual DI.** Each engine exposes a factory (`BikeLink.create(…)`, later `CarPlayEngine.create(…)`), and
   `:app` has one `AppGraph`. Engines never use a DI framework internally. Hilt can be introduced later in
   `:app` alone, once there are about 4 ViewModel screens, without touching the engines.
3. **Errors are values at the edges and typed exceptions inside a session.**
   - Each engine has one sealed error type, e.g. `LinkError`.
   - Android exceptions and status codes are translated in exactly one adapter per engine.
   - A supervisor turns every session end into a typed state and a recovery decision.
   - `CancellationException` is never swallowed.
4. **Connection lifecycle is an explicit state machine.** A sealed `LinkState` with a pure transition
   function, unit-tested as a table. A single-threaded supervisor coroutine runs it, with capped, jittered
   backoff. The CarPlay engine will follow the same pattern.
5. **Structured logging through our own `Logger` (in `:core`) to sinks**: logcat, an in-memory ring for the
   UI, and a rotating file. On top of that:
   - an uncaught-exception file,
   - `ApplicationExitInfo` read on the next start,
   - a "share diagnostics" action.

   No third-party crash SDK.
6. **Security**:
   - Every component is non-exported unless a system permission guards it.
   - No secrets in git. That includes the future MFi/CarPlay identity assets, which are injected at build
     time from outside the repo.
   - R8 is on with `optimization { enable = true }` and `-dontobfuscate`.
   - The bike-only phase needs no `INTERNET` permission.
7. **Testing**:
   - Fakes only: a scripted `FakeMeter` behind a `GattTransport` interface.
   - `runTest` virtual time, Turbine and AssertK.
   - Robolectric only for the service shell.
   - Kover gates on the engine modules.
8. **Static analysis**:
   - Spotless + ktlint for formatting; detekt 1.23.8 for code smells and coroutine rules.
   - Android Lint and Kotlin compiler warnings both fail the build.
   - `explicitApi()` in every library module.
   - A module-boundary check enforces the dependency rules.
9. **CI**: one GitHub Actions workflow on ubuntu-24.04 with JDK 21.
   - `setup-gradle` with `cache-provider: basic`. The default "enhanced" cache is only a free *preview* for
     private repos.
   - Actions pinned by SHA.
   - Runs `./gradlew check assembleRelease`, plus Dependabot and gitleaks.

---

## 1. What the prototype teaches (keep / fix)

Keep (it works on the bike, and the patterns are right):
- `GattClient.writeAndAwait` subscribes with `CoroutineStart.UNDISPATCHED` **before** the write, so a fast
  reply cannot be missed (`app/src/main/java/com/abid/handlebarlink/GattClient.kt`).
- One GATT operation at a time behind a `Mutex`, per-op timeouts. A session is one `coroutineScope`, so
  every child dies with the link (`BikeSession.run`).
- Media keys go only to the CarPlay host's media session, never broadcast (`CarPlayControl.kt`).
- Protocol facts and the DFU denylist are documented (`SAB_PROTOCOL.md` §7–8, "Verified on the CB350").

Fix (each one maps to a rule in §5):
| Prototype | Problem | v2 |
|---|---|---|
| `@SuppressLint("MissingPermission")` on 4 whole classes | Lint's most useful BLE check is off everywhere; a revoked permission becomes a `SecurityException` crash | One permission gate; `MissingPermission` is a lint **error**; no class-level suppression |
| `pending`, `connectWaiter`, `CtsServer.subscribers` mutated from binder callback threads and coroutines without synchronization | Data races | Callbacks only `trySend` into a channel; all link state is confined to one single-threaded dispatcher |
| `runCatching { … }` inside suspend code (`BikeSession.run`, `readVehicleInfo`) | Swallows `CancellationException` | detekt `SuspendFunSwallowedCancellation`; explicit catches |
| `catch (e: Exception)` + fixed `delay(2000)` loop | No error classification, no backoff, no reason shown to the rider | `LinkError` + `Recovery` + backoff policy (§4) |
| Status is a `String`; log lines carry UI text and emoji (`Log.kt`) | Untestable, unparseable after a ride | `LinkState` sealed type; structured events (§3) |
| Global `object Log`; `BikeStore(context)` built ad hoc; `LocalDateTime.now()` in `CtsServer` | Hidden dependencies, untestable time | Constructor injection, injected `Clock`/`TimeSource` |
| `SharedPreferences`, including the SAB key in plain base64 | Sync I/O; secret at rest unprotected | Preferences DataStore; key material wrapped with an Android Keystore AES-GCM key |
| `BootReceiver exported=true`; `registerReceiver` without flags | Wider attack surface; lint `UnspecifiedRegisterReceiverFlag` | `exported=false` (system broadcasts still arrive; verify once on device); `RECEIVER_NOT_EXPORTED` |
| `BikeService.onPress` maps buttons straight to `CarPlayControl` | Bike code knows about the output | A `ControlRouter` in `:app` maps `ButtonEvent` → `ControlTarget`; the bike engine knows nothing about CarPlay |
| Release: `isMinifyEnabled=false`, debug signing | Not a release | R8 on, real key from outside the repo (§6) |
| `Sab` object mixes UUIDs, byte builders, enums | Hard to test in isolation | `:bike:protocol` of pure functions with golden-vector tests |

---

## 2. Module / package layout

### 2.1 Reasoning

- The official guide says modules buy "reusability, strict visibility control … scalability, ownership,
  encapsulation, testability, build time". It also warns: "Too fine-grained: every module brings …
  overhead", and "It doesn't always make sense to modularize … if you don't expect your project to grow"
  (<https://developer.android.com/topic/modularization>). NiA's own doc: "If your data layer is small,
  it's fine to keep it in a single module" (`nowinandroid/docs/ModularizationLearningJourney.md:232`).
- Our project **will** grow, along one known axis: a second, much larger engine (CarPlay: Wi-Fi Direct,
  mDNS, RTSP/AirPlay pairing and crypto, iAP2, MediaCodec video, several audio streams, mic uplink). Two
  benefits therefore matter from day one:
  - **Encapsulation with a compiler-enforced API.** Each engine is a module with `explicitApi()` and an
    `internal` interior, so the CarPlay internals can never leak into bike code or UI.
  - **Build time and test speed.** CarPlay will be the heaviest code. Editing bike code must not recompile
    it, and bike tests must not wait for it.
- So we cut along **engine boundaries**: bike, later CarPlay, and the app that composes them. We do not cut
  along layers (`data`/`domain`/`ui` modules) or per screen. That is the "too fine-grained" pitfall for a
  single developer; NiA has 30+ modules (`nowinandroid/settings.gradle.kts`) and Bitwarden 10, both built
  for teams.
- Inside an engine, pure-Kotlin protocol code is split from Android code (`:bike:protocol` vs `:bike:link`).
  This gets us millisecond JUnit tests without Robolectric and a compiler-enforced ban on `android.*`. The
  same pattern will carry the CarPlay protocol work (RTSP/plist/iAP2 framing, pairing crypto), which is
  exactly the part that needs exhaustive tests against captured bytes. The cost is a 5-line build file per
  module, thanks to convention plugins.
- **Seams first, modules on demand.** `:carplay:*` modules are created when the first CarPlay code is
  written, not before. What exists from day one: the dependency rules, the `ControlTarget` seam in `:app`,
  `:core` shared by both engines, and a service designed to host more than one engine.

### 2.2 Dependency rules (enforced)

```
            ┌──────────── :app ─────────────┐        composition root: services, UI, routing, DI
            │               │               │
            ▼               ▼               ▼
      :bike:link      :carplay:engine    (androidx, compose)
            │          (later) │
            ▼                  ▼
      :bike:protocol   :carplay:protocol (later, only if it earns its keep)
            │                  │
            └──────► :core ◄───┘          pure Kotlin: Logger, time, Outcome
```

- `:bike:*` and `:carplay:*` never depend on each other.
- Only `:app` depends on engines.
- `*:protocol` and `:core` are Kotlin/JVM modules (no Android).
- Enforced by a small `checkModuleBoundaries` task in `build-logic`. It walks `project.configurations` and
  fails on forbidden project dependencies. It is wired into `check` (NiA has similar graph tooling in
  `build-logic/.../Graph.kt`).

### 2.3 Tree (day one, plus where CarPlay goes)

```
handlebar-link/                         (branch v2)
├── settings.gradle.kts                 includeBuild("build-logic"), repos with content filters
├── build.gradle.kts                    root: id("hl.root") only
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   ├── gradle-daemon-jvm.properties    toolchainVersion=21 (daemon JVM criteria)
│   └── wrapper/gradle-wrapper.properties   9.8.2 + distributionSha256Sum
├── build-logic/                        included build, precompiled script plugins (Thunderbird style)
│   └── convention/src/main/kotlin/
│       ├── hl.root.gradle.kts                   spotless for *.kts, module-boundary check
│       ├── hl.jvm.library.gradle.kts            Kotlin/JVM + explicitApi + quality
│       ├── hl.android.library.gradle.kts        AGP library + explicitApi + lint + quality
│       ├── hl.android.application.gradle.kts    AGP app + lint + R8 + signing hook
│       ├── hl.compose.gradle.kts                compose compiler + BOM
│       ├── hl.quality.gradle.kts                spotless, detekt, kover wired into `check`
│       └── Libs.kt                              `val Project.libs: LibrariesForLibs`
├── config/detekt/detekt.yml, config/lint/lint.xml
├── core/                               :core (Kotlin/JVM, tiny, stable)
│   └── src/main/kotlin/com/abid/handlebarlink/core/
│       ├── log/Logger.kt, LogEntry.kt, LogSink.kt, Level.kt
│       ├── time/TimeProvider.kt        Clock + TimeSource bundle for injection
│       └── Outcome.kt                  (only if a second engine needs it; else stays per-engine)
├── bike/
│   ├── protocol/                       :bike:protocol (Kotlin/JVM, zero Android deps)
│   │   └── …/bike/protocol/
│   │       ├── SabUuids.kt             every UUID + DFU denylist (SAB_PROTOCOL.md §2, §8)
│   │       ├── FunctionId.kt           1..10 (§5.1)
│   │       ├── AssignmentFrames.kt     request-id counters, 10 CB350 frames, END (§5.2)
│   │       ├── DataCommands.kt         REQUEST_SAB_MODE, ACTIVE_INDICATORS, command ids (§6)
│   │       ├── Replies.kt              reply matching + status codes
│   │       ├── VehicleInfo.kt          series code / display capability (§1.2)
│   │       └── CurrentTime.kt          CTS 0x2A2B / 0x2A0F encoders (input: ZonedDateTime)
│   └── link/                           :bike:link (Android library, explicitApi, no DI framework)
│       └── src/
│           ├── main/kotlin/…/bike/link/
│           │   ├── BikeLink.kt         PUBLIC API: state, buttons, run(); `BikeLink.create(...)`
│           │   ├── LinkState.kt, LinkError.kt, ButtonEvent.kt, BikeLinkConfig.kt   (public)
│           │   ├── gatt/GattTransport.kt          internal seam (fakeable)
│           │   ├── gatt/AndroidGattTransport.kt   the ONLY BluetoothGatt user; error translation
│           │   ├── gatt/CtsServer.kt              phone-side GATT server
│           │   ├── session/MeterSession.kt        setup sequence + await disconnect
│           │   └── supervisor/LinkSupervisor.kt   loop, backoff, state transitions
│           ├── testFixtures/kotlin/…   FakeGattTransport, FakeMeter, FakeBikeLink
│           └── test/kotlin/…
├── carplay/                            (LATER, not created on day one)
│   ├── protocol/                       :carplay:protocol (JVM) RTSP/plist/iAP2 framing, pairing crypto
│   └── engine/                         :carplay:engine (Android) Wi-Fi Direct, mDNS, sessions,
│                                       MediaCodec video, audio streams, mic; PUBLIC API = §2.5 only
└── app/                                :app (composition root)
    └── src/
        ├── main/AndroidManifest.xml
        ├── main/keepRules/handlebarlink.keep
        ├── main/kotlin/com/abid/handlebarlink/
        │   ├── HandlebarLinkApp.kt     creates AppGraph, installs CrashRecorder first
        │   ├── AppGraph.kt             manual DI: engines via factories, sinks, repositories
        │   ├── session/RideService.kt  the one FGS; hosts engines (bike now, CarPlay later)
        │   ├── session/RideCoordinator.kt   decides which engines run; owns their scopes
        │   ├── session/RideNotification.kt
        │   ├── session/BikePresenceService.kt   CompanionDeviceService trigger (see BLE doc)
        │   ├── session/BootReceiver.kt
        │   ├── controls/ControlTarget.kt        interface: media/knob/siri actions → Outcome
        │   ├── controls/ControlRouter.kt        ButtonEvent → ControlAction mapping (pure) + dispatch
        │   ├── controls/MediaSessionControlTarget.kt  interim: an external CarPlay host's media session
        │   ├── controls/MediaAccessListener.kt        NotificationListenerService stub (interim)
        │   │                                          later: CarPlayControlTarget → CarPlayEngine.input
        │   ├── settings/SettingsRepository.kt         DataStore
        │   ├── bike/                     pairing (CDM associate + bond), bike status UI
        │   ├── carplay/                  (later) CarPlayActivity/SurfaceView host, CarPlay settings
        │   ├── diagnostics/              FileLogSink, RingBufferSink, CrashRecorder, ExitInfoReporter,
        │   │                             DiagnosticsExporter
        │   └── ui/                       MainActivity, home/status screen + ViewModel, theme
        └── test/kotlin/…
```

Package rules:
- Package by feature, not by layer type: `bike`, `carplay`, `controls`, `session`, `diagnostics`.
- Module namespaces follow the module path (`com.abid.handlebarlink.bike.link`, `…carplay.engine`).
- Everything in a library module is `internal` unless it is part of the documented API; `explicitApi()`
  enforces this.

### 2.4 The seam between bike and CarPlay

The bike engine emits `ButtonEvent(function: FunctionId, at: Instant)` and nothing else. `:app` owns the
mapping and the destination:

```kotlin
// :app — controls/ControlTarget.kt
sealed interface ControlAction { data object PlayPause; data object Next; data object Previous
    data object VolumeUp; data object VolumeDown; data object Siri
    data class Knob(val delta: Int); data object Select; data object Back }

fun interface ControlTarget { fun perform(action: ControlAction): ControlResult }
sealed interface ControlResult { data object Done : ControlResult
    data class Unavailable(val reason: String) : ControlResult }   // e.g. "no CarPlay session"
```

- Today: `MediaSessionControlTarget` (the prototype's path to an external CarPlay host's media session).
  It is explicitly interim.
- Later: `CarPlayControlTarget(engine.input)`, in-process. No IPC, no notification-listener grant. The
  interim target and `MediaAccessListener` are then deleted.
- The `ControlRouter` mapping (FunctionId → ControlAction, user-configurable later) is a pure function with
  table tests. RoadSync's suggested mapping is in SAB_PROTOCOL.md §5.5.

### 2.5 Sketch of the future CarPlay engine API (to keep the boundary small)

Informed by how CarLink is wired (`notes/CARLINK_NOTES.md` §2.1: `CarPlayController.sendMediaButton`,
`sendKnob`, `requestSiri` are thread-safe and non-blocking; session state is a flow; video is a surface
the host provides). Not a design commitment; it fixes the *shape*:

```kotlin
public interface CarPlayEngine {
    public val state: StateFlow<CarPlayState>      // Idle, Advertising, Connecting, Streaming(info), Failed(err)
    public val input: CarPlayInput                 // the ControlTarget adapter calls only this
    public suspend fun run(config: CarPlayConfig)  // owns Wi-Fi Direct, mDNS, sessions; returns on cancel
    public fun attachVideo(surface: Surface, sizePx: Size, densityDpi: Int)
    public fun detachVideo()
    public companion object { public fun create(context: Context, deps: CarPlayDeps): CarPlayEngine = … }
}
public interface CarPlayInput {          // non-blocking, any thread; false = no live session
    public fun mediaButton(button: MediaButton): Boolean
    public fun knob(event: KnobEvent): Boolean
    public fun touch(event: TouchEvent): Boolean
    public fun siri(): Boolean
}
```

`CarPlayDeps` carries `Logger`, `TimeProvider` and an `MfiIdentityProvider` (keys loaded at runtime from
build-injected assets, §6.4). Audio focus, `AudioTrack`/`AudioRecord` and `MediaCodec` stay internal.
Real-time media runs on dedicated threads inside the engine; coroutines drive only control-plane work.

### 2.6 Layering inside the modules (official layers, right-sized)

- **Data layer** (strongly recommended officially): `SettingsRepository` (DataStore) and the engines.
  `BikeLink` is the "Bluetooth data provider" that the recommendations page says UI must reach only
  through a repository-like class. Streams are exposed as `StateFlow`/`Flow`, one-shots as `suspend`.
- **Domain layer**: skipped. The official priority is "Recommended in big apps", and nothing is reused
  across ViewModels. `ControlRouter` is a pure function in `:app`.
- **UI layer**:
  - One Activity (plus a dedicated CarPlay activity later, for the video surface).
  - One `uiState: StateFlow` per ViewModel, built with `stateIn(WhileSubscribed(5_000))` and collected
    with `collectAsStateWithLifecycle`. All of this is "strongly recommended".
  - Navigation 3 once a second screen exists.
- **Services are not UI.** `RideService` owns the long-running scopes. UI and service both *observe* the
  same process-wide engines from `AppGraph`; the UI never binds to the service.

---

## 3. Error handling and logging

### 3.1 Pattern

These rules are distilled from Bitwarden and from the official coroutine guide:
- Bitwarden `docs/ARCHITECTURE.md` ("Data Sources"): data sources wrap throwing libraries and do not throw.
- Bitwarden `docs/STYLE_AND_BEST_PRACTICES.md`: "Functions should not intentionally throw exceptions",
  "Never catch `Exception` generically", "Never wrap huge chunks of code in a try/catch".
- The official coroutine guide: "don't consume exceptions of type `CancellationException` … Prefer
  catching specific exception types".

They are adapted to coroutine sessions, and both engines follow them:

- **One error vocabulary per engine.** `LinkError` is sealed (later `CarPlayError`). No raw `Throwable`
  appears in public state, except an `Unexpected` case that marks a bug.
- **Exceptions are caught at named boundaries only:**
  1. The engine's platform adapter. For the bike, `AndroidGattTransport` translates `BluetoothGatt`
     booleans and status codes, `SecurityException` (permission revoked), `DeadObjectException` and
     timeouts into `LinkError`. For CarPlay later, the `MediaCodec`, `AudioTrack` and socket adapters.
  2. The engine's supervisor `runOnce()`. It ends a session and maps it to a state plus a recovery
     decision. It catches generic `Exception` *only* to survive our own bugs mid-ride, logged at ERROR.
  3. `ControlTarget` implementations. A failing output returns `ControlResult.Unavailable` and never
     kills the bike link.
  4. Process level: `CrashRecorder` (uncaught handler, chained) and the service scope's
     `CoroutineExceptionHandler`, which logs and never silently swallows.
- **Inside a session**, a failure is a typed `LinkException(error)` thrown from the transport. Structured
  concurrency then cancels sibling coroutines (notification collector, pending writes) for free. It never
  escapes the engine; the public API exposes states and `Flow`s.
- **Kotlin `Result`** is used only for small non-suspending helpers (Bitwarden uses it for data sources).
  Never `runCatching` around suspend calls.

Sketch (illustrative):

```kotlin
// :bike:link — LinkError.kt
public sealed interface LinkError {
    public val recovery: Recovery

    public data object BluetoothOff : LinkError { override val recovery = Recovery.WaitForBluetooth }
    public data class PermissionMissing(val permission: String) : LinkError {
        override val recovery = Recovery.WaitForUser
    }
    public data object NoBikePaired : LinkError { override val recovery = Recovery.WaitForUser }
    public data object NotAHondaMeter : LinkError { override val recovery = Recovery.WaitForUser }
    public data class Disconnected(val gattStatus: Int) : LinkError { override val recovery = Recovery.Backoff }
    public data class GattFailed(val op: GattOp, val gattStatus: Int) : LinkError {
        override val recovery = Recovery.Backoff
    }
    public data class TimedOut(val op: GattOp, val after: Duration) : LinkError {
        override val recovery = Recovery.Backoff
    }
    public data class MeterRefused(val function: FunctionId, val status: Int) : LinkError {
        override val recovery = Recovery.Backoff
    }
    /** A bug in our code. Logged with stack trace; the ride goes on. */
    public data class Unexpected(val cause: Throwable) : LinkError { override val recovery = Recovery.Backoff }
}

public enum class Recovery { Backoff, WaitForBluetooth, WaitForUser }

internal class LinkException(val error: LinkError) : Exception(error.toString())

// :bike:link — AndroidGattTransport.kt (boundary 1: only place android.bluetooth errors are translated)
internal suspend fun <T> GattOpRunner.await(op: GattOp, timeout: Duration, start: () -> Boolean): T {
    val reply = expect(op)                          // registered before start(), like writeAndAwait
    val started = try { start() } catch (e: SecurityException) {
        throw LinkException(LinkError.PermissionMissing(Manifest.permission.BLUETOOTH_CONNECT))
    }
    if (!started) throw LinkException(LinkError.GattFailed(op, gattStatus = -1))
    return try {
        withTimeout(timeout) { reply.await() }      // completes, or fails with LinkException
    } catch (e: TimeoutCancellationException) {
        throw LinkException(LinkError.TimedOut(op, timeout))
    }
}

// :bike:link — LinkSupervisor.kt (boundary 2)
private suspend fun runOnce(attempt: Int): SessionEnd {
    val started = time.source.markNow()
    return try {
        session.run(onStep = { step -> publish(LinkState.SettingUp(step)) },
                    onReady = { info -> publish(LinkState.Ready(info, time.clock.instant())) })
        SessionEnd(LinkError.Disconnected(gattStatus = 0), started.elapsedNow())
    } catch (e: LinkException) {
        SessionEnd(e.error, started.elapsedNow())
    } catch (e: CancellationException) {
        throw e                                     // service stopping: never swallow
    } catch (e: Exception) {                        // TooGenericExceptionCaught suppressed here only
        log.error("link.session.bug", e, "attempt" to attempt)
        SessionEnd(LinkError.Unexpected(e), started.elapsedNow())
    }
}
```

### 3.2 Logging

The pattern comes from Thunderbird, slimmed down. In `core/logging/internal/.../LoggingModule.kt` a
`Logger` writes to a `CompositeLogSink` (console + file), and a separate `testing` artifact provides a fake.
The `Logger` lives in `:core` so both engines share it without depending on each other. We don't use
Timber: we need key/value fields and several sinks at different levels, which it does not give out of the
box.

```kotlin
// :core — log/Logger.kt
public enum class Level { DEBUG, INFO, WARN, ERROR }
public fun interface LogSink { public fun write(entry: LogEntry) }
public data class LogEntry(
    val at: Instant, val level: Level, val tag: String, val event: String,
    val fields: Map<String, Any?>, val error: Throwable?,
)

public class Logger(private val tag: String, private val clock: Clock, private val sinks: List<LogSink>) {
    public fun log(level: Level, event: String, error: Throwable? = null, vararg fields: Pair<String, Any?>) {
        val entry = LogEntry(clock.instant(), level, tag, event, fields.toMap(), error)
        sinks.forEach { it.write(entry) }
    }
    public fun child(tag: String): Logger = Logger(tag, clock, sinks)
    public fun info(event: String, vararg f: Pair<String, Any?>): Unit = log(Level.INFO, event, null, *f)
    public fun error(event: String, e: Throwable?, vararg f: Pair<String, Any?>): Unit =
        log(Level.ERROR, event, e, *f)
}
```

Conventions:
- Event names are stable, dotted and lower-case: `link.state`, `link.session.ready`, `gatt.op.failed`,
  `button.press`, `control.performed`, `control.unavailable`, `ride.start`. Later the CarPlay engine adds
  `carplay.session.*`, `carplay.video.*`, and so on. Free text never carries data.
- Each entry is rendered as one logfmt line:
  `2026-11-16T21:01:03.412+05:30 I bike link.session.ready attempt=2 series=03F5 display=1 setup_ms=812`.
- Sinks are wired in `AppGraph`:
  - `LogcatSink`: DEBUG in debug builds, INFO in release.
  - `RingBufferSink`: the last 500 entries as a `StateFlow`, for the UI log view.
  - `FileLogSink`: `filesDir/logs`, 1 MB × 5 rotation, written on a dedicated single-thread executor,
    flushed on WARN and above.
- High-rate media paths (later CarPlay video/audio) log **counters and summaries** (a periodic
  `carplay.video.stats fps=… dropped=…`), never per frame.
- Never log key material or MFi identity data. Log the bike MAC truncated (`…:4F:2A`).
- Every state transition is logged by its supervisor (one place each), so a post-ride log reads as a
  timeline.

### 3.3 Crash and death reporting without a third-party service

1. **`CrashRecorder`** is installed first in `Application.onCreate`. It chains the default uncaught
   handler and synchronously writes `filesDir/crash/crash-<epochMs>.txt` (thread, stack, last 50
   ring-buffer entries). It then delegates, so the process still dies and `START_STICKY` restarts the
   service. This is the same idea as Bitwarden's `UncaughtErrorLoggingContentProvider`
   (`app/src/main/kotlin/com/x8bit/bitwarden/data/platform/contentprovider/UncaughtErrorLoggingContentProvider.kt`)
   and its F-Droid flavor that ships without Crashlytics (`app/src/fdroid/.../LogsManagerImpl.kt`).
2. **`ExitInfoReporter`** runs on every start. It calls
   `ActivityManager.getHistoricalProcessExitReasons(packageName, 0, 16)` and logs each exit newer than the
   last-seen timestamp (reason, status, importance, description). This catches what an uncaught handler
   cannot:
   - low-memory kills,
   - ANRs (we save `traceInputStream` to a file),
   - "user stopped",
   - Android 17's new memory limiter (`REASON_OTHER` with `"MemoryLimiter:AnonSwap"` in the description,
     per the all-apps changes). This will matter once video buffers exist.
3. **`DiagnosticsExporter`**: a "Share diagnostics" action zips logs and crash/ANR files and shares them
   via `FileProvider` with an explicit `FLAG_GRANT_READ_URI_PERMISSION`. Android 17 announces the end of
   implicit grants for `ACTION_SEND`. Debug builds can also use `adb shell run-as … cat files/logs/…` over
   the Windows SSH bridge.
4. Debug builds enable `StrictMode` (thread and VM policies, `penaltyLog`).
5. Because R8 keeps names (`-dontobfuscate`, §6.4), these files are readable without retracing.

---

## 4. Coroutines, the ride service and the connection state machine

### 4.1 Scope tree

```
RideService : LifecycleService  (one FGS for the whole ride; type connectedDevice now,
│                                 + mediaPlayback|microphone when CarPlay arrives)
└── RideCoordinator.scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler{log})
    ├── bike: launch(dispatchers.bikeLink) { bikeLink.run() }
    │     └── LinkSupervisor: forever { session → recovery decision }
    │           └── coroutineScope { … }   one per connection; everything below dies with the link
    │                 ├── notifications collector → buttons (Channel, capacity 16, DROP_OLDEST)
    │                 └── setup sequence, then await disconnect
    │     └── CtsServer.run()              GATT server, as long as the bike engine runs
    ├── controls: bikeLink.buttons.collect { router.dispatch(it) }   errors → ControlResult, never thrown
    ├── status: combine(bikeLink.state, carPlay?.state) → notification (distinctUntilChanged)
    └── carplay: (later) launch { carPlayEngine.run(config) }  sibling of bike, isolated failure domain
```

Rules (official coroutine best practices, plus what the prototype got wrong):
- **One failure domain per engine.** The coordinator's `SupervisorJob` means a CarPlay crash-loop can
  never take the handlebar link down, and the reverse also holds.
- **Confinement.** `dispatchers.bikeLink = Dispatchers.Default.limitedParallelism(1, "bike-link")` holds
  **all** link state. BLE and GATT-server callbacks (binder threads) only `trySend` into channels; there
  are no shared mutable fields.
- **Injection.** Dispatchers and time come in through an `AppDispatchers` and a `TimeProvider`, so tests run
  on `StandardTestDispatcher` with virtual time. No `GlobalScope`, and no hardcoded `Dispatchers.X` outside
  `AppGraph` (detekt `InjectDispatcher`, `GlobalCoroutineUsage`).
- **Who launches.** Engines expose `suspend fun run()` plus `Flow`s. Only `RideCoordinator` and ViewModels
  *launch*.
- **Cleanup.** Anything that must run after cancellation (`gatt.disconnect(); gatt.close()`, unregistering
  receivers, later codec release) goes in `finally { withContext(NonCancellable) { … } }`.
- **Stopping.** Stopping means cancelling the coordinator scope; there are no stop flags.
- **Real-time media is not coroutine work.** Later, CarPlay audio and video run on dedicated threads inside
  the engine; only control-plane work runs in coroutines.

### 4.2 Recovery policy (bike; CarPlay will reuse the shape)

- **`Recovery.Backoff`:**
  - exponential 1 s → 2 → 4 → … capped at 30 s, with ±20 % jitter;
  - the attempt counter resets once a session has stayed `Ready` for 60 s;
  - RoadSync retries timeouts and disconnects the same way and re-runs the full setup every time; status 13
    "already registered" counts as success (SAB_PROTOCOL.md §7).
- **`Recovery.WaitForBluetooth`:** suspend on the adapter-state flow (`ACTION_STATE_CHANGED`); no polling.
- **`Recovery.WaitForUser`:** suspend until settings or permissions change. These come as flows from
  `SettingsRepository` and from a permission checker re-evaluated on app resume.
- **Waiting for the bike is not a failure.** "Waiting for the bike" with `autoConnect=true` does not consume
  backoff.
- **Start/stop trigger: deferred to the BLE research doc.** The two candidates are a forever-autoConnect and
  CDM device presence (`startObservingDevicePresence(ObservingDevicePresenceRequest)` +
  `CompanionDeviceService.onDevicePresenceEvent`, API 36+, which also grants the CDM exemption for starting
  an FGS from the background). `run()` is the same either way.

### 4.3 State machine

```kotlin
public sealed interface LinkState {
    public data object Stopped : LinkState
    public data class Blocked(val error: LinkError) : LinkState          // Recovery.WaitFor*
    public data object WaitingForBike : LinkState                        // autoConnect pending
    public data class SettingUp(val step: SetupStep) : LinkState         // Mtu, Discover, ReadInfo,
                                                                         // Notify, Assign(i, of), PhoneMode
    public data class Ready(val meter: MeterInfo, val since: Instant) : LinkState
    public data class Recovering(val last: LinkError, val attempt: Int, val retryAt: Instant) : LinkState
}

internal sealed interface LinkEvent { /* Start, Stop, AdapterOff, AdapterOn, PermissionLost,
                                         Connected, StepStarted(step), SetupDone(info),
                                         SessionEnded(error), BackoffElapsed */ }

/** Pure; no I/O. Illegal transitions return the same state and are logged by the caller. */
internal fun transition(state: LinkState, event: LinkEvent, now: Instant, backoff: BackoffPolicy): LinkState
```

- Hand-written sealed types plus a pure `transition` function, tested as a table (every state × every
  event). No FSM library: six states.
- Each engine has its own state. The UI and notification combine them and never merge them into one
  product type. The same applies to control availability (`ControlResult.Unavailable` reasons); folding
  these together would multiply states.

### 4.4 Android 17 items that touch this design (target 37)

- **Background audio hardening (targeting 37).** Volume changes, audio focus and playback started in the
  background need an FGS *with while-in-use capability*.
  - Today: the prototype's `adjustStreamVolume` from a `connectedDevice` FGS started by boot or CDM may
    fail silently. Test it in week 1.
  - Later, CarPlay:
    - `microphone` FGS types cannot be *created* from the background, even with the usual exemptions (FGS
      restrictions doc, "while-in-use").
    - So the CarPlay session must start from a visible activity. That is natural, because our activity
      hosts the video surface.
    - The ride service then adds `mediaPlayback|microphone` to its types via `startForeground(…, types)`
      while visible.
    - Design implication: `RideService` must support **upgrading** its FGS types at runtime. It must not be
      hard-wired to `connectedDevice`.
- **App memory limits (all apps).** Handled by `ExitInfoReporter` (§3.3). For CarPlay, budget codec
  buffers and test with `am memory-limiter`.
- **Local network permission (targeting 37).** `ACCESS_LOCAL_NETWORK` is required for LAN access. This is
  relevant to the CarPlay engine's mDNS/AirPlay sockets over Wi-Fi Direct; the CarPlay research must
  confirm whether P2P group traffic is covered.
- **Autonomous Bluetooth re-pairing (all apps).** `ACTION_KEY_MISSING` arrives only after the system's own
  retry fails. Log bond-state broadcasts; don't build our own re-pair UI.

---

## 5. Coding rules (short, enforceable)

| # | Rule | Enforced by |
|---|---|---|
| 1 | Official Kotlin style, 120 cols, trailing commas | Spotless + ktlint 1.8.0 (`spotlessCheck` in `check`) |
| 2 | Kotlin warnings are errors | `allWarningsAsErrors = true` in convention plugins (NiA does it behind a property) |
| 3 | Library modules use explicit API mode; default to `internal` | `kotlin { explicitApi() }` in `hl.*.library` |
| 4 | Engines never depend on each other; only `:app` depends on engines; `:core`/`*:protocol` have no Android | `checkModuleBoundaries` in `check` |
| 5 | No `!!`; nullable only when null is meaningful | detekt `UnsafeCallOnNullableType`; Bitwarden style guide |
| 6 | No `GlobalScope`; no hardcoded `Dispatchers.*` outside `AppGraph`/tests | detekt `GlobalCoroutineUsage` (on), `InjectDispatcher` |
| 7 | Never swallow cancellation: no `runCatching` around suspend calls; rethrow `CancellationException` | detekt `SuspendFunSwallowedCancellation`, `TooGenericExceptionCaught` (allowed only at the named boundaries, with `@Suppress` + reason) |
| 8 | Time only via injected `Clock`/`TimeSource` | detekt `ForbiddenMethodCall`: `java.time.*.now`, `System.currentTimeMillis` |
| 9 | Logging only via `Logger`; no `android.util.Log`, `println`, `printStackTrace` | detekt `ForbiddenImport`/`ForbiddenMethodCall`, `PrintStackTrace` |
| 10 | Expose `StateFlow`/`Flow`, never `Mutable*` | `explicitApi` return types + review |
| 11 | No class-level `@SuppressLint("MissingPermission")`; permissions checked at the adapter, functions annotated `@RequiresPermission` | Lint `MissingPermission` = error; CI `grep` step fails on the string |
| 12 | Every manifest component `exported="false"` unless guarded by a system permission | Lint (`ExportedService`, `ExportedReceiver`, …) as errors + review |
| 13 | Interfaces named for the role; impls prefixed by technology (`AndroidGattTransport`, `MediaSessionControlTarget`); fakes `Fake*` | Official naming recommendation; Thunderbird ADR 0004 |
| 14 | Protocol bytes and UUIDs only in `*:protocol`, each with a KDoc pointer to its spec section | detekt `MagicNumber` outside protocol modules |
| 15 | DFU service `0ea1bd92-…` and data command `0x0B` are never written | Denylist in `:bike:protocol` + `FakeGattTransport` fails any test that touches them |
| 16 | No `FIXME`/`STOPSHIP` on main; KDoc on public API | detekt `ForbiddenComment`, `UndocumentedPublicClass/Function` (Bitwarden config) |
| 17 | `./gradlew check` green before every commit to `v2` | CI required check; optional local pre-push hook |

---

## 6. Security

### 6.1 Permissions (least privilege)

Bike phase:
- `BLUETOOTH_CONNECT`
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- `POST_NOTIFICATIONS`
- `RECEIVE_BOOT_COMPLETED`
- CDM: `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` (preferred over
  `…RUN_IN_BACKGROUND`, per the FGS doc). Add `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` if the BLE doc
  picks presence observation.
- `BLUETOOTH_SCAN` only if we keep our own scan (CDM pairing does the scan for us).
- Drop `WAKE_LOCK` unless a measured need appears.
- **No `INTERNET`** in the bike phase.

CarPlay phase adds `NEARBY_WIFI_DEVICES` (Wi-Fi Direct), `INTERNET`, `ACCESS_LOCAL_NETWORK`,
`RECORD_AUDIO`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and `FOREGROUND_SERVICE_MICROPHONE`. Each one is
added in the PR that first needs it, never ahead of time.

### 6.2 Exported components

| Component | exported | Guard |
|---|---|---|
| `MainActivity` (launcher) | true | required for launcher |
| `RideService` | false | — |
| `BootReceiver` | false | `BOOT_COMPLETED` is a protected system broadcast; verify once on the Poco |
| `MediaAccessListener` (NotificationListenerService) | true | `BIND_NOTIFICATION_LISTENER_SERVICE` (system only). **Interim**: notification access is a very broad grant, used only to reach an external CarPlay host's media session. Deleted when our own receiver exists. |
| `BikePresenceService` (CompanionDeviceService) | true | `BIND_COMPANION_DEVICE_SERVICE` |
| `FileProvider` (diagnostics) | false | `grantUriPermissions=true`, explicit grant flags |
| Dynamic receivers | n/a | `RECEIVER_NOT_EXPORTED` (lint `UnspecifiedRegisterReceiverFlag`) |

Also set `android:allowBackup="false"` plus `dataExtractionRules` that exclude everything. The bond lives
in the OS, and settings are trivially re-created.

Single app, single process: the bike engine and the CarPlay engine talk in-process through `:app`, so there
is no IPC surface to protect.

### 6.3 Secrets, signing, R8

- **Never in git:**
  - keystores and passwords;
  - `local.properties`;
  - **the future MFi/CarPlay identity assets** (private key + certificate).

  CarLink is a cautionary reference here. It copies only `offline-mfi/` from `DIPLAY_AUTH_ASSETS_DIR` into
  a generated asset dir at build time, because "1.0.0-1.0.7 shipped the whole private folder, release keys
  included" (CARLINK_NOTES.md §3.2). It also disables lint `PackagedPrivateKey` (`carlink-src/app/build.gradle.kts:155`).

  We do the same injection, from `~/.handlebarlink-private/`, via a `providers.environmentVariable`/Gradle
  property input. Differences: we keep `PackagedPrivateKey` enabled, with a narrowly scoped `lint.xml`
  ignore for that one generated path, and CI builds without the assets (the engine reports
  "identity missing").
- **`.gitignore`** covers `*.jks`, `*.keystore`, `*.pk8`, `*.p7b`, `local.properties` and
  `keystore.properties`.
- **Release signing** reads `HL_KEYSTORE_PATH`, `HL_KEYSTORE_PASSWORD`, `HL_KEY_ALIAS` and
  `HL_KEY_PASSWORD` from env or `~/.gradle/gradle.properties`. In CI the keystore is a base64-encoded
  encrypted repository secret, decoded to `$RUNNER_TEMP`.
- **gitleaks** runs in CI (GitHub secret scanning and push protection on private repos are a paid add-on).
- **Key material at runtime** (the SAB session key if the exchange is ever implemented, SAB_PROTOCOL.md §3;
  later CarPlay pairing keys): AES-GCM wrap with a non-exportable Android Keystore key. DataStore stores
  only ciphertext.
- **R8:**
  - Use the AGP ≥ 9.3 DSL `buildTypes { release { optimization { enable = true } } }`, with keep rules in
    `app/src/main/keepRules/handlebarlink.keep` (official "Enable app optimization" page).
  - Add `-dontobfuscate`: shrinking and optimization still apply, and on-device crash/ANR files stay
    readable with no retrace step. There are no secrets in code to hide.
  - Archive `mapping.txt` per release anyway.
- **Wrapper integrity:** `distributionSha256Sum` in `gradle-wrapper.properties` (NiA does this), plus the
  default wrapper validation in `setup-gradle`.

---

## 7. Gradle setup and exact tooling

### 7.1 Versions (verified 2026-11-16)

| Tool / library | Version | Verified from |
|---|---|---|
| JDK (build) | 21 (local `/opt/homebrew/opt/openjdk@21`; CI Temurin 21) | AGP 9.5 needs ≥17; Bitwarden/NiA CI use 21 |
| JVM bytecode target | 17 (`compileOptions` + `jvmTarget`; built-in Kotlin follows `targetCompatibility`) | Prototype already used 17; no external consumer constrains it |
| Gradle | 9.8.2 (9.9.1 is out; AGP 9.5 needs ≥ 9.6.0) | works locally; Bitwarden wrapper; services.gradle.org |
| Android Gradle Plugin | 9.5.1 | Google Maven; Thunderbird 9.5.1, Bitwarden 9.5.0 |
| Kotlin (via AGP built-in Kotlin) | 2.4.21 | Maven Central; Bitwarden, Thunderbird (2.5.0 is RC only) |
| compileSdk / targetSdk / minSdk | 37 / 37 / 33 | AGP 9.5 max API 37; device is Android 17; 33 gives the non-deprecated GATT APIs |
| kotlinx-coroutines | 1.11.0 | Maven Central; Bitwarden, Thunderbird |
| androidx core-ktx | 1.19.1 | Google Maven |
| androidx lifecycle (service, runtime-compose, viewmodel-compose) | 2.11.0 | Google Maven; Bitwarden |
| activity-compose | 1.13.0 | Google Maven |
| Compose BOM | 2026.09.00 (Material 3 from BOM) | Google Maven; Bitwarden, Thunderbird |
| Navigation 3 (when a 2nd screen appears) | 1.2.1 | Google Maven; official "single activity + Navigation 3" |
| DataStore (preferences) | 1.3.0 | Google Maven |
| JUnit | 4.13.2 | NiA, Thunderbird |
| kotlinx-coroutines-test | 1.11.0 | — |
| Turbine | 1.2.1 | Maven Central; Bitwarden, Thunderbird |
| AssertK | 0.28.1 | Maven Central; Thunderbird ADR 0003 |
| Robolectric | 4.18 (API 24–37) | GitHub release 2026-11-13, README |
| androidx.test ext-junit | 1.3.0 | Google Maven |
| Kover | 0.9.11 | Maven Central; Bitwarden, Thunderbird |
| Spotless plugin | 8.11.0 | Maven Central; Thunderbird |
| ktlint | 1.8.0 | Maven Central |
| detekt | 1.23.8 (`io.gitlab.arturbosch.detekt`) | Maven Central; Bitwarden runs it with Kotlin 2.4.21 / AGP 9.5. 2.0 is still `2.0.0-alpha.6` (Thunderbird) |
| Android Lint | bundled with AGP 9.5.1 | — |
| androidx.lint:lint-gradle (lint for build-logic) | 1.0.0 | NiA `build-logic/convention/build.gradle.kts` |
| GitHub Actions | checkout v7.0.1, setup-java v6, gradle/actions v6.4.1, upload-artifact v7.0.2, all pinned by commit SHA | GitHub releases API |

Deliberately not used (see §10): Hilt 2.60.1, KSP 2.3.14, Koin 4.2.2, MockK, Roborazzi, baseline-profile plugin.

### 7.2 Build files (shape)

- **`settings.gradle.kts`:**
  - `pluginManagement { includeBuild("build-logic") }`.
  - Repositories with `content { includeGroupByRegex("androidx.*") … }` filters and
    `RepositoriesMode.FAIL_ON_PROJECT_REPOS` (NiA `settings.gradle.kts`).
  - `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`.
  - `include(":core", ":bike:protocol", ":bike:link", ":app")`.
- **`gradle.properties`:**
  - `org.gradle.configuration-cache=true`, `org.gradle.caching=true`, `org.gradle.parallel=true`;
  - `org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8`;
  - `kotlin.code.style=official`;
  - `android.experimental.enableTestFixturesKotlinSupport=true` (Bitwarden).
- **AGP 9 built-in Kotlin:** do *not* apply `org.jetbrains.kotlin.android`.
  - Compose comes via `org.jetbrains.kotlin.plugin.compose` 2.4.21 (Bitwarden `build.gradle.kts`).
  - Kotlin options go under `kotlin { compilerOptions { … } }`; `jvmTarget` follows
    `compileOptions.targetCompatibility`.
  - JVM modules use `org.jetbrains.kotlin.jvm` 2.4.21.
- **Convention plugins as precompiled scripts** (`hl.*.gradle.kts`), like Thunderbird's `build-plugin/`,
  not NiA's one-class-per-plugin style. That means less ceremony for six plugins. The version catalog is
  reached through the `LibrariesForLibs` accessor trick
  (`thunderbird-android/build-plugin/src/main/kotlin/ProjectExtension.kt`, `build-plugin/build.gradle.kts`).
- **Module build files stay around 10 lines.** For example, `:bike:link` is
  `plugins { id("hl.android.library") }` plus
  `dependencies { api(projects.bike.protocol); api(projects.core); implementation(libs.kotlinx.coroutines.android) }`.
- **Lint** (in `hl.android.*`):
  - `warningsAsErrors = true`, `abortOnError = true`;
  - `checkDependencies = true` (app), `checkReleaseBuilds = true`, `sarifReport = true`;
  - `lintConfig = config/lint/lint.xml`;
  - no baseline (greenfield).

  NiA and Thunderbird set the same flags; Thunderbird keeps warnings non-fatal because of legacy code, and
  we have none.
- **`check`** depends on `spotlessCheck`, `detekt`, `lint`, unit tests, `koverVerify` and
  `checkModuleBoundaries`. One command, locally and in CI.

---

## 8. Testing plan

Principles:
- The official recommendation "Prefer fakes to mocks" is "strongly recommended".
- NiA "does not use any mocking libraries" (`nowinandroid/README.md:91-98`).
- Thunderbird's testing guide: "Prefer fake implementations over mocks", AAA structure, `testSubject`.
- Test doubles for an engine live in that engine's `testFixtures`, so `:app` tests reuse them (Bitwarden's
  `src/testFixtures/` convention, `docs/ARCHITECTURE.md` "Supporting Modules"). Each engine ships a
  `Fake<Engine>` for app-level tests.

| Layer | What | How | Gate |
|---|---|---|---|
| `:bike:protocol` | Assignment frames & request-id wraparound (uint16 pre-increment), command ids (uint8 post-increment), reply matching, status 1/13, series-code decoding (`F5 03` → `03F5` → CB350), CTS bytes for fixed times/zones, DFU denylist | Plain JUnit4 + AssertK; golden vectors from `SAB_PROTOCOL.md` and real captures in `notes/bike-test.log` | Kover ≥ 90 % lines |
| `:bike:link` state machine | `transition()` table: every state × event | Pure function tests | — |
| `:bike:link` session | Setup order = protocol §4.2; retries (5 per assignment, 3 per command); missing services → `NotAHondaMeter`; disconnect mid-setup → `Recovering`; notifications → `buttons`; unknown codes logged, not fatal; cancellation closes the transport | `FakeGattTransport` + scripted `FakeMeter` (replies, delays, silent drops, disconnects; 10 then 9 after REQUEST_SAB_MODE as verified on the CB350); `runTest` + `StandardTestDispatcher`; Turbine | Kover ≥ 80 % lines |
| `:bike:link` supervisor | Backoff growth, cap, jitter bounds, reset after 60 s Ready; `WaitForBluetooth` resumes on adapter ON; no busy loop | Virtual time (`advanceTimeBy`), injected `TimeProvider` | — |
| `:core` | Logger fan-out, logfmt rendering | JUnit4 | — |
| `:app` controls | FunctionId → ControlAction table; `Unavailable` paths | Pure tests + `FakeControlTarget` | — |
| `:app` coordinator | Engine failure isolation (bike keeps running when another child fails), status combination | `FakeBikeLink` (+ later `FakeCarPlayEngine`), virtual time | — |
| `:app` ViewModel | `uiState` for each engine-state combination; actions | Fakes, `MainDispatcherRule` (NiA `core/testing/.../MainDispatcherRule.kt`), assert `value` + Turbine | — |
| `:app` service shell | Starts foreground with the right type(s), posts notification, stops cleanly | Robolectric 4.18 (only here) | — |
| Device | Ride checklist: cold start from boot, bike on/off ×3, Bluetooth toggle, CarPlay host not yet playing, permission revoked mid-ride, 2-hour ride | Manual on the Poco + `adb` log pull over the Windows SSH bridge | before each tag |

Notes:
- Real BLE is not automated: no emulator stack is worth trusting.
- Optional later: an ESP32 meter simulator for hardware-in-the-loop tests at the desk. RoadSync itself
  knows `HONDA BTU SIM`/`ARD` test devices (SAB_PROTOCOL.md §1.1).
- For CarPlay later, `:carplay:protocol` gets the same golden-vector treatment from captured sessions.
- Coverage uses Kover 0.9.11 with `koverVerify` rules per engine module, and exclusions like Bitwarden's root
  `build.gradle.kts` (`@Preview`, generated code). The HTML report is a CI artifact; no Codecov.

---

## 9. CI (GitHub Actions, private repo)

`.github/workflows/ci.yml` (shape, not final):

```yaml
name: CI
on: { push: { branches: [v2, main] }, pull_request: {}, workflow_dispatch: {} }
concurrency: { group: ci-${{ github.ref }}, cancel-in-progress: true }
permissions: { contents: read }
jobs:
  build:
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@<sha> # v7.0.1
        with: { persist-credentials: false }
      - uses: actions/setup-java@<sha> # v6
        with: { distribution: temurin, java-version: 21 }
      - uses: gradle/actions/setup-gradle@<sha> # v6.4.1
        with: { cache-provider: basic }          # MIT-licensed, free for private repos
      - run: "! grep -rn 'SuppressLint(\"MissingPermission\")' --include='*.kt' ."
      - run: ./gradlew check assembleDebug assembleRelease --stacktrace
      - uses: actions/upload-artifact@<sha> # v7.0.2
        if: always()
        with:
          name: reports
          path: |
            **/build/reports/
            app/build/outputs/mapping/release/mapping.txt
  secrets-scan:
    runs-on: ubuntu-24.04
    steps:  # checkout with fetch-depth: 0, then run the gitleaks CLI (v8.30.x) via its container image
```

Notes:
- **Release builds.** In CI, `assembleRelease` uses debug signing unless the signing secrets exist. A
  separate `release.yml` runs on `v*` tags: it signs with the secrets and attaches the APK and
  `mapping.txt` to a GitHub release. CI never has the MFi assets.
- **Action hygiene.** Pin actions by SHA and keep `persist-credentials: false`
  (Bitwarden `.github/workflows/test.yml`). Run `zizmor` (Bitwarden `.github/zizmor.yml`) occasionally or as
  a second job.
- **Dependabot** (`.github/dependabot.yml`): `gradle` + `github-actions`, weekly, grouped (androidx,
  kotlin, test). Renovate (Bitwarden) is more powerful but needs an app install; not worth it here.
- **Budget.** GitHub Free gives private repos 2,000 Linux minutes per month; one run takes a few minutes.
- **No emulator or instrumented job.** There is nothing meaningful to run without the meter or an iPhone.

---

## 10. What we deliberately skip, and why

| Skipped | Why |
|---|---|
| Hilt (and KSP) now | Officially "Recommended" for apps with several ViewModel screens, WorkManager, nav-scoped VMs. Day one has one screen and one service. Engines expose factories, so adopting Hilt later touches only `:app`. Trigger: ~4 ViewModel screens or WorkManager. |
| Koin | Runtime-resolved graph (errors surface at runtime). Thunderbird needs it for ~100 modules; manual DI is ~50 lines for us. |
| Creating `:carplay:*` modules now | Empty modules are pure overhead. The seams (dependency rules, `ControlTarget`, `:core`, multi-engine service) are what must exist on day one. |
| NiA-style `:core:*` granularity, `:feature:*:api/impl`, domain layer / use cases | "Too fine-grained" pitfall; one `:core` is enough. The engine module *is* the API boundary (`explicitApi`), so no separate api module is needed. |
| Room / any database | A few settings → DataStore. |
| Navigation library on day one | One screen; pairing is the system CDM dialog. Adopt Navigation 3 when a second screen appears. |
| Baseline profiles / Macrobenchmark | Value is in the background service; startup time is irrelevant. Compose ships its own profiles. Revisit for the CarPlay activity if first-frame latency matters. |
| Screenshot tests (Roborazzi/Paparazzi), Compose UI test suite | A small status UI; cost exceeds value. |
| Instrumented tests / Gradle Managed Devices in CI | BLE and CarPlay cannot be meaningfully emulated; the device checklist covers it. |
| MockK / Mockito | Fakes are the official recommendation and match NiA/Thunderbird. Mocks couple tests to call order, which is exactly what changes in BLE code. |
| JUnit 5/6 | Robolectric runs on JUnit 4. Bitwarden needs the Jupiter + Vintage engine combination to mix the two; one framework is simpler. |
| Crashlytics/Sentry/analytics | No third-party services; local crash/exit capture + share is enough for one rider. |
| Product flavors | One distribution channel (sideload). |
| Dependency Guard, dependency locking, verification-metadata | Team-scale supply-chain controls. Wrapper checksum + Dependabot + a small dependency set suffice; reconsider when the CarPlay engine adds crypto/media dependencies. |
| detekt 2.0 alpha, detekt-formatting | 2.0 is still alpha in Nov 2026. Formatting already comes from Spotless/ktlint 1.8.0; detekt-formatting would bundle an older ktlint and fight it. |
| Compose detekt rules (`io.nlopez.compose.rules`) | Little UI yet; add when the CarPlay/settings UI grows. |
| Custom lint rules module (NiA `:lint`) | `checkModuleBoundaries` + a CI grep cover our custom rules. |
| FSM libraries | Six states per engine; a pure function is clearer. |
| Timber | Our `Logger` needs fields and multiple sinks, and lives in `:core`. |
| R8 obfuscation | No secrets in code; readable on-device stack traces matter more. Shrinking and optimization stay on. |
| License headers, Codecov, Renovate | Private single-developer project. |
| Choosing a BLE library (own wrapper vs. Nordic Kotlin-BLE-Library) | Out of scope here; the `GattTransport` seam makes it swappable. See the BLE research doc. |

---

## 11. Open points to settle on the bike / in later research

1. Does volume up/down work from the background service when targeting 37 (§4.4)?
2. Does `BootReceiver` with `exported="false"` receive `BOOT_COMPLETED` on this ROM? Expected: yes.
3. Trigger model (forever-autoConnect vs. CDM presence): decided in the BLE research doc. `BikeLink.run()`
   does not change.
4. For the CarPlay research:
   - FGS-type upgrade flow (start from the visible activity);
   - whether `ACCESS_LOCAL_NETWORK` covers Wi-Fi Direct group traffic;
   - whether `:carplay:protocol` earns a separate module.
