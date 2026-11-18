# Handlebar Link — rules for coding agents

Read `docs/PLAN.md` first: goal, decisions, modules, milestones. Then read `docs/LESSONS.md`: every
mistake made so far and the rule that prevents it. **When you find a mistake, add it there** (what
happened → rule) in the same commit as the fix. Do not start a milestone before the
previous one is done. CarPlay follows docs/PLAN.md "CarPlay receiver" (started at the owner's request
2026-11-18), one step at a time. One agent session per worktree (docs/LESSONS.md 52).

## Hard rules

- `./gradlew check` must pass before any commit. Never weaken a gate to make it pass; fix the code,
  or write down the reason next to the setting (see `config/lint/lint.xml`, `config/detekt/detekt.yml`).
- Every protocol byte comes from `docs/protocol/SAB_PROTOCOL.md` (section cited in the KDoc) or from
  a capture on the bike. Never guess; read RoadSync's decompiled source when the spec is unclear.
- CarPlay bytes come from DiPlay's source (`docs/research/carplay-spec/`, file:line in the KDoc), and every
  codec is tested against an independent implementation (RFC vectors, DiPlay test literals, plistlib,
  BouncyCastle) — never against values computed by the code under test (docs/LESSONS.md 46).
- Never write to the meter's DFU service or send data command 0x0B: `SabSafety` must approve every
  write and subscription.
- Engines (`:bike:*`, later `:carplay:*`) never depend on each other or on `:core:designsystem`; only
  `:app` wires them. Every module needs a rule in `ModuleRules.kt` (`checkModuleDependencies`).
- Types in a public signature come through `api(...)`. Test doubles of public types live in test-only
  modules (`:core:testing`, `*-test`); fakes of internal types stay in the module's `src/test`.

## Engine architecture (architecture review, section (c))

- One public interface per engine (`BikeLink`), implemented by an `internal` runtime, built by a
  factory; Hilt `@Singleton` scoping and all Hilt modules live in `:app/di`.
- Expose `StateFlow` (from a private `MutableStateFlow`) for state, a no-replay `Flow` for events.
  Effects the app must act on (keep the service alive, show a problem) are state, not callbacks.
  No `stateIn`/`shareIn(WhileSubscribed)` inside an engine.
- No hidden scopes: the engine runs in `suspend fun run()` called by the app's coordinator; each
  connection generation is a child job; cleanup in `finally { withContext(NonCancellable) { … } }`.
- All engine state is confined to one injected single-threaded dispatcher; platform callbacks only
  `trySend`; public command methods are non-suspending and thread-safe.
- Dispatcher, `TimeProvider` and `Random` are injected; tests use one test scheduler.
- Errors: `LinkException` inside; one runtime boundary turns it into an event, rethrows
  `CancellationException`, and maps anything else to `Unexpected` with a reasoned `@Suppress`.
- No `println`, `android.util.Log`, direct clock reads or `GlobalScope` (`checkForbiddenCode`).
- Library modules use explicit API: everything is `internal` unless it is documented public API.
- Follow the official Android skills in `.claude/skills/` where they give a recommendation (e.g.
  Hilt and JaCoCo from `testing-setup`); record any deviation and its reason in `docs/PLAN.md`.
- **Never type a library or a version into the build** (docs/LESSONS.md 17). Add one with
  `./gradlew addLibrary --coordinates=group:artifact [--alias=key] [--version-ref=existingKey]`
  (latest stable from Google Maven / Maven Central, formatted afterwards); update all with
  `./gradlew versionCatalogUpdate`; `./gradlew versionCatalogUpdate --check` fails on outdated ones.
- Prefer the official sources: `android docs search "…"` (Android Knowledge Base) and the skills in
  `.claude/skills/`. Check versions on Maven before changing `gradle/libs.versions.toml`.
- Android 12+ (minSdk 31). Use the current API; branch to the older one only where a supported release
  needs it, in one place with the reason. Retries and recovery the link needs are fine. No hidden APIs,
  reflection or known-fragile workarounds.
- The phone runs v2 only; never reinstall the old prototype.

## Testing

See [`docs/testing.md`](docs/testing.md).
