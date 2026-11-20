# Contributing

Thanks for helping improve Handlebar Link. This app talks to a motorcycle instrument
cluster and to an iPhone over CarPlay, so correctness and safety matter more than speed.

## Read first

1. [`docs/PLAN.md`](docs/PLAN.md) — goal, fixed decisions, modules and milestones.
2. [`docs/LESSONS.md`](docs/LESSONS.md) — every mistake made so far and the rule that
   prevents it. When you find a new mistake, add a row in the same commit as the fix.
3. [`AGENTS.md`](AGENTS.md) — the hard rules for coding agents and the architecture.
4. [`docs/testing.md`](docs/testing.md) — how the gates and screenshot tests work.

## Before you open a pull request

- `./gradlew check` must pass. Never weaken a quality gate to make it pass: fix the code,
  or record the reason next to the setting. Run it as `./gradlew check && git commit` —
  never pipe the gate into `&&`, which would hide a failure (LESSONS 39, 98).
- Every protocol byte comes from `docs/protocol/SAB_PROTOCOL.md` (bike) or from
  `docs/research/carplay-spec/` with a file:line citation (CarPlay). Never guess values.
- Test codecs against an independent implementation: RFC vectors, DiPlay test literals,
  `plistlib`, BouncyCastle. Never against values produced by the code under test.
- Never write a library or a version into `gradle/libs.versions.toml` by hand. Use
  `./gradlew addLibrary --coordinates=group:artifact` and `./gradlew versionCatalogUpdate`.
- One agent session per worktree; check `git status` before starting.
- Never commit `local.properties`, identity files (`*.pk8`, `*.p7b`), keystores, passwords
  or anything under `~/.handlebarlink-private`.

## Licence

The project is AGPL-3.0. The CarPlay modules are GPL-3.0 because they are a port of
DiPlay; preserve `carplay/LICENSE` and the per-file provenance comments. Contributions are
accepted under the project's licence, and new files should carry an SPDX header matching
the module. See [`NOTICE.md`](NOTICE.md) and
[`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md). When you change any of those
texts, refresh their copies in `app/src/main/assets/licenses/` so the published APK
carries the same version.

## Reporting bugs

Use the issue templates on GitHub. Include the app version, phone and Android version,
bike/head-unit details, iPhone/iOS version, connection type and a diagnostic report.
Never include hotspot passwords, identity files or phone numbers.
