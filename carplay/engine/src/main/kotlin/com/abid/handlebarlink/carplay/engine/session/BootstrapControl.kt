package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.protocol.iap2.AccessoryIdentity
import com.abid.handlebarlink.carplay.protocol.iap2.CallStateUpdate
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2Message
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2MessageId
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2ProtocolException
import com.abid.handlebarlink.carplay.protocol.iap2.StartSession
import com.abid.handlebarlink.carplay.protocol.iap2.WirelessCarPlayMessages
import com.abid.handlebarlink.carplay.protocol.mfi.MfiIdentity
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** What the bootstrap tells the iPhone. */
internal class BootstrapSetup(
    val identity: AccessoryIdentity,
    val bluetoothMac: ByteArray,
    val mfi: MfiIdentity,
    val startSession: StartSession,
)

/** What the bootstrap reports while it serves the iPhone (for the link watch and the attempt's steps). */
internal interface BootstrapMilestones {
    /** A Wi-Fi configuration (0x5703) went out. */
    fun wifiOffered()

    /** A StartSession (0x4301) went out. */
    fun startSessionSent()
}

/**
 * The iAP2 control client of the Bluetooth bootstrap, in DiPlay's order (Iap2WirelessControlClient.kt:23-235,
 * spec B §3): identification (one corrected retry after a 0x1D03), MFi authentication, five subscriptions,
 * then answers: 0x5702 → Wi-Fi configuration (at most 5, and 2 after 0x4E0E), 0x4300 → StartSession (at
 * most 3), 0x4E0E → Wi-Fi configuration again; CallStateUpdates (0x4155, asked for by the 0x4154 subscription) go to
 * [calls], as DiPlay forwards them from this loop (Iap2WirelessControlClient.kt:32, CarPlayController.kt:942-943).
 *
 * The same client runs again over the AirPlay iAP2 tunnel once the iPhone opens it, as DiPlay runs
 * `Iap2WirelessControlClient` on both links (CarPlayController.kt:1783-1802, 1914-1931): the same identification,
 * authentication, subscriptions and answers ([IapTunnel]); only the Bluetooth link is nudged.
 *
 * Unlike DiPlay, a message we do not expect during identification or authentication is logged and skipped
 * instead of aborting (spec B §7.10): a stray 0x4E0D must not cost a whole reconnect. [nudge] re-sends the Wi-Fi
 * configuration and StartSession when no AirPlay connection follows (AllPlay, E-connection.md #3).
 */
internal class BootstrapControl(
    private val channel: Iap2Channel,
    private val setup: BootstrapSetup,
    private val time: TimeProvider,
    private val log: Logger,
    private val milestones: BootstrapMilestones,
    /** Where the iPhone's CallStateUpdates go. */
    private val calls: CallStates,
) {
    private val firstStartSession = CompletableDeferred<Unit>()
    private var lastStartSessionAt = 0L

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
        log.info("carplay.iap2.identified", "link" to channel.name)
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
        log.info("carplay.iap2.authenticated", "link" to channel.name)
        WirelessCarPlayMessages.subscriptions().forEach { channel.send(it) }
    }

    /** Answers the iPhone until the link ends. */
    suspend fun serve(): Nothing {
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
                        sendStartSession("request $startSessions")
                    }
                }

                Iap2MessageId.WIRELESS_CARPLAY_UPDATE -> {
                    log.info("carplay.iap2.wireless_update", "link" to channel.name)
                }

                CallStateUpdate.MESSAGE_ID -> {
                    calls.accept(message)
                }

                else -> {
                    skip(message)
                }
            }
        }
    }

    /**
     * AllPlay's nudge (wireless_client.cpp:446-462, constants :66-70, reason wireless_client.h:76-80): when no
     * AirPlay connection ([tcp]) has arrived [NUDGE_AFTER] ms after the latest StartSession, send the Wi-Fi
     * configuration again and then StartSession, at most [MAX_NUDGES] times; every StartSession re-arms the wait.
     * The Wi-Fi configuration goes first because the iPhone most likely missed the join. Not counted against the
     * reply caps (AllPlay keeps separate counters). Returns when the connection came or the nudges are spent.
     */
    suspend fun nudge(tcp: Deferred<Unit>) {
        firstStartSession.await()
        var nudges = 0
        while (nudges < MAX_NUDGES && !tcp.isCompleted) {
            val wait = lastStartSessionAt + NUDGE_AFTER - time.elapsedMillis()
            if (wait > 0) {
                withTimeoutOrNull(wait) { tcp.await() }
            } else {
                nudges++
                offerWifi("nudge $nudges")
                sendStartSession("nudge $nudges")
            }
        }
    }

    private suspend fun sendStartSession(reason: String) {
        channel.send(WirelessCarPlayMessages.startSession(setup.startSession))
        lastStartSessionAt = time.elapsedMillis()
        log.info("carplay.iap2.start_session", "link" to channel.name, "reason" to reason)
        milestones.startSessionSent()
        firstStartSession.complete(Unit)
    }

    private suspend fun offerWifi(reason: String) {
        channel.send(WirelessCarPlayMessages.wifiConfiguration(setup.startSession.network))
        log.info("carplay.iap2.wifi_offered", "link" to channel.name, "reason" to reason)
        milestones.wifiOffered()
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

    private fun skip(message: Iap2Message) =
        log.debug(
            "carplay.iap2.skipped",
            "link" to channel.name,
            "id" to hex(message.id),
        )

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

        /** AllPlay kAirPlayRetryMs and kAirPlayRetryMax (wireless_client.cpp:66-70). */
        const val NUDGE_AFTER = 3_000L
        const val MAX_NUDGES = 2

        fun hex(id: Int) = "0x%04X".format(id)
    }
}
