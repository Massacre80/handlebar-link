import com.android.build.api.dsl.Lint
import org.gradle.api.Project

/** One lint policy for every Android module: any warning fails the build. */
internal fun Lint.applyProjectPolicy(project: Project) {
    warningsAsErrors = true
    abortOnError = true
    checkReleaseBuilds = true
    lintConfig = project.rootProject.file("config/lint/lint.xml")
}
