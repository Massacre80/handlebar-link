package com.abid.handlebarlink.carplay.engine

/** Why a connection attempt failed. [needsUser] errors keep failing until the rider acts. */
public sealed interface CarPlayError {
    /** Whether only the rider can fix it (shown as a problem rather than a passing state). */
    public val needsUser: Boolean get() = false

    /** The MFi files or the AirPlay identity could not be loaded. */
    public data class IdentityUnavailable(
        /** What was wrong. */
        val reason: String,
    ) : CarPlayError {
        override val needsUser: Boolean get() = true
    }

    /** Bluetooth is off, the permission is missing, or no iPhone is paired. */
    public data class BluetoothUnavailable(
        /** What was wrong. */
        val reason: String,
    ) : CarPlayError {
        override val needsUser: Boolean get() = true
    }

    /** A runtime permission CarPlay needs is not granted (Nearby devices, local network). */
    public data class PermissionMissing(
        /** The Android permission's name. */
        val permission: String,
    ) : CarPlayError {
        override val needsUser: Boolean get() = true
    }

    /** Wi-Fi is off on this phone, so no Wi-Fi Direct group can be created. */
    public data object WifiOff : CarPlayError {
        override val needsUser: Boolean get() = true
    }

    /**
     * The start stalled after the iPhone got the Wi-Fi details: failed in seconds instead of waiting for a 30 s
     * step timeout (CarLink CarPlayLinkWatch.kt:5-26; E-connection.md #10).
     */
    public data class Stalled(
        /** Where it stalled. */
        val verdict: StallVerdict,
    ) : CarPlayError

    /** The Wi-Fi Direct group could not be created (busy radio, unsupported channel, no answer). */
    public data class WifiDirectFailed(
        /** What the system reported. */
        val reason: String,
    ) : CarPlayError

    /** The AirPlay listener or a socket failed. */
    public data class NetworkFailed(
        /** What the system reported. */
        val reason: String,
    ) : CarPlayError

    /** The RFCOMM dial to the iPhone failed. */
    public data class DialFailed(
        /** What the system reported. */
        val reason: String,
    ) : CarPlayError

    /** The iAP2 link or a control message was broken, or the iPhone closed Bluetooth. */
    public data class LinkFailed(
        /** What happened. */
        val reason: String,
    ) : CarPlayError

    /** The iPhone refused our identification. */
    public data object IdentificationRejected : CarPlayError

    /** The iPhone reported MFi authentication failed. */
    public data object AuthenticationFailed : CarPlayError

    /** A step took too long. */
    public data class TimedOut(
        /** The step. */
        val step: ConnectStep,
    ) : CarPlayError

    /** The verified AirPlay control connection ended. */
    public data class PhoneDisconnected(
        /** What happened. */
        val reason: String,
    ) : CarPlayError

    /** A bug in our code; logged with its stack trace, and the receiver tries again. */
    public data class Unexpected(
        /** The exception. */
        val cause: Throwable,
    ) : CarPlayError
}

/** Where a start stalled (CarLink CarPlayLinkWatch.Verdict). */
public enum class StallVerdict {
    /** The iPhone was given the Wi-Fi details but did not join the group within 9 s (its Wi-Fi may be off). */
    NOT_JOINED,

    /** The iPhone joined the group but did not ask to start CarPlay within 4 s. */
    NO_START_REQUEST,

    /** The iPhone joined, holds a DHCP lease and was sent StartSession, but opened no AirPlay connection in 8 s. */
    NO_TCP,
}

/**
 * A typed failure of a connection step. Thrown by port implementations and the session; the runtime turns
 * it into [CarPlayState.Recovering], so it never reaches [CarPlayLink]'s users.
 */
public class CarPlayException(
    /** What failed. */
    public val error: CarPlayError,
    cause: Throwable? = null,
) : Exception(error.toString(), cause)
