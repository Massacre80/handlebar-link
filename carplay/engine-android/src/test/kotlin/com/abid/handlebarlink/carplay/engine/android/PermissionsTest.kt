package com.abid.handlebarlink.carplay.engine.android

import android.Manifest
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.carplay.engine.android.wifi.ChannelPlan
import org.junit.Test

class PermissionsTest {
    @Test
    fun `Android 12 asks for Bluetooth, location and the microphone`() {
        assertThat(CarPlayPermissions.runtime(31))
            .containsExactly(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.RECORD_AUDIO,
            )
        assertThat(CarPlayPermissions.runtime(32))
            .containsExactly(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.RECORD_AUDIO,
            )
    }

    @Test
    fun `Android 13 to 16 ask for Bluetooth, nearby Wi-Fi devices and the microphone`() {
        assertThat(CarPlayPermissions.runtime(33))
            .containsExactly(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.RECORD_AUDIO,
            )
        assertThat(CarPlayPermissions.runtime(36))
            .containsExactly(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.RECORD_AUDIO,
            )
    }

    @Test
    fun `Android 17 adds the local network`() {
        assertThat(CarPlayPermissions.runtime(37)).containsExactly(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    @Test
    fun `the Wi-Fi Direct permission the port checks matches the list`() {
        listOf(31, 32, 33, 36, 37).forEach { sdk ->
            assertThat(CarPlayPermissions.runtime(sdk)[1]).isEqualTo(ChannelPlan.wifiDirectPermission(sdk))
        }
    }
}
