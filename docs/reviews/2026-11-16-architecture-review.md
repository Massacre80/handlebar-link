# Handlebar Link v2: architecture review against Google's official guidance

Date: 2026-11-16. Repo: `/Volumes/My files/pocof1/handlebar-link`, branch `v2` at `188321b` (M2.3).
Scope: structure, build logic, `:core`, `:bike:protocol`, `:bike:link`, `:designsystem`, `:app`, and the
plans for M2.4 (runtime + public `BikeLink` API), M2.5 (Android adapters) and M3 (ride service, routing,
status UI, Hilt). This was a read-only review: nothing in the repo was changed, built or run.

## Sources used

Official Android Knowledge Base (`~/.local/bin/android docs fetch …`):

| Short name | URL |
|---|---|
| ARCH-REC | `kb://android/topic/architecture/recommendations` |
| ARCH | `kb://android/topic/architecture/index` |
| DATA | `kb://android/topic/architecture/data-layer/index` |
| UI | `kb://android/topic/architecture/ui-layer/index`, `kb://android/topic/architecture/ui-layer/state-production` |
| MOD | `kb://android/topic/modularization/index` |
| MOD-PAT | `kb://android/topic/modularization/patterns` |
| CORO | `kb://android/kotlin/coroutines/coroutines-best-practices` |
| CORO-TEST | `kb://android/kotlin/coroutines/test` |
| FLOW | `kb://android/kotlin/flow/stateflow-and-sharedflow` |
| HILT | `kb://android/training/dependency-injection/hilt-android` |
| HILT-TEST | `kb://android/training/dependency-injection/hilt-testing` |
| DOUBLES | `kb://android/training/testing/fundamentals/test-doubles` |
| BLE-BG | `kb://android/develop/connectivity/bluetooth/ble/background` |
| FGS-TYPES | `kb://android/develop/background-work/services/fgs/service-types` (Connected device) |
| FGS-LAUNCH | `kb://android/develop/background-work/services/fgs/launch` |
| CDM | `kb://android/develop/connectivity/bluetooth/companion-device-pairing` ("Keep companion apps awake") |
| AGP9 | `kb://android/build/releases/agp-9-0-0-release-notes` |

The repo's official skills live in `.claude/skills/`. The ones cited here are `testing-setup`,
`android-intent-security`, `android-permissions-security`, `anr-audit-and-fix` and `r8-analyzer`.
`navigation-3`, `edge-to-edge` and `jetpack-compose-performance` matter for M5 only.

Now in Android (NiA) is the reference app, at commit `2982e63`, shallow-cloned and only read. The paths
below are relative to its root, and `…/` stands for `src/main/kotlin/com/google/samples/apps/nowinandroid/`.

**[J]** marks my own judgement where no official text says it. Everything else cites a source.

---

## (a) Findings, ranked

Severity:
- **MUST**: must-fix before M2.4 starts.
- **M2.4–M3**: fix while building M2.4 to M3.
- **OPT**: optional.

### MUST-1: the coroutine and cancellation gates never run, just before M2.4 needs them

- **Evidence:**
  - `config/detekt/detekt.yml:14-20` turns on `SuspendFunSwallowedCancellation`, and the project
    relies on detekt's `InjectDispatcher` (research 01 §5, rules 6 and 7).
  - Both rules need type resolution in detekt 1.23.8. I checked: the `InjectDispatcher` and
    `SuspendFunSwallowedCancellation` classes in `detekt-rules-coroutines-1.23.8.jar` carry
    `@RequiresTypeResolution`.
  - The type-resolving detekt tasks are deliberately left out of `check`
    (`build-logic/convention/src/main/kotlin/hl.jvm.library.gradle.kts:23-26`, `docs/testing.md:18-22`).
    So both rules are dead configuration.
  - The fallback scan `CheckForbiddenCode.kt:60-77` covers `println`, `Log`, clocks, `GlobalScope` and
    `MissingPermission`. It does **not** catch hard-coded `Dispatchers.*`, `runCatching`, generic
    `catch (e: Exception|Throwable)`, `runBlocking`, or ad-hoc `CoroutineScope(...)`.
- **Why it matters now:** M2.4 is the first code with dispatchers, scopes, timers and a catch-all
  session boundary.
- **Official source:**
  - CORO "Inject Dispatchers": "Don't hardcode `Dispatchers` when creating new coroutines or calling
    `withContext`".
  - CORO "Watch out for exceptions": don't consume `CancellationException`.
  - CORO "Avoid GlobalScope … inject a `CoroutineScope`".
- **Fix:** add these rules to `checkForbiddenCode`:
  - `\bDispatchers\.(Main|IO|Default|Unconfined)\b`, allowed only in `:app`'s `di/` files;
  - `\brunCatching\b` and `\brunBlocking\b` in `src/main`;
  - `CoroutineScope\(` outside an allowlist **[J]**;
  - `catch \(\w+: (Exception|Throwable)\)`, unless a `@Suppress("TooGenericExceptionCaught")` with a
    reason sits on the same declaration.

  Also correct `docs/testing.md:18-22` so it no longer implies these rules are covered.

