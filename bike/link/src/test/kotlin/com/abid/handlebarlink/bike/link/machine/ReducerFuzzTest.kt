package com.abid.handlebarlink.bike.link.machine

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.fail
import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.SetupStep
import org.junit.Test
import kotlin.random.Random

/**
 * Random event sequences must never break the link's safety rules
 * (docs/research/02-ble-connection.md §6.3, test 16).
 */
class ReducerFuzzTest {
    @Test
    fun `10 000 random event sequences keep every invariant`() {
        val random = Random(SEED)
        val visits = mutableMapOf<String, Int>()
        repeat(RUNS) { run ->
            val h =
                Harness(
                    MachineConfig(lingerMillis = if (random.nextBoolean()) 600_000 else null),
                    Facts(random.nextInt(10) > 0, random.nextInt(10) > 0, random.nextInt(10) > 0),
                )
            val invariants = Invariants()
            repeat(STEPS) { step ->
                val event = randomEvent(random, h)
                val effects = h.send(event, after = random.nextLong(0, 31_000))
                invariants.check(effects, h, "run $run step $step: $event → ${h.mode}")
                val kind = h.mode::class.simpleName.orEmpty()
                visits[kind] = (visits[kind] ?: 0) + 1
            }
        }
        // The generator must actually reach every mode, or the test proves little.
        // Every Mode; likelyNext's exhaustive `when` fails to compile when a mode is added and not handled.
        val modes =
            listOf(
                "Stopped",
                "Dormant",
                "Arming",
                "Direct",
                "DirectPause",
                "Waiting",
                "Linked",
                "Ready",
                "Backoff",
                "BluetoothOff",
                "BondBroken",
                "PermissionMissing",
            )
        modes.forEach { assertThat(visits[it] ?: 0, name = "visits to $it").isGreaterThan(MIN_VISITS) }
    }

    private class Invariants {
        val open = mutableSetOf<Int>()
        var foreground = false
        var wakeLock = false

        fun check(
            effects: List<Effect>,
            h: Harness,
            where: String,
        ) {
            effects.forEach { apply(it, where) }
            val mode = h.mode
            require(open == setOfNotNull(h.machine.openGen), "effects say $open open, machine says $mode", where)
            require(!wakeLock || mode is Mode.Linked, "wake lock held outside setup", where)
            require(mode !in INACTIVE || open.isEmpty(), "connection open while inactive", where)
            require(mode != Mode.BluetoothOff || open.isEmpty(), "connection open while Bluetooth is off", where)
        }

        private fun apply(
            effect: Effect,
            where: String,
        ) {
            when (effect) {
                is Effect.Foreground -> {
                    foreground = effect.on
                }

                is Effect.Connect -> {
                    require(open.isEmpty(), "two open generations ($open + ${effect.gen})", where)
                    require(foreground, "connect without the foreground service", where)
                    open += effect.gen
                }

                is Effect.Close -> {
                    open -= effect.gen
                }

                is Effect.RunSetup -> {
                    require(effect.gen in open, "setup on a closed generation", where)
                }

                is Effect.WakeLock -> {
                    wakeLock = effect.on
                }

                is Effect.StartTimer -> {
                    if (effect.timer == Timer.BACKOFF) checkBackoff(effect, where)
                }

                else -> {}
            }
        }

        private fun checkBackoff(
            effect: Effect.StartTimer,
            where: String,
        ) {
            val cap = if (effect.jitter) Timeouts.BACKOFF_MAX_MS else Timeouts.STUCK_REARM_MS
            require(effect.delayMillis <= cap, "backoff ${effect.delayMillis} ms", where)
        }

        private fun require(
            ok: Boolean,
            what: String,
            where: String,
        ) {
            if (!ok) fail("$what — at $where")
        }
    }

    private fun randomEvent(
        random: Random,
        h: Harness,
    ): Event {
        val current = h.machine.openGen ?: (h.machine.nextGen - 1)
        val gen = if (random.nextInt(5) == 0) random.nextInt(0, current + 2) else current
        // Half the time, the event a real radio would most likely send next in this mode.
        if (random.nextBoolean()) likelyNext(h.mode, current, random)?.let { return it }
        return when (random.nextInt(17)) {
            0 -> Event.Start(random.nextBoolean())
            1 -> if (random.nextInt(4) == 0) Event.Stop else Event.Start(true)
            2 -> Event.PresenceAppeared
            3 -> Event.PresenceGone
            4 -> Event.Bluetooth(random.nextInt(4) > 0)
            5 -> Event.Bond(random.nextInt(4) > 0)
            6 -> if (random.nextBoolean()) Event.KeyMissing else Event.Permission(random.nextInt(4) > 0)
            7 -> Event.ServerOpened(random.nextInt(4) > 0)
            8, 9 -> Event.Connected(gen)
            10 -> Event.Disconnected(gen, STATUSES.random(random))
            11 -> Event.SetupProgress(gen, SetupStep.entries.random(random))
            12, 13 -> Event.SetupDone(gen, Harness.METER)
            14 -> Event.SetupFailed(gen, ERRORS.random(random))
            15 -> Event.ServiceChanged(gen)
            else -> Event.TimerFired(Timer.entries.random(random), gen)
        }
    }

    private fun likelyNext(
        mode: Mode,
        gen: Int,
        random: Random,
    ): Event? =
        when (mode) {
            Mode.Stopped, Mode.Dormant -> Event.Start(random.nextBoolean())
            Mode.Arming -> Event.ServerOpened(ok = true)
            is Mode.Direct, is Mode.Waiting -> Event.Connected(gen)
            is Mode.Linked -> Event.SetupDone(gen, Harness.METER)
            is Mode.Ready -> Event.Disconnected(gen, STATUSES.random(random))
            is Mode.DirectPause -> Event.TimerFired(Timer.DIRECT_RETRY)
            is Mode.Backoff -> Event.TimerFired(Timer.BACKOFF)
            Mode.BluetoothOff -> Event.Bluetooth(on = true)
            Mode.BondBroken -> Event.Bond(bonded = true)
            Mode.PermissionMissing -> Event.Permission(granted = true)
        }

    private companion object {
        const val MIN_VISITS = 100
        const val SEED = 20_261_116
        const val RUNS = 10_000
        const val STEPS = 40
        val STATUSES = listOf(0, 8, 19, 22, 62, 133, 147, 257)
        val ERRORS =
            listOf(
                LinkError.ServicesMissing,
                LinkError.AuthenticationFailed(5),
                LinkError.TimedOut(GattOp.WRITE),
                LinkError.GattFailed(GattOp.READ, 133),
                LinkError.Disconnected(19),
            )
    }
}
