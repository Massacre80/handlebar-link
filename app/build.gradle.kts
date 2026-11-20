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
        versionCode = 3
        versionName = "2.0.0"
        // Starts instrumented tests with HiltTestApplication (Hilt testing guide).
        testInstrumentationRunner = "com.abid.handlebarlink.HiltTestRunner"
    }
    // BuildConfig.DEBUG keeps MainActivity's test hooks (skip_setup, start_ride) out of release builds.
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.bike.linkAndroid)
    // The CarPlay receiver engine (docs/PLAN.md "CarPlay receiver", C2) and its Android ports, bound through
    // CarPlayPortsSource (carplay/) in di/CarPlayModule.
    implementation(projects.carplay.engine)
    implementation(projects.carplay.engineAndroid)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3.adaptive)
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
    // Garage (docs/PLAN.md "Garage"): its records in a typed DataStore as JSON (DataStore guide), and the daily
    // reminder check in WorkManager, which keeps its schedule across reboots.
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(projects.core.testing)
    testImplementation(projects.bike.linkTest)
    testImplementation(projects.carplay.engineTest)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.runner)
}