### MUST-2: `:bike:link`'s public API exposes other modules' types through `implementation` dependencies

- **Evidence:**
  - Public `LinkError.MeterRefused(function: FunctionId, status: AssignmentStatus)` (`LinkError.kt:3-4,116-121`).
  - `ButtonEvent(switch: HandlebarSwitch)` and `MeterInfo(seriesCode: SeriesCode?, displayCapability: DisplayCapability?)`
    (`LinkState.kt:3-5,81-94`).
  - Yet `bike/link/build.gradle.kts:13-17` declares `implementation(projects.bike.protocol)`,
    `implementation(projects.core)` and `implementation(libs.kotlinx.coroutines.android)`.
  - It compiles only because `:app` adds `:bike:protocol` and `:core` itself (`app/build.gradle.kts:19-21`).
  - The M2.4 API will add `StateFlow` (coroutines) and `Logger`/`TimeProvider` (`:core`) to public
    signatures (`BikeLink.create(...)`).
- **Official source:**
  - MOD-PAT "Expose as little as possible: prefer `implementation` over `api`". That covers *internal*
    use. When a type is part of the public API, the consumer needs it.
  - NiA does exactly this: `core/data/build.gradle.kts` uses `api(projects.core.common)`,
    `api(projects.core.database)` and so on for modules whose types appear in its API.
  - ARCH-REC "Create a model per layer in complex apps" (Recommended) is the alternative.
- **Fix:** before writing `BikeLink`, decide each exposed type:
  - `api(projects.core)` (or `:core:common`, see MUST-4) and `api(libs.kotlinx.coroutines.core)` for
    `Logger`, `TimeProvider` and `StateFlow`;
  - for the protocol types, either `api(projects.bike.protocol)` or link-owned models **[J: `api` is
    simpler; `ControlRouter` needs `HandlebarSwitch` anyway]**;
  - then drop `:app`'s direct `:bike:protocol` dependency if nothing else in `:app` uses it.

### MUST-3: shared test doubles have no home; the doc says `testFixtures`, the code uses `src/test`

- **Evidence:**
  - `docs/testing.md:26-28` says a `Fake*` goes "in the module's `testFixtures`, so other modules' tests
    can reuse it".
  - In reality `FakeGattLink` is in `bike/link/src/test/.../gatt/FakeGattLink.kt:22`, and no convention
    plugin enables test fixtures.
  - `MeterSessionTest.kt:48-52` builds an ad-hoc `Logger` with `Clock.fixed`. Every engine test, and
    later `:app`, will repeat that.
  - In M3, `:app` tests need a `FakeBikeLink` (public API), a virtual-time `TimeProvider` and a
    recording `LogSink`.
- **Official source:**
  - MOD-PAT "Test modules … Shared test code: if … some of the test code is applicable to more than one
    module, you can create a test module to share the code".
  - DOUBLES: library authors should provide "officially-supported testing infrastructures, such as fakes".
  - `testing-setup` SKILL.md Step 4: put the `Fake` in the test source set and use DI to swap runtime fakes.
  - NiA uses test modules, never `testFixtures` (no hit for `testFixtures` in the repo):
    - `core/testing` (test dispatchers, `TestDispatchersModule`, test repositories);
    - `core/data-test` (`Fake*Repository` plus `TestDataModule`, `@TestInstallIn`), which `app` consumes
      as `testImplementation`/`androidTestImplementation(projects.core.dataTest)` (`app/build.gradle.kts`).
