package com.abid.handlebarlink.ride

import android.content.pm.ServiceInfo
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import org.junit.Test
import java.time.Instant

/** The ride service's foreground types: connectedDevice, and the microphone while CarPlay may record. */
class ServiceTypesTest {
    private val connected = CarPlayState.Connected("phone", Instant.EPOCH, 36)
    private val recovering = CarPlayState.Recovering(CarPlayError.PhoneDisconnected("gone"), 1, Instant.EPOCH)

    @Test
    fun `the microphone type is added only while CarPlay is connected, allowed and the app visible`() {
        assertThat(keepsMicrophone(has = false, connected, visible = true, permitted = true)).isTrue()
        assertThat(keepsMicrophone(has = false, connected, visible = false, permitted = true)).isFalse()
        assertThat(keepsMicrophone(has = false, connected, visible = true, permitted = false)).isFalse()
        assertThat(keepsMicrophone(false, CarPlayState.Connecting(ConnectStep.PAIRING), true, true)).isFalse()
    }

    @Test
    fun `once held it survives screen-off and CarPlay's drops and retries`() {
        assertThat(keepsMicrophone(has = true, connected, visible = false, permitted = true)).isTrue()
        assertThat(keepsMicrophone(has = true, recovering, visible = false, permitted = true)).isTrue()
        assertThat(keepsMicrophone(true, CarPlayState.Connecting(ConnectStep.DIALING_PHONE), false, true)).isTrue()
    }

    @Test
    fun `it is given back when CarPlay stops or the permission goes`() {
        assertThat(keepsMicrophone(has = true, CarPlayState.Idle, visible = true, permitted = true)).isFalse()
        assertThat(keepsMicrophone(has = true, CarPlayState.Stopped, visible = true, permitted = true)).isFalse()
        assertThat(keepsMicrophone(has = true, connected, visible = true, permitted = false)).isFalse()
    }

    @Test
    fun `the types always keep connectedDevice and add the microphone, never a phone call`() {
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        assertThat(rideServiceTypes(microphone = false)).isEqualTo(device)
        assertThat(
            rideServiceTypes(microphone = true),
        ).isEqualTo(device or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
}
