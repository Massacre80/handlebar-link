import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.Properties

/**
 * Copies the owner's MFi identity (`identity.pk8`, `certificate.p7b`) from the directory named by `carplay.mfi.dir`
 * into generated assets under `build/` (`offline-mfi/…`, where CarLink's APK keeps them). The files never enter a
 * source folder or git (docs/PLAN.md "CarPlay receiver", C2). Without the property the output stays empty, the
 * build still works, and CarPlay reports `IdentityUnavailable` on the phone.
 */
@DisableCachingByDefault(because = "Copies two small private files; caching would store them in the build cache")
abstract class CopyMfiAssets : DefaultTask() {
    /** The directory from `carplay.mfi.dir`, or absent. */
    @get:Input
    @get:Optional
    abstract val sourceDirectory: Property<String>

    /** The two files, so a changed identity re-runs the copy. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceFiles: ConfigurableFileCollection

    /** The generated assets root; the files land in `offline-mfi/` below it. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val target = File(root, ASSET_DIRECTORY).apply { mkdirs() }
        val source = sourceDirectory.orNull ?: return
        FILES.forEach { name ->
            val file = File(source, name)
            if (!file.isFile) {
                throw GradleException("carplay.mfi.dir=$source has no $name (expected $FILES)")
            }
            file.copyTo(File(target, name), overwrite = true)
        }
    }

    companion object {
        /** The assets folder the app reads (`AssetMfiSource`). */
        const val ASSET_DIRECTORY = "offline-mfi"

        /** The files copied, by name. */
        val FILES = listOf("identity.pk8", "certificate.p7b")
    }
}

/** `carplay.mfi.dir` from the Gradle properties, else from local.properties; `~/` is the home directory. */
private fun Project.carplayMfiDirectory(): Provider<String> {
    // Locals only: lambdas must not capture the Project or a script (configuration cache, docs/LESSONS.md 10).
    val key = "carplay.mfi.dir"
    val home = System.getProperty("user.home")
    val localProperties = rootProject.layout.projectDirectory.file("local.properties")
    return providers
        .gradleProperty(key)
        .orElse(
            providers.fileContents(localProperties).asText.map { text ->
                Properties().apply { load(text.reader()) }.getProperty(key).orEmpty()
            },
        ).map { it.trim().replaceFirst(Regex("^~(?=/|$)"), home) }
        .filter { it.isNotEmpty() }
}

/** Registers `copyMfiAssets` (output under build/generated/carplay-mfi/assets). */
internal fun Project.registerCopyMfiAssets(): TaskProvider<CopyMfiAssets> {
    val directory = carplayMfiDirectory()
    val files =
        directory
            .map { dir ->
                CopyMfiAssets.FILES.map { File(dir, it) }.filter { it.isFile }
            }.orElse(emptyList())
    val output = layout.buildDirectory.dir("generated/carplay-mfi/assets")
    return tasks.register<CopyMfiAssets>("copyMfiAssets") {
        description = "Copies the MFi identity named by carplay.mfi.dir into generated assets (never into src/)."
        sourceDirectory.set(directory)
        sourceFiles.from(files)
        outputDirectory.set(output)
    }
}
