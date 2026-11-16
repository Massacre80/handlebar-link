// The root project: one `check` that also verifies module boundaries.
plugins {
    base
    id("com.diffplug.spotless")
}

// Formatting for what no module covers: build-logic and the root build scripts.
spotless {
    kotlin {
        target("build-logic/**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "build-logic/**/*.gradle.kts", "build-logic/**/*.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
}

// Engines never depend on each other; only :app wires them together (docs/PLAN.md, "Structure").
val boundaryRules: Map<String, List<String>> =
    mapOf(
        ":core" to emptyList(),
        ":bike:protocol" to emptyList(),
        ":bike:link" to listOf(":core", ":bike:protocol"),
        ":designsystem" to emptyList(),
    )

val modules = subprojects.filter { it.buildFile.exists() }

val checkModuleBoundaries =
    tasks.register<CheckModuleBoundaries>("checkModuleBoundaries") {
        group = "verification"
        description = "Fails when a module depends on a project it is not allowed to."
        allowed.set(boundaryRules)
        buildFilePaths.set(modules.associate { it.path to it.buildFile.relativeTo(rootDir).invariantSeparatorsPath })
        buildFiles.from(modules.map { it.buildFile })
        report.set(layout.buildDirectory.file("reports/module-boundaries.txt"))
    }

tasks.named("check") { dependsOn(checkModuleBoundaries) }
