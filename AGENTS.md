# Handlebar Link — rules for coding agents

Read `docs/PLAN.md` first: goal, decisions, modules, milestones. Do not start a milestone before the
previous one is done, and never work on CarPlay before M5.

## Hard rules

- `./gradlew check` must pass before any commit. Never weaken a gate to make it pass; fix the code,
  or write down the reason next to the setting (see `config/lint/lint.xml`, `config/detekt/detekt.yml`).
- Every protocol byte comes from `docs/protocol/SAB_PROTOCOL.md` (section cited in the KDoc) or from
  a capture on the bike. Never guess; read RoadSync's decompiled source when the spec is unclear.
- Never write to the meter's DFU service or send data command 0x0B: `SabSafety` must approve every
  write and subscription.
- Engines (`:bike:*`, later `:carplay:*`) never depend on each other or on `:designsystem`; only
  `:app` wires them (`checkModuleBoundaries`).
- No `println`, `android.util.Log`, direct clock reads or `GlobalScope` (`checkForbiddenCode`).
- Library modules use explicit API: everything is `internal` unless it is documented public API.
- Follow the official Android skills in `.claude/skills/` where they give a recommendation (e.g.
  Hilt and JaCoCo from `testing-setup`); record any deviation and its reason in `docs/PLAN.md`.
- Prefer the official sources: `android docs search "…"` (Android Knowledge Base) and the skills in
  `.claude/skills/`. Check versions on Maven before changing `gradle/libs.versions.toml`.
- Android 12+ (minSdk 31). Use the current API; branch to the older one only where a supported release
  needs it, in one place with the reason. Retries and recovery the link needs are fine. No hidden APIs,
  reflection or known-fragile workarounds.
- The phone runs v2 only; never reinstall the old prototype.

## Testing

See [`docs/testing.md`](docs/testing.md).
