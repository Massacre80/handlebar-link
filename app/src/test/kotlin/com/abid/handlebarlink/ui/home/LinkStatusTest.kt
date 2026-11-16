package com.abid.handlebarlink.ui.home

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkState
import com.abid.handlebarlink.bike.link.MeterInfo
import com.abid.handlebarlink.bike.link.SetupStep
import org.junit.Test
import java.time.Instant

class LinkStatusTest {
    @Test
    fun `the pill says what the link is doing`() {
        val expected =
            mapOf(
                LinkState.Stopped to LinkStatus.NOT_RIDING,
                LinkState.Idle to LinkStatus.NOT_RIDING,
                LinkState.WaitingForBike to LinkStatus.LOOKING,
                LinkState.Connecting(1) to LinkStatus.CONNECTING,
                LinkState.SettingUp(SetupStep.ASSIGNMENTS) to LinkStatus.CONNECTING,
                LinkState.Ready(MeterInfo(null, null), Instant.EPOCH) to LinkStatus.CONNECTED,
                LinkState.Recovering(LinkError.ServicesMissing, 1, Instant.EPOCH) to LinkStatus.RECONNECTING,
                LinkState.Blocked(LinkError.BluetoothOff) to LinkStatus.BLUETOOTH_OFF,
                LinkState.Blocked(LinkError.PermissionMissing) to LinkStatus.NEEDS_PERMISSION,
                LinkState.Blocked(LinkError.BondLost) to LinkStatus.NEEDS_PAIRING,
            )

        expected.forEach { (state, status) -> assertThat(linkStatus(true, state), state.toString()).isEqualTo(status) }
    }

    @Test
    fun `without a bike the pill asks for one, whatever the link says`() {
        assertThat(linkStatus(false, LinkState.WaitingForBike)).isEqualTo(LinkStatus.NO_BIKE)
        assertThat(linkStatus(true, null)).isEqualTo(LinkStatus.NOT_RIDING)
    }
}
