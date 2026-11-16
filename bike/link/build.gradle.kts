// The bike link engine: pure Kotlin, so it cannot use Android APIs. Its `spi` package declares what
// the platform must provide; `:bike:link-android` (M2.5) implements it (Android modularization guide,
// "API and implementation" pattern).
plugins {
    id("hl.jvm.library")
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

    testImplementation(projects.core.testing)
}
