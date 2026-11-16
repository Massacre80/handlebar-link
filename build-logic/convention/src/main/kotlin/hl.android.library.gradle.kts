// An Android library module (AGP 9 built-in Kotlin): explicit public API, lint as errors.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("com.android.library")
    id("hl.quality")
}

android {
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()
    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
        targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
    }
    lint { applyProjectPolicy(project) }
    // JaCoCo for unit tests (Google's testing-setup skill); the tasks are set up in Coverage.kt.
    testCoverage { jacocoVersion = libs.versions.jacoco.get() }
    buildTypes.named("debug") { enableUnitTestCoverage = true }
}

configureAndroidCoverage()

configure<KotlinAndroidProjectExtension> {
    jvmToolchain(jdkVersion)
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(jvmTargetVersion)
        allWarningsAsErrors = true
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
