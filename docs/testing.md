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
errors, JaCoCo coverage verification (pure Kotlin modules), and at the root `checkModuleBoundaries` and `checkForbiddenCode`.

detekt runs without type resolution. Its type-resolving tasks are not in `check`: AGP 9 Android
modules have none, and in pure Kotlin modules detekt 1.23 cannot resolve Kotlin 2.4's standard
library, so it missed `println` and flagged reachable code as unreachable. `checkForbiddenCode`
covers the rules that would need it (println, android.util.Log, direct clock reads, GlobalScope,
`@SuppressLint("MissingPermission")`).

## Rules

- **Fakes, not mocks.** A platform dependency gets an interface, an Android implementation and a
  `Fake*` in the module's `testFixtures`, so other modules' tests can reuse it. Mockk only if a fake
  is impossible (testing-setup skill).
- **JUnit 4 + AssertK**, one behaviour per test, Arrange / Act / Assert, names in backticks that read
  as a sentence.
- **Golden vectors.** Protocol tests compare against bytes copied from `docs/protocol/SAB_PROTOCOL.md`
  or captured on the bike, never against values computed by the code under test. A test cites its
  source (spec section, or "verified on the CB350").
- **Coroutines:** `runTest` with virtual time, Turbine for flows (from M2).
- **New tests must be able to fail.** When a suite passes on the first run, plant a bug and check
  that a test catches it (done for M1: id byte order, CTS fraction, command id start).
- No unit tests for Activities, Compose layouts or DI wiring (Google's testing-setup skill).

## Coverage gates (JaCoCo)

| Module | Minimum lines | Current |
|---|---|---|
| `:bike:protocol` | 90 % | 98.9 % (M1) |

JaCoCo, as Google's testing-setup skill prescribes (it replaced Kover from the research). Pure Kotlin
modules use Gradle's `jacoco` plugin with `jacocoTestCoverageVerification` in `check`; Android modules
have `enableUnitTestCoverage` on debug, and get a verification task when they get tests (M2).

## Dependency injection in tests (Hilt)

Set up per Google's testing-setup skill and its Hilt testing reference:
- `hilt-android-testing` with `kspTest` / `kspAndroidTest` (`build-logic/.../hl.hilt.gradle.kts`).
- Instrumented tests run in `HiltTestApplication` through `app/src/androidTest/.../HiltTestRunner.kt`.
- Robolectric tests use `@HiltAndroidTest` + `@Config(application = HiltTestApplication::class)`.
- Replace a binding for all tests with `@TestInstallIn` (preferred); `@UninstallModules` only for one test.

## Not automated

Real Bluetooth: there is no emulator stack worth trusting. Each milestone that touches the bike ends
with an on-device check (`docs/research/02-ble-connection.md` §6.5).
