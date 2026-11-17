package com.abid.handlebarlink.setup

import android.content.Intent
import android.content.IntentSender
import kotlinx.coroutines.flow.Flow

/** A Honda meter our own scan found (docs/PLAN.md "First-time setup"). */
data class FoundMeter(
    /** Its advertised name, e.g. "HONDA BTU 2040". */
    val name: String,
    /** Its Bluetooth address. */
    val address: String,
    /** Signal strength in dBm (`ScanResult.getRssi`). */
    val rssi: Int,
)

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

/** Our own scan for Honda meters. */
fun interface MeterScanner {
    /**
     * The meters found so far, strongest first; scanning while collected and stopped when the collector is
     * cancelled. Fails with [SetupException] (SCAN_FAILED, PERMISSION_MISSING, BLUETOOTH_OFF).
     */
    fun scan(): Flow<List<FoundMeter>>
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
