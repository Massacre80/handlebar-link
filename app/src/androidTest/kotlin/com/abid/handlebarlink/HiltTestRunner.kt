package com.abid.handlebarlink

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Runs instrumented tests in HiltTestApplication instead of [HandlebarLinkApp], so tests can
 * replace bindings with fakes (`@TestInstallIn`). Set as testInstrumentationRunner in app/build.gradle.kts.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        name: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
