// Virtual-time control of kotlinx-coroutines-test (runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.carplay.engine.test

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeCarPlayLinkTest {
    @Test
    fun `records commands and reports what the test sets`() {
        val link = FakeCarPlayLink()

        link.start()
        link.stop()
        link.setState(CarPlayState.Connecting(ConnectStep.PAIRING))
        link.setKeepAlive(true)

        assertThat(link.commands).containsExactly(FakeCarPlayLink.Command.Start, FakeCarPlayLink.Command.Stop)
        assertThat(link.state.value).isEqualTo(CarPlayState.Connecting(ConnectStep.PAIRING))
        assertThat(link.keepAlive.value).isTrue()
    }

    @Test
    fun `run suspends until cancelled and refuses a second run`() =
        runTest {
            val link = FakeCarPlayLink()

            val job = launch { link.run() }
            runCurrent()
            assertThat(link.isRunning).isTrue()
            assertFailure { link.run() }.isInstanceOf<IllegalStateException>()
            job.cancel()
            runCurrent()

            assertThat(link.isRunning).isFalse()
        }
}
