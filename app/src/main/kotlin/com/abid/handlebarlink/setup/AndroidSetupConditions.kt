package com.abid.handlebarlink.setup

import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Bluetooth and Location Services, read at the moment they are needed (never cached). */
class AndroidSetupConditions
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : SetupConditions {
        override fun bluetoothOn(): Boolean =
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

        override fun locationOn(): Boolean =
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    }
