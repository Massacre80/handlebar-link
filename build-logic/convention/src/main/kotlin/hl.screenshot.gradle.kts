// Compose Preview Screenshot Testing as an AGP test suite (testing-setup skill; AGP 9.5): previews marked
// @PreviewTest in src/screenshotTest/kotlin are rendered on the JVM (Layoutlib) and compared with the
// reference images committed under src/screenshotTestDebug/reference (or the suite's own reference folder).
// Update the references: ./gradlew update<Suite>TestSuite tasks; verify: the test<Suite>TestSuite tasks.
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

/**
 * Pixels that may differ before a screenshot fails, as a fraction (0.0003 = 0.03 %): below the smallest
 * real change tried (cards' corner radius 28 → 24 dp: 0.04 % or more). References and checks both run on
 * Linux, where renders repeat exactly; macOS differs up to 10 % (gradient fades), so it never verifies.
 * Declared before the blocks below: `withPlugin` runs them at once if AGP is already applied.
 */
val screenshotTolerance = 0.0003f

pluginManager.withPlugin("com.android.application") {
    configure<ApplicationExtension> {
        testOptions {
            // Preview rendering resolves fonts, drawables and strings: tests need the compiled resources.
            unitTests.isIncludeAndroidResources = true
            screenshotTests.create("screenshotTest") {
                // The engine is released together with the validation API, under the same version.
                engineVersion = libs.versions.screenshotValidationApi.get()
                imageDifferenceThreshold = screenshotTolerance
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
                imageDifferenceThreshold = screenshotTolerance
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

// `check` verifies the screenshots on CI only: references are recorded and verified on Linux, where CI
// runs (docs/LESSONS.md 26); macOS renders gradients and blurred shadows a little differently. Locally,
// render previews to look at a change (docs/testing.md). By name, not by matching all tasks (lesson 21).
if (providers.environmentVariable("CI").orNull == "true") {
    tasks.named("check") { dependsOn("testDebugScreenshotTestDefaultTestSuite") }
}
