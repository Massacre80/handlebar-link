package com.abid.handlebarlink.setup

import kotlin.math.exp

/** A found device's signal as the rider sees it: bars and a word ("Choose your bike"), weakest first. */
enum class SignalLevel {
    WEAK,
    GOOD,
    STRONG,
    ;

    /** Lit bars of four: one for Weak, two for Good, three for Strong. */
    val bars: Int get() = ordinal + 1
}

/** The level for [rssi] dBm on its own, without history: strong above -60, good above -75, weak below. */
fun signalLevel(rssi: Int): SignalLevel = signalLevel(rssi.toDouble())

private fun signalLevel(dbm: Double): SignalLevel =
    when {
        dbm > STRONG_ABOVE_DBM -> SignalLevel.STRONG
        dbm > GOOD_ABOVE_DBM -> SignalLevel.GOOD
        else -> SignalLevel.WEAK
    }

/**
 * Steady signal levels for the devices one scan hears (docs/PLAN.md "Stable live indicators"). One advertisement's
 * RSSI jumps by several dB from packet to packet (multipath, the body, the radio's own steps), so levels taken from
 * single packets flipped Strong, Good and Weak within seconds on the bike (owner, 2026-11-20). As Android and beacon
 * libraries do, each device's RSSI is smoothed, levels change with hysteresis, and a shown level is held:
 * - an exponential moving average with a [timeConstantMillis] time constant (Wi-Fi's connected score filters RSSI
 *   with a Kalman filter, AltBeacon averages 20 s of samples; an average weighted by time handles the irregular gaps
 *   between advertisements, and a device heard again after a long gap starts from its new value);
 * - a level changes only once the average is [hysteresisDb] past the boundary (telephony asks the modem for 2 dB
 *   between reports, `SignalStrengthController.REPORTING_HYSTERESIS_DB`; a phone's BLE RSSI is noisier);
 * - a shown level stays for at least [holdMillis] (telephony's `REPORTING_HYSTERESIS_MILLIS` is 3 s).
 * A device that is not heard for a while keeps its level (the list never drops a device mid-scan).
 * Not thread-safe: the scan's callbacks arrive on one thread.
 */
class MeterSignals(
    private val timeConstantMillis: Long = SIGNAL_TIME_CONSTANT_MS,
    private val hysteresisDb: Int = SIGNAL_HYSTERESIS_DB,
    private val holdMillis: Long = SIGNAL_HOLD_MS,
) {
    private val devices = HashMap<String, Device>()

    /** Takes one advertisement's [rssi] from [address] heard at [nowMillis] (elapsed) and returns its level now. */
    fun level(
        address: String,
        rssi: Int,
        nowMillis: Long,
    ): SignalLevel {
        val device = devices[address]
        if (device == null) {
            val level = signalLevel(rssi)
            devices[address] = Device(rssi.toDouble(), nowMillis, level, nowMillis)
            return level
        }
        val gap = (nowMillis - device.heardAt).coerceAtLeast(0)
        val weight = 1 - exp(-gap.toDouble() / timeConstantMillis)
        device.average += weight * (rssi - device.average)
        device.heardAt = nowMillis
        val next = withHysteresis(device.level, device.average)
        if (next != device.level && nowMillis - device.shownAt >= holdMillis) {
            device.level = next
            device.shownAt = nowMillis
        }
        return device.level
    }

    /** The level for [average] seen from [current]: it moves only once the average is past a boundary by the margin. */
    private fun withHysteresis(
        current: SignalLevel,
        average: Double,
    ): SignalLevel {
        val up = signalLevel(average - hysteresisDb)
        val down = signalLevel(average + hysteresisDb)
        return when {
            up > current -> up
            down < current -> down
            else -> current
        }
    }

    private class Device(
        var average: Double,
        var heardAt: Long,
        var level: SignalLevel,
        var shownAt: Long,
    )
}

private const val STRONG_ABOVE_DBM = -60
private const val GOOD_ABOVE_DBM = -75

/** The average's time constant: a sustained change is mostly through in about two of these. */
const val SIGNAL_TIME_CONSTANT_MS: Long = 1_500L

/** How far past a boundary the average must go before the level changes. */
const val SIGNAL_HYSTERESIS_DB: Int = 4

/** How long a shown level stays at least. */
const val SIGNAL_HOLD_MS: Long = 2_500L
