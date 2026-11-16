// Virtual-time controls of kotlinx-coroutines-test (runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.ride

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.bike.link.ButtonEvent
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkProblem
import com.abid.handlebarlink.bike.link.LinkState
import com.abid.handlebarlink.bike.link.test.FakeBikeLink
import com.abid.handlebarlink.bike.link.test.FakeBikeLink.Command
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.ControlAction
import com.abid.handlebarlink.controls.ControlResult
import com.abid.handlebarlink.controls.ControlTarget
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.data.SelectedBike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class RideCoordinatorTest {
    private val bike = SelectedBike("D0:B2:70:42:7F:FB", "HONDA BTU 2040")
    private val link = FakeBikeLink()
    private val created = mutableListOf<String>()
    private val performed = mutableListOf<ControlAction>()
    private val settings = InMemorySettings()
    private var rechecks = 0

    private fun TestScope.coordinator(): RideCoordinator {
        val time = TestTimeProvider(testScheduler)
        return RideCoordinator(
            settings = settings,
            provider = { address -> RideLink(link.also { created += address }) { rechecks++ } },
            target =
                ControlTarget {
                    performed += it
                    ControlResult.Done
                },
            io = StandardTestDispatcher(testScheduler),
            scope = backgroundScope,
            logger = testLogger(RecordingLogSink(), time),
        )
    }

    /** What the engine does when it arms (keepAlive first, then the state: BikeLink.keepAlive). */
    private fun FakeBikeLink.arm() {
        setKeepAlive(true)
        setState(LinkState.WaitingForBike)
    }

    @Test
    fun `a ride runs the selected bike's link, connecting directly`() =
        runTest {
            settings.bike.value = bike
            val coordinator = coordinator()

            backgroundScope.async { coordinator.ride() }
            runCurrent()

            assertThat(created).containsExactly(bike.address)
            assertThat(link.isRunning).isTrue()
            assertThat(link.commands).containsExactly(Command.Start(presentHint = true))
        }

    @Test
    fun `presses are routed to CarPlay, unmapped ones are ignored`() =
        runTest {
            settings.bike.value = bike
            val coordinator = coordinator()
            backgroundScope.async { coordinator.ride() }
            runCurrent()

            listOf(HandlebarSwitch.UP, HandlebarSwitch.LEFT, HandlebarSwitch.RIGHT).forEach {
                link.press(ButtonEvent(it, Instant.EPOCH))
                runCurrent()
            }

            assertThat(performed).containsExactly(ControlAction.PREVIOUS, ControlAction.PLAY_PAUSE)
        }

    @Test
    fun `the ride ends with the problem when the link stops needing the service`() =
        runTest {
            settings.bike.value = bike
            val coordinator = coordinator()
            val ride = backgroundScope.async { coordinator.ride() }
            runCurrent()
            link.arm()
            runCurrent()
            assertThat(ride.isActive).isTrue()

            link.setProblem(LinkProblem.NEEDS_REPAIR)
            link.setKeepAlive(false)
            link.setState(LinkState.Blocked(LinkError.BondLost))
            runCurrent()

            assertThat(ride.await()).isEqualTo(LinkProblem.NEEDS_REPAIR)
            assertThat(link.isRunning).isFalse()
        }

    @Test
    fun `Bluetooth off keeps the ride going`() =
        runTest {
            settings.bike.value = bike
            val coordinator = coordinator()
            val ride = backgroundScope.async { coordinator.ride() }
            runCurrent()
            link.arm()
            runCurrent()

            link.setState(LinkState.Blocked(LinkError.BluetoothOff))
            runCurrent()

            assertThat(ride.isActive).isTrue()
        }

    @Test
    fun `stop asks the link to stop, and the ride ends without a problem`() =
        runTest {
            settings.bike.value = bike
            val coordinator = coordinator()
            val ride = backgroundScope.async { coordinator.ride() }
            runCurrent()
            link.arm()
            runCurrent()

            coordinator.stop()
            link.setKeepAlive(false)
            link.setState(LinkState.Stopped)
            runCurrent()

            assertThat(link.commands.last()).isEqualTo(Command.Stop)
            assertThat(ride.await()).isNull()
        }

    @Test
    fun `without a selected bike the ride ends at once and nothing is created`() =
        runTest {
            val coordinator = coordinator()

            assertThat(coordinator.ride()).isNull()
            coordinator.stop()

            assertThat(created).isEmpty()
            assertThat(link.commands).isEmpty()
        }

    @Test
    fun `one link per bike, kept across rides`() =
        runTest {
            settings.bike.value = bike
            val coordinator = coordinator()
            repeat(2) {
                val ride = backgroundScope.async { coordinator.ride() }
                runCurrent()
                link.arm()
                runCurrent()
                link.setKeepAlive(false)
                link.setState(LinkState.Stopped)
                ride.await()
            }

            assertThat(created).containsExactly(bike.address)
        }

    @Test
    fun `coming back to the app re-reads the permission of the selected bike's link`() =
        runTest {
            val coordinator = coordinator()
            coordinator.recheckPermission()
            assertThat(rechecks).isEqualTo(0)

            settings.bike.value = bike
            runCurrent()
            coordinator.recheckPermission()

            assertThat(rechecks).isEqualTo(1)
        }

    private class InMemorySettings : BikeSettings {
        override val bike = MutableStateFlow<SelectedBike?>(null)

        override suspend fun select(bike: SelectedBike) {
            this.bike.value = bike
        }

        override suspend fun forget() {
            bike.value = null
        }
    }
}
