// The CarPlay engine: pure Kotlin, no Android. Its `spi` package declares what the platform must
// provide; a future `:carplay:engine-android` implements it (same pattern as `:bike:link-android`).
plugins {
    id("hl.jvm.library")
}

coverage {
    minimumLines = "0.90".toBigDecimal()
}

dependencies {
    api(projects.core.common)
    api(projects.carplay.protocol)
    api(libs.kotlinx.coroutines.core)

    testImplementation(projects.core.testing)
    // An independent HKDF and ChaCha20-Poly1305 for the simulated iPhone's media (AGENTS.md, CarPlay bytes).
    testImplementation(libs.bouncycastle.bcprov)
}
