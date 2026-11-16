# Handlebar Link

Android app (Poco F1, Android 17) that turns a Honda CB350's handlebar switch into CarPlay controls,
and later hosts our own CarPlay receiver. Plan and decisions: [`docs/PLAN.md`](docs/PLAN.md).

## Build

Requires JDK 21 and the Android SDK (platform 37).

```sh
./gradlew check            # format, detekt, lint, tests, coverage, module boundaries — must pass
./gradlew spotlessApply    # fix formatting
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

## Layout

| Module | What |
|---|---|
| `:core` | pure Kotlin: logging, time, shared types |
| `:bike:protocol` | pure Kotlin: the meter's BLE protocol (`docs/protocol/SAB_PROTOCOL.md`) |
| `:bike:link` | Bluetooth link to the meter: GATT, state machine |
| `:designsystem` | our Compose tokens and components |
| `:app` | ride service, auto-start, pairing, screens |
| `build-logic/` | convention plugins (`hl.*`) shared by every module |

Engines never depend on each other; `./gradlew checkModuleBoundaries` enforces it.
