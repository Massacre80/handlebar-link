package com.abid.handlebarlink.bike.link.android.gatt

import com.abid.handlebarlink.bike.link.android.AndroidBluetooth
import com.abid.handlebarlink.bike.link.spi.BondState
import com.abid.handlebarlink.bike.link.spi.GattConnection
import com.abid.handlebarlink.bike.link.spi.GattRadio
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.flow.StateFlow

/** Opens a new [AndroidGattConnection] (a new `BluetoothGatt`) for every generation (C2). */
internal class AndroidGattRadio(
    private val bluetooth: AndroidBluetooth,
    private val bondState: StateFlow<BondState>,
    private val log: Logger,
) : GattRadio {
    override fun open(
        gen: Int,
        autoConnect: Boolean,
    ): GattConnection = AndroidGattConnection(bluetooth, gen, bondState, log).also { it.connect(autoConnect) }
}
