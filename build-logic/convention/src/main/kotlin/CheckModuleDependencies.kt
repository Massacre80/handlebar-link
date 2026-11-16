import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a module's project dependencies break [ModuleRules]: production code may use only the
 * modules its rule allows and never a test-only module; every module must have a rule.
 * The dependencies come from the module's real configurations (lesson 9 in docs/LESSONS.md).
 */
@CacheableTask
abstract class CheckModuleDependencies : DefaultTask() {
    /** This module's path, e.g. `:bike:link`. */
    @get:Input
    abstract val modulePath: Property<String>

    /** Every declared project dependency as `configuration :path`. */
    @get:Input
    abstract val declared: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val module = modulePath.get()
        val problems = mutableListOf<String>()
        val rule = ModuleRules.production[module]
        if (rule == null && module != ModuleRules.APP) {
            problems += "$module has no rule in ModuleRules.production: add one"
        }
        declared.get().map { it.split(' ', limit = 2) }.forEach { (configuration, dependency) ->
            when {
                // Test code may use test-only modules and anything else.
                ModuleRules.isTestConfiguration(configuration) -> {}

                ModuleRules.isTestOnly(dependency) && !ModuleRules.isTestOnly(module) -> {
                    problems += "$configuration $dependency: test-only modules are for test configurations"
                }

                rule != null && dependency !in rule -> {
                    problems += "$configuration $dependency: $module may use only ${rule.ifEmpty { setOf("nothing") }}"
                }
            }
        }
        report.get().asFile.writeText(problems.joinToString("\n").ifEmpty { "ok" })
        if (problems.isNotEmpty()) {
            throw GradleException("Module dependency violations in $module:\n" + problems.joinToString("\n"))
        }
    }
}
