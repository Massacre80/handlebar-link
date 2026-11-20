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
| `:carplay:protocol` | 90 % | 96.9 % (C1): RFC 8439/5869/7748/8032 vectors, DiPlay test literals, plistlib, BouncyCastle SRP client |
| `:carplay:engine` | 90 % | 94.4 % (C2 engine items E1-E9, 2026-11-19; C1: 93.1 %): runtime against a simulated iPhone (its own iAP2 framing, DiPlay's acknowledging or zero-acknowledgement link parameters) over fake ports; 12 planted bugs of the connection items each caught by a named test (docs/research/carplay-spec/E-connection.md) |
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
- `RideService`, `RideNotifications`, `BikePresenceService`, `AppUpdatedReceiver` and
  `AndroidCompanionPresence` have no automated test yet (Robolectric service tests were planned for M4 and
  are still open). They stay thin: the decisions are pure and unit-tested (`RideCoordinator`, `planPresence`,
  `presenceCallbacks`, `presenceOf`, `LinkPolicy`, `PresenceArming`, `PresenceEvents`, `AppUpdated`, each with
  planted bugs caught); the rest is covered by the M4 on-bike check in docs/PLAN.md.
- Each `AndroidBikeLink` starts a `HandlerThread` that is never quit. `RideCoordinator` creates one
  link per selected address, so this only leaks when the rider switches bikes; fix when bike
  switching exists (M5 settings).
- Component previews (`core/designsystem/.../component/*Previews.kt`) render without a device:
  `JAVA_HOME=…; PATH=$JAVA_HOME/bin:$PATH android preview render :core:designsystem --fqn=<preview> -o <dir>`
  (`android preview list :core:designsystem` lists them). The CLI's Gradle run needs Java on the PATH.
  Renders show the first clickable element with Compose's focus highlight (non-touch mode); on the phone
  (touch mode) it does not appear. Press feedback (a state layer from `HlIndication`, the whole component's
  spring from `pressScale`) shows only on touch, so check it on a device.
- Screenshot tests (Compose Preview Screenshot Testing as an AGP test suite, `hl.screenshot`): `@PreviewTest`
  previews in `src/screenshotTest/kotlin` of `:core:designsystem` (every component, both looks, font 1 and
  1.5) and `:app` (every page, both looks, the nine sizes and 400x500 at font 1.5; the setup states at the
  411 × 856 artboard, Night also at font 1.5 and its bike stages at the nine sizes). Expect 36 + 254 images
  (lesson 20; :app went from 144 to 170 with the CarPlay tab, off at the nine sizes and 400x500 at font 1.5, on and
  connecting at the artboard and font 1.5, and Home's card connecting at the artboard; then +35 with the CarPlay flow v2: the tab not set
  up at the nine sizes and font 1.5, connecting, connected and Wi-Fi off at the artboard and font 1.5, the switch off
  at the artboard; in-app iPhone pairing and the iPhone sheet in `CarPlayScreenshots.kt`, 25 images; then +39 for the
landscape layout, `LandscapeScreenshots.kt`: 13 pages at `@Landscape`'s 914 x 411, 817 x 393 and 817 x 393 at font 1.5,
then +21 for 7 more (setup Linking/Done, pairing steps/Waiting/Paired, Devices, Garage);
the design system +4 for the tab rail; dropping Rides on 2026-11-20 removed no screenshot test, since the Rides page
had none, so the counts hold, but every image with the tab bar or rail (four tabs now), the design system's tab bar,
rail and component sheet, Settings, Permissions and setup's "You're set" (one row) change and are recorded again; the same day every row with a subtext and a trailing element moved its text 4 dp
further from it and balanced its lines, so most page images change too; then +10 for the Devices tab's empty hero:
`IntercomNone` now over the tab bar, `IntercomNoneBento` at the artboard and font 1.5, and Devices none Night and Bento
at `@Landscape`; the same day the feature became BikePlay on screen, so every image with the tab bar or rail, the
CarPlay tab, pairing, the iPhone sheet, Home's card, Settings › Permissions, the handlebar pages and setup's welcome
change and are recorded again). References are recorded and verified on Linux CI only (lesson 26); `check` verifies them when
  `CI=true`. After an intended visual change:
  1. render the previews locally and look (`android preview render ...`);
  2. push with `[record screenshots]` in the commit message (or run the CI workflow by hand with
     record-screenshots), which records on Linux and uploads the artifact `screenshot-references`;
  3. `gh run download <run id> -n screenshot-references -D .` from the repository root, look at every
     changed image, and commit them.

