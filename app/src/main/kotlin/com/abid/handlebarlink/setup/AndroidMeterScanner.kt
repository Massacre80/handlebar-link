package com.abid.handlebarlink.setup

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.abid.handlebarlink.bike.protocol.SabUuids
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.data.PairedMeters
import com.abid.handlebarlink.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Scans for nearby named devices while the setup screen is open (docs/PLAN.md "First-time setup"), low
 * latency because the app is in the foreground (ScanSettings). Honda meters ([isHondaMeter]: the Vehicle
 * Information service or the name) come first, every other named device after them as a fallback. No
 * hardware filter: on the bike, scans filtered on the service returned nothing before the meter was found
 * unfiltered (cause not established, SAB_PROTOCOL.md §1.1), and the fallback needs every device. Names
 * end in bytes that are not text and are cleaned ([cleanName]). What a meter advertises is logged once per
 * address. The collector sets the time limit and cancelling stops the scan.
 */
class AndroidMeterScanner
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val pairedMeters: PairedMeters,
        @param:IoDispatcher private val io: CoroutineDispatcher,
        logger: Logger,
    ) : MeterScanner {
        private val log = logger.child("setup.scan")

        // Android's bonded devices: a binder call, so off the main thread.
        override suspend fun paired(): List<FoundMeter> =
            withContext(io) {
                pairedMeters.list().orEmpty().map {
                    FoundMeter(
                        it.name,
                        it.address,
                        rssi = 0,
                        honda = true,
                        paired = true,
                    )
                }
            }

        override fun scan(): Flow<List<FoundMeter>> =
            callbackFlow {
                val scanner =
                    context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
                        ?: throw SetupException(SetupProblem.BLUETOOTH_OFF)
                val found = LinkedHashMap<String, FoundMeter>()
                val seen = ScanTally()
                val callback =
                    object : ScanCallback() {
                        override fun onScanResult(
                            callbackType: Int,
                            result: ScanResult,
                        ) {
                            val name = nameOf(result)
                            seen.add(result.device.address, name)
                            if (name != null) {
                                val address = result.device.address
                                val services = result.scanRecord?.serviceUuids.orEmpty()
                                val honda = isHondaMeter(name, VEHICLE_SERVICE in services)
                                if (honda && address !in found) {
                                    log.info(
                                        "scan.meter",
                                        "name" to name,
                                        // The advertised name's characters, to record what follows the text.
                                        "advertised" to result.scanRecord?.deviceName?.map { it.code },
                                        "services" to services,
                                        "connectable" to result.isConnectable,
                                    )
                                }
                                found[address] = FoundMeter(name, address, result.rssi, honda)
                                // Honda meters first, then the order they were first seen: a list sorted by
                                // signal kept jumping under the rider's thumb (owner, 2026-11-17).
                                trySend(found.values.sortedByDescending { it.honda })
                            }
                        }

                        override fun onScanFailed(errorCode: Int) {
                            log.warn("scan.failed", "code" to errorCode)
                            close(SetupException(SetupProblem.SCAN_FAILED))
                        }
                    }
                start(scanner, callback)
                log.info("scan.start")
                awaitClose {
                    stop(scanner, callback)
                    log.info("scan.summary", "devices" to seen.devices, "named" to seen.named, "honda" to seen.honda)
                }
            }

        /**
         * A device's cleaned name ([cleanName]): from the advertisement, else the name Android already holds
         * for the address (learnt from an earlier scan response or discovery).
         */
        private fun nameOf(result: ScanResult): String? =
            cleanName(result.scanRecord?.deviceName) ?: cleanName(knownName(result))

        private fun knownName(result: ScanResult): String? =
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    result.device.name
                } catch (e: SecurityException) {
                    log.warn("scan.name.denied", "error" to e.message)
                    null
                }
            } else {
                null
            }

        private fun start(
            scanner: android.bluetooth.le.BluetoothLeScanner,
            callback: ScanCallback,
        ) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SetupException(SetupProblem.PERMISSION_MISSING)
            }
            try {
                scanner.startScan(emptyList(), SETTINGS, callback)
            } catch (e: SecurityException) {
                throw SetupException(SetupProblem.PERMISSION_MISSING, e)
            }
        }

        private fun stop(
            scanner: android.bluetooth.le.BluetoothLeScanner,
            callback: ScanCallback,
        ) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            try {
                scanner.stopScan(callback)
                log.info("scan.stop")
            } catch (e: SecurityException) {
                log.warn("scan.stop.denied", "error" to e.message)
            }
        }

        private companion object {
            val VEHICLE_SERVICE = ParcelUuid(SabUuids.VEHICLE_INFO_SERVICE)
            val SETTINGS: ScanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        }
    }

/** What one scan saw, for the log: devices, how many had a name, and the Honda meters (diagnosing setup). */
private class ScanTally {
    private val names = HashMap<String, String?>()

    val devices: Int get() = names.size
    val named: Int get() = names.values.count { it != null }
    val honda: List<String> get() = names.values.filterNotNull().filter { isHondaMeter(it) }

    fun add(
        address: String,
        name: String?,
    ) {
        if (names[address] == null) names[address] = name
    }
}
