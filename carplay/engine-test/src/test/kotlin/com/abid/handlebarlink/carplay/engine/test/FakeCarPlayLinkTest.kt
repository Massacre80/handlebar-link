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
import com.abid.handlebarlink.carplay.engine.CarPlayCall
import com.abid.handlebarlink.carplay.engine.CarPlayCallStatus
import com.abid.handlebarlink.carplay.engine.CarPlayKey
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.TouchContact
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
        link.touch(listOf(TouchContact(4, 0.5, 0.5, down = true)))
        link.touch(emptyList())

        assertThat(
            link.touches,
        ).containsExactly(listOf(TouchContact(4, 0.5, 0.5, down = true)), emptyList<TouchContact>())
        assertThat(link.commands).containsExactly(FakeCarPlayLink.Command.Start, FakeCarPlayLink.Command.Stop)
        assertThat(link.state.value).isEqualTo(CarPlayState.Connecting(ConnectStep.PAIRING))
        assertThat(link.keepAlive.value).isTrue()
    }

    @Test
    fun `records presses and Siri in order and reports the calls the test sets`() {
        val link = FakeCarPlayLink()

        link.press(CarPlayKey.HOOK_SWITCH)
        link.requestSiri()
        link.setCalls(listOf(CarPlayCall(CarPlayCallStatus.RINGING)))

        assertThat(link.controls).containsExactly(
            FakeCarPlayLink.Control.Press(CarPlayKey.HOOK_SWITCH),
            FakeCarPlayLink.Control.Siri,
        )
        assertThat(link.calls.value).containsExactly(CarPlayCall(CarPlayCallStatus.RINGING))
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
