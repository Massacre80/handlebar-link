// Test doubles of :carplay:engine's public API for other modules' tests (Now in Android's `*-test` pattern).
// Only test configurations may depend on this module (ModuleRules).
plugins {
    id("hl.jvm.library")
}

dependencies {
    api(projects.carplay.engine)

    testImplementation(projects.core.testing)
}
