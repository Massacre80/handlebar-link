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
    // Types of these appear in the public API (LinkError, LinkState, Logger, StateFlow): `api`, as in
    // Now in Android's core/data.
    api(projects.core.common)
    api(projects.bike.protocol)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(projects.core.testing)
}
