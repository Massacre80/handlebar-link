package com.abid.handlebarlink.bike.link.machine

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsSubList
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkProblem
import com.abid.handlebarlink.bike.link.SetupStep
import org.junit.Test

/** The required scenarios of docs/research/02-ble-connection.md §6.3, at the machine level. */
class ReducerTest {
    @Test
    fun `1 happy path - server opens before the first connect, then setup, then ready`() {
        val h = Harness()

        val start = h.send(Event.Start(presentHint = true))
        val opened = h.send(Event.ServerOpened(ok = true))
        val connected = h.send(Event.Connected(1), after = 1_200)
        val done = h.send(Event.SetupDone(1, Harness.METER), after = 2_200)

        assertThat(start).containsExactly(Effect.Foreground(true), Effect.OpenServer)
        assertThat(opened).containsExactly(
            Effect.Connect(1, autoConnect = false),
            Effect.StartTimer(Timer.CONNECT_TIMEOUT, Timeouts.DIRECT_CONNECT_MS, 1),
        )
        assertThat(connected).containsSubList(listOf(Effect.WakeLock(true), Effect.RunSetup(1)))
        assertThat(connected.timers(Timer.SETUP_WATCHDOG).single().delayMillis).isEqualTo(Timeouts.SETUP_WATCHDOG_MS)
        assertThat(done).containsExactly(
            Effect.CancelTimer(Timer.SETUP_WATCHDOG),
            Effect.WakeLock(false),
            Effect.StartTimer(Timer.READY_STABLE, Timeouts.READY_STABLE_MS, 1),
        )
        assertThat(h.mode).isInstanceOf<Mode.Ready>()
    }

    @Test
    fun `without a presence hint the first connect waits with autoConnect`() {
        val h = Harness()

        h.send(Event.Start(presentHint = false))
        val opened = h.send(Event.ServerOpened(ok = true))

        assertThat(opened.connects()).containsExactly(Effect.Connect(1, autoConnect = true))
        assertThat(h.mode).isInstanceOf<Mode.Waiting>()
    }

    @Test
    fun `2 ignition off - waiting is re-armed at once, then lingers and goes dormant`() {
        val h = Harness(MachineConfig(lingerMillis = 600_000)).toReady()
        val readyGen = h.gen

        val lost = h.send(Event.Disconnected(readyGen, status = 8))

        assertThat(lost).containsSubList(listOf(Effect.Close(readyGen)))
        assertThat(lost.connects()).containsExactly(Effect.Connect(readyGen + 1, autoConnect = true))
        assertThat(lost.timers(Timer.BACKOFF)).isEmpty()
        assertThat(lost.timers(Timer.LINGER).single().delayMillis).isEqualTo(600_000)

        val dormant = h.send(Event.TimerFired(Timer.LINGER), after = 600_000)

        assertThat(dormant).containsSubList(listOf(Effect.Close(readyGen + 1)))
        assertThat(dormant).containsSubList(listOf(Effect.CloseServer, Effect.Foreground(false)))
        assertThat(h.mode).isEqualTo(Mode.Dormant)
    }

    @Test
    fun `without a linger setting the link stays armed after ignition off`() {
        val h = Harness().toReady()

        val lost = h.send(Event.Disconnected(h.gen, status = 8))

        assertThat(lost.timers(Timer.LINGER)).isEmpty()
        assertThat(h.mode).isInstanceOf<Mode.Waiting>()
    }

    @Test
    fun `3 a stall within linger reconnects without a cold start`() {
        val h = Harness(MachineConfig(lingerMillis = 600_000)).toReady()
        h.send(Event.Disconnected(h.gen, status = 8))

        val back = h.send(Event.Connected(h.gen), after = 5_000)

        assertThat(back).containsSubList(listOf(Effect.CancelTimer(Timer.LINGER)))
        assertThat(back).doesNotContain(Effect.OpenServer)
        assertThat(h.mode).isInstanceOf<Mode.Linked>()
    }

