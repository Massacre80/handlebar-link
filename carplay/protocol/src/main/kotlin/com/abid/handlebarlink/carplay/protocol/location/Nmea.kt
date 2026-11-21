package com.abid.handlebarlink.carplay.protocol.location

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One GPS fix from the accessory's own receiver (the Poco), in the fields the NMEA pair carries. Ported from DiPlay
 * (CarLink vendor/diplay `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2LocationClient.kt:14-32`,
 * `CarPlayLocationFix`), with [timestampMillis] required instead of defaulting to the clock: the engine passes the
 * sample's own time, so nothing here reads a clock (AGENTS, engine architecture). Latitude and longitude are checked
 * on construction, as DiPlay does.
 */
public data class CarPlayLocationFix(
    /** Degrees, -90..90. */
    val latitudeDegrees: Double,
    /** Degrees, -180..180. */
    val longitudeDegrees: Double,
    /** When the fix was taken, epoch milliseconds (UTC). */
    val timestampMillis: Long,
    /** Metres above sea level, or null when the receiver gave none. */
    val altitudeMeters: Double? = null,
    /** Course over ground in degrees, or null without a direction (parked): the RMC course stays empty, not 0. */
    val bearingDegrees: Double? = null,
    /** Ground speed in metres per second, or null. */
    val speedMetersPerSecond: Double? = null,
    /** Horizontal accuracy in metres, mapped to HDOP, or null. */
    val accuracyMeters: Double? = null,
) {
    init {
        require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0) {
            "latitudeDegrees must be between -90 and 90"
        }
        require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0) {
            "longitudeDegrees must be between -180 and 180"
        }
    }
}

/**
 * The `$GPGGA` + `$GPRMC` text the iPhone reads from iAP2 LocationInformation (0xFFFB). Byte for byte as DiPlay
 * (CarLink vendor/diplay `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2LocationClient.kt:51-139`,
 * `NmeaLocationEncoder`): both sentences UTC, fix quality 1 with 8 satellites, HDOP from the accuracy (metres / 5,
 * clamped 0.5..50, else 1.0), altitude in metres (else 0.0), RMC speed in knots (else 0.00) and an empty course
 * without a bearing (0.00 would say due north). Each sentence ends `*<XOR checksum, two hex>\r\n`.
 */
public object NmeaLocationEncoder {
    /** The GGA and RMC pair for [fix], ready to carry in a 0xFFFB LocationInformation. */
    public fun encode(fix: CarPlayLocationFix): String {
        val at = Instant.ofEpochMilli(fix.timestampMillis).atZone(ZoneOffset.UTC)
        val time = format("%02d%02d%02d.00", at.hour, at.minute, at.second)
        val date = format("%02d%02d%02d", at.dayOfMonth, at.monthValue, at.year % 100)

        val latitude = degreesToNmea(fix.latitudeDegrees, latitude = true)
        val longitude = degreesToNmea(fix.longitudeDegrees, latitude = false)
        val hdop =
            fix.accuracyMeters
                ?.takeIf { it.isFinite() && it > 0 }
                ?.let { min(MAX_HDOP, max(MIN_HDOP, it / HDOP_PER_METER)) }
                ?: DEFAULT_HDOP
        val altitude = fix.altitudeMeters?.takeIf { it.isFinite() }?.let { format("%.1f", it) } ?: "0.0"

        val gga =
            "GPGGA,$time,${latitude.value},${latitude.hemisphere}," +
                "${longitude.value},${longitude.hemisphere},1,08,${format("%.1f", hdop)}," +
                "$altitude,M,0.0,M,,"
        val speedKnots =
            fix.speedMetersPerSecond
                ?.takeIf { it.isFinite() && it >= 0 }
                ?.let { format("%.2f", it * KNOTS_PER_METER_PER_SECOND) }
                ?: "0.00"
        val course =
            fix.bearingDegrees
                ?.takeIf { it.isFinite() }
                ?.let { format("%.2f", it) }
                .orEmpty()
        val rmc =
            "GPRMC,$time,A,${latitude.value},${latitude.hemisphere}," +
                "${longitude.value},${longitude.hemisphere},$speedKnots,$course,$date,,"

        return "$$gga*${checksum(gga)}\r\n$$rmc*${checksum(rmc)}\r\n"
    }

    private fun degreesToNmea(
        value: Double,
        latitude: Boolean,
    ): NmeaCoordinate {
        val absolute = abs(value)
        var degrees = absolute.toInt()
        var minutes = (absolute - degrees) * MINUTES_PER_DEGREE
        if (minutes >= MINUTES_PER_DEGREE - MINUTE_ROUNDING_TOLERANCE) {
            degrees += 1
            minutes = 0.0
        }
        val degreeText = if (latitude) format("%02d", degrees) else format("%03d", degrees)
        val hemisphere =
            when {
                latitude && value >= 0 -> "N"
                latitude -> "S"
                value >= 0 -> "E"
                else -> "W"
            }
        return NmeaCoordinate("$degreeText${format("%07.4f", minutes)}", hemisphere)
    }

    private fun checksum(body: String): String {
        var value = 0
        for (character in body) value = value xor character.code
        return format("%02X", value)
    }

    private fun format(
        format: String,
        vararg arguments: Any,
    ): String = String.format(Locale.US, format, *arguments)

    private data class NmeaCoordinate(
        val value: String,
        val hemisphere: String,
    )

    private const val KNOTS_PER_METER_PER_SECOND = 1.94384449
    private const val MINUTES_PER_DEGREE = 60.0
    private const val MINUTE_ROUNDING_TOLERANCE = 0.00005
    private const val HDOP_PER_METER = 5.0
    private const val MIN_HDOP = 0.5
    private const val MAX_HDOP = 50.0
    private const val DEFAULT_HDOP = 1.0
}
