import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a module depends on a project it is not allowed to (docs/PLAN.md, "Structure").
 *
 * Reads each module's build file for type-safe project accessors (`projects.bike.protocol`), and
 * rejects string paths (`project(":x")`) so every dependency is visible to this check.
 */
@CacheableTask
abstract class CheckModuleBoundaries : DefaultTask() {
    /** Module path → the module paths it may depend on. Modules not listed may depend on anything. */
    @get:Input
    abstract val allowed: MapProperty<String, List<String>>

    /** Module path → its build file, relative to the root (for the error message). */
    @get:Input
    abstract val buildFilePaths: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val files = buildFiles.files.associateBy { it.invariantSeparatorsPath }
        val problems = mutableListOf<String>()
        buildFilePaths.get().forEach { (module, relativePath) ->
            val file = files.entries.firstOrNull { it.key.endsWith(relativePath) }?.value ?: return@forEach
            val text = file.readText()
            if (STRING_PATH.containsMatchIn(text)) {
                problems += "$relativePath: use the type-safe accessor (projects.…) instead of project(\":…\")"
            }
            val rule = allowed.get()[module] ?: return@forEach
            ACCESSOR
                .findAll(text)
                .map { ":" + it.groupValues[1].replace('.', ':') }
                .toSet()
                .filterNot { it in rule }
                .forEach {
                    problems +=
                        "$relativePath: $module must not depend on $it (allowed: ${rule.ifEmpty { listOf("nothing") }})"
                }
        }
        report.get().asFile.writeText(problems.joinToString("\n").ifEmpty { "ok" })
        if (problems.isNotEmpty()) throw GradleException("Module boundary violations:\n" + problems.joinToString("\n"))
    }

    private companion object {
        val ACCESSOR = Regex("""\bprojects\.([a-z][A-Za-z0-9]*(?:\.[a-z][A-Za-z0-9]*)*)""")
        val STRING_PATH = Regex("""\bproject\(\s*"""")
    }
}