    @Test
    fun `4 early 133s are retried directly with 300 then 600 ms pauses, each on a new generation`() {
        val h = Harness()
        h.send(Event.Start(presentHint = true))
        h.send(Event.ServerOpened(ok = true))

        val first = h.send(Event.Disconnected(1, status = 133), after = 500)
        val retry1 = h.send(Event.TimerFired(Timer.DIRECT_RETRY), after = 300)
        val second = h.send(Event.Disconnected(2, status = 133), after = 500)
        val retry2 = h.send(Event.TimerFired(Timer.DIRECT_RETRY), after = 600)
        h.send(Event.Connected(3), after = 900)

        assertThat(first.timers(Timer.DIRECT_RETRY).single().delayMillis).isEqualTo(300)
        assertThat(retry1.connects()).containsExactly(Effect.Connect(2, autoConnect = false))
        assertThat(second.timers(Timer.DIRECT_RETRY).single().delayMillis).isEqualTo(600)
        assertThat(retry2.connects()).containsExactly(Effect.Connect(3, autoConnect = false))
        assertThat(h.mode).isInstanceOf<Mode.Linked>()
    }

    @Test
    fun `three failed direct attempts fall back to waiting`() {
        val h = Harness()
        h.send(Event.Start(presentHint = true))
        h.send(Event.ServerOpened(ok = true))
        repeat(2) {
            h.send(Event.Disconnected(h.gen, status = 62), after = 400)
            h.send(Event.TimerFired(Timer.DIRECT_RETRY), after = 600)
        }

        val third = h.send(Event.Disconnected(h.gen, status = 62), after = 400)

        assertThat(third.connects()).containsExactly(Effect.Connect(4, autoConnect = true))
    }

    @Test
    fun `5 a 133 after 26 s is the stack's connect timeout - wait, do not retry`() {
        val h = Harness()
        h.send(Event.Start(presentHint = true))
        h.send(Event.ServerOpened(ok = true))

        val late = h.send(Event.Disconnected(1, status = 133), after = 26_000)

        assertThat(late.timers(Timer.DIRECT_RETRY)).isEmpty()
        assertThat(late.connects()).containsExactly(Effect.Connect(2, autoConnect = true))
    }

    @Test
    fun `the app's own 10 s connect timeout also falls back to waiting`() {
        val h = Harness()
        h.send(Event.Start(presentHint = true))
        h.send(Event.ServerOpened(ok = true))

        val timeout = h.send(Event.TimerFired(Timer.CONNECT_TIMEOUT, gen = 1), after = 10_000)

        assertThat(timeout).containsExactly(Effect.Close(1), Effect.Connect(2, autoConnect = true))
    }

    @Test
    fun `6 six early 133s within 2 minutes report a stuck stack once and keep the link armed`() {
        val h = Harness()
        h.send(Event.Start(presentHint = false))
        h.send(Event.ServerOpened(ok = true))
        val problems = mutableListOf<Effect>()

        repeat(8) {
            problems += h.send(Event.Disconnected(h.gen, status = 133), after = 2_000)
            if (h.mode is Mode.Backoff) h.send(Event.TimerFired(Timer.BACKOFF), after = Timeouts.STUCK_REARM_MS)
        }

        assertThat(problems.filter { it == Effect.Problem(LinkProblem.BLUETOOTH_STUCK) }.size).isEqualTo(1)
        assertThat(h.machine.openGen != null || h.mode is Mode.Backoff).isTrue()
    }

    @Test
    fun `7 setup failures back off 1, 2, 4, 8, 16, 30, 30 s and reset after a stable ready`() {
        val h = Harness().toReady()
        h.send(Event.Disconnected(h.gen, status = 8))
        val delays = mutableListOf<Long>()

        repeat(7) {
            h.send(Event.Connected(h.gen), after = 2_000)
            val failed = h.send(Event.SetupFailed(h.gen, LinkError.TimedOut(GattOp.WRITE)))
            delays += failed.timers(Timer.BACKOFF).single().delayMillis
            h.send(Event.TimerFired(Timer.BACKOFF), after = delays.last())
        }

        assertThat(delays).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        assertThat(h.all.timers(Timer.BACKOFF).all { it.jitter }).isTrue()

        h.send(Event.Connected(h.gen))
        h.send(Event.SetupDone(h.gen, Harness.METER))
        h.send(Event.TimerFired(Timer.READY_STABLE, gen = h.gen), after = Timeouts.READY_STABLE_MS)
        assertThat(h.machine.failures).isEqualTo(0)
    }

    @Test
    fun `the setup watchdog ends a setup that hangs`() {
        val h = Harness()
        h.send(Event.Start(presentHint = true))
        h.send(Event.ServerOpened(ok = true))
        h.send(Event.Connected(1))

        val watchdog = h.send(Event.TimerFired(Timer.SETUP_WATCHDOG, gen = 1), after = Timeouts.SETUP_WATCHDOG_MS)

        assertThat(watchdog).containsSubList(listOf(Effect.Close(1), Effect.WakeLock(false)))
        assertThat((h.mode as Mode.Backoff).error).isEqualTo(LinkError.TimedOut(GattOp.SETUP))
    }

