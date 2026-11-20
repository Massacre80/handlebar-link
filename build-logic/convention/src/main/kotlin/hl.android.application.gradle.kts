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
    // Release signing is optional (docs/research/01-architecture.md §6.3): local and CI builds without the
    // four properties stay debug-signed so the optimised build can still be installed and benchmarked.
    // Tagged releases set all four and get the same signing key on every build.
    val hlKeyStorePath =
        providers
            .environmentVariable("HL_KEYSTORE_PATH")
            .orElse(providers.gradleProperty("HL_KEYSTORE_PATH"))
            .orNull
    val hlKeyStorePassword =
        providers
            .environmentVariable("HL_KEYSTORE_PASSWORD")
            .orElse(providers.gradleProperty("HL_KEYSTORE_PASSWORD"))
            .orNull
    val hlKeyAlias =
        providers
            .environmentVariable("HL_KEY_ALIAS")
            .orElse(providers.gradleProperty("HL_KEY_ALIAS"))
            .orNull
    val hlKeyPassword =
        providers
            .environmentVariable("HL_KEY_PASSWORD")
            .orElse(providers.gradleProperty("HL_KEY_PASSWORD"))
            .orNull
    val hasReleaseSigning =
        listOf(hlKeyStorePath, hlKeyStorePassword, hlKeyAlias, hlKeyPassword).all { !it.isNullOrBlank() }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                val path = hlKeyStorePath!!
                storeFile =
                    file(
                        if (path.startsWith("~/")) {
                            System.getProperty("user.home") + path.substring(1)
                        } else {
                            path
                        },
                    )
                storePassword = hlKeyStorePassword!!
                keyAlias = hlKeyAlias!!
                keyPassword = hlKeyPassword!!
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    lint { applyProjectPolicy(project) }
    // JaCoCo for unit tests (Google's testing-setup skill); the tasks are set up in Coverage.kt.
    testCoverage { jacocoVersion = libs.versions.jacoco.get() }
    buildTypes.named("debug") { enableUnitTestCoverage = true }
}

configureAndroidCoverage()

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
