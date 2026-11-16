plugins {
    id("hl.android.application")
    id("hl.compose")
    id("hl.hilt")
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
    implementation(projects.core)
    implementation(projects.bike.protocol)
    implementation(projects.bike.link)
    implementation(projects.designsystem)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    androidTestImplementation(libs.androidx.test.runner)
}
