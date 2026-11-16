package com.abid.handlebarlink.bike.protocol

import java.time.LocalDateTime
import java.time.ZoneOffset

/** Why the phone's time changed; the last byte of Current Time (Bluetooth CTS, §4.6). */
public enum class AdjustReason(
    /** The byte on the wire. */
    public val code: Int,
) {
    /** A plain read. */
    NONE(0x00),

    /** The user set the time (`ACTION_TIME_CHANGED`). */
    MANUAL_TIME_UPDATE(0x01),

    /** The time zone changed (`ACTION_TIMEZONE_CHANGED`). */
    TIME_ZONE_CHANGE(0x04),
}

/**
 * The values the phone serves in its Current Time Service, byte for byte as RoadSync's
 * `CTSData` builds them (§4.6). The meter reads them to set its clock.
 */
public object CurrentTimeService {
    /**
     * Current Time (0x2A2B), 10 bytes little-endian: year (uint16), month, day, hour, minute,
     * second, day of week (1 = Monday … 7 = Sunday), fraction, [reason].
     *
     * The fraction is RoadSync's `millis * 0.255f` (a float, truncated), not the exact 1/256 s of
     * the Bluetooth spec; kept identical to what the meter has been reading.
     */
    public fun currentTime(
        time: LocalDateTime,
        reason: AdjustReason,
    ): SabFrame {
        val millis = time.nano / NANOS_PER_MILLI
        return frameOf(
            time.year and 0xFF,
            time.year shr 8 and 0xFF,
            time.monthValue,
            time.dayOfMonth,
            time.hour,
            time.minute,
            time.second,
            time.dayOfWeek.value,
            (millis * FRACTION_PER_MILLI).toInt(),
            reason.code,
        )
    }

    /**
     * Local Time Information (0x2A0F), 2 bytes: the offset from UTC in 15-minute steps (signed),
     * then DST, which RoadSync always sends as 0.
     */
    public fun localTimeInformation(offset: ZoneOffset): SabFrame =
        frameOf(offset.totalSeconds / SECONDS_PER_QUARTER_HOUR, 0)
}

private const val NANOS_PER_MILLI = 1_000_000
private const val FRACTION_PER_MILLI = 0.255f
private const val SECONDS_PER_QUARTER_HOUR = 15 * 60