    @Test
    fun `8 Bluetooth off in every active state closes everything, and on reopens the server before connecting`() {
        val setups: List<(Harness) -> Unit> =
            listOf(
                { it.send(Event.Start(presentHint = true)) },
                { it.send(Event.Start(presentHint = true)).also { _ -> it.send(Event.ServerOpened(true)) } },
                { it.toReady() },
                {
                    it.toReady()
                    it.send(Event.Disconnected(it.gen, status = 8))
                },
            )
        setups.forEach { setup ->
            val h = Harness()
            setup(h)
            val open = h.machine.openGen

            val off = h.send(Event.Bluetooth(on = false))
            val on = h.send(Event.Bluetooth(on = true))
            val opened = h.send(Event.ServerOpened(ok = true))

            if (open != null) assertThat(off).containsSubList(listOf(Effect.Close(open)))
            assertThat(off).containsSubList(listOf(Effect.CloseServer))
            assertThat(on).containsExactly(Effect.Foreground(true), Effect.OpenServer)
            assertThat(opened.connects().single().autoConnect).isEqualTo(false)
        }
    }

    @Test
    fun `9 losing the bond while ready blocks the link and nothing connects afterwards`() {
        val h = Harness().toReady()

        val lost = h.send(Event.Bond(bonded = false))
        val later =
            listOf(
                Event.PresenceAppeared,
                Event.Start(true),
                Event.TimerFired(Timer.BACKOFF),
            ).flatMap { h.send(it) }

        assertThat(lost).containsSubList(listOf(Effect.Close(1)))
        assertThat(lost).containsSubList(listOf(Effect.Problem(LinkProblem.NEEDS_REPAIR)))
        assertThat(h.mode).isEqualTo(Mode.BondBroken)
        assertThat(later.connects()).isEmpty()
    }

    @Test
    fun `pairing again recovers from a broken bond`() {
        val h = Harness().toReady()
        h.send(Event.Bond(bonded = false))

        val repaired = h.send(Event.Bond(bonded = true))

        assertThat(repaired).containsExactly(Effect.Problem(null), Effect.Foreground(true), Effect.OpenServer)
    }

    @Test
    fun `10 missing keys twice break the bond`() {
        val h = Harness().toReady()

        h.send(Event.KeyMissing)
        assertThat(h.mode).isInstanceOf<Mode.Ready>()
        h.send(Event.KeyMissing)

        assertThat(h.mode).isEqualTo(Mode.BondBroken)
    }

    @Test
    fun `authentication failures during setup count towards a broken bond`() {
        val h = Harness().toReady()
        h.send(Event.Disconnected(h.gen, status = 8))
        h.send(Event.Connected(h.gen))
        h.send(Event.SetupFailed(h.gen, LinkError.AuthenticationFailed(5)))
        assertThat(h.mode).isInstanceOf<Mode.Backoff>()

        h.send(Event.TimerFired(Timer.BACKOFF), after = 1_000)
        h.send(Event.Connected(h.gen))
        h.send(Event.SetupFailed(h.gen, LinkError.AuthenticationFailed(5)))

        assertThat(h.mode).isEqualTo(Mode.BondBroken)
    }

    @Test
    fun `11 a service change while ready runs the setup again on the same link`() {
        val h = Harness().toReady()

        val changed = h.send(Event.ServiceChanged(1))

        assertThat(changed).containsSubList(listOf(Effect.WakeLock(true), Effect.RunSetup(1)))
        assertThat(h.mode).isEqualTo(Mode.Linked(1, SetupStep.DISCOVER))
        h.send(Event.SetupDone(1, Harness.METER))
        assertThat(h.mode).isInstanceOf<Mode.Ready>()
    }

    @Test
    fun `12 events from a closed generation change nothing`() {
        val h = Harness().toReady()
        h.send(Event.Disconnected(1, status = 8))
        val before = h.machine

        val stale =
            listOf(
                Event.Connected(1),
                Event.Disconnected(1, 19),
                Event.SetupDone(1, Harness.METER),
                Event.SetupFailed(1, LinkError.ServicesMissing),
                Event.ServiceChanged(1),
                Event.TimerFired(Timer.SETUP_WATCHDOG, gen = 1),
                Event.TimerFired(Timer.READY_STABLE, gen = 1),
            ).flatMap { h.send(it) }

        assertThat(stale).isEmpty()
        assertThat(h.machine).isEqualTo(before)
    }