- **Fix:**
  - (a) Fakes of `internal` seams (`FakeGattLink`, `Harness`) stay in `src/test`. They can't be shared
    anyway.
  - (b) Create `:core:testing` (JVM) for `TestTimeProvider(testScheduler)`, `RecordingLogSink` and
    `testLogger()`.
  - (c) Create `:bike:link-test` (or `:bike:testing`) with `FakeBikeLink`, implementing the public
    `BikeLink`.
  - Update `docs/testing.md:26-28` to match.
  - **[J]** Prefer test modules over `testFixtures`. They behave the same in JVM and Android modules,
    match NiA, and work with Hilt `@TestInstallIn`. AGP 9 supports `testFixtures` (AGP9 "IDE support
    for test fixtures"), but nothing in the repo or in NiA uses them.

### MUST-4: module naming should follow the `core:*` convention while it is still cheap **[J on timing]**

- **Evidence:**
  - `settings.gradle.kts:39-43` has `:core` (a module), `:designsystem` (an empty module: only an empty
    package directory exists), `:bike:protocol`, `:bike:link` and `:app`.
  - M2.4 adds at least one shared test module (MUST-3).
  - A module named `:core` that also has child modules (`:core:testing`) works, but it is the odd one out
    compared with the reference layout.
- **Official source:**
  - MOD-PAT "Common modules, also known as core modules … UI module: … encapsulating your widget
    collection into a module".
  - NiA `settings.gradle.kts` and `docs/ModularizationLearningJourney.md`: `core:common` (dispatchers,
    `Result`), `core:designsystem` ("Design system which includes Core UI components … app theme"),
    `core:testing`; `core` is a folder, not a module.
- **Fix:**
  - Rename `:core` → `:core:common` (namespace `com.abid.handlebarlink.core.common`; this changes the
    imports of 4 main files and 2 tests).
  - Rename `:designsystem` → `:core:designsystem`.
  - Add `:core:testing`.
  - Update `hl.root.gradle.kts:22-28`, `AGENTS.md:14-15`, the README table and `PLAN.md:45-51`.
  - Keep `:bike:protocol` and `:bike:link`: per-domain modules match MOD-PAT "Data modules … of a certain
    domain".
  - **[J]** Doing this after M3 would touch every import and boundary rule. Today it is a ~10-file
    change. Do it in the same commit as MUST-5.

### MUST-5: `checkModuleBoundaries` cannot express the target graph

- **Evidence:**
  - `CheckModuleBoundaries.kt:39-57` regex-scans build files.
  - Research 01 says the check "walks `project.configurations`" (`docs/research/01-architecture.md:165`).
  - Consequences:
    - (i) Dependencies added by convention plugins are invisible. NiA's feature plugin adds
      `project(":core:designsystem")` this way.
    - (ii) It ignores configurations, so it cannot allow `testImplementation(projects.core.testing)`
      while forbidding `implementation(projects.core.testing)`.
    - (iii) Kebab-case paths in the NiA style (`:core:data-test`, `:bike:link-test`) break: the accessor
      `projects.bike.linkTest` maps to `:bike:linkTest` (`CheckModuleBoundaries.kt:50,63`).
- **Official source:**
  - MOD-PAT "Low coupling … Modules shouldn't have knowledge of the inner workings of other modules".
  - MOD "Strict visibility control".
  - NiA `build-logic/convention/src/main/kotlin/com/google/samples/apps/nowinandroid/Graph.kt` walks the
    resolved configurations.
- **Fix:**
  - Collect project dependencies per configuration from `configurations` at configuration time and
    pass them as task inputs (configuration-cache safe).
  - Add a rule: test-only modules (`:core:testing`, `*-test`) may appear only in
    `test*`/`androidTest*` configurations.
  - Add a rule: `:core:*` and `*:protocol` stay leaves.

---

### M2.4–M3-1: runtime lifetime, scope ownership and structured concurrency (the M2.4 core)

- **Evidence:**
  - The reducer emits effects that need a long-lived owner: `Connect`, `Close(gen)`, `RunSetup(gen)`,
    `StartTimer`/`CancelTimer`, `OpenServer`/`CloseServer`, `WakeLock`, `Foreground`, `Problem`
    (`machine/Machine.kt:179-225`).
  - There is no runtime yet.
  - Research 01 §4.1 has `RideCoordinator` launch `bikeLink.run()` (`01-architecture.md:494-514`).
  - Research 02 §6.1 proposes a `LinkRuntime` actor with `ServiceControl`/`Notifier` ports
    (`02-ble-connection.md:736-745`).
- **Official source:**
  - CORO "The data and business layer should expose suspend functions and Flows … the caller … control
    the execution and lifecycle".
  - CORO "Creating coroutines in the data layer": for work that must outlive the screen, use an
    *injected* external scope that "should be created and managed by a class that lives longer than
    the current screen".
  - DATA "Lifecycle": scope crucial instances to `Application`.
  - BLE-BG: process lifetime comes from a `connectedDevice` FGS or `CompanionDeviceService`.
- **Fix:** the M2.4 checklist in (c). In short:
  - the engine never builds its own `CoroutineScope`;
  - the actor runs inside `suspend fun run()` (or in an injected scope; pick one and write it down);
  - each connection generation is a child `Job`;
  - cleanup runs in `finally { withContext(NonCancellable) { … } }`.
  - **[J]** Prefer `run()` under the service's scope over app-scope ownership: the FGS is what keeps the
    process and the GATT link alive (BLE-BG).

### M2.4–M3-2: effects that belong to the app should cross the boundary as state, not callbacks **[J]**

- **Evidence:** `Effect.Foreground(on)` and `Effect.Problem(problem)` (`Machine.kt:181-183,221-224`) are
  app concerns (`startForeground`/`stopSelf`, the user notification). `Effect.WakeLock` (`:217-219`) is
  a platform resource.
- **Official source:**
  - CORO: the data layer exposes Flows for "data changes over time".
  - ARCH "Unidirectional data flow".
  - CORO "Don't expose mutable types".
- **Fix:**
  - Surface them in the public API as read-only state, either fields of one public status or separate
    `StateFlow`s: e.g. `keepAlive: StateFlow<Boolean>`, `problem: StateFlow<LinkProblem?>`. The ride
    service observes them.
  - Handle the wake lock inside `:bike:link`'s Android adapter (M2.5).
  - Avoid research 02's `ServiceControl` port: with it, the engine would call `stopSelf()` on the
    service that owns the engine's own scope, which inverts the lifetime.

### M2.4–M3-3: layering, i.e. where the "repository" is

- **Evidence:**
  - Research 01 §2.6 (`01-architecture.md:303-307`) calls `BikeLink` the "Bluetooth data provider" that
    the UI must reach "through a repository-like class", but it names no such class.
  - `:bike:link` already hides its data sources (`GattLink` is `internal`, `gatt/GattLink.kt:13`).
- **Official source:**
  - ARCH-REC "Expose application data from the data layer using a repository" (Strongly recommended).
    Its list of data sources includes "Bluetooth data providers".
  - MOD-PAT "Data modules: … Expose the repository as an external API … Hide all implementation details
    and data sources".
  - DATA "Multiple levels of repositories" (a repository may depend on other repositories).
  - DATA naming note: "managers" are acceptable.
- **Fix:**
  - Treat `:bike:link` as the bike data module. Its public `BikeLink` interface *is* the repository-level
    API (single source of truth: the actor's `StateFlow<LinkState>`).
  - The `BluetoothGatt` adapter, CTS server and system-broadcast monitors are its hidden data sources.
  - ViewModels depend only on `BikeLink` (read-only flows) or on an `:app` repository that combines it
    with `SettingsRepository` (DataStore), never on adapters.
  - Commands (`start`/`stop`/presence) come from the service, receivers and CDM service, not from
    composables.
  - Write this down in `PLAN.md` so "repository" isn't invented a second time in M3.
  - **[J]** Don't add an `api`/`impl` split. MOD-PAT "When to separate" (diverse capabilities, multiple
    apps, independent teams, large codebase) doesn't apply, and a `FakeBikeLink` can implement the public
    interface directly.

### M2.4–M3-4: dispatcher and time injection must share one test scheduler

- **Evidence:**
  - `core/time/TimeProvider.kt:9-15` gives `elapsedMillis()` for reducer time.
  - The runtime will implement `StartTimer` with `delay`. If `TimeProvider` and the test dispatcher are
    not the same virtual clock, timer tests go flaky.
  - Backoff `jitter` (`Machine.kt:204-210`) needs randomness.
- **Official source:**
  - CORO-TEST "There should only be one scheduler instance used in a test, shared between all
    `TestDispatchers`".
  - CORO "Inject TestDispatchers in tests".
- **Fix:**
  - `:core:testing` provides `TestTimeProvider(scheduler: TestCoroutineScheduler)`, with `elapsedMillis()`
    reading `scheduler.currentTime`.
  - The runtime takes `dispatcher: CoroutineDispatcher`, `time: TimeProvider` and `random: Random`
    through its factory **[J: inject `Random`]**.
  - Production uses one confined dispatcher (see M2.4–M3-6).

### M2.4–M3-5: the reducer caches the permission state, and the permissions skill forbids relying on that

- **Evidence:** `Facts.permissionGranted` (`machine/Machine.kt:35-39`) gates arming
  (`machine/Lifecycle.kt:33-35`) and is set only by `Event.Permission` (`machine/SystemFacts.kt:80-89`).
- **Official source:** `.claude/skills/android-permissions-security/SKILL.md`:
  - §8: "Never cache permission states … Check `ContextCompat.checkSelfPermission()` dynamically before
    each operation";
  - checklist: "MUST catch `SecurityException` around protected API calls" and "NEVER cache permission
    grant states in in-memory fields".
- **Fix:** keep the fact, but only as a *hint* for the state machine.
  - The M2.5 adapter checks `BLUETOOTH_CONNECT` at each call (`@RequiresPermission`).
  - It catches `SecurityException` in exactly one place and turns it into `Event.Permission(false)` /
    `LinkError.PermissionMissing`.
  - `:app` re-evaluates the permission on resume and on a permission change.
  - Document this in the KDoc of `Facts`.

### M2.4–M3-6: Android adapter rules for M2.5 (threading, receivers, main-safety)

- **Evidence:** research 02 §2.1 (`02-ble-connection.md:159-177`) plans a dedicated thread for GATT
  callbacks. `:bike:link` already depends on `kotlinx-coroutines-android` (`bike/link/build.gradle.kts:16`).
- **Official source:**
  - CORO "Suspend functions should be main-safe" and DATA "Threading: calling data sources and
    repositories should be main-safe".
  - `android-intent-security` SKILL.md §3 (lines ~106-118):
    - rely on protected broadcasts for system events;
    - use `RECEIVER_NOT_EXPORTED` for dynamic receivers;
    - with `minSdk < 33`, verify that the `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
      signature permission is declared.
  - NiA `core/data/…/core/data/util/TimeZoneMonitor.kt`: a broadcast becomes
    `callbackFlow { registerReceiver … awaitClose { unregister } }.distinctUntilChanged().conflate()
    .flowOn(io).shareIn(appScope, SharingStarted.WhileSubscribed(5_000), 1)`.
- **Fix:**
  - `AndroidGattLink` is the only class that uses `BluetoothGatt`. It calls `connectGatt` with the
    `Handler`/`Executor` overload on a dedicated thread, and its callbacks only `trySend` into the actor.
  - The CTS server's callbacks arrive on binder threads and also only `trySend`.
  - `minSdk` is 31 (< 33), so dynamic receivers use `ContextCompat.registerReceiver(…,
    RECEIVER_NOT_EXPORTED)`. Check that the merged manifest has the signature permission.
  - Never touch the main thread.

### M2.4–M3-7: Hilt wiring and test replacement for engines that stay framework-free

- **Evidence:**
  - Only `:app` applies `hl.hilt` (`hl.hilt.gradle.kts:3-17`); this is a deliberate PLAN decision
    (`PLAN.md:56-58`).
  - `:app` has no `di/` package yet.
  - Robolectric is in the catalog but not on any test classpath (`app/build.gradle.kts:18-27`), although
    `docs/testing.md:65` describes Robolectric + `@HiltAndroidTest` tests.
- **Official source:**
  - HILT "Provide multiple bindings for the same type" with qualifiers: "if you add a qualifier to a
    type, add qualifiers to all the ways to provide that dependency".
  - HILT-TEST "Replace a binding" with `@TestInstallIn(replaces = [...])`.
  - ARCH-REC "Scope to a component when … the type contains mutable data that needs to be shared"
    (Strongly recommended).
  - NiA `core/common/…/core/common/network/NiaDispatchers.kt` (`@Dispatcher(NiaDispatchers.X)`),
    `…/di/DispatchersModule.kt`, `…/di/CoroutineScopesModule.kt` (`@ApplicationScope`,
    `SupervisorJob() + Default`), and `core/testing/…/di/TestDispatchersModule.kt`.
- **Fix (M3):** `:app/di/` contains:
  - `DispatchersModule` plus qualifiers;
  - `TimeModule` (system `TimeProvider`);
  - `LoggingModule`;
  - `BikeLinkModule` (`@Provides @Singleton`, calling `BikeLink.create(...)`).

  Because `BikeLinkModule` is in `:app`, its `@TestInstallIn(replaces = [BikeLinkModule::class])`
  module must also live in `:app`'s `test`/`androidTest` source sets. It binds `FakeBikeLink` from
  `:bike:link-test`.

  Add `testImplementation(libs.robolectric)` in `:app` before writing the service test.

  **[J/deviation]** NiA puts Hilt modules next to the implementations: its `HiltConventionPlugin`
  applies Hilt to JVM and Android libraries, and `core/data/…/core/data/di/DataModule.kt` is
  `internal`. Keeping the engines framework-free is allowed, since ARCH-REC accepts Hilt *or* manual
  DI. Record it in `PLAN.md` as a conscious deviation from NiA.

### M2.4–M3-8: service, manifest and permission security (M3/M4)

- **Official source:**
  - FGS-TYPES, Connected device: declare `foregroundServiceType="connectedDevice"` and
    `FOREGROUND_SERVICE_CONNECTED_DEVICE`. Runtime prerequisite: `BLUETOOTH_CONNECT` (or SCAN/ADVERTISE)
    must be **granted** before `startForeground`.
  - FGS-LAUNCH: call `ServiceCompat.startForeground(…, type)`, and pass only declared types.
  - BLE-BG / CDM: `CompanionDeviceService` with `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`.
  - `android-intent-security`:
    - `exported="false"` for everything that doesn't need external callers;
    - `FLAG_IMMUTABLE` and explicit intents for every `PendingIntent`;
    - validate `onNewIntent`.
  - `android-permissions-security` §6:
    - check, show a rationale, then launch the request;
    - on permanent denial, send the user to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`;
    - never loop the request.
- **Fix:**
  - `RideService`: `exported=false`, type `connectedDevice`. Promote only when `keepAlive` is true and the
    permission is granted; otherwise stay in `Blocked` and post the problem notification.
  - CDM presence service: `exported=true`, guarded by `BIND_COMPANION_DEVICE_SERVICE`.
  - `BootReceiver`: `exported=false`, relying on the protected broadcast (verify on the Poco, as already
    planned).
  - Interim notification listener: guarded by `BIND_NOTIFICATION_LISTENER_SERVICE`.
  - Use the three-state request flow for `BLUETOOTH_CONNECT` and `POST_NOTIFICATIONS`.
  - Research 01 §6.2 already has the right table; turn it into a manifest checklist in M3.

### M2.4–M3-9: UI layer for the M3 status screen

- **Official source:** ARCH-REC (UI and ViewModel sections, Strongly recommended or Recommended):
  - a screen-level `ViewModel` exposing one `uiState: StateFlow` built with
    `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …)`;
  - `collectAsStateWithLifecycle`;
  - don't send events from the ViewModel to the UI;
  - don't use `AndroidViewModel`;
  - no `Context` in the ViewModel.
- **Fix:**
  - `StatusViewModel(bikeLink, settings)` with `uiState = combine(...).map(::toUiState).stateIn(...)`.
  - The UI never binds to the service.
  - The service builds its notification from the same flows with `distinctUntilChanged()`.
  - `WhileSubscribed(5_000)` belongs only here, never inside the engine (see (c)).
  - Add `androidx.lifecycle:lifecycle-service` (for `LifecycleService`) and
    `androidx.hilt:hilt-lifecycle-viewmodel-compose` to the catalog when M3 needs them.

### M2.4–M3-10: main-thread and ANR risks in logging and component callbacks

- **Evidence:** `Logger.log` fans out synchronously on the caller's thread (`core/log/Logger.kt:21-22`),
  and `LogSink` only promises not to throw (`core/log/Logging.kt:42-45`). The planned `FileLogSink` will
  do disk I/O.
- **Official source:**
  - `anr-audit-and-fix` `references/risk_analysis_guide.md:10-12`: synchronous disk I/O and "heavy work
    inside `Application`, `Activity`, `Service`, `BroadcastReceiver` … lifecycle callbacks" are ANR
    candidates.
  - DATA "Threading" (main-safe).
- **Fix:**
  - Document in `LogSink`'s KDoc that a sink must be non-blocking.
  - `FileLogSink` hands entries to its own single-thread writer (as research 01 §3.2 already plans).
  - `onReceive` and `onDevicePresenceEvent` only `trySend` or start the service.
  - `Application.onCreate` stays light.

---

### OPT-1: split `:bike:link` into a JVM engine and an Android adapter **[J]**

Today `machine/`, `session/` and the coming `runtime/` are pure Kotlin. Only M2.5 needs Android, yet the
whole module is an Android library (`bike/link/build.gradle.kts:2`).

MOD-PAT "Prefer Kotlin & Java modules … Android modules come with overhead" supports keeping the engine
and its public API in a JVM module. The `BluetoothGatt`, receiver and wake-lock adapters would go in an
Android module that `:app` depends on. This would make "the reducer and runtime never touch `android.*`"
a compiler guarantee, the same way `:bike:protocol` already is.

Cost: one more module and a factory that lives in the adapter module. It is worth doing only if done
before M2.5.

### OPT-2: R8 configuration follows the legacy DSL, not the AGP 9.3+ DSL the research chose

- **Evidence:** `hl.android.application.gradle.kts:29-35` (`isMinifyEnabled`, `isShrinkResources`,
  `proguardFiles`) and `app/proguard-rules.pro:2` (`-dontobfuscate`).
- **The repo's own intent:** research 01 picks `optimization { enable = true }` with
  `src/main/keepRules/*.keep` (`01-architecture.md:72,678-679`).
- **Official source:**
  - `r8-analyzer` `references/CONFIGURATION.md:11-37` (AGP ≥ 9.3: use `optimization { enable = true }`).
  - ARCH-REC Performance: "Enable R8 in full mode … obfuscate classes" (Strongly recommended).
- **Fix:** switch the DSL, and record `-dontobfuscate` in `PLAN.md` as a deviation with its reason
  (readable crash files without retracing).

### OPT-3: documentation drift (AGENTS.md:18-19 requires recording deviations)

- `PLAN.md:3` still says "proposed, awaiting approval … Nothing below is built yet".
- The M2.1–M2.5 sub-milestones aren't in the milestone table (`PLAN.md:104`).
- Research 01 still describes manual DI, `AppGraph`, Kover and `testFixtures`
  (`01-architecture.md:50,215`) without a "superseded by PLAN" note.
- Neither the deviations from NiA (framework-free engines, no test modules yet) nor the R8 choice is
  recorded.

### OPT-4: the empty `:designsystem` module

It contradicts research 01's "No empty placeholder modules" (`01-architecture.md:49`). Either rename it
now (MUST-4) and keep it, or drop it until M5.

### OPT-5: small naming and file-layout items

- `Timeouts.MTU` is a size, not a timeout (`machine/Timeouts.kt:54-55`).
- The public `ButtonEvent` and `LinkProblem` sit in `LinkState.kt:88-106`. **[J]** One file per public
  type makes the API easier to scan.
- ARCH-REC naming: name streams `get{Model}Stream` (Optional). Properties named `state` and `buttons`
  are fine; be consistent.
- ARCH-REC naming: name implementations meaningfully, `Default*` if nothing better, and fakes `Fake*`.
  The repo's `Android*` / `Fake*` rule (research 01 rule 13) already complies.

### OPT-6: Baseline Profiles and Macrobenchmark

ARCH-REC marks them "Strongly recommended". For a single-screen, mostly-background app they are low
value. Record them in `PLAN.md` as a conscious skip, or schedule them for M5 (a `generating-baseline-profiles`
skill exists).

### Already right (brief)

- **Build setup:**
  - One version catalog plus precompiled convention plugins (MOD-PAT "Keep your configuration
    consistent").
  - Repository content filters, configuration cache, built-in Kotlin.
  - CI pins actions by SHA.
- **Module design:**
  - `explicitApi()` and `internal`-by-default in every library (MOD-PAT "Expose as little as possible").
  - Pure-Kotlin `:core` and `:bike:protocol` (MOD-PAT "Prefer Kotlin & Java modules").
  - The engines don't depend on each other, and only `:app` composes them (MOD-PAT "Module to module
    communication": the app is the mediator).
- **Link code:**
  - A pure reducer with effects as data (`machine/Reducer.kt:13-39`), plus a randomised invariant test:
    UDF and testability.
  - Generation numbers drop stale callbacks.
  - `MeterSession` catches only `LinkException` and turns only its own `TimeoutCancellationException`
    into a typed error (`session/MeterSession.kt:73-82,177-187`), so cancellation propagates (CORO).
  - It subscribes before writing with `CoroutineStart.UNDISPATCHED` (`session/Exchange.kt:35-45`).
- **Testing and DI:**
  - Fakes rather than mocks, and a fake that can expose races (DOUBLES "Fakes … are preferred";
    `testing-setup` Step 4).
  - Virtual time with `runTest` (CORO-TEST).
  - Hilt only in `:app`, with a `HiltTestRunner` (HILT-TEST "Set the test application in instrumented
    tests").
  - JaCoCo through AGP's Variant API (`testing-setup` Step 14; NiA `Jacoco.kt`).
- **Hardening:**
  - `allowBackup=false` plus exclude-all extraction rules.
  - R8 is on.
  - Lint warnings are errors.

---

## (b) Recommended target module tree and package layout (M2.4–M3)

```
handlebar-link/
├── build-logic/convention/          hl.* plugins (+ hl.jvm.testing for test modules: no coverage gate,
│                                     no explicitApi [J]); CheckModuleBoundaries reads configurations
├── core/                            (folder, not a module)
│   ├── common/        :core:common       JVM   log/ (Logger, LogSink, LogEntry, Logfmt), time/ (TimeProvider)
│   ├── testing/       :core:testing      JVM   TestTimeProvider(scheduler), RecordingLogSink, testLogger(),
│   │                                            (M3) MainDispatcherRule        ← test configurations only
│   └── designsystem/  :core:designsystem Android+Compose (M5)                 ← no engine may depend on it
├── bike/
│   ├── protocol/      :bike:protocol     JVM   unchanged
│   ├── link/          :bike:link         Android lib (or JVM engine + Android adapter, OPT-1)
│   │                                            api(:core:common, :bike:protocol, coroutines-core)
│   └── link-test/     :bike:link-test    JVM   FakeBikeLink (public API), sample LinkStates  ← tests only
├── app/               :app               application, the only Hilt module, composition root
└── (later) carplay/protocol, carplay/engine, carplay/engine-test — same pattern
```

Dependency rules (enforced by MUST-5):
- `:core:common` and `*:protocol` are leaves.
- `:bike:link` may use only `:core:common` and `:bike:protocol`.
- `*-test` and `:core:testing` appear only in `test*`/`androidTest*` configurations.
- `:core:designsystem` may be used only by `:app` (and later `feature`/`ui` modules).
- `:bike:*` and `:carplay:*` never depend on each other.

`:bike:link` packages (`com.abid.handlebarlink.bike.link`):
```
(root)      PUBLIC: BikeLink (interface), BikeLink.create(...) / BikeLinkFactory, BikeLinkConfig,
            LinkState, LinkError, Recovery, GattOp, ButtonEvent, MeterInfo, SetupStep, LinkProblem
            internal: LinkException
machine/    internal pure reducer (exists)
session/    internal MeterSession, Exchange (exists)
runtime/    internal LinkRuntime : BikeLink (actor: event Channel → reduce → EffectRunner), Timers  (M2.4)
gatt/       internal GattLink seam (exists); AndroidGattLink — sole BluetoothGatt user          (M2.5)
server/     internal CtsServer port + AndroidCtsServer (BluetoothGattServer)                    (M2.5)
system/     internal SystemSignals port (adapter on/off, bond, KEY_MISSING, permission) +
            AndroidSystemSignals (callbackFlow receivers), WakeLockHolder                        (M2.5)
```

`:app` packages (`com.abid.handlebarlink`), package-by-feature:
```
HandlebarLinkApp, MainActivity
di/           DispatchersModule (+ @Dispatcher qualifier), TimeModule, LoggingModule, BikeLinkModule
ride/         RideService (FGS connectedDevice), RideCoordinator (owns engine scopes, SupervisorJob +
              CoroutineExceptionHandler), RideNotification, BootReceiver, (M4) BikePresenceService
controls/     ControlAction, ControlTarget, ControlRouter (pure, table-tested),
              MediaSessionControlTarget + MediaAccessListener (interim)
data/         SettingsRepository (DataStore), optional RideStatusRepository (BikeLink + settings)
diagnostics/  LogcatSink, RingBufferSink, FileLogSink, CrashRecorder, ExitInfoReporter
permissions/  PermissionState (flow re-evaluated on resume), request flow helpers
ui/status/    StatusScreen, StatusViewModel, StatusUiState
src/test/     di/TestBikeLinkModule (@TestInstallIn replaces BikeLinkModule → FakeBikeLink),
              ControlRouterTest, StatusViewModelTest, RideServiceTest (Robolectric)
```

---

## (c) Rules M2.4 (runtime and public API) must follow

1. **One public entry point.**
   - `BikeLink` is an interface in the root package. Its implementation (`LinkRuntime`) is `internal`.
   - Construction goes through a factory taking `Context` (M2.5), `Logger`, `TimeProvider`, a
     `CoroutineDispatcher`, `Random` and `BikeLinkConfig`.
   - There is no `object`/singleton state in the engine; Hilt `@Singleton` in `:app` scopes it (ARCH-REC
     "Scope to a component").
2. **Read-only, immutable exposure.**
   - `val state: StateFlow<LinkState>` comes from a private `MutableStateFlow` (`asStateFlow()`) and
     starts at `Stopped`. It is valid whether or not the actor is running.
   - App-facing effects are exposed as state: `keepAlive` and `problem` (M2.4–M3-2).
   - `buttons: Flow<ButtonEvent>` is backed by a `MutableSharedFlow(replay = 0, extraBufferCapacity = N,
     onBufferOverflow = DROP_OLDEST)` **[J: presses are events, not state; no replay]**.
   - Never expose `Mutable*` (CORO "Don't expose mutable types"; FLOW backing properties).
3. **No `stateIn`/`shareIn(WhileSubscribed)` inside the engine.** Its state must not depend on
   subscribers. `WhileSubscribed(5_000)` is for ViewModel `uiState` (ARCH-REC) and for shared system
   signal flows (NiA `TimeZoneMonitor`).
4. **Structured concurrency, no hidden scopes.**
   - The actor runs in `suspend fun run()` called by `RideCoordinator`, or in an injected scope
     (choose one and document it).
   - No `CoroutineScope(...)`, `GlobalScope` or `runBlocking` (CORO "Avoid GlobalScope").
   - Each connection generation runs its session and button collection in a child `Job`, cancelled by
     `Effect.Close(gen)`.
   - Releasing GATT, the server and receivers happens in `finally { withContext(NonCancellable) { … } }`.
5. **Confinement.**
   - All runtime state is touched only on the injected single-threaded dispatcher (prod:
     `limitedParallelism(1)` or a `HandlerThread` dispatcher shared with the GATT callbacks).
   - Platform callbacks only `trySend` events into the actor's `Channel`, with no shared mutable fields.
   - Public command methods (`start(presentHint)`, `stop()`, `onPresence(present)`) are non-suspending,
     thread-safe and idempotent (main-safe: CORO, DATA "Threading").
6. **Time and timers are injected.**
   - `now` for `reduce` is `TimeProvider.elapsedMillis()`.
   - Timers are `delay` jobs keyed by `Timer` on the same dispatcher; `StartTimer` replaces the job and
     `CancelTimer` cancels it.
   - Jitter comes from the injected `Random`.
   - Tests use `StandardTestDispatcher(testScheduler)` plus `TestTimeProvider(testScheduler)`: one
     scheduler (CORO-TEST).
7. **Errors.**
   - Inside a session: `LinkException(LinkError)`.
   - At the single runtime boundary: `catch (e: LinkException)` becomes `Event.SetupFailed`;
     `catch (e: CancellationException) { throw e }`; one `catch (e: Exception)` becomes
     `LinkError.Unexpected`, logged at ERROR, with a reasoned `@Suppress`.
   - No `runCatching` around suspend calls.
   - Every state transition is logged once, by the runtime.
8. **Visibility and dependencies.**
   - Everything new is `internal` unless it is listed in (b)'s public set.
   - Types in public signatures come through `api` dependencies (MUST-2).
   - KDoc on every public member (detekt rule 16).
9. **Testability before Android.**
   - M2.4 runs fully against `FakeGattLink` plus fake ports for the CTS server and system signals
     (internal, `src/test`).
   - `FakeBikeLink` (public, in `:bike:link-test`) is written in the same milestone, so M3 can use it.
   - Runtime tests: at least the scenario tests (start → ready, link loss, backoff timing with jitter
     bounds, generation staleness, stop cancels everything).
   - A randomised actor test that checks the effect invariants on top of `ReducerFuzzTest`.
   - Follow `docs/testing.md`: bug-planting for new suites.
10. **Gates first.** MUST-1 (dispatcher, `runCatching`, generic-catch and scope rules) and MUST-5
    (configuration-aware boundaries plus the new modules) land *before* the first runtime commit, so
    M2.4 is checked by them from day one.
