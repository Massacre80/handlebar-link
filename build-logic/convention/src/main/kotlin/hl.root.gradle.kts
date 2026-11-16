// The root project: formatting for build-logic, and the forbidden-code scan over every module.
// Module dependency rules are checked per module (hl.quality, ModuleRules.kt).
plugins {
    base
    id("com.diffplug.spotless")
}

// Formatting for what no module covers: build-logic and the root build scripts. The targets are file
// trees rooted at build-logic/, never a glob from the project root: that would walk every module's
// build/ folder while other tasks write to it (docs/LESSONS.md 14).
spotless {
    kotlin {
        target(fileTree("build-logic") { include("**/*.kt").exclude("**/build/**") })
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target(
            fileTree(".") { include("*.gradle.kts") },
            fileTree("build-logic") { include("**/*.kts").exclude("**/build/**") },
        )
        ktlint(libs.versions.ktlint.get())
    }
}

val modules = subprojects.filter { it.buildFile.exists() }

val checkForbiddenCode =
    tasks.register<CheckForbiddenCode>("checkForbiddenCode") {
        group = "verification"
        description = "Fails on forbidden calls (println, Log, clocks, Dispatchers, runCatching, scopes, …)."
        sources.from(modules.map { it.fileTree("src/main") { include("**/*.kt") } })
        rootDirectory.set(layout.projectDirectory)
        report.set(layout.buildDirectory.file("reports/forbidden-code.txt"))
    }

tasks.named("check") { dependsOn(checkForbiddenCode) }
