// Formatting (Spotless + ktlint), static analysis (detekt) and the module dependency rules for every
// module, all wired into `check`. Coverage (JaCoCo) is set up per module type in hl.jvm.library / hl.android.*.
import io.gitlab.arturbosch.detekt.Detekt

plugins {
    id("com.diffplug.spotless")
    id("io.gitlab.arturbosch.detekt")
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

// Lint reads files under build/ while Spotless writes and deletes its scratch copies there
// (build/spotless-clean): run in parallel, lint failed with FileNotFoundException (2026-11-16, -17;
// docs/LESSONS.md 25). Lint always runs after the two Spotless tasks that write them (the formats set
// below). By exact name: a name filter still creates every task to test it (lesson 21).
tasks.configureEach {
    if (name.startsWith("lint")) mustRunAfter("spotlessKotlin", "spotlessKotlinGradle")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
    parallel = true
}

// detekt 1.23 must run on the Kotlin compiler it was built with, not the project's newer one.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion(libs.versions.detektKotlin.get())
    }
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = jvmTargetVersion
    reports {
        html.required = true
        xml.required = false
        sarif.required = false
        md.required = false
        txt.required = false
    }
}

// Module dependency rules (ModuleRules.kt), from this module's real configurations.
val declaredProjectDependencies = objects.listProperty<String>()
configurations.configureEach {
    val configuration = name
    dependencies.withType<ProjectDependency>().configureEach {
        val dependency = this
        declaredProjectDependencies.add("$configuration ${dependency.path}")
    }
}
val thisModule = path
val checkModuleDependencies =
    tasks.register<CheckModuleDependencies>("checkModuleDependencies") {
        group = "verification"
        description = "Fails when this module's project dependencies break ModuleRules."
        modulePath.set(thisModule)
        declared.set(declaredProjectDependencies)
        report.set(layout.buildDirectory.file("reports/module-dependencies.txt"))
    }
tasks.named("check") { dependsOn(checkModuleDependencies) }
