package com.abid.handlebarlink.data

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.abid.handlebarlink.bike.protocol.SabUuids
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The Honda meters Android has already paired (bonded), to choose the bike from. Pairing itself happens in
 * Android's Bluetooth settings for now; the companion-device dialog replaces it in M4.
 */
class PairedMeters
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) {
        private val log = logger.child("meters")

        /**
         * The paired meters by name (`SabUuids.DEVICE_NAME_PATTERN`, as RoadSync matches them), or null
         * when the Nearby devices permission is missing. A binder call: not on the main thread.
         */
        fun list(): List<SelectedBike>? {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            return if (adapter == null) emptyList() else bonded(adapter)
        }

        private fun bonded(adapter: BluetoothAdapter): List<SelectedBike>? {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            return try {
                adapter.bondedDevices
                    .mapNotNull { device ->
                        device.name?.takeIf(METER::matches)?.let { SelectedBike(device.address, it) }
                    }.sortedBy { it.name }
            } catch (e: SecurityException) {
                // Revoked between the check and the call: the same as not allowed.
                log.warn("meters.denied", "error" to e.message)
                null
            }
        }

        private companion object {
            val METER = Regex(SabUuids.DEVICE_NAME_PATTERN)
        }
    }
