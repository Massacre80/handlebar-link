// Virtual-time control of kotlinx-coroutines-test (runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.bike.link.test

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.abid.handlebarlink.bike.link.ButtonEvent
import com.abid.handlebarlink.bike.link.LinkProblem
import com.abid.handlebarlink.bike.link.LinkState
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class FakeBikeLinkTest {
    @Test
    fun `records commands and reports what the test sets`() {
        val link = FakeBikeLink()

        link.start(presentHint = true)
        link.onPresence(present = false)
        link.stop()
        link.setState(LinkState.WaitingForBike)
        link.setKeepAlive(true)
        link.setProblem(LinkProblem.NEEDS_REPAIR)

        assertThat(link.commands).containsExactly(
            FakeBikeLink.Command.Start(true),
            FakeBikeLink.Command.Presence(false),
            FakeBikeLink.Command.Stop,
        )
        assertThat(link.state.value).isEqualTo(LinkState.WaitingForBike)
        assertThat(link.keepAlive.value).isTrue()
        assertThat(link.problem.value).isEqualTo(LinkProblem.NEEDS_REPAIR)
    }

    @Test
    fun `presses reach collectors and run lasts until cancelled`() =
        runTest {
            val link = FakeBikeLink()
            val event = ButtonEvent(HandlebarSwitch.UP, Instant.EPOCH)
            val received = async(start = CoroutineStart.UNDISPATCHED) { link.buttons.first() }
            val running = launch { link.run() }
            runCurrent()

            assertThat(link.press(event)).isTrue()
            assertThat(received.await()).isEqualTo(event)
            assertThat(link.isRunning).isTrue()
            running.cancel()
            runCurrent()
            assertThat(link.isRunning).isFalse()
        }
}
