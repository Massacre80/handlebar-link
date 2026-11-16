# Testing

How Handlebar Link is tested, and the commands to run. Strategy and reasons:
`docs/research/01-architecture.md` §8; Google's `testing-setup` skill (`.claude/skills/testing-setup`).

## Commands

```sh
./gradlew check                          # everything below; must pass before every commit
./gradlew :bike:protocol:test            # one module's unit tests
./gradlew :bike:protocol:jacocoTestReport   # build/reports/jacoco/test/html/index.html
./gradlew :app:createDebugUnitTestCoverageReport  # coverage for an Android module's unit tests
```

`check` runs, in every module: unit tests, Spotless + ktlint, detekt, Android Lint with warnings as
errors, JaCoCo coverage verification, `checkModuleDependencies`, and at the root `checkForbiddenCode`.

detekt runs without type resolution. Its type-resolving tasks are not in `check`: AGP 9 Android
modules have none, and in pure Kotlin modules detekt 1.23 cannot resolve Kotlin 2.4's standard
library, so it missed `println` and flagged reachable code as unreachable. `checkForbiddenCode`
covers the rules that would need it: println, android.util.Log, direct clock reads, GlobalScope,
hard-coded `Dispatchers.*` and new `CoroutineScope(...)` (both allowed only in `:app`'s `di/`),
`runCatching`, `runBlocking`, `@SuppressLint("MissingPermission")`. Generic catches are detekt's
`TooGenericExceptionCaught`, which works without type resolution.

## Rules

- **Fakes, not mocks.** A platform dependency gets an interface, an Android implementation and a
  `Fake*`. Fakes of internal types stay in the module's `src/test`; fakes of public types go in a
  test-only module (`:core:testing`, `:bike:link-test`) that other modules' tests depend on, as in
  Now in Android (`core:testing`, `core:data-test`). Mockk only if a fake
  is impossible (testing-setup skill).
- **JUnit 4 + AssertK**, one behaviour per test, Arrange / Act / Assert, names in backticks that read
  as a sentence.
- **Golden vectors.** Protocol tests compare against bytes copied from `docs/protocol/SAB_PROTOCOL.md`
  or captured on the bike, never against values computed by the code under test. A test cites its
  source (spec section, or "verified on the CB350").
- **Coroutines:** `runTest` with virtual time, Turbine for flows (from M2).
- **New tests must be able to fail.** When a suite passes on the first run, plant a bug and check
  that a test catches it (M1: id byte order, CTS fraction, command id start; M2.2: four missing
  closes / wake-lock releases, each caught by the fuzz test and by its scenario test; M2.3: five
  session bugs, incl. subscribing after the write, caught only once an instant-reply test existed).
- **Fakes must be able to expose races.** `FakeGattLink` can answer with no radio time
  (`opMillis = 0`), which is what proves the reply is subscribed before the write.
- **State machines get a randomised invariant test** (`ReducerFuzzTest`: 10 000 seeded runs, every
  mode visited ≥ 100 times, invariants checked after every event).
- No unit tests for Activities, Compose layouts or DI wiring (Google's testing-setup skill).

## Coverage gates (JaCoCo)

| Module | Minimum lines | Current |
|---|---|---|
| `:core` | 90 % | 95.0 % (M2.1) |
| `:bike:protocol` | 90 % | 98.9 % (M1) |
| `:bike:link` | 90 % | 97.0 % (pure Kotlin since the split) |
| `:bike:link-android` | none | thin Android adapters: unit tests for their pure parts (`GattStatus`); verified on the bike in M3 (docs/research/02 §6.5 checklist) |

JaCoCo, as Google's testing-setup skill prescribes (it replaced Kover from the research). A module
sets its minimum with `coverage { minimumLines = "0.90".toBigDecimal() }`; zero means no gate.
- Pure Kotlin modules: Gradle's `jacoco` plugin, `jacocoTestCoverageVerification` in `check`.
- Android modules: `jacocoDebugUnitTestReport` / `jacocoDebugUnitTestCoverageVerification`
  (`build-logic/.../Coverage.kt`), after Now in Android's `Jacoco.kt`: class directories come from
  AGP's Variant API (`ScopedArtifact.CLASSES`), never from internal build paths.

## Dependency injection in tests (Hilt)

Set up per Google's testing-setup skill and its Hilt testing reference:
- `hilt-android-testing` with `kspTest` / `kspAndroidTest` (`build-logic/.../hl.hilt.gradle.kts`).
- Instrumented tests run in `HiltTestApplication` through `app/src/androidTest/.../HiltTestRunner.kt`.
- Robolectric tests use `@HiltAndroidTest` + `@Config(application = HiltTestApplication::class)`.
- Replace a binding for all tests with `@TestInstallIn` (preferred); `@UninstallModules` only for one test.

## Not automated

Real Bluetooth: there is no emulator stack worth trusting. Each milestone that touches the bike ends
with an on-device check (`docs/research/02-ble-connection.md` §6.5).

## Known intermittent issues

- 2026-11-16, once: `:bike:link:lintAnalyzeDebugUnitTest` failed with `FileNotFoundException` for a file
  under `build/spotless-clean/` (Spotless's scratch copy), right after that test file was rewritten.
  Two full `check` runs afterwards passed; lint's model does not reference `spotless-clean`. Cause not
  established. If it happens again: keep the log, check `build/intermediates/lint-cache`, and record
  the finding in docs/LESSONS.md.
- Gradle deprecations from detekt 1.23.8 (`DetektPlugin.kt:28` `ReportingExtension.file`, removed in
  Gradle 10; `DetektPlugin.kt:99` `Configuration.setVisible`, removed in Gradle 11), traced with
  `./gradlew help --warning-mode all --stacktrace`. Not our code; blocks Gradle 10 until detekt 2 is
  stable (it also fixes type resolution, docs/LESSONS.md 1–2).
- `versionCatalogUpdate`/`versionCatalogFormat` (version-catalog-update 1.1.2) are declared
  `notCompatibleWithConfigurationCache`: they run without the cache; every other task uses it.
- `RideService` and `RideNotifications` have no automated test yet: Robolectric service tests come in
  M4 (PLAN). Until then they are covered by the on-bike checklist; `RideCoordinator` (the logic) is
  unit-tested, including a planted "end the ride on any Blocked" bug.
- Each `AndroidBikeLink` starts a `HandlerThread` that is never quit. `RideCoordinator` creates one
  link per selected address, so this only leaks when the rider switches bikes; fix when bike
  switching exists (M5 settings).
- Component previews (`core/designsystem/.../component/*Previews.kt`) render without a device:
  `JAVA_HOME=…; PATH=$JAVA_HOME/bin:$PATH android preview render :core:designsystem --fqn=<preview> -o <dir>`
  (`android preview list :core:designsystem` lists them). The CLI's Gradle run needs Java on the PATH.
  Renders show the first clickable element with Compose's focus highlight (non-touch mode); on the phone
  (touch mode) it does not appear. Press feedback is foundation's default indication until the design
  defines one.
- Screenshot tests (Compose Preview Screenshot Testing as an AGP test suite, `hl.screenshot`): `@PreviewTest`
  previews in `src/screenshotTest/kotlin` of `:core:designsystem` (every component, both looks, font 1 and
  1.5) and `:app` (every page, both looks, the nine sizes and 400x500 at font 1.5). `check` verifies them.
  After an intended visual change: `./gradlew :core:designsystem:updateDebugScreenshotTestDefaultTestSuite
  :app:updateDebugScreenshotTestDefaultTestSuite`, look at every changed image, then commit the references
  (`src/screenshotTestDefaultDebug/reference`). Expect 24 + 60 images (lesson 20).

