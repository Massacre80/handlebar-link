// A pure Kotlin module (no Android): fast tests, explicit public API.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("hl.quality")
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

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
