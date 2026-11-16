# Lessons learned

Every mistake found in this project, and the rule that prevents it. Read before working; add an
entry whenever a mistake is found (what happened → rule). Rules here are binding, like AGENTS.md.

| # | Date | What went wrong | Rule |
|---|---|---|---|
| 1 | 2026-11-16 | Enabled detekt rules (`SuspendFunSwallowedCancellation`, relied on `InjectDispatcher`) that need type resolution, which `check` does not run: they never fired. | **A gate counts only after a planted violation has failed the build.** Check whether a detekt rule has `@RequiresTypeResolution` (javap on the rules jar) before relying on it. |
| 2 | 2026-11-16 | `ForbiddenMethodCall` via plain `detekt` silently did nothing; detekt 1.23's type resolution also missed `println` and flagged reachable code with Kotlin 2.4. | Same as 1. Rules that need type resolution go into `checkForbiddenCode` instead. |
| 3 | 2026-11-16 | Set detekt `constantPattern` to the Compose style, which rejected normal `CONSTANT_CASE`. | Read the default config entry (detekt-core `default-detekt-config.yml`) before overriding a rule; know which property applies to what. |
| 4 | 2026-11-16 | Split files with a regex extraction script; it silently dropped functions. | **Never restructure code with regex extraction.** Write the files out in full, then compile. |
| 5 | 2026-11-16 | Wrote an action's pinned SHA (`upload-artifact`) from memory; it was stale. | **Never write versions, SHAs or checksums from memory.** Look them up (Maven, `gh api`, release checksums) at the moment of writing. |
| 6 | 2026-11-16 | The Android docs show Hilt 2.57.1, which does not support AGP 9. | Docs examples lag. Check release notes / compatibility (AGP, Kotlin, KSP) for the exact versions used. |
| 7 | 2026-11-16 | Misread "no fallbacks" as "Android 17 only" and changed minSdk. | Restate an ambiguous constraint to the user in one line before acting on it. |
| 8 | 2026-11-16 | `:bike:link`'s public API used `:bike:protocol` types through `implementation`; it compiled only because `:app` added the module itself. | A type in a public signature comes through `api(...)`. |
| 9 | 2026-11-16 | Module boundaries were checked by regex on build files: test vs production dependencies were indistinguishable. | Check structure from the real Gradle model (configurations), not from text. |
| 10 | 2026-11-16 | A Gradle task's mapping lambda captured `Project` (configuration-cache failure); a local val shadowed a task property. | In build logic, copy `objects`/values into locals before lambdas; don't name locals like task properties. Run with the configuration cache on (default here). |
| 11 | 2026-11-16 | Reinstalled the old prototype on the phone after the user had said v2 replaces it. | The phone runs v2 only. |
| 12 | 2026-11-16 | A test suite passed on the first run but could not detect "reply subscribed after the write". | When tests pass first time, plant the bugs that matter; if one survives, the suite is missing a test (add it, e.g. an instant-reply fake). |
| 13 | 2026-11-16 | Wrote `else -> Unit` in statement `when`s (twice); ktlint wraps it into `{ Unit }`, which Kotlin 2.4 reports as an unused expression. | In a statement `when`, write an empty branch as `-> {}`, never `-> Unit`. |
| 14 | 2026-11-16 | The root Spotless target `build-logic/**/*.kt` made Gradle walk the whole tree, including other modules' `build/` while they were being written: an intermittent "Could not read path" failure. | Give file scans the narrowest root (`fileTree("build-logic")`), never a glob from the project root. |
