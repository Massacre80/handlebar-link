// Virtual-time controls of kotlinx-coroutines-test (currentTime, advanceTimeBy, runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.bike.link.runtime

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.bike.link.BikeLinkConfig
import com.abid.handlebarlink.bike.link.ButtonEvent
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkProblem
import com.abid.handlebarlink.bike.link.LinkState
import com.abid.handlebarlink.bike.link.spi.SystemChange
import com.abid.handlebarlink.bike.protocol.FunctionId
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.bike.protocol.SabUuids
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random

class LinkRuntimeTest {
    private val sink = RecordingLogSink()

    private fun TestScope.link(
        bike: FakeBike,
        config: BikeLinkConfig = BikeLinkConfig(),
    ): LinkRuntime {
        val time = TestTimeProvider(testScheduler)
        return LinkRuntime(
            bike.ports,
            StandardTestDispatcher(testScheduler),
            time,
            Random(SEED),
            testLogger(sink, time),
            config,
        )
    }

    /** Records every published state with its virtual time. */
    private fun TestScope.record(link: LinkRuntime): MutableList<Pair<Long, LinkState>> {
        val seen = mutableListOf<Pair<Long, LinkState>>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { link.state.collect { seen += currentTime to it } }
        return seen
    }

    private fun TestScope.running(
        bike: FakeBike,
        config: BikeLinkConfig = BikeLinkConfig(),
    ): Pair<LinkRuntime, Job> {
        val link = link(bike, config)
        return link to backgroundScope.launch { link.run() }
    }

    @Test
    fun `ignition on - the clock server opens first, then a direct connect, then ready within seconds`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { ignition(true) }
            val (link, _) = running(bike)
            val readyAt =
                async(
                    start = CoroutineStart.UNDISPATCHED,
                ) { link.state.first { it is LinkState.Ready }.let { currentTime } }

            link.start(presentHint = true)

