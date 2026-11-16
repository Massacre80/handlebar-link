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
 * Fails the build on code the project forbids in production sources (docs/research/01-architecture.md
 * §5; architecture review MUST-1; Android "Best practices for coroutines").
 *
 * detekt has rules for most of these, but they need type resolution, which `check` does not run
 * (AGP 9 Android modules have no type-resolving task, and detekt 1.23 cannot resolve Kotlin 2.4's
 * standard library; docs/LESSONS.md 1–2). This plain source scan is the guaranteed check.
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
                val path = file.relativeTo(root).invariantSeparatorsPath
                file.readLines().withIndex().flatMap { (index, line) -> problemsIn(path, index + 1, line) }
            }
        report.get().asFile.writeText(problems.joinToString("\n").ifEmpty { "ok" })
        if (problems.isNotEmpty()) throw GradleException("Forbidden code:\n" + problems.joinToString("\n"))
    }

    private fun problemsIn(
        path: String,
        lineNumber: Int,
        line: String,
    ): List<String> {
        val code = line.trim()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return emptyList()
        return RULES
            .filter { rule -> rule.pattern.containsMatchIn(line) && rule.allowedIn?.containsMatchIn(path) != true }
            .map { "$path:$lineNumber: ${it.reason}" }
    }

    private class Rule(
        val pattern: Regex,
        val reason: String,
        /** Production paths where the call is the designated place for it. */
        val allowedIn: Regex? = null,
    )

    private companion object {
        /** :app's dependency-injection modules: the one place dispatchers and app scopes are created. */
        val APP_DI = Regex("""^app/src/main/kotlin/com/abid/handlebarlink/di/""")

        /** The one bridge from our Logger to Android's log. */
        val LOGCAT_SINK = Regex("""^app/src/main/kotlin/com/abid/handlebarlink/diagnostics/LogcatSink\.kt$""")

        val RULES =
            listOf(
                Rule(Regex("""\bprintln?\("""), "print/println: use the Logger (rule 9)"),
                Rule(
                    Regex("""^\s*import\s+android\.util\.Log\b"""),
                    "android.util.Log: use the Logger (rule 9)",
                    allowedIn = LOGCAT_SINK,
                ),
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
                    Regex("""\bDispatchers\.(Main|IO|Default|Unconfined)\b"""),
                    "hard-coded Dispatchers: inject a dispatcher (coroutines best practices)",
                    allowedIn = APP_DI,
                ),
                Rule(
                    Regex("""\bCoroutineScope\("""),
                    "new CoroutineScope: run in the caller's scope or an injected one (rule 6)",
                    allowedIn = APP_DI,
                ),
                Rule(
                    Regex("""\brunCatching\b"""),
                    "runCatching swallows cancellation: catch specific exceptions (rule 7)",
                ),
                Rule(Regex("""\brunBlocking\b"""), "runBlocking blocks the thread: use suspend functions"),
                Rule(
                    Regex("""@SuppressLint\(\s*"MissingPermission"\s*\)"""),
                    "@SuppressLint(\"MissingPermission\"): check permissions at the adapter (rule 11)",
                ),
            )
    }
}
