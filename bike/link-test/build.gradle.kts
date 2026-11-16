// Test doubles of :bike:link's public API for other modules' tests (Now in Android's `*-test` pattern).
// Only test configurations may depend on this module (ModuleRules).
plugins {
    id("hl.jvm.library")
}

dependencies {
    api(projects.bike.link)

    testImplementation(projects.core.testing)
}