            assertThat(readyAt.await()).isLessThan(3_000L)
            assertThat(bike.calls.take(2)).containsExactly("server open", "open 1 direct")
            assertThat(link.keepAlive.value).isTrue()
            assertThat(bike.wakeLock.held).isFalse()
            assertThat(link.problem.value).isEqualTo(null)
        }

    @Test
    fun `presses come out as button events stamped with the wall clock`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { ignition(true) }
            val (link, _) = running(bike)
            link.start(presentHint = true)
            advanceTimeBy(5_000)
            val press = async(start = CoroutineStart.UNDISPATCHED) { link.buttons.first() }

            bike.press(HandlebarSwitch.RIGHT.function.code)

            val event = press.await()
            assertThat(event.switch).isEqualTo(HandlebarSwitch.RIGHT)
            assertThat(
                event,
            ).isEqualTo(ButtonEvent(HandlebarSwitch.RIGHT, TestTimeProvider(testScheduler).clock.instant()))
        }

    @Test
    fun `ignition off re-arms at once without backoff, ignition on reconnects and 13 is accepted`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { ignition(true) }
            val (link, _) = running(bike)
            val states = record(link)
            link.start(presentHint = true)
            advanceTimeBy(5_000)

            bike.ignition(false)
            runCurrent()
            assertThat(link.state.value).isEqualTo(LinkState.WaitingForBike)
            assertThat(bike.calls.takeLast(2)).containsExactly("close 1", "open 2 auto")

            advanceTimeBy(30_000)
            bike.ignition(true)
            advanceTimeBy(5_000)

            assertThat(link.state.value).isInstanceOf<LinkState.Ready>()
            assertThat(states.none { it.second is LinkState.Recovering }).isTrue()
        }

    @Test
    fun `a failing setup backs off about 1 s, then about 2 s, with up to 20 percent jitter`() =
        runTest {
            val bike =
                FakeBike(backgroundScope).apply {
                    ignition(true)
                    meterScript = { meter, _ -> meter.silentAssignments[FunctionId.PREVIOUS.code] = Int.MAX_VALUE }
                }
            val (link, _) = running(bike)
            val states = record(link)
            link.start(presentHint = true)

            advanceTimeBy(60_000)

            val pauses =
                states
                    .zipWithNext()
                    .filter { (a, b) ->
                        a.second is LinkState.Recovering &&
                            b.second !is LinkState.Recovering
                    }.map { (a, b) -> b.first - a.first }
            assertThat(pauses[0]).isBetween(800L, 1_200L)
            assertThat(pauses[1]).isBetween(1_600L, 2_400L)
            // Jitter is really applied (the seeded Random makes this deterministic).
            assertThat(pauses[0] != 1_000L && pauses[1] != 2_000L).isTrue()
            val first = states.first { it.second is LinkState.Recovering }.second as LinkState.Recovering
            assertThat(
                first.error,
            ).isEqualTo(LinkError.TimedOut(com.abid.handlebarlink.bike.link.GattOp.ASSIGNMENT_REPLY))
        }

    @Test
    fun `stop closes the connection, the server and the wake lock, and nothing connects afterwards`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { ignition(true) }
            val (link, _) = running(bike)
            link.start(presentHint = true)
            advanceTimeBy(5_000)

            link.stop()
            runCurrent()
            val callsAfterStop = bike.calls.size
            advanceTimeBy(120_000)

            assertThat(link.state.value).isEqualTo(LinkState.Stopped)
            assertThat(link.keepAlive.value).isFalse()
            assertThat(bike.open).isEmpty()
            assertThat(bike.server.isOpen).isFalse()
            assertThat(bike.calls.size).isEqualTo(callsAfterStop)
        }

    @Test
    fun `cancelling run closes everything and reports stopped`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { ignition(true) }
            val (link, job) = running(bike)
            link.start(presentHint = true)
            advanceTimeBy(1_500)

            job.cancel()
            runCurrent()

            assertThat(link.state.value).isEqualTo(LinkState.Stopped)
            assertThat(bike.open).isEmpty()
            assertThat(bike.server.isOpen).isFalse()
            assertThat(bike.wakeLock.held).isFalse()
        }

    @Test
    fun `Bluetooth off mid-ride closes everything, and on reopens the server before connecting`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { ignition(true) }
            val (link, _) = running(bike)
            link.start(presentHint = true)
            advanceTimeBy(5_000)

            bike.signals.report(SystemChange.Bluetooth(on = false))
            runCurrent()
            assertThat(link.state.value).isEqualTo(LinkState.Blocked(LinkError.BluetoothOff))
            assertThat(bike.open).isEmpty()
            assertThat(bike.server.isOpen).isFalse()
            assertThat(link.keepAlive.value).isTrue()

            val before = bike.calls.size
            bike.signals.report(SystemChange.Bluetooth(on = true))
            advanceTimeBy(5_000)

            assertThat(bike.calls.drop(before).take(2)).containsExactly("server open", "open 2 direct")
            assertThat(link.state.value).isInstanceOf<LinkState.Ready>()
        }

    @Test
    fun `a revoked permission blocks the link and asks the rider`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { ignition(true) }
            val (link, _) = running(bike)
            link.start(presentHint = true)
            advanceTimeBy(5_000)

            bike.signals.report(SystemChange.Permission(granted = false))
            runCurrent()

            assertThat(link.state.value).isEqualTo(LinkState.Blocked(LinkError.PermissionMissing))
            assertThat(link.problem.value).isEqualTo(LinkProblem.PERMISSION_MISSING)
            assertThat(link.keepAlive.value).isFalse()
            assertThat(bike.open).isEmpty()
        }

    @Test
    fun `losing the bond blocks the link and nothing connects afterwards`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { ignition(true) }
            val (link, _) = running(bike)
            link.start(presentHint = true)
            advanceTimeBy(5_000)

            bike.signals.report(SystemChange.Bond(bonded = false))
            runCurrent()
            val calls = bike.calls.size
            link.start(presentHint = true)
            link.onPresence(present = true)
            advanceTimeBy(60_000)

            assertThat(link.state.value).isEqualTo(LinkState.Blocked(LinkError.BondLost))
            assertThat(link.problem.value).isEqualTo(LinkProblem.NEEDS_REPAIR)
            assertThat(bike.calls.drop(calls).none { it.startsWith("open") }).isTrue()
        }

    @Test
    fun `a bug inside the setup becomes Unexpected, is logged as an error, and the link recovers`() =
        runTest {
            val bike =
                FakeBike(backgroundScope).apply {
                    ignition(true)
                    // The table lists the characteristic, but reading it hits a missing value: a bug.
                    meterScript = { meter, index -> if (index == 0) meter.values.remove(SabUuids.SERIES_CODE) }
                }
            val (link, _) = running(bike)
            val states = record(link)
            link.start(presentHint = true)

            advanceTimeBy(10_000)

            val recovering = states.map { it.second }.filterIsInstance<LinkState.Recovering>().first()
            assertThat(recovering.error).isInstanceOf<LinkError.Unexpected>()
            assertThat(sink.events("link.setup.bug").size).isEqualTo(1)
            assertThat(link.state.value).isInstanceOf<LinkState.Ready>()
        }

    @Test
    fun `the wake lock is held during setup only`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { ignition(true) }
            val (link, _) = running(bike)
            link.start(presentHint = true)

            advanceTimeBy(bike.connectMillis + 20)
            assertThat(link.state.value).isInstanceOf<LinkState.SettingUp>()
            assertThat(bike.wakeLock.held).isTrue()
            advanceTimeBy(5_000)

            assertThat(link.state.value).isInstanceOf<LinkState.Ready>()
            assertThat(bike.wakeLock.held).isFalse()
        }

    @Test
    fun `a service change sets the meter up again and presses keep coming`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { ignition(true) }
            val (link, _) = running(bike)
            link.start(presentHint = true)
            advanceTimeBy(5_000)

            bike.open.values
                .single()
                .serviceChanged()
            runCurrent()
            assertThat(link.state.value).isInstanceOf<LinkState.SettingUp>()
            advanceTimeBy(5_000)
            val press = async(start = CoroutineStart.UNDISPATCHED) { link.buttons.first() }
            bike.press(HandlebarSwitch.UP.function.code)

            assertThat(link.state.value).isInstanceOf<LinkState.Ready>()
            assertThat(press.await().switch).isEqualTo(HandlebarSwitch.UP)
        }

    @Test
    fun `commands sent before run are queued`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { ignition(true) }
            val link = link(bike)

            link.start(presentHint = true)
            backgroundScope.launch { link.run() }
            advanceTimeBy(5_000)

            assertThat(link.state.value).isInstanceOf<LinkState.Ready>()
        }

    @Test
    fun `only one run at a time`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val (link, _) = running(bike)
            runCurrent()

            val second = runCatching { link.run() }

            assertThat(second.exceptionOrNull()).isNotNull().isInstanceOf<IllegalStateException>()
        }

    @Test
    fun `early connect errors are retried directly, then the link waits for the bike`() =
        runTest {
            val bike =
                FakeBike(backgroundScope).apply {
                    ignition(true)
                    directFailures.addAll(listOf(133, 133))
                }
            val (link, _) = running(bike)
            link.start(presentHint = true)

            advanceTimeBy(10_000)

            assertThat(
                bike.calls.filter {
                    it.startsWith("open")
                },
            ).containsExactly("open 1 direct", "open 2 direct", "open 3 direct")
            assertThat(link.state.value).isInstanceOf<LinkState.Ready>()
        }

    private companion object {
        const val SEED = 7
    }
}
