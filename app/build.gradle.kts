plugins {
    id("hl.android.application")
    id("hl.compose")
    id("hl.screenshot")
    id("hl.hilt")
    // Navigation 3 keys are @Serializable so the back stack survives configuration changes.
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.abid.handlebarlink"
    defaultConfig {
        applicationId = "com.abid.handlebarlink"
        versionCode = 2
        versionName = "2.0.0-dev"
        // Starts instrumented tests with HiltTestApplication (Hilt testing guide).
        testInstrumentationRunner = "com.abid.handlebarlink.HiltTestRunner"
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.bike.linkAndroid)
    // The CarPlay receiver engine (docs/PLAN.md "CarPlay receiver", C2); its platform ports come through
    // CarPlayPortsSource (carplay/), bound in di/CarPlayModule.
    implementation(projects.carplay.engine)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.telecom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.core)

    testImplementation(projects.core.testing)
    testImplementation(projects.bike.linkTest)
    testImplementation(projects.carplay.engineTest)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.runner)
}
