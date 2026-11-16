// Jetpack Compose for an Android application or library module.
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

pluginManager.withPlugin("com.android.application") {
    configure<ApplicationExtension> { buildFeatures.compose = true }
}
pluginManager.withPlugin("com.android.library") {
    configure<LibraryExtension> { buildFeatures.compose = true }
}

dependencies {
    "implementation"(platform(libs.androidx.compose.bom))
    "implementation"(libs.androidx.compose.ui)
    "implementation"(libs.androidx.compose.foundation)
    "implementation"(libs.androidx.compose.ui.tooling.preview)
    "debugImplementation"(libs.androidx.compose.ui.tooling)
}
