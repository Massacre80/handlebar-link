package com.abid.handlebarlink.setup

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.abid.handlebarlink.bike.protocol.SabUuids
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * Scans for Honda meters while the setup screen is open (docs/PLAN.md "First-time setup"): a hardware filter
 * on the Vehicle Information service (as RoadSync, SAB_PROTOCOL.md §1), low latency because the app is in
 * the foreground (ScanSettings), names checked against `SabUuids.DEVICE_NAME_PATTERN`. The collector sets
 * the time limit and cancelling stops the scan.
 */
class AndroidMeterScanner
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) : MeterScanner {
        private val log = logger.child("setup.scan")

        override fun scan(): Flow<List<FoundMeter>> =
            callbackFlow {
                val scanner =
                    context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
                        ?: throw SetupException(SetupProblem.BLUETOOTH_OFF)
                val found = LinkedHashMap<String, FoundMeter>()
                val callback =
                    object : ScanCallback() {
                        override fun onScanResult(
                            callbackType: Int,
                            result: ScanResult,
                        ) {
                            val name = result.scanRecord?.deviceName ?: return
                            if (!METER.matches(name)) return
                            found[result.device.address] = FoundMeter(name, result.device.address, result.rssi)
                            trySend(found.values.sortedByDescending { it.rssi })
                        }

                        override fun onScanFailed(errorCode: Int) {
                            log.warn("scan.failed", "code" to errorCode)
                            close(SetupException(SetupProblem.SCAN_FAILED))
                        }
                    }
                start(scanner, callback)
                log.info("scan.start")
                awaitClose { stop(scanner, callback) }
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
                scanner.startScan(listOf(FILTER), SETTINGS, callback)
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
            val METER = Regex(SabUuids.DEVICE_NAME_PATTERN)
            val FILTER: ScanFilter =
                ScanFilter.Builder().setServiceUuid(ParcelUuid(SabUuids.VEHICLE_INFO_SERVICE)).build()
            val SETTINGS: ScanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        }
    }
