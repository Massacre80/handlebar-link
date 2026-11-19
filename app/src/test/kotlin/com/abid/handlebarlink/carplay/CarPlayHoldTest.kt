// Virtual-time controls of kotlinx-coroutines-test (runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.carplay

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.carplay.engine.test.FakeCarPlayLink
import com.abid.handlebarlink.carplay.engine.test.FakeCarPlayLink.Command
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The CarPlay tab's Cancel and Disconnect stop CarPlay until Start CarPlay, with the switch left on. */
class CarPlayHoldTest {
    private val link = FakeCarPlayLink()
    private val settings = InMemoryCarPlaySettings(enabled = true)

    private fun TestScope.running(): CarPlayCoordinator {
        val coordinator =
            carPlayCoordinator(
                link,
                settings,
                FakeCarPlayPermissions(),
                testLogger(RecordingLogSink(), TestTimeProvider(testScheduler)),
            )
        backgroundScope.launch { coordinator.run() }
        runCurrent()
        return coordinator
    }

    @Test
    fun `a hold stops the engine and shows Held, the switch stays on`() =
        runTest {
            val carPlay = running()
            assertThat(link.isRunning).isEqualTo(true)

            carPlay.hold()
            runCurrent()

            assertThat(link.commands).containsExactly(Command.Start, Command.Stop)
            assertThat(link.isRunning).isEqualTo(false)
            assertThat(carPlay.status.first()).isEqualTo(CarPlayStatus.Held)
            assertThat(settings.enabled.value).isEqualTo(true)
        }

    @Test
    fun `resume starts the engine again`() =
        runTest {
            val carPlay = running()
            carPlay.hold()
            runCurrent()

            carPlay.resume()
            runCurrent()

            assertThat(link.commands).containsExactly(Command.Start, Command.Stop, Command.Start)
            assertThat(link.isRunning).isEqualTo(true)
            assertThat(carPlay.status.first()).isEqualTo(CarPlayStatus.Engine(link.state.value))
        }

    @Test
    fun `a hold made before the ride keeps CarPlay from starting`() =
        runTest {
            val carPlay =
                carPlayCoordinator(
                    link,
                    settings,
                    FakeCarPlayPermissions(),
                    testLogger(RecordingLogSink(), TestTimeProvider(testScheduler)),
                )
            carPlay.hold()
            backgroundScope.launch { carPlay.run() }
            runCurrent()

            assertThat(link.commands).containsExactly()
            assertThat(link.isRunning).isEqualTo(false)
        }

    @Test
    fun `a missing permission still shows over a hold`() {
        val status = carPlayStatus(true, true, listOf("p"), false, link.state.value, held = true)
        assertThat(status).isEqualTo(CarPlayStatus.NeedsPermission(listOf("p")))
        assertThat(
            carPlayStatus(false, true, emptyList(), false, link.state.value, held = true),
        ).isEqualTo(CarPlayStatus.Off)
    }
}
