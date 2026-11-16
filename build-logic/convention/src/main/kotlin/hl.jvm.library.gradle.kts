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

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
