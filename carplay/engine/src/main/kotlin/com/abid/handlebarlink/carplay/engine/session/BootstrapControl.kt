package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.protocol.iap2.AccessoryIdentity
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2Message
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2MessageId
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2ProtocolException
import com.abid.handlebarlink.carplay.protocol.iap2.StartSession
import com.abid.handlebarlink.carplay.protocol.iap2.WirelessCarPlayMessages
import com.abid.handlebarlink.carplay.protocol.mfi.MfiIdentity
import com.abid.handlebarlink.core.common.log.Logger

/** What the bootstrap tells the iPhone. */
internal class BootstrapSetup(
    val identity: AccessoryIdentity,
    val bluetoothMac: ByteArray,
    val mfi: MfiIdentity,
    val startSession: StartSession,
)

/**
 * The iAP2 control client of the Bluetooth bootstrap, in DiPlay's order (Iap2WirelessControlClient.kt:23-235,
 * spec B §3): identification (one corrected retry after a 0x1D03), MFi authentication, five subscriptions,
 * then answers: 0x5702 → Wi-Fi configuration (at most 5, and 2 after 0x4E0E), 0x4300 → StartSession (at
 * most 3), 0x4E0E → Wi-Fi configuration again.
 *
 * Unlike DiPlay, a message we do not expect during identification or authentication is logged and skipped
 * instead of aborting (spec B §7.10): a stray 0x4E0D must not cost a whole reconnect.
 */
internal class BootstrapControl(
    private val channel: Iap2Channel,
    private val setup: BootstrapSetup,
    private val log: Logger,
) {
    /** Identification; returns when the iPhone accepted it. */
    suspend fun identify() {
        var omitted = emptySet<Int>()
        var retried = false
        var accepted = false
        while (!accepted) {
            val message = channel.receive()
            when (message.id) {
                Iap2MessageId.START_IDENTIFICATION -> {
                    channel.send(identification(omitted))
                }

                Iap2MessageId.IDENTIFICATION_ACCEPTED -> {
                    accepted = true
                }

                Iap2MessageId.IDENTIFICATION_REJECTED -> {
                    val correctable = WirelessCarPlayMessages.correctableRejection(message)
                    if (correctable == null || retried) throw CarPlayException(CarPlayError.IdentificationRejected)
                    log.warn("carplay.iap2.identification_corrected", "omitted" to correctable.map(::hex))
                    omitted = omitted + correctable
                    retried = true
                    channel.send(identification(omitted))
                }

                else -> {
                    skip(message)
                }
            }
        }
        log.info("carplay.iap2.identified")
    }

    /** MFi authentication; returns on AuthenticationSucceeded. */
    suspend fun authenticate() {
        var succeeded = false
        while (!succeeded) {
            val message = channel.receive()
            when (message.id) {
                Iap2MessageId.REQUEST_AUTHENTICATION_CERTIFICATE -> {
                    channel.send(WirelessCarPlayMessages.authenticationCertificate(setup.mfi.certificate))
                }

                Iap2MessageId.REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE -> {
                    channel.send(response(message))
                }

                Iap2MessageId.AUTHENTICATION_SUCCEEDED -> {
                    succeeded = true
                }

                Iap2MessageId.AUTHENTICATION_FAILED -> {
                    throw CarPlayException(CarPlayError.AuthenticationFailed)
                }

                else -> {
                    skip(message)
                }
            }
        }
        log.info("carplay.iap2.authenticated")
        WirelessCarPlayMessages.subscriptions().forEach { channel.send(it) }
    }

    /** Answers the iPhone until the link ends; calls [onStartSessionSent] after each StartSession. */
    suspend fun serve(onStartSessionSent: () -> Unit): Nothing {
        val offers = WifiOffers()
        var startSessions = 0
        while (true) {
            val message = channel.receive()
            when (message.id) {
                Iap2MessageId.REQUEST_ACCESSORY_WIFI_CONFIGURATION -> {
                    if (offers.take()) offerWifi("request")
                }

                Iap2MessageId.DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION -> {
                    if (offers.transportSeen()) offerWifi("transport")
                }

                Iap2MessageId.CARPLAY_AVAILABILITY -> {
                    if (startSessions < MAX_START_SESSIONS) {
                        startSessions++
                        channel.send(WirelessCarPlayMessages.startSession(setup.startSession))
                        log.info("carplay.iap2.start_session", "count" to startSessions)
                        onStartSessionSent()
                    }
                }

                Iap2MessageId.WIRELESS_CARPLAY_UPDATE -> {
                    log.info("carplay.iap2.wireless_update")
                }

                else -> {
                    skip(message)
                }
            }
        }
    }

    private suspend fun offerWifi(reason: String) {
        channel.send(WirelessCarPlayMessages.wifiConfiguration(setup.startSession.network))
        log.info("carplay.iap2.wifi_offered", "reason" to reason)
    }

    private fun identification(omitted: Set<Int>): Iap2Message =
        WirelessCarPlayMessages.identification(
            setup.identity,
            setup.bluetoothMac,
            setup.startSession.network.ssid,
            omitted,
        )

    private fun response(request: Iap2Message): Iap2Message {
        val challenge =
            try {
                WirelessCarPlayMessages.challenge(request)
            } catch (e: Iap2ProtocolException) {
                throw CarPlayException(CarPlayError.LinkFailed(e.message ?: "bad MFi challenge request"), e)
            }
        if (challenge.size != CHALLENGE_BYTES) {
            throw CarPlayException(CarPlayError.LinkFailed("MFi challenge of ${challenge.size} bytes"))
        }
        return WirelessCarPlayMessages.authenticationResponse(setup.mfi.sign(challenge))
    }

    private fun skip(message: Iap2Message) = log.debug("carplay.iap2.skipped", "id" to hex(message.id))

    /** DiPlay's caps on Wi-Fi configuration replies (Iap2WirelessControlClient.kt:247-248). */
    private class WifiOffers {
        private var before = 0
        private var after = 0
        private var transport = false

        /** Whether one more reply is allowed; counts it if so. */
        fun take(): Boolean {
            val allowed = if (transport) after < MAX_AFTER else before < MAX_BEFORE
            if (allowed && transport) after++
            if (allowed && !transport) before++
            return allowed
        }

        fun transportSeen(): Boolean {
            transport = true
            return take()
        }

        private companion object {
            const val MAX_BEFORE = 5
            const val MAX_AFTER = 2
        }
    }

    private companion object {
        const val CHALLENGE_BYTES = 32
        const val MAX_START_SESSIONS = 3

        fun hex(id: Int) = "0x%04X".format(id)
    }
}
