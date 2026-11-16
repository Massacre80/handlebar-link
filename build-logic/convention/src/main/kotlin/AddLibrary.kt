import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Adds a library to `gradle/libs.versions.toml` without typing a version (docs/LESSONS.md 17):
 *
 * ```
 * ./gradlew addLibrary --coordinates=androidx.lifecycle:lifecycle-service --version-ref=androidxLifecycle
 * ./gradlew addLibrary --coordinates=com.example:thing [--alias=example-thing]
 * ```
 *
 * The version comes from the repository's published metadata (Google Maven, then Maven Central): the
 * latest stable release, or, with `--version-ref`, an existing version entry after checking that the
 * artifact is published at that version. The catalog is then formatted by `versionCatalogFormat`.
 */
@UntrackedTask(because = "edits gradle/libs.versions.toml in place")
abstract class AddLibrary : DefaultTask() {
    /** `group:artifact`. */
    @get:Input
    @get:Option(option = "coordinates", description = "group:artifact of the library")
    abstract val coordinates: Property<String>

    /** The catalog key; defaults to the artifact id. */
    @get:Input
    @get:Optional
    @get:Option(option = "alias", description = "catalog key (default: the artifact id)")
    abstract val alias: Property<String>

    /** Reuse this existing `[versions]` entry instead of adding one. */
    @get:Input
    @get:Optional
    @get:Option(option = "version-ref", description = "existing [versions] key to reuse")
    abstract val versionRef: Property<String>

    @get:Internal
    abstract val catalog: RegularFileProperty

    @TaskAction
    fun add() {
        val parts = coordinates.get().split(':')
        if (parts.size != 2 || parts.any { it.isBlank() }) throw GradleException("--coordinates must be group:artifact")
        val (group, artifact) = parts
        val key = alias.orNull ?: artifact
        val file = catalog.get().asFile
        var toml = file.readText()
        if (Regex("""(?m)^\Q$key\E\s*=""").containsMatchIn(section(toml, "libraries"))) {
            throw GradleException("'$key' is already in [libraries]")
        }
        val published = publishedVersions(group, artifact)
        val ref = versionRef.orNull
        val versionKey: String
        val version: String
        if (ref != null) {
            version = versionOf(toml, ref) ?: throw GradleException("no [versions] entry '$ref'")
            if (version !in published) throw GradleException("$group:$artifact is not published at $ref = $version")
            versionKey = ref
        } else {
            version = published.lastOrNull(::isStable) ?: throw GradleException("no stable release of $group:$artifact")
            versionKey = camelCase(key)
            if (versionOf(toml, versionKey) != null) throw GradleException("[versions] already has '$versionKey'")
            toml = appendTo(toml, "versions", "$versionKey = \"$version\"")
        }
        toml = appendTo(toml, "libraries", "$key = { module = \"$group:$artifact\", version.ref = \"$versionKey\" }")
        file.writeText(toml)
        logger.lifecycle("Added $key = $group:$artifact:$version (version.ref $versionKey)")
    }

    /** Versions in the order the repository lists them (oldest first). */
    private fun publishedVersions(
        group: String,
        artifact: String,
    ): List<String> {
        val path = "${group.replace('.', '/')}/$artifact/maven-metadata.xml"
        val metadata =
            REPOSITORIES.firstNotNullOfOrNull { fetch("$it/$path") }
                ?: throw GradleException("$group:$artifact not found in ${REPOSITORIES.joinToString()}")
        return Regex("<version>([^<]+)</version>").findAll(metadata).map { it.groupValues[1] }.toList()
    }

    private fun fetch(url: String): String? {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        return response.body().takeIf { response.statusCode() == HTTP_OK }
    }

    private fun isStable(version: String): Boolean = !UNSTABLE.containsMatchIn(version)

    private fun versionOf(
        toml: String,
        key: String,
    ): String? =
        Regex("""(?m)^\Q$key\E\s*=\s*"([^"]+)"""")
            .find(section(toml, "versions"))
            ?.groupValues
            ?.get(1)

    private fun section(
        toml: String,
        name: String,
    ): String {
        val start = toml.indexOf("[$name]")
        if (start < 0) return ""
        val end = toml.indexOf("\n[", start + 1).let { if (it < 0) toml.length else it }
        return toml.substring(start, end)
    }

    /** Adds [line] at the end of section [name] (the formatter sorts it afterwards). */
    private fun appendTo(
        toml: String,
        name: String,
        line: String,
    ): String {
        val start = toml.indexOf("[$name]")
        if (start < 0) return toml.trimEnd() + "\n\n[$name]\n$line\n"
        val end = toml.indexOf("\n[", start + 1).let { if (it < 0) toml.length else it }
        val body = toml.substring(0, end).trimEnd()
        return body + "\n" + line + "\n" + toml.substring(end)
    }

    private fun camelCase(key: String): String =
        key
            .split('-', '_', '.')
            .filter { it.isNotEmpty() }
            .mapIndexed { index, part ->
                if (index == 0) part else part.replaceFirstChar { it.uppercase() }
            }.joinToString("")

    private companion object {
        const val HTTP_OK = 200
        val REPOSITORIES = listOf("https://dl.google.com/android/maven2", "https://repo1.maven.org/maven2")
        val UNSTABLE = Regex("""(?i)(alpha|beta|rc|cr|m\d+|snapshot|dev|eap|preview)""")
    }
}
