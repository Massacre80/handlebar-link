package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.spi.LocationPort
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2Message
import com.abid.handlebarlink.carplay.protocol.iap2.LocationMessages
import com.abid.handlebarlink.carplay.protocol.location.NmeaLocationEncoder
import com.abid.handlebarlink.core.common.log.Logger

/**
 * The accessory side of iAP2 Location Information on one link (DiPlay `Iap2LocationReporter`): the bike's GPS goes to
 * the iPhone while it has subscribed. [handle] turns the [port] on at 0xFFFA and off at 0xFFFC; [latest] gives the
 * next 0xFFFB (the latest fix as NMEA) while active, which the link's pump sends about once a second. The subscription
 * belongs to this link: a request on Bluetooth never authorises output on the Wi-Fi tunnel (this reporter is the
 * tunnel's; the Bluetooth bootstrap has none, and its identification never offers location). Owner, 2026-11-21: "when
 * I start the ignition CarPlay doesn't try" was the connect side; this is the GPS side of the same ride.
 */
internal class LocationReporter(
    private val port: LocationPort,
    log: Logger,
) {
    private val log = log.child("carplay")
    private var active = false

    /** Handles 0xFFFA (start) and 0xFFFC (stop); returns false for any other message, which the caller skips. */
    fun handle(message: Iap2Message): Boolean =
        when (message.id) {
            LocationMessages.START_LOCATION_INFORMATION -> {
                val components = LocationMessages.requestedComponents(message)
                active = port.start()
                log.info("location.start", "components" to components, "started" to active)
                true
            }

            LocationMessages.STOP_LOCATION_INFORMATION -> {
                active = false
                port.stop()
                log.info("location.stop")
                true
            }

            else -> {
                false
            }
        }

    /** The 0xFFFB for the latest fix while the iPhone is subscribed, or null (not subscribed, or no fix yet). */
    fun latest(): Iap2Message? =
        if (active) {
            port.latestFix()?.let { LocationMessages.locationInformation(NmeaLocationEncoder.encode(it)) }
        } else {
            null
        }
}
