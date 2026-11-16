plugins {
    id("hl.android.library")
}

android {
    namespace = "com.abid.handlebarlink.bike.link"
}

coverage {
    minimumLines = "0.90".toBigDecimal()
}

dependencies {
    implementation(projects.core)
    implementation(projects.bike.protocol)
    implementation(libs.kotlinx.coroutines.android)
}
