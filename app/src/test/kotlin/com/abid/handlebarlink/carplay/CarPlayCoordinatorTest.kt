// Virtual-time controls of kotlinx-coroutines-test (runCurrent, advanceTimeBy).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.carplay

import android.Manifest
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.bike.link.LinkState
import com.abid.handlebarlink.bike.link.test.FakeBikeLink
import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.test.FakeCarPlayLink
import com.abid.handlebarlink.carplay.engine.test.FakeCarPlayLink.Command
import com.abid.handlebarlink.controls.ButtonPresses
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.data.SelectedBike
import com.abid.handlebarlink.ride.RideCoordinator
import com.abid.handlebarlink.ride.RideLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** CarPlay beside the bike link during a ride, through the real RideCoordinator (docs/PLAN.md "CarPlay in the app"). */
class CarPlayCoordinatorTest {
    private val bike = SelectedBike("D0:B2:70:42:7F:FB", "HONDA BTU 2040")
    private val bikeLink = FakeBikeLink()
    private val carPlayLink = FakeCarPlayLink()
    private val settings = InMemoryCarPlaySettings()
    private var switch: CarPlaySettings = settings
    private val permissions = FakeCarPlayPermissions()
    private val logs = RecordingLogSink()
    private val canvas = RecordingCanvas()

    private lateinit var carPlay: CarPlayCoordinator

    private fun TestScope.ride(link: CarPlayLink? = carPlayLink): Pair<RideCoordinator, Deferred<*>> {
        val logger = testLogger(logs, TestTimeProvider(testScheduler))
        carPlay = carPlayCoordinator(link, switch, permissions, logger, canvas)
        val coordinator =
            RideCoordinator(
                settings = Bikes(bike),
                provider = { RideLink(bikeLink) {} },
                buttons = ButtonPresses {},
                io = StandardTestDispatcher(testScheduler),
                scope = backgroundScope,
                logger = logger,
            )
        val ride = backgroundScope.async { coordinator.ride(presentHint = true, beside = carPlay) }
        runCurrent()
        return coordinator to ride
    }

    /** The bike link arms and then no longer needs the service (ignition off, lingered out). */
    private fun TestScope.bikeDone() {
        bikeLink.setKeepAlive(true)
        bikeLink.setState(LinkState.WaitingForBike)
        runCurrent()
        bikeLink.setKeepAlive(false)
        bikeLink.setState(LinkState.Idle)
        runCurrent()
    }

    @Test
    fun `with the switch off CarPlay never runs and the ride is the bike's alone`() =
        runTest {
            val (_, ride) = ride()

            assertThat(carPlayLink.isRunning).isFalse()
            assertThat(carPlayLink.commands).isEmpty()
            assertThat(carPlay.status.first()).isEqualTo(CarPlayStatus.Off)
            bikeDone()
            assertThat(ride.isCompleted).isTrue()
        }

    @Test
    fun `with the switch on CarPlay runs beside the bike link and is started`() =
        runTest {
            settings.enabled.value = true
            ride()

            assertThat(carPlayLink.isRunning).isTrue()
            assertThat(carPlayLink.commands).containsExactly(Command.Start)
            assertThat(bikeLink.isRunning).isTrue()
            carPlayLink.setState(CarPlayState.Connecting(ConnectStep.PAIRING))
            assertThat(carPlay.status.first())
                .isEqualTo(CarPlayStatus.Engine(CarPlayState.Connecting(ConnectStep.PAIRING)))
        }

    @Test
    fun `the CarPlay screen setting reaches the canvas before the start, landscape by default`() =
        runTest {
            settings.enabled.value = true
            ride()

            assertThat(canvas.used).containsExactly(CarPlayOrientation.LANDSCAPE)
            assertThat(carPlayLink.commands).containsExactly(Command.Start)
        }

    @Test
    fun `a stored portrait choice is what the first attempt reports`() =
        runTest {
            settings.enabled.value = true
            settings.orientation.value = CarPlayOrientation.PORTRAIT
            ride()

            assertThat(canvas.used).containsExactly(CarPlayOrientation.PORTRAIT)
        }

    @Test
    fun `a new orientation while CarPlay runs stops it, ends its run and starts it again with the new canvas`() =
        runTest {
            settings.enabled.value = true
            val (_, ride) = ride()

            settings.orientation.value = CarPlayOrientation.PORTRAIT
            runCurrent()

            assertThat(carPlayLink.commands).containsExactly(Command.Start, Command.Stop, Command.Start)
            assertThat(canvas.used).containsExactly(CarPlayOrientation.LANDSCAPE, CarPlayOrientation.PORTRAIT)
            // The first engine run ended (everything released) before the new one began: the fake refuses two at once,
            // which would show as Failed.
            assertThat(carPlayLink.isRunning).isTrue()
            assertThat(carPlay.status.first()).isEqualTo(CarPlayStatus.Engine(CarPlayState.Stopped))
            assertThat(ride.isActive).isTrue()
            assertThat(logs.events("carplay.restart")).isNotEmpty()
        }

