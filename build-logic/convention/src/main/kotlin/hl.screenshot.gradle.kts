// Compose Preview Screenshot Testing as an AGP test suite (testing-setup skill; AGP 9.5): previews marked
// @PreviewTest in src/screenshotTest/kotlin are rendered on the JVM (Layoutlib) and compared with the
// reference images committed under src/screenshotTestDebug/reference (or the suite's own reference folder).
// Update the references: ./gradlew update<Suite>TestSuite tasks; verify: the test<Suite>TestSuite tasks.
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

pluginManager.withPlugin("com.android.application") {
    configure<ApplicationExtension> {
        testOptions {
            // Preview rendering resolves fonts, drawables and strings: tests need the compiled resources.
            unitTests.isIncludeAndroidResources = true
            screenshotTests.create("screenshotTest") {
                // The engine is released together with the validation API, under the same version.
                engineVersion = libs.versions.screenshotValidationApi.get()
                targetVariants.add("debug")
                dependencies {
                    implementation(project.dependencies.platform(libs.androidx.compose.bom))
                    implementation(libs.androidx.compose.ui.tooling)
                    implementation(libs.screenshot.validation.api)
                }
            }
        }
    }
}
pluginManager.withPlugin("com.android.library") {
    configure<LibraryExtension> {
        testOptions {
            // Preview rendering resolves fonts, drawables and strings: tests need the compiled resources.
            unitTests.isIncludeAndroidResources = true
            screenshotTests.create("screenshotTest") {
                engineVersion = libs.versions.screenshotValidationApi.get()
                targetVariants.add("debug")
                dependencies {
                    implementation(project.dependencies.platform(libs.androidx.compose.bom))
                    implementation(libs.androidx.compose.ui.tooling)
                    implementation(libs.screenshot.validation.api)
                }
            }
        }
    }
}

// `check` verifies the screenshots too (a planted card-radius change failed both suites). By name: matching
// over all tasks would create AGP's release preview task, which fails because AGP 9 runs unit tests for
// debug only.
tasks.named("check") { dependsOn("testDebugScreenshotTestDefaultTestSuite") }
