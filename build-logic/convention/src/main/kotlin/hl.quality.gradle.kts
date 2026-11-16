// Formatting (Spotless + ktlint), static analysis (detekt) and coverage (Kover) for every module.
// All three are wired into `check`.
import io.gitlab.arturbosch.detekt.Detekt

plugins {
    id("com.diffplug.spotless")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
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
