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
            // Test doubles of :bike:link's public API (test-only).
            ":bike:link-test" to setOf(":bike:link"),
        )

    /** Modules only tests may use: `:core:testing` and every `*-test` module. */
    fun isTestOnly(path: String): Boolean = path == ":core:testing" || path.endsWith("-test")

    /** Configurations of test code (`testImplementation`, `androidTestImplementation`, `kspTest`, …). */
    fun isTestConfiguration(name: String): Boolean = name.contains("test", ignoreCase = true)
}
