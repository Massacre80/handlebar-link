import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build on calls the project forbids (docs/research/01-architecture.md §5, rules 6, 8, 9, 11).
 *
 * detekt has rules for these, but they need type resolution: AGP 9 Android modules get no
 * type-resolving detekt task, and in pure Kotlin modules detekt 1.23 cannot resolve Kotlin 2.4's
 * standard library (it missed `println` and flagged reachable code as unreachable), so those tasks
 * are not part of `check`. This plain source scan is the guaranteed check.
 */
@CacheableTask
abstract class CheckForbiddenCode : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val root = rootDirectory.get().asFile
        val problems =
            sources.files.filter { it.extension == "kt" }.sorted().flatMap { file ->
                file.readLines().withIndex().flatMap { (index, line) ->
                    val code = line.trim()
                    if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                        emptyList()
                    } else {
                        RULES
                            .filter { it.pattern.containsMatchIn(line) }
                            .map { "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}: ${it.reason}" }
                    }
                }
            }
        report.get().asFile.writeText(problems.joinToString("\n").ifEmpty { "ok" })
        if (problems.isNotEmpty()) throw GradleException("Forbidden code:\n" + problems.joinToString("\n"))
    }

    private class Rule(
        val pattern: Regex,
        val reason: String,
    )

    private companion object {
        val RULES =
            listOf(
                Rule(Regex("""\bprintln?\("""), "print/println: use the Logger (rule 9)"),
                Rule(Regex("""^\s*import\s+android\.util\.Log\b"""), "android.util.Log: use the Logger (rule 9)"),
                Rule(
                    Regex("""\bSystem\.(currentTimeMillis|nanoTime)\("""),
                    "System time: use the injected clock (rule 8)",
                ),
                Rule(
                    Regex("""\b(Instant|LocalDateTime|ZonedDateTime|OffsetDateTime|LocalDate|LocalTime)\.now\("""),
                    "java.time now(): use the injected clock (rule 8)",
                ),
                Rule(Regex("""\bGlobalScope\b"""), "GlobalScope: use a scope you own (rule 6)"),
                Rule(
                    Regex("""@SuppressLint\(\s*"MissingPermission"\s*\)"""),
                    "@SuppressLint(\"MissingPermission\"): check permissions at the adapter (rule 11)",
                ),
            )
    }
}
