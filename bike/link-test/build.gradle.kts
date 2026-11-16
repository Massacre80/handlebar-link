// Test doubles of :bike:link's public API for other modules' tests (Now in Android's `*-test` pattern).
// Only test configurations may depend on this module (ModuleRules).
plugins {
    id("hl.android.library")
}

android {
    namespace = "com.abid.handlebarlink.bike.link.test"
}

dependencies {
    api(projects.bike.link)

    testImplementation(projects.core.testing)
}
