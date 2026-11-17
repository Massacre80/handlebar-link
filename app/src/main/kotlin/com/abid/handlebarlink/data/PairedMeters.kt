package com.abid.handlebarlink.data

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.setup.cleanName
import com.abid.handlebarlink.setup.isHondaMeter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The Honda meters Android has already paired (bonded): Settings' bike list, and setup's "Paired with this
 * phone".
 */
class PairedMeters
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) {
        private val log = logger.child("meters")

        /**
         * The paired meters by their cleaned name ([isHondaMeter], as RoadSync matches them), or null when
         * the Nearby devices permission is missing. A binder call: not on the main thread.
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
                        // Cleaned first: the meter's name ends in two NULs (SAB_PROTOCOL.md §1.1).
                        cleanName(device.name)?.takeIf { isHondaMeter(it) }?.let { SelectedBike(device.address, it) }
                    }.sortedBy { it.name }
            } catch (e: SecurityException) {
                // Revoked between the check and the call: the same as not allowed.
                log.warn("meters.denied", "error" to e.message)
                null
            }
        }
    }
