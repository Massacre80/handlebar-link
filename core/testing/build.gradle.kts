// Test utilities shared by every module's tests. Only test configurations may depend on this module
// (checkModuleDependencies enforces it).
plugins {
    id("hl.jvm.library")
}

dependencies {
    api(projects.core.common)
    api(libs.kotlinx.coroutines.test)
    api(libs.junit)
}
