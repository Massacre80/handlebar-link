plugins {
    id("hl.android.application")
    id("hl.compose")
}

android {
    namespace = "com.abid.handlebarlink"
    defaultConfig {
        applicationId = "com.abid.handlebarlink"
        versionCode = 2
        versionName = "2.0.0-dev"
    }
}

dependencies {
    implementation(projects.core)
    implementation(projects.bike.protocol)
    implementation(projects.bike.link)
    implementation(projects.designsystem)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
