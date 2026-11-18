// The CarPlay wire protocol: iAP2 link and control session, AirPlay RTSP, binary plist, TLV8 pairing,
// SRP-6a, ChaCha20-Poly1305 channels and MFi signing. Pure Kotlin: no Android, no I/O, no coroutines.
// Bytes come from DiPlay's working receiver (docs/research/carplay-spec, file:line cited in each KDoc).
plugins {
    id("hl.jvm.library")
}

coverage {
    minimumLines = "0.90".toBigDecimal()
}

dependencies {
    // X25519 and Ed25519 for Android 12 (the platform has them from API 33 only), and the MFi key parsing.
    implementation(libs.bouncycastle.bcprov)
}
