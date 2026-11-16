plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(
        libs.versions.jdk
            .get()
            .toInt(),
    )
    // The build logic follows the same rule as the modules: a warning fails the build.
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.serialization.gradlePlugin)
    implementation(libs.spotless.gradlePlugin)
    implementation(libs.detekt.gradlePlugin)
    implementation(libs.hilt.gradlePlugin)
    implementation(libs.ksp.gradlePlugin)
    implementation(libs.vcu.gradlePlugin)
    // Makes the type-safe `libs` catalog usable inside the precompiled script plugins (Libs.kt).
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
