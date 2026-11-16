# Testing

How Handlebar Link is tested, and the commands to run. Strategy and reasons:
`docs/research/01-architecture.md` §8; Google's `testing-setup` skill (`.claude/skills/testing-setup`).

## Commands

```sh
./gradlew check                          # everything below; must pass before every commit
./gradlew :bike:protocol:test            # one module's unit tests
./gradlew :bike:protocol:koverLog        # line coverage of a module
./gradlew :bike:protocol:koverHtmlReport # build/reports/kover/html/index.html
```

`check` runs, in every module: unit tests, Spotless + ktlint, detekt, Android Lint with warnings as
errors, Kover, and at the root `checkModuleBoundaries` and `checkForbiddenCode`.

detekt runs without type resolution. Its type-resolving tasks are not in `check`: AGP 9 Android
modules have none, and in pure Kotlin modules detekt 1.23 cannot resolve Kotlin 2.4's standard
library, so it missed `println` and flagged reachable code as unreachable. `checkForbiddenCode`
covers the rules that would need it (println, android.util.Log, direct clock reads, GlobalScope,
`@SuppressLint("MissingPermission")`).

## Rules

- **Fakes, not mocks.** A platform dependency gets an interface, an Android implementation and a
  `Fake*` in the module's `testFixtures`, so other modules' tests can reuse it. No mocking library.
- **JUnit 4 + AssertK**, one behaviour per test, Arrange / Act / Assert, names in backticks that read
  as a sentence.
- **Golden vectors.** Protocol tests compare against bytes copied from `docs/protocol/SAB_PROTOCOL.md`
  or captured on the bike, never against values computed by the code under test. A test cites its
  source (spec section, or "verified on the CB350").
- **Coroutines:** `runTest` with virtual time, Turbine for flows (from M2).
- **New tests must be able to fail.** When a suite passes on the first run, plant a bug and check
  that a test catches it (done for M1: id byte order, CTS fraction, command id start).
- No unit tests for Activities, Compose layouts or DI wiring (Google's testing-setup skill).

## Coverage gates (Kover)

| Module | Minimum lines | Current |
|---|---|---|
| `:bike:protocol` | 90 % | 98.8 % (M1) |

Kover rather than JaCoCo: it is JetBrains' coverage tool for Kotlin and reports Kotlin constructs
(inline functions, `when`) correctly; the plan chose it in `01-architecture.md` §7.

## Not automated

Real Bluetooth: there is no emulator stack worth trusting. Each milestone that touches the bike ends
with an on-device check (`docs/research/02-ble-connection.md` §6.5).