    @Test
    fun `a new orientation while CarPlay is off or held starts nothing, and applies at the next start`() =
        runTest {
            ride()

            settings.orientation.value = CarPlayOrientation.PORTRAIT
            runCurrent()

            assertThat(carPlayLink.commands).isEmpty()
            assertThat(canvas.used).isEmpty()
            settings.enabled.value = true
            runCurrent()
            assertThat(canvas.used).containsExactly(CarPlayOrientation.PORTRAIT)
        }

    @Test
    fun `the keep-alive holds across a restart for a new orientation`() =
        runTest {
            settings.enabled.value = true
            ride()
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { carPlay.keepAlive.collect { seen += it } }

            settings.orientation.value = CarPlayOrientation.PORTRAIT
            runCurrent()

            assertThat(seen).doesNotContain(false)
        }

    @Test
    fun `turning the switch on mid-ride starts CarPlay`() =
        runTest {
            ride()

            settings.enabled.value = true
            runCurrent()

            assertThat(carPlayLink.isRunning).isTrue()
            assertThat(carPlayLink.commands).containsExactly(Command.Start)
        }

    @Test
    fun `turning the switch off stops CarPlay and ends its run, releasing everything, and on again restarts it`() =
        runTest {
            settings.enabled.value = true
            val (_, ride) = ride()

            settings.enabled.value = false
            runCurrent()

            assertThat(carPlayLink.commands).containsExactly(Command.Start, Command.Stop)
            assertThat(carPlayLink.isRunning).isFalse()
            assertThat(bikeLink.isRunning).isTrue()
            assertThat(ride.isActive).isTrue()

            settings.enabled.value = true
            runCurrent()

            assertThat(carPlayLink.isRunning).isTrue()
            assertThat(carPlayLink.commands).containsExactly(Command.Start, Command.Stop, Command.Start)
        }

    @Test
    fun `a CarPlay crash never touches the bike link or the ride, and shows as failed`() =
        runTest {
            settings.enabled.value = true
            val failing = FailingCarPlayLink(IllegalStateException("bug in the engine"))
            val (_, ride) = ride(failing)
            bikeLink.setKeepAlive(true)
            bikeLink.setState(LinkState.WaitingForBike)

            advanceTimeBy(2_000)
            runCurrent()

            assertThat(ride.isActive).isTrue()
            assertThat(bikeLink.isRunning).isTrue()
            assertThat(bikeLink.commands).containsExactly(FakeBikeLink.Command.Start(presentHint = true))
            assertThat(carPlay.status.first()).isEqualTo(CarPlayStatus.Failed)
            assertThat(carPlay.keepAlive.first()).isFalse()
            assertThat(logs.events("carplay.failed")).isNotEmpty()
        }

    @Test
    fun `a cancellation from inside the engine is a failure, not the ride's end`() =
        runTest {
            settings.enabled.value = true
            val (_, ride) = ride(FailingCarPlayLink(CancellationException("a timeout inside the engine")))
            bikeLink.setKeepAlive(true)
            bikeLink.setState(LinkState.WaitingForBike)

            advanceTimeBy(2_000)
            runCurrent()

            assertThat(ride.isActive).isTrue()
            assertThat(bikeLink.isRunning).isTrue()
            assertThat(carPlay.status.first()).isEqualTo(CarPlayStatus.Failed)
        }

    @Test
    fun `after a crash, switching off and on runs CarPlay again`() =
        runTest {
            settings.enabled.value = true
            val failing = FailingCarPlayLink(IllegalStateException("bug in the engine"), afterMillis = 1_000)
            ride(failing)
            advanceTimeBy(1_500)

            settings.enabled.value = false
            runCurrent()
            settings.enabled.value = true
            runCurrent()

            assertThat(carPlay.status.first()).isInstanceOf(CarPlayStatus.Engine::class)
            assertThat(failing.fake.commands.last()).isEqualTo(Command.Start)
        }

    @Test
    fun `the ride stays while CarPlay needs the service, and ends once neither does`() =
        runTest {
            settings.enabled.value = true
            val (_, ride) = ride()
            carPlayLink.setKeepAlive(true)

            bikeDone()
            assertThat(ride.isActive).isTrue()

            settings.enabled.value = false
            runCurrent()
            // The engine's keepAlive outlasts the stop until its teardown is done (CarPlayLink.keepAlive).
            assertThat(ride.isActive).isTrue()
            carPlayLink.setKeepAlive(false)
            runCurrent()

            assertThat(ride.isCompleted).isTrue()
            assertThat(bikeLink.isRunning).isFalse()
            assertThat(carPlayLink.isRunning).isFalse()
        }

