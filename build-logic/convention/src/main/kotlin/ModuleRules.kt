/**
 * The module dependency rules (docs/PLAN.md "Structure"; docs/reviews architecture review, MUST-5).
 * Checked per module from its real Gradle configurations by [CheckModuleDependencies].
 */
internal object ModuleRules {
    /** The composition root: may use any production module. */
    const val APP = ":app"

    /**
     * Production (non-test) project dependencies each module may have. A module missing from this map
     * fails the check, so every new module must state its rule here.
     */
    val production: Map<String, Set<String>> =
        mapOf(
            ":core:common" to emptySet(),
            ":core:testing" to setOf(":core:common"),
            ":core:designsystem" to emptySet(),
            ":bike:protocol" to emptySet(),
            // Engines never depend on each other, on the design system, or on test modules.
            ":bike:link" to setOf(":core:common", ":bike:protocol"),
            // The Android implementation of :bike:link's spi.
            ":bike:link-android" to setOf(":bike:link"),
            // Test doubles of :bike:link's public API (test-only).
            ":bike:link-test" to setOf(":bike:link"),
            ":carplay:protocol" to emptySet(),
            // Engines never depend on each other, on the design system, or on test modules.
            ":carplay:engine" to setOf(":core:common", ":carplay:protocol"),
            // The Android implementation of :carplay:engine's spi (RFCOMM, Wi-Fi Direct, sockets, mDNS, files).
            ":carplay:engine-android" to setOf(":carplay:engine", ":carplay:protocol", ":core:common"),
            // Test doubles of :carplay:engine's public API (test-only).
            ":carplay:engine-test" to setOf(":carplay:engine"),
        )

    /** Modules only tests may use: `:core:testing` and every `*-test` module. */
    fun isTestOnly(path: String): Boolean = path == ":core:testing" || path.endsWith("-test")

    /** Configurations of test code (`testImplementation`, `androidTestImplementation`, `kspTest`, …). */
    fun isTestConfiguration(name: String): Boolean = name.contains("test", ignoreCase = true)
}
