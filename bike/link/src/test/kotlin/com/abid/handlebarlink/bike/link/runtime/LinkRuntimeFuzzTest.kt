// Virtual-time controls of kotlinx-coroutines-test (advanceTimeBy, runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.bike.link.runtime

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.fail
import com.abid.handlebarlink.bike.link.BikeLinkConfig
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkState
import com.abid.handlebarlink.bike.link.spi.SystemChange
import com.abid.handlebarlink.bike.protocol.FunctionId
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random

/** Random rides against the whole runtime; its invariants must hold after every step. */
class LinkRuntimeFuzzTest {
    @Test
    fun `random rides keep the runtime's invariants`() {
        val random = Random(SEED)
        var readyRuns = 0
        repeat(RUNS) { run ->
            if (ride(random, run)) readyRuns++
        }
        // The generator must reach the interesting states, or the test proves little.
        assertThat(readyRuns).isGreaterThan(RUNS / 3)
    }

    /** One random ride; returns whether the link was ever ready. */
    private fun ride(
        random: Random,
        run: Int,
    ): Boolean {
        var everReady = false
        runTest {
            val bike = FakeBike(backgroundScope)
            val time = TestTimeProvider(testScheduler)
            val config = BikeLinkConfig(lingerMillis = if (random.nextBoolean()) 600_000 else null)
            val link =
                LinkRuntime(
                    bike.ports,
                    StandardTestDispatcher(testScheduler),
                    time,
                    Random(run),
                    testLogger(RecordingLogSink(), time),
                    config,
                )
            val job = backgroundScope.launch { link.run() }
            link.start(presentHint = random.nextBoolean())
            repeat(STEPS) { step ->
                val action = act(random, bike, link)
                advanceTimeBy(random.nextLong(0, 20_000))
                runCurrent()
                check(bike, link, job, "run $run step $step after $action → ${link.state.value}")
                if (link.state.value is LinkState.Ready) everReady = true
            }
        }
        return everReady
    }

    /** A rider or system action, with how often it is picked. */
    private class Action(
        val name: String,
        val weight: Int,
        val apply: (Random, FakeBike, LinkRuntime) -> Unit,
    )

    private val actions =
        listOf(
            Action("ignition on", 2) { _, bike, _ -> bike.ignition(true) },
            Action("ignition off", 1) { _, bike, _ -> bike.ignition(false) },
            Action("bluetooth off", 1) { _, bike, _ -> bike.signals.report(SystemChange.Bluetooth(false)) },
            Action("bluetooth on", 1) { _, bike, _ -> bike.signals.report(SystemChange.Bluetooth(true)) },
            Action("bond lost", 1) { _, bike, _ -> bike.signals.report(SystemChange.Bond(false)) },
            Action("bond back", 1) { _, bike, _ -> bike.signals.report(SystemChange.Bond(true)) },
            Action("permission revoked", 1) { _, bike, _ -> bike.signals.report(SystemChange.Permission(false)) },
            Action("permission granted", 1) { _, bike, _ -> bike.signals.report(SystemChange.Permission(true)) },
            Action("presence", 1) { random, _, link -> link.onPresence(random.nextBoolean()) },
            Action("start", 2) { random, _, link -> link.start(random.nextBoolean()) },
            Action("stop", 1) { _, _, link -> link.stop() },
            Action(
                "connect error",
                1,
            ) { random, bike, _ -> bike.directFailures.addLast(CONNECT_ERRORS.random(random)) },
            Action(
                "service changed",
                1,
            ) { _, bike, _ ->
                bike.open.values
                    .firstOrNull { it.connected }
                    ?.serviceChanged()
            },
            Action("stuck setup", 1) { _, bike, _ ->
                bike.meterScript = { meter, _ -> meter.silentAssignments[FunctionId.MENU.code] = 2 }
            },
            Action("press", 2) { _, bike, _ ->
                if (bike.open.values.any { it.connected }) bike.press(FunctionId.SELECT.code)
            },
        )

    private fun act(
        random: Random,
        bike: FakeBike,
        link: LinkRuntime,
    ): String {
        var pick = random.nextInt(actions.sumOf { it.weight })
        val action =
            actions.first {
                pick -= it.weight
                pick < 0
            }
        action.apply(random, bike, link)
        return action.name
    }

    private fun check(
        bike: FakeBike,
        link: LinkRuntime,
        job: Job,
        where: String,
    ) {
        val state = link.state.value
        require(job.isActive, "the runtime crashed", where)
        require(bike.open.size <= 1, "${bike.open.size} connections open", where)
        require(!bike.wakeLock.held || state is LinkState.SettingUp, "wake lock held while $state", where)
        require(link.keepAlive.value || bike.open.isEmpty(), "a connection open while the service may stop", where)
        require(
            state != LinkState.Blocked(LinkError.BluetoothOff) || bike.open.isEmpty(),
            "open while Bluetooth is off",
            where,
        )
        require(state != LinkState.Stopped || bike.open.isEmpty(), "open while stopped", where)
    }

    private fun require(
        ok: Boolean,
        what: String,
        where: String,
    ) {
        if (!ok) fail("$what — at $where")
    }

    private companion object {
        const val SEED = 20_261_116
        const val RUNS = 300
        const val STEPS = 25
        val CONNECT_ERRORS = listOf(133, 62, 257, 22)
    }
}
