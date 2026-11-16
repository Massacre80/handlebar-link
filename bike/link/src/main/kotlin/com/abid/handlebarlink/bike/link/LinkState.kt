package com.abid.handlebarlink.bike.link

import com.abid.handlebarlink.bike.protocol.DisplayCapability
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.bike.protocol.SeriesCode
import java.time.Instant

/** What the bike link is doing, for the UI and the notification. */
public sealed interface LinkState {
    /** Not running. */
    public data object Stopped : LinkState

    /** Not running; the system tells the app when the bike appears (companion device presence). */
    public data object Idle : LinkState

    /** Waiting for the rider: Bluetooth off, permission missing or pairing lost. */
    public data class Blocked(
        /** What is needed. */
        val error: LinkError,
    ) : LinkState

    /** Ready to connect as soon as the meter advertises (ignition on). */
    public data object WaitingForBike : LinkState

    /** Connecting directly; [attempt] counts from 1. */
    public data class Connecting(
        /** The attempt. */
        val attempt: Int,
    ) : LinkState

    /** Linked, setting up the meter. */
    public data class SettingUp(
        /** The current step. */
        val step: SetupStep,
    ) : LinkState

    /** Handlebar presses are being forwarded. */
    public data class Ready(
        /** What the meter reported. */
        val meter: MeterInfo,
        /** When the link became ready. */
        val since: Instant,
    ) : LinkState

    /** A setup failed; retrying by itself. */
    public data class Recovering(
        /** Why. */
        val error: LinkError,
        /** How many setups failed in a row. */
        val attempt: Int,
        /** When the next attempt starts. */
        val retryAt: Instant,
    ) : LinkState
}

/** Setup steps after the link comes up, in order (docs/protocol/SAB_PROTOCOL.md §4.2). */
public enum class SetupStep {
    /** Waiting for bonding to finish before any GATT work. */
    BOND,

    /** Requesting the MTU. */
    MTU,

    /** Discovering services. */
    DISCOVER,

    /** Reading the vehicle information. */
    VEHICLE_INFO,

    /** Enabling the three notifications. */
    NOTIFICATIONS,

    /** Registering the switch functions. */
    ASSIGNMENTS,

    /** Asking the meter for phone mode. */
    PHONE_MODE,
}

/** What the meter reported during setup. Null fields could not be read. */
public data class MeterInfo(
    /** The vehicle series code (03F5 on a CB350). */
    val seriesCode: SeriesCode?,
    /** What the meter can display. */
    val displayCapability: DisplayCapability?,
)

/** One handlebar press. */
public data class ButtonEvent(
    /** Which press. */
    val switch: HandlebarSwitch,
    /** When it arrived. */
    val at: Instant,
)

/** A problem that needs a notification because the rider has to act. */
public enum class LinkProblem {
    /** The Bluetooth permission was revoked. */
    PERMISSION_MISSING,

    /** The pairing with the meter is gone; pair again. */
    NEEDS_REPAIR,

    /** Connections keep failing at once; switching Bluetooth off and on usually helps. */
    BLUETOOTH_STUCK,
}
