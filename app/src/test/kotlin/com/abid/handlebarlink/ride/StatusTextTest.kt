package com.abid.handlebarlink.ride

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.R
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkProblem
import com.abid.handlebarlink.bike.link.LinkState
import com.abid.handlebarlink.bike.link.MeterInfo
import com.abid.handlebarlink.bike.link.SetupStep
import org.junit.Test
import java.time.Instant

class StatusTextTest {
    @Test
    fun `each link state has the rider-facing line`() {
        val expected =
            mapOf(
                LinkState.Stopped to R.string.status_stopped,
                LinkState.Idle to R.string.status_stopped,
                LinkState.WaitingForBike to R.string.status_waiting,
                LinkState.Connecting(1) to R.string.status_connecting,
                LinkState.SettingUp(SetupStep.MTU) to R.string.status_connecting,
                LinkState.Ready(MeterInfo(null, null), Instant.EPOCH) to R.string.status_ready,
                LinkState.Recovering(LinkError.ServicesMissing, 1, Instant.EPOCH) to R.string.status_recovering,
                LinkState.Blocked(LinkError.BluetoothOff) to R.string.status_bluetooth_off,
                LinkState.Blocked(LinkError.PermissionMissing) to R.string.problem_permission,
                LinkState.Blocked(LinkError.BondLost) to R.string.problem_repair,
            )

        expected.forEach { (state, text) -> assertThat(statusText(state), state.toString()).isEqualTo(text) }
    }

    @Test
    fun `each problem tells the rider what to do`() {
        val expected =
            mapOf(
                LinkProblem.PERMISSION_MISSING to R.string.problem_permission,
                LinkProblem.NEEDS_REPAIR to R.string.problem_repair,
                LinkProblem.BLUETOOTH_STUCK to R.string.problem_bluetooth_stuck,
            )

        assertThat(expected.keys).isEqualTo(LinkProblem.entries.toSet())
        expected.forEach { (problem, text) -> assertThat(problemText(problem)).isEqualTo(text) }
    }
}
