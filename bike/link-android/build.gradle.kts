// The Android implementation of :bike:link's `spi`: BluetoothGatt, the clock (CTS) GATT server, system
// broadcasts and a wake lock (Android modularization guide, "API and implementation" pattern).
plugins {
    id("hl.android.library")
}

android {
    namespace = "com.abid.handlebarlink.bike.link.android"
}

dependencies {
    api(projects.bike.link)
    implementation(libs.androidx.core.ktx)

    testImplementation(projects.core.testing)
}
