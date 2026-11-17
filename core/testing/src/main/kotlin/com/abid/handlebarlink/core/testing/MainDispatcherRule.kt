// Dispatchers.setMain / resetMain are kotlinx-coroutines-test API.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Replaces `Dispatchers.Main` with [dispatcher] for a test (ViewModel tests: `viewModelScope` runs on Main;
 * Android's "Test Kotlin coroutines on Android" pattern).
 */
public class MainDispatcherRule(
    /** The dispatcher Main becomes; share its scheduler with `runTest`. */
    public val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
