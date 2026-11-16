// The application module: R8 on for release (without obfuscation, so crash files stay readable).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("com.android.application")
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
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
        targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint { applyProjectPolicy(project) }
    // JaCoCo for unit tests (Google's testing-setup skill): createDebugUnitTestCoverageReport.
    testCoverage { jacocoVersion = libs.versions.jacoco.get() }
    buildTypes.named("debug") { enableUnitTestCoverage = true }
}

configure<KotlinAndroidProjectExtension> {
    jvmToolchain(jdkVersion)
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
