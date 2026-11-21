package com.abid.handlebarlink.carplay.protocol.iap2

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.Test

/**
 * The iAP2 Location Information messages against DiPlay's vectors (CarLink vendor/diplay
 * `shared/src/test/java/com/shilapi/xcertplay/transport/Iap2LocationClientTest.kt`): the 0xFFFB carries the NMEA as
 * one NUL-terminated string in parameter 0, and the tunnel identification (`location = true`) is the only one that
 * advertises 0xFFFB sent and 0xFFFA/0xFFFC received.
 */
class LocationMessagesTest {
    private val identity =
        AccessoryIdentity(
            name = "LIVI",
            model = "LIVI",
            manufacturer = "LIVI",
            serialNumber = "0123456",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            externalAccessoryProtocol = "com.shilapi.xcertplay",
        )

    @Test
    fun `a 0xFFFB carries the NMEA as one NUL-terminated string in parameter 0`() {
        // DiPlay Iap2LocationClientTest: locationInformation("$GPGGA") -> param 0 == "$GPGGA\u0000".
        val message = LocationMessages.locationInformation("\$GPGGA")
        assertThat(message.id).isEqualTo(LocationMessages.LOCATION_INFORMATION)
        assertThat(message.parameter(0)!!.payload.toList())
            .isEqualTo("\$GPGGA\u0000".encodeToByteArray().toList())
    }

    @Test
    fun `an empty NMEA sentence is refused`() {
        assertFailure { LocationMessages.locationInformation("") }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `requestedComponents reads the request's parameter ids`() {
        val request = iap2Message(LocationMessages.START_LOCATION_INFORMATION) { listOf(4, 1, 2).forEach(::none) }
        assertThat(LocationMessages.requestedComponents(request)).isEqualTo(sortedSetOf(1, 2, 4))
    }

    @Test
    fun `the tunnel identification advertises location, the Bluetooth one does not`() {
        val tunnel = WirelessCarPlayMessages.identification(identity, ByteArray(6), "LIVI", location = true)
        assertThat(tunnel.parameter(6)!!.u16List()).contains(LocationMessages.LOCATION_INFORMATION)
        assertThat(tunnel.parameter(7)!!.u16List()).contains(LocationMessages.START_LOCATION_INFORMATION)
        assertThat(tunnel.parameter(7)!!.u16List()).contains(LocationMessages.STOP_LOCATION_INFORMATION)

        val bluetooth = WirelessCarPlayMessages.identification(identity, ByteArray(6), "LIVI")
        assertThat(bluetooth.parameter(6)!!.u16List()).doesNotContain(LocationMessages.LOCATION_INFORMATION)
        assertThat(bluetooth.parameter(7)!!.u16List()).doesNotContain(LocationMessages.START_LOCATION_INFORMATION)
    }
}