    @Test
    fun `13 duplicate start triggers connect exactly once`() {
        val h = Harness()

        repeat(3) { h.send(Event.Start(presentHint = true)) }
        h.send(Event.ServerOpened(ok = true))
        h.send(Event.Start(presentHint = true))

        assertThat(h.all.connects()).containsExactly(Effect.Connect(1, autoConnect = false))
    }

    @Test
    fun `a start hint while waiting switches to a direct connect`() {
        val h = Harness()
        h.send(Event.Start(presentHint = false))
        h.send(Event.ServerOpened(ok = true))

        val hinted = h.send(Event.Start(presentHint = true))

        assertThat(hinted).containsSubList(listOf(Effect.Close(1)))
        assertThat(hinted.connects()).containsExactly(Effect.Connect(2, autoConnect = false))
    }

    @Test
    fun `14 the bike disappearing from scans while ready changes nothing`() {
        val h = Harness(MachineConfig(lingerMillis = 600_000)).toReady()

        assertThat(h.send(Event.PresenceGone)).isEmpty()
        assertThat(h.mode).isInstanceOf<Mode.Ready>()
    }

    @Test
    fun `15 a device without the meter's services backs off (no hidden refresh)`() {
        val h = Harness().toReady()
        h.send(Event.Disconnected(1, 8))
        h.send(Event.Connected(2))

        h.send(Event.SetupFailed(2, LinkError.ServicesMissing))

        assertThat((h.mode as Mode.Backoff).error).isEqualTo(LinkError.ServicesMissing)
    }

    @Test
    fun `presence appearing while dormant starts a new cycle with a direct connect`() {
        val h = Harness(MachineConfig(lingerMillis = 1_000)).toReady()
        h.send(Event.Disconnected(1, 8))
        h.send(Event.TimerFired(Timer.LINGER), after = 1_000)

        val appeared = h.send(Event.PresenceAppeared)
        val opened = h.send(Event.ServerOpened(true))

        assertThat(appeared).containsExactly(Effect.Foreground(true), Effect.OpenServer)
        assertThat(opened.connects().single().autoConnect).isEqualTo(false)
    }

    @Test
    fun `revoking the permission blocks the link, granting it starts again`() {
        val h = Harness().toReady()

        val revoked = h.send(Event.Permission(granted = false))
        val granted = h.send(Event.Permission(granted = true))

        assertThat(revoked).containsSubList(listOf(Effect.Problem(LinkProblem.PERMISSION_MISSING)))
        assertThat(granted).containsExactly(Effect.Problem(null), Effect.Foreground(true), Effect.OpenServer)
    }

    @Test
    fun `starting without prerequisites blocks instead of connecting`() {
        val off = Harness(facts = Facts(bluetoothOn = false, bonded = true, permissionGranted = true))
        val unbonded = Harness(facts = Facts(bluetoothOn = true, bonded = false, permissionGranted = true))

        off.send(Event.Start(true))
        unbonded.send(Event.Start(true))

        assertThat(off.mode).isEqualTo(Mode.BluetoothOff)
        assertThat(unbonded.mode).isEqualTo(Mode.BondBroken)
        assertThat(off.all.connects() + unbonded.all.connects()).isEmpty()
    }

    @Test
    fun `a failed server open still connects and retries the server`() {
        val h = Harness()
        h.send(Event.Start(presentHint = true))

        val failed = h.send(Event.ServerOpened(ok = false))
        val retry = h.send(Event.TimerFired(Timer.SERVER_RETRY), after = 1_000)

        assertThat(failed.timers(Timer.SERVER_RETRY).single().delayMillis).isEqualTo(1_000)
        assertThat(failed.connects().size).isEqualTo(1)
        assertThat(retry).containsExactly(Effect.OpenServer)
    }

    @Test
    fun `stop closes everything`() {
        val h = Harness().toReady()

        val stopped = h.send(Event.Stop)

        assertThat(stopped).containsSubList(listOf(Effect.Close(1)))
        assertThat(stopped).containsSubList(listOf(Effect.CloseServer, Effect.Problem(null), Effect.Foreground(false)))
        assertThat(h.mode).isEqualTo(Mode.Stopped)
    }

    @Test
    fun `backoff delays never exceed the cap`() {
        (1..64).forEach { assertThat(backoffDelay(it)).isLessThan(Timeouts.BACKOFF_MAX_MS + 1) }
    }
}
