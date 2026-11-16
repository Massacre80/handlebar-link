import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.math.BigDecimal

/**
 * `coverage { minimumLines = "0.90".toBigDecimal() }` in a module's build file: the line coverage its
 * unit tests must reach. `check` fails below it. Zero (the default) means no gate.
 */
abstract class CoverageExtension {
    /** Minimum covered-lines ratio, 0.0–1.0. */
    abstract val minimumLines: Property<BigDecimal>
}

/** Classes that are generated or only wire things together. */
private val COVERAGE_EXCLUSIONS =
    listOf("**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*", "**/*_Hilt*.class", "**/Hilt_*.class")

internal fun Project.coverageExtension(): CoverageExtension =
    extensions.create<CoverageExtension>("coverage").apply { minimumLines.convention(BigDecimal.ZERO) }

/**
 * JaCoCo for an Android module's debug unit tests, after Now in Android's `Jacoco.kt`: class
 * directories come from AGP's Variant API (ScopedArtifact.CLASSES), not from internal build paths.
 * Adds `jacocoDebugUnitTestReport` and `jacocoDebugUnitTestCoverageVerification` (part of `check`).
 */
internal fun Project.configureAndroidCoverage() {
    pluginManager.apply("jacoco")
    configure<JacocoPluginExtension> { toolVersion = libs.versions.jacoco.get() }
    val coverage = coverageExtension()

    // Registered under its concrete type (Application/LibraryAndroidComponentsExtension); the name is common.
    val androidComponents = extensions.getByName("androidComponents") as AndroidComponentsExtension<*, *, *>
    androidComponents.onVariants { variant ->
        if (variant.buildType != "debug") return@onVariants
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val testTask = "test${name}UnitTest"
        val execFiles =
            layout.buildDirectory
                .dir("outputs/unit_test_code_coverage/${variant.name}UnitTest")
                .map { dir -> dir.asFileTree.matching { include("**/*.exec") } }
        val sources =
            variant.sources.kotlin
                ?.all
                ?.map { dirs -> dirs.map { it.asFile } } ?: provider { emptyList() }

        // A local factory, not `project.objects`: lambdas must not capture the Project (configuration cache).
        val factory = objects

        fun classTrees(dirs: ListProperty<Directory>) =
            dirs.map { list -> list.map { factory.fileTree().setDir(it).exclude(COVERAGE_EXCLUSIONS) } }

        val reportJars = objects.listProperty(RegularFile::class.java)
        val reportDirs = objects.listProperty(Directory::class.java)
        val report =
            tasks.register<JacocoReport>("jacoco${name}UnitTestReport") {
                dependsOn(testTask)
                classDirectories.setFrom(reportJars, classTrees(reportDirs))
                sourceDirectories.setFrom(sources)
                executionData.setFrom(execFiles)
                reports {
                    xml.required.set(true)
                    html.required.set(true)
                }
            }
        variant.artifacts
            .forScope(ScopedArtifacts.Scope.PROJECT)
            .use(report)
            .toGet(ScopedArtifact.CLASSES, { _ -> reportJars }, { _ -> reportDirs })

        val verifyJars = objects.listProperty(RegularFile::class.java)
        val verifyDirs = objects.listProperty(Directory::class.java)
        val verify =
            tasks.register<JacocoCoverageVerification>("jacoco${name}UnitTestCoverageVerification") {
                dependsOn(testTask)
                classDirectories.setFrom(verifyJars, classTrees(verifyDirs))
                sourceDirectories.setFrom(sources)
                executionData.setFrom(execFiles)
                violationRules {
                    rule {
                        limit {
                            counter = "LINE"
                            minimum = coverage.minimumLines.get()
                        }
                    }
                }
            }
        variant.artifacts
            .forScope(ScopedArtifacts.Scope.PROJECT)
            .use(verify)
            .toGet(ScopedArtifact.CLASSES, { _ -> verifyJars }, { _ -> verifyDirs })
        tasks.named("check") { dependsOn(verify) }
    }

    tasks.withType<Test>().configureEach {
        configure<JacocoTaskExtension> {
            // Needed for JaCoCo with Robolectric (robolectric/robolectric#2230, gradle/gradle#5184).
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }
}
