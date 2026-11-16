// The root project: formatting for build-logic, and the forbidden-code scan over every module.
// Module dependency rules are checked per module (hl.quality, ModuleRules.kt).
plugins {
    base
    id("com.diffplug.spotless")
    id("nl.littlerobots.version-catalog-update")
}

// `./gradlew versionCatalogUpdate` updates every library to its latest stable release;
// `--check` fails when one is outdated. Entries without a library (SDK levels, JDK, pinned tool
// versions such as detektKotlin) are kept as they are.
versionCatalogUpdate {
    keep {
        keepUnusedVersions.set(true)
    }
}

// version-catalog-update 1.1.2 uses the Project while running; declare it so Gradle runs these tasks
// without the configuration cache instead of failing (all other tasks keep using it).
tasks.matching { it.name.startsWith("versionCatalog") }.configureEach {
    notCompatibleWithConfigurationCache("version-catalog-update 1.1.2 accesses the Project at execution time")
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

// Dependencies are never typed by hand (docs/LESSONS.md 17): `./gradlew addLibrary --coordinates=g:a`.
tasks.register<AddLibrary>("addLibrary") {
    group = "dependencies"
    description = "Adds a library to the version catalog with its latest stable (or a referenced) version."
    catalog.set(layout.projectDirectory.file("gradle/libs.versions.toml"))
    finalizedBy("versionCatalogFormat")
}
