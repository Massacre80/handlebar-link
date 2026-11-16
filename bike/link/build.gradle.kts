plugins {
    id("hl.android.library")
}

android {
    namespace = "com.abid.handlebarlink.bike.link"
}

dependencies {
    implementation(projects.core)
    implementation(projects.bike.protocol)
    implementation(libs.kotlinx.coroutines.android)
}
