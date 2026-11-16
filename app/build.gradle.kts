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
    implementation(projects.core.common)
    implementation(projects.bike.linkAndroid)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(projects.core.testing)
    testImplementation(projects.bike.linkTest)

    androidTestImplementation(libs.androidx.test.runner)
}
