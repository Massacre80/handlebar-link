// Formatting (Spotless + ktlint) and static analysis (detekt) for every module, both wired into `check`.
// Coverage (JaCoCo) is set up per module type in hl.jvm.library / hl.android.*.
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
