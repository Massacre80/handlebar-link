package com.abid.handlebarlink.carplay.protocol.iap2

/**
 * The iAP2 Location Information messages (DiPlay CarLink vendor/diplay
 * `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2LocationClient.kt:221-236`, `Iap2LocationMessages`).
 * The iPhone starts location with 0xFFFA and stops it with 0xFFFC; the accessory answers each requested fix as a
 * 0xFFFB carrying one NUL-terminated NMEA string in parameter 0. These only ever flow over the Wi-Fi runtime tunnel
 * (docs/research/carplay-spec/B-iap2-messages.md:121), where the identification declares location support
 * ([WirelessCarPlayMessages.identification] with `location = true`); the Bluetooth bootstrap never offers it.
 */
public object LocationMessages {
    /** StartLocationInformation (iPhone to accessory): begin sending location; its parameters select the sentences. */
    public const val START_LOCATION_INFORMATION: Int = 0xFFFA

    /** LocationInformation (accessory to iPhone): the NMEA text for one fix. */
    public const val LOCATION_INFORMATION: Int = 0xFFFB

    /** StopLocationInformation (iPhone to accessory): stop sending location. */
    public const val STOP_LOCATION_INFORMATION: Int = 0xFFFC

    /**
     * The sentence types a 0xFFFA request asks for: its parameter ids (DiPlay `requestedComponents`). We send the
     * GGA/RMC pair regardless; this is read only for the log. Empty when the request carries no parameters.
     */
    public fun requestedComponents(request: Iap2Message): Set<Int> = request.parameters.mapTo(sortedSetOf()) { it.id }

    /** A 0xFFFB LocationInformation carrying [nmea] as its one NUL-terminated string (DiPlay `locationInformation`). */
    public fun locationInformation(nmea: String): Iap2Message {
        require(nmea.isNotEmpty()) { "NMEA sentence must not be empty" }
        return iap2Message(LOCATION_INFORMATION) { string(0, nmea) }
    }
}
