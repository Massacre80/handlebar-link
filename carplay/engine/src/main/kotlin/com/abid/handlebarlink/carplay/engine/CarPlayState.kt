package com.abid.handlebarlink.carplay.engine

import java.time.Instant

/** What the CarPlay receiver is doing, for the UI and the notification. */
public sealed interface CarPlayState {
    /** [CarPlayLink.run] is not running. */
    public data object Stopped : CarPlayState

    /** Running, not connecting (before [CarPlayLink.start] or after [CarPlayLink.stop]). */
    public data object Idle : CarPlayState

    /** Working through the connection [step]. */
    public data class Connecting(
        /** The current step. */
        val step: ConnectStep,
    ) : CarPlayState

    /** The iPhone is paired, verified and authenticated on the encrypted AirPlay control connection. */
    public data class Connected(
        /** The iPhone's pairing id. */
        val phone: String,
        /** When the connection was established. */
        val since: Instant,
        /**
         * The Wi-Fi Direct group's channel number (1-14 on 2.4 GHz, 36-165 on 5 GHz; the Android port creates no
         * other band), as offered to the iPhone in the iAP2 Wi-Fi information; null when unknown.
         */
        val wifiChannel: Int? = null,
    ) : CarPlayState

    /** An attempt failed; the next one starts at [retryAt]. */
    public data class Recovering(
        /** Why. */
        val error: CarPlayError,
        /** Failed attempts in a row. */
        val attempt: Int,
        /** When the next attempt starts. */
        val retryAt: Instant,
    ) : CarPlayState
}

/** The steps of one connection attempt, in order (DiPlay CarPlayController.kt:1377-1886, spec D §1.2). */
public enum class ConnectStep {
    /** Loading the MFi and AirPlay identities. */
    LOADING_IDENTITY,

    /** Creating the Wi-Fi Direct group, the AirPlay listener and the Bonjour record; dialing the iPhone. */
    PREPARING,

    /** Waiting for the Bluetooth RFCOMM connection to the iPhone. */
    DIALING_PHONE,

    /** iAP2 link and identification. */
    IDENTIFYING,

    /** MFi authentication over iAP2. */
    AUTHENTICATING,

    /** Offering the Wi-Fi network and AirPlay address; waiting for the iPhone to join and connect. */
    WAITING_FOR_PHONE,

    /** AirPlay pair-setup / pair-verify on the control connection. */
    PAIRING,
}
