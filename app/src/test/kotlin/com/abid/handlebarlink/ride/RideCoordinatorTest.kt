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
import com.abid.handlebarlink.bike.link.MeterInfo
import com.abid.handlebarlink.bike.link.SetupStep
import com.abid.handlebarlink.bike.link.test.FakeBikeLink
import com.abid.handlebarlink.bike.link.test.FakeBikeLink.Command
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.ButtonPresses
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.data.SelectedBike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class RideCoordinatorTest {
    private val bike = SelectedBike("D0:B2:70:42:7F:FB", "HONDA BTU 2040")
    private val link = FakeBikeLink()
    private val created = mutableListOf<String>()
    private val pressed = mutableListOf<HandlebarSwitch>()
    private val settings = InMemorySettings()
    private var rechecks = 0

    private fun TestScope.coordinator(): RideCoordinator {
        val time = TestTimeProvider(testScheduler)
        return RideCoordinator(
            settings = settings,
            provider = { address -> RideLink(link.also { created += address }) { rechecks++ } },
            buttons = ButtonPresses { pressed += it },
            io = StandardTestDispatcher(testScheduler),
            scope = backgroundScope,
            time = time,
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

            backgroundScope.async { coordinator.ride(presentHint = true) }
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
            backgroundScope.async { coordinator.ride(presentHint = true) }
            runCurrent()

            listOf(HandlebarSwitch.UP, HandlebarSwitch.LEFT, HandlebarSwitch.RIGHT).forEach {
                link.press(ButtonEvent(it, Instant.EPOCH))
                runCurrent()
            }

            // Every press reaches the rider's mapping, which decides what it does (HandlebarMappingTest).
            assertThat(pressed).containsExactly(HandlebarSwitch.UP, HandlebarSwitch.LEFT, HandlebarSwitch.RIGHT)
        }

    @Test
    fun `the ride ends with the problem when the link stops needing the service`() =
        runTest {
            settings.bike.value = bike
            val coordinator = coordinator()
            val ride = backgroundScope.async { coordinator.ride(presentHint = true) }
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
            val ride = backgroundScope.async { coordinator.ride(presentHint = true) }
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
            val ride = backgroundScope.async { coordinator.ride(presentHint = true) }
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

            assertThat(coordinator.ride(presentHint = true)).isNull()
            coordinator.stop()

            assertThat(created).isEmpty()
            assertThat(link.commands).isEmpty()
        }

    @Test
    fun `without a bike CarPlay rides alone until it no longer needs the service`() =
        runTest {
            val coordinator = coordinator()
            val carPlay = Companion()

            val ride = backgroundScope.async { coordinator.ride(presentHint = false, beside = carPlay) }
            runCurrent()

            assertThat(carPlay.running).isTrue()
            assertThat(ride.isActive).isTrue()
            assertThat(created).isEmpty()
            assertThat(link.commands).isEmpty()

            carPlay.keepAlive.value = false
            runCurrent()

            assertThat(ride.await()).isNull()
            assertThat(carPlay.running).isFalse()
        }

    @Test
    fun `stopping a ride without a bike stops CarPlay`() =
        runTest {
            val coordinator = coordinator()
            val carPlay = Companion()
            backgroundScope.async { coordinator.ride(presentHint = false, beside = carPlay) }
            runCurrent()

            coordinator.stop()

            assertThat(carPlay.stops).isEqualTo(1)
        }

    /** CarPlay beside the ride: keeps the service while it runs, until told otherwise. */
    private class Companion : RideCompanion {
        override val keepAlive = MutableStateFlow(false)
        var running = false
        var stops = 0

        override suspend fun run() {
            keepAlive.value = true
            running = true
            try {
                awaitCancellation()
            } finally {
                running = false
            }
        }

        override fun stop() {
            stops++
        }
    }

    @Test
    fun `one link per bike, kept across rides`() =
        runTest {
            settings.bike.value = bike
            val coordinator = coordinator()
            repeat(2) {
                val ride = backgroundScope.async { coordinator.ride(presentHint = true) }
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
    fun `a ride started without a hint waits for the bike`() =
        runTest {
            settings.bike.value = bike
            val coordinator = coordinator()

            backgroundScope.async { coordinator.ride(presentHint = false) }
            runCurrent()

            assertThat(link.commands).containsExactly(Command.Start(presentHint = false))
        }

    @Test
    fun `presence reports for the selected bike go to its link, matched ignoring case`() =
        runTest {
            settings.bike.value = bike
            val coordinator = coordinator()

            // Lower case, as MacAddress prints it.
            val appeared = coordinator.onPresence(bike.address.lowercase(), present = true)
            val gone = coordinator.onPresence(bike.address, present = false)

            assertThat(listOf(appeared, gone)).containsExactly(true, true)
            assertThat(link.commands).containsExactly(Command.Presence(true), Command.Presence(false))
        }

    @Test
    fun `presence reports for another device or without a bike are not for the ride`() =
        runTest {
            val coordinator = coordinator()
            val noBike = coordinator.onPresence(bike.address, present = true)
            settings.bike.value = bike
            val other = coordinator.onPresence("00:11:22:33:44:55", present = true)

            assertThat(listOf(noBike, other)).containsExactly(false, false)
            assertThat(link.commands).isEmpty()
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

    /** What [RideCoordinator.shownState] shows and when, from now on. */
    private fun TestScope.shown(coordinator: RideCoordinator): List<Pair<Long, LinkState?>> {
        val seen = mutableListOf<Pair<Long, LinkState?>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.shownState.collect { seen += testScheduler.currentTime to it }
        }
        runCurrent()
        return seen
    }

    private val ready = LinkState.Ready(MeterInfo(null, null), Instant.EPOCH)

    @Test
    fun `the shown state hides a reconnect of a moment and shows Ready at once`() =
        runTest {
            settings.bike.value = bike
            val coordinator = coordinator()
            val seen = shown(coordinator)
            link.setState(LinkState.WaitingForBike)
            advanceTimeBy(5_000)

            link.setState(ready)
            runCurrent()
            link.setState(LinkState.Recovering(LinkError.Disconnected(8), 1, Instant.EPOCH))
            advanceTimeBy(300)
            link.setState(ready)
            advanceTimeBy(5_000)

            // Waiting shows once Stopped has had its minimum time; the drop never shows.
            assertThat(seen.filter { it.second != null })
                .containsExactly(0L to LinkState.Stopped, 1_000L to LinkState.WaitingForBike, 5_000L to ready)
        }

    @Test
    fun `connecting and setting up show as one state after the show delay, Blocked at once`() =
        runTest {
            settings.bike.value = bike
            val coordinator = coordinator()
            val seen = shown(coordinator)
            advanceTimeBy(5_000)

            link.setState(LinkState.Connecting(1))
            advanceTimeBy(100)
            link.setState(LinkState.SettingUp(SetupStep.BOND))
            advanceTimeBy(299)
            assertThat(seen.last().second).isEqualTo(LinkState.Stopped)
            advanceTimeBy(2)
            assertThat(seen.last()).isEqualTo(5_400L to LinkState.SettingUp(SetupStep.BOND))

            // The next steps replace it in place, at once.
            link.setState(LinkState.SettingUp(SetupStep.MTU))
            runCurrent()
            assertThat(seen.last()).isEqualTo(5_401L to LinkState.SettingUp(SetupStep.MTU))

            link.setState(LinkState.Blocked(LinkError.BluetoothOff))
            runCurrent()
            assertThat(seen.last()).isEqualTo(5_401L to LinkState.Blocked(LinkError.BluetoothOff))
        }
}
