package com.abid.handlebarlink.setup

import android.content.Intent
import android.content.IntentSender
import kotlinx.coroutines.flow.Flow

/** A Honda meter our own scan found (docs/PLAN.md "First-time setup"). */
data class FoundMeter(
    /** Its name, cleaned ([cleanName]), e.g. "HONDA BTU 2040". */
    val name: String,
    /** Its Bluetooth address. */
    val address: String,
    /** Signal strength in dBm (`ScanResult.getRssi`). */
    val rssi: Int,
    /** A Honda meter by its name ([isHondaMeter]); other devices are listed after, as a fallback. */
    val honda: Boolean = true,
    /** Already paired with this phone: choosing it skips linking and pairing. */
    val paired: Boolean = false,
)

/**
 * A device name as advertised, up to the first character outside printable ASCII. The CB350's meter
 * advertises "HONDA BTU 2040" followed by two NUL characters (SAB_PROTOCOL.md §1.1, capture 2026-11-17), so
 * names are cut there before matching or showing (meter names are ASCII; another device's
 * non-ASCII name is cut too, which only shortens how a fallback entry reads). Null when nothing is left.
 */
fun cleanName(raw: String?): String? =
    raw
        ?.takeWhile { it in ' '..'~' }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * A Honda meter: it advertises the Vehicle Information service, or its cleaned name starts with "HONDA
 * BTU" (RoadSync's name pattern); SAB_PROTOCOL.md §1.1. Either is enough.
 */
fun isHondaMeter(
    name: String,
    vehicleService: Boolean = false,
): Boolean = vehicleService || name.startsWith(HONDA_METER_PREFIX)

private const val HONDA_METER_PREFIX = "HONDA BTU"

/** Why setup cannot go on; each has its own message and way out. */
enum class SetupProblem {
    /** Bluetooth is switched off. */
    BLUETOOTH_OFF,

    /** Location Services are off: companion-device pairing requires them. */
    LOCATION_OFF,

    /** A permission is missing (it was refused). */
    PERMISSION_MISSING,

    /** The scan could not run. */
    SCAN_FAILED,

    /** Android did not link the meter (refused, timed out, or failed). */
    NOT_LINKED,

    /** Pairing failed or took too long. */
    PAIRING_FAILED,
}

/** Thrown inside setup when [problem] stops it. */
class SetupException(
    /** What went wrong. */
    val problem: SetupProblem,
    cause: Throwable? = null,
) : Exception(problem.name, cause)

/** What must be on before searching. */
interface SetupConditions {
    /** Bluetooth is on. */
    fun bluetoothOn(): Boolean

    /** Location Services are on (companion-device pairing requires them). */
    fun locationOn(): Boolean
}

/** Finding meters: the ones this phone already paired, and our own scan for the others. */
interface MeterScanner {
    /**
     * The devices found so far, Honda meters first; scanning while collected and stopped when the collector
     * is cancelled. Fails with [SetupException] (SCAN_FAILED, PERMISSION_MISSING, BLUETOOTH_OFF).
     */
    fun scan(): Flow<List<FoundMeter>>

    /**
     * Honda meters this phone has already paired ([FoundMeter.paired]); empty when there are none or Nearby
     * devices is not allowed. A paired meter that is connected stops advertising, so the scan cannot find it
     * (seen on the bike, 2026-11-17, after a reinstall).
     */
    suspend fun paired(): List<FoundMeter>
}

/** Linking a meter to the app through Android's companion-device consent. */
interface MeterAssociation {
    /** Asks Android to link [meter]; returns the consent screen to launch. Fails with [SetupException]. */
    suspend fun request(meter: FoundMeter): IntentSender

    /** The linked meter's address from the consent screen's result, or null when not linked. */
    fun addressFrom(
        resultCode: Int,
        data: Intent?,
    ): String?
}

/** Pairing (bonding) a linked meter. */
fun interface MeterBonding {
    /** Pairs the meter at [address]; true once bonded (also when it already was). */
    suspend fun bond(address: String): Boolean
}
