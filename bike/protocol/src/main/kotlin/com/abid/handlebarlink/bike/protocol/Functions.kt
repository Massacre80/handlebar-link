package com.abid.handlebarlink.bike.protocol

/**
 * The function ids the meter sends on the Function Notification Source, one byte per press
 * (docs/protocol/SAB_PROTOCOL.md §5.1). The meter decides short versus long press itself.
 */
public enum class FunctionId(
    /** The byte on the wire. */
    public val code: Int,
) {
    /** Previous; sent for a short press up on the CB350. */
    PREVIOUS(0x01),

    /** Next; sent for a short press down on the CB350. */
    NEXT(0x02),

    /** Back; sent for a short press left on the CB350. */
    BACK(0x03),

    /** Select; sent for a short press right on the CB350. */
    SELECT(0x04),

    /** Volume up; registered as a HID key (§5.2), not delivered on the Function Notification Source. */
    VOLUME_UP(0x05),

    /** Volume down; registered as a HID key (§5.2), not delivered on the Function Notification Source. */
    VOLUME_DOWN(0x06),

    /** Volume mute; sent for a long press down on the CB350. */
    VOLUME_MUTE(0x07),

    /** Menu; sent for a long press left on the CB350. */
    MENU(0x08),

    /** The meter entered phone ("SAB") mode and forwards the switch to the phone (§5.4). */
    MODE_SAB(0x09),

    /** The meter left phone mode and uses the switch for its own functions again (§5.4). */
    MODE_HU(0x0A),
    ;

    /** Lookup by wire byte. */
    public companion object {
        /** The function for [code], or null if the meter sent something undocumented. */
        public fun fromCode(code: Int): FunctionId? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The handlebar switch presses that reach the phone on a CB350, as verified on the bike on
 * 2026-11-16 (SAB_PROTOCOL.md "Verified on the CB350"). Long press right and long press up never
 * reach the phone.
 */
public enum class HandlebarSwitch(
    /** The function id the meter sends for this press. */
    public val function: FunctionId,
) {
    /** Short press up. */
    UP(FunctionId.PREVIOUS),

    /** Short press down. */
    DOWN(FunctionId.NEXT),

    /** Short press left. */
    LEFT(FunctionId.BACK),

    /** Short press right. */
    RIGHT(FunctionId.SELECT),

    /** Long press down. */
    HOLD_DOWN(FunctionId.VOLUME_MUTE),

    /** Long press left. */
    HOLD_LEFT(FunctionId.MENU),
}

/** One notification from the Function Notification Source, decoded. */
public sealed interface FunctionEvent {
    /** The rider pressed the handlebar switch. */
    public data class Press(
        /** Which press. */
        val switch: HandlebarSwitch,
    ) : FunctionEvent

    /** The meter changed mode: [phoneMode] true after MODE_SAB (9), false after MODE_HU (10). */
    public data class MeterMode(
        /** Whether the meter now forwards the switch to the phone. */
        val phoneMode: Boolean,
    ) : FunctionEvent

    /** A documented function id that the CB350 does not send as a press (5 or 6, the HID volume keys). */
    public data class Other(
        /** The function id. */
        val function: FunctionId,
    ) : FunctionEvent

    /** A notification that is not a documented function id; kept as hex for the log. */
    public data class Unknown(
        /** The raw notification, as hex. */
        val hex: String,
    ) : FunctionEvent
}

/** Decodes notifications from [SabUuids.FUNCTION_SOURCE] (§5.3). */
public object FunctionSource {
    /** Decodes one notification. Like RoadSync, only the first byte is read. */
    public fun decode(value: ByteArray): FunctionEvent {
        val function =
            value.firstOrNull()?.let { FunctionId.fromCode(it.toInt() and 0xFF) }
                ?: return FunctionEvent.Unknown(value.toSabHex())
        val switch = HandlebarSwitch.entries.firstOrNull { it.function == function }
        return when {
            switch != null -> FunctionEvent.Press(switch)
            function == FunctionId.MODE_SAB -> FunctionEvent.MeterMode(phoneMode = true)
            function == FunctionId.MODE_HU -> FunctionEvent.MeterMode(phoneMode = false)
            else -> FunctionEvent.Other(function)
        }
    }
}
