// The Android implementation of :carplay:engine's `spi`: the RFCOMM dial, the Wi-Fi Direct group, the AirPlay
// sockets, mDNS through NsdManager, and identity and pairing storage (same pattern as :bike:link-android).
// hl.carplay.mfi injects the owner's MFi files as generated assets from `carplay.mfi.dir` (never from src/).
plugins {
    id("hl.android.library")
    id("hl.carplay.mfi")
}

android {
    namespace = "com.abid.handlebarlink.carplay.engine.android"
}

dependencies {
    api(projects.carplay.engine)
    api(projects.carplay.protocol)
    api(projects.core.common)
    implementation(libs.androidx.core.ktx)

    testImplementation(projects.core.testing)
}
