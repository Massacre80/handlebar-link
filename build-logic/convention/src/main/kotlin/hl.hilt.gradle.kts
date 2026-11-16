// Hilt dependency injection with KSP, as dagger.dev/hilt/gradle-setup and Google's testing-setup
// skill prescribe: production, local unit tests (Robolectric) and instrumented tests.
// Engines (:bike:*) stay free of DI frameworks; only :app applies this plugin and provides them.
plugins {
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

dependencies {
    "implementation"(libs.hilt.android)
    "ksp"(libs.hilt.compiler)

    "testImplementation"(libs.hilt.android.testing)
    "kspTest"(libs.hilt.compiler)

    "androidTestImplementation"(libs.hilt.android.testing)
    "kspAndroidTest"(libs.hilt.compiler)
}
