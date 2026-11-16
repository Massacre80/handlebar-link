import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the

/** The `libs` version catalog, usable from the convention plugins. */
internal val Project.libs: LibrariesForLibs get() = the()

internal val Project.jdkVersion: Int get() =
    libs.versions.jdk
        .get()
        .toInt()
internal val Project.jvmTargetVersion: String get() = libs.versions.jvmTarget.get()