    @Test
    fun `the ride stays while the bike needs the service, whatever CarPlay does`() =
        runTest {
            settings.enabled.value = true
            val (_, ride) = ride()
            bikeLink.setKeepAlive(true)
            bikeLink.setState(LinkState.WaitingForBike)
            runCurrent()

            settings.enabled.value = false
            carPlayLink.setKeepAlive(false)
            runCurrent()

            assertThat(ride.isActive).isTrue()
        }

    @Test
    fun `CarPlay keeps the service from the moment it is started, before the engine reports anything`() =
        runTest {
            settings.enabled.value = true
            val (_, ride) = ride()

            // The engine has not taken the start yet: its own keepAlive is still false.
            bikeDone()

            assertThat(ride.isActive).isTrue()
            assertThat(carPlay.keepAlive.first()).isTrue()
        }

    @Test
    fun `the ride waits for the switch to load before it can end, then CarPlay runs`() =
        runTest {
            switch = SlowCarPlaySettings(enabled = true, loadMillis = 100)
            val (_, ride) = ride()

            // The bike is done before the stored switch has been read.
            bikeDone()
            assertThat(ride.isActive).isTrue()

            advanceTimeBy(150)
            assertThat(carPlayLink.isRunning).isTrue()
            assertThat(ride.isActive).isTrue()
        }

    @Test
    fun `with the switch loading off, the ride ends once it is read`() =
        runTest {
            switch = SlowCarPlaySettings(enabled = false, loadMillis = 100)
            val (_, ride) = ride()
            bikeDone()

            advanceTimeBy(150)

            assertThat(ride.isCompleted).isTrue()
            // Never started (the run's last act is a Stop, so a start it had queued cannot carry over).
            assertThat(carPlayLink.commands).doesNotContain(Command.Start)
            assertThat(carPlayLink.isRunning).isFalse()
        }

    @Test
    fun `stopping the ride stops CarPlay too, and the ride ends`() =
        runTest {
            settings.enabled.value = true
            val (coordinator, ride) = ride()
            carPlayLink.setKeepAlive(true)
            bikeLink.setKeepAlive(true)
            bikeLink.setState(LinkState.WaitingForBike)
            runCurrent()

            coordinator.stop()
            bikeLink.setKeepAlive(false)
            bikeLink.setState(LinkState.Stopped)
            carPlayLink.setKeepAlive(false)
            runCurrent()

            assertThat(carPlayLink.commands.last()).isEqualTo(Command.Stop)
            assertThat(ride.await()).isNull()
            assertThat(carPlayLink.isRunning).isFalse()
        }

    @Test
    fun `without platform ports CarPlay is not available, never holds the ride, and the bike rides alone`() =
        runTest {
            settings.enabled.value = true
            val (_, ride) = ride(link = null)

            assertThat(carPlay.status.first()).isEqualTo(CarPlayStatus.NotAvailable)
            assertThat(carPlay.keepAlive.first()).isFalse()
            bikeDone()
            assertThat(ride.isCompleted).isTrue()
        }

    @Test
    fun `a missing permission keeps CarPlay from running and says so, until it is allowed`() =
        runTest {
            settings.enabled.value = true
            permissions.missing = listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            ride()

            assertThat(carPlayLink.isRunning).isFalse()
            assertThat(carPlay.status.first())
                .isEqualTo(CarPlayStatus.NeedsPermission(listOf(Manifest.permission.NEARBY_WIFI_DEVICES)))

            permissions.missing = emptyList()
            carPlay.recheckPermission()
            runCurrent()

            assertThat(carPlayLink.isRunning).isTrue()
            assertThat(carPlayLink.commands).containsExactly(Command.Start)
        }

    @Test
    fun `a permission withdrawn mid-ride stops CarPlay`() =
        runTest {
            settings.enabled.value = true
            ride()

            permissions.missing = listOf(Manifest.permission.BLUETOOTH_CONNECT)
            carPlay.recheckPermission()
            runCurrent()

            assertThat(carPlayLink.commands).containsExactly(Command.Start, Command.Stop)
            assertThat(carPlayLink.isRunning).isFalse()
        }

    @Test
    fun `when the ride ends a start the engine had not taken yet is cancelled by a stop`() =
        runTest {
            settings.enabled.value = true
            val (_, ride) = ride()
            carPlayLink.setKeepAlive(true)
            bikeDone()

            ride.cancel()
            runCurrent()

            assertThat(carPlayLink.commands).containsExactly(Command.Start, Command.Stop)
            assertThat(carPlayLink.isRunning).isFalse()
        }

    private class Bikes(
        bike: SelectedBike,
    ) : BikeSettings {
        override val bike = MutableStateFlow<SelectedBike?>(bike)

        override suspend fun select(bike: SelectedBike) {
            this.bike.value = bike
        }

        override suspend fun forget() {
            bike.value = null
        }
    }
}
