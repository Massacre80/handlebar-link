// A pure Kotlin module (no Android): fast tests, explicit public API.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("hl.quality")
    jacoco
}

kotlin {
    jvmToolchain(jdkVersion)
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(jvmTargetVersion)
        allWarningsAsErrors = true
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = jvmTargetVersion.toInt()
}

// detekt's type-resolving tasks (detektMain/detektTest) are deliberately NOT part of `check`: detekt 1.23
// cannot resolve Kotlin 2.4's standard library, so they missed `println` and reported reachable code
// after `firstOrNull() ?: return` as unreachable (M1, 2026-11-16). Plain `detekt` and the root
// `checkForbiddenCode` scan are the gates. Revisit with detekt 2.x.

// Coverage with JaCoCo (Google's testing-setup skill). A module sets its minimum in its own build file:
// coverage { minimumLines = "0.90".toBigDecimal() }
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val coverage = coverageExtension()

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = coverage.minimumLines.get()
            }
        }
    }
}

tasks.named<Test>("test") { finalizedBy(tasks.named("jacocoTestReport")) }

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
    }
}

// JaCoCo skips its verification when there is no execution data, so a module with a minimum but no
// tests passed `check` (docs/LESSONS.md 45: all three :carplay modules, 2026-11-18). This task fails instead.
val requireCoverageData =
    tasks.register("requireCoverageData") {
        val minimum = coverage.minimumLines
        val execFile = layout.buildDirectory.file("jacoco/test.exec")
        val modulePath = path
        dependsOn(tasks.named("test"))
        inputs.property("minimumLines", minimum)
        doLast {
            check(minimum.get().signum() == 0 || execFile.get().asFile.exists()) {
                "$modulePath sets a coverage minimum of ${minimum.get()} but has no unit tests to measure."
            }
        }
    }

tasks.named("jacocoTestCoverageVerification") { dependsOn(requireCoverageData) }

tasks.named("check") { dependsOn(tasks.named("jacocoTestCoverageVerification")) }

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
