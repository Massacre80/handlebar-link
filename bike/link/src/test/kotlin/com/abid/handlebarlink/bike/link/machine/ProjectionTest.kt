package com.abid.handlebarlink.bike.link.machine

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkState
import com.abid.handlebarlink.bike.link.SetupStep
import org.junit.Test
import java.time.Instant

class ProjectionTest {
    private val wall = Instant.parse("2026-11-16T15:30:00Z")
    private val now = 100_000L

    @Test
    fun `each internal mode maps to what the rider should see`() {
        val expected =
            mapOf(
                Mode.Stopped to LinkState.Stopped,
                Mode.Dormant to LinkState.Idle,
                Mode.Arming to LinkState.WaitingForBike,
                Mode.Waiting(3, armedAt = 0, lingering = true) to LinkState.WaitingForBike,
                Mode.Direct(3, attempt = 2, startedAt = 0) to LinkState.Connecting(2),
                Mode.DirectPause(3) to LinkState.Connecting(3),
                Mode.Linked(3, SetupStep.ASSIGNMENTS) to LinkState.SettingUp(SetupStep.ASSIGNMENTS),
                Mode.BluetoothOff to LinkState.Blocked(LinkError.BluetoothOff),
                Mode.BondBroken to LinkState.Blocked(LinkError.BondLost),
                Mode.PermissionMissing to LinkState.Blocked(LinkError.PermissionMissing),
            )

        expected.forEach { (mode, state) -> assertThat(mode.toLinkState(now, wall)).isEqualTo(state) }
    }

    @Test
    fun `monotonic times become wall-clock instants`() {
        val ready = Mode.Ready(3, Harness.METER, since = now - 5_000)
        val backoff = Mode.Backoff(LinkError.TimedOut(GattOp.SETUP), attempt = 2, until = now + 2_000)

        assertThat(ready.toLinkState(now, wall)).isEqualTo(LinkState.Ready(Harness.METER, wall.minusSeconds(5)))
        assertThat(backoff.toLinkState(now, wall))
            .isEqualTo(LinkState.Recovering(LinkError.TimedOut(GattOp.SETUP), 2, wall.plusSeconds(2)))
    }
}
