package com.abid.handlebarlink.bike.link.android.gatt

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Build
import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.spi.ConnectionChange
import com.abid.handlebarlink.bike.link.spi.GattNotification
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * One generation's `BluetoothGattCallback`, running on the Bluetooth thread. It only completes the
 * pending operation or queues events; once [isClosed] is true, everything is ignored (C3).
 */
internal class GattEvents(
    private val gen: Int,
    private val slot: OperationSlot,
    private val changes: SendChannel<ConnectionChange>,
    private val notifications: MutableSharedFlow<GattNotification>,
    private val isClosed: () -> Boolean,
    private val log: Logger,
) : BluetoothGattCallback() {
    override fun onConnectionStateChange(
        g: BluetoothGatt,
        status: Int,
        newState: Int,
    ) {
        if (isClosed()) return
        log.debug("gatt.state", "gen" to gen, "status" to status, "state" to newState)
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                changes.trySend(ConnectionChange.Connected)
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                slot.fail(status)
                changes.trySend(ConnectionChange.Disconnected(status))
            }
        }
    }

    override fun onServiceChanged(g: BluetoothGatt) {
        if (!isClosed()) changes.trySend(ConnectionChange.ServiceChanged)
    }

    override fun onMtuChanged(
        g: BluetoothGatt,
        mtu: Int,
        status: Int,
    ) = slot.complete(GattOp.MTU, null, status, mtu)

    override fun onServicesDiscovered(
        g: BluetoothGatt,
        status: Int,
    ) = slot.complete(GattOp.DISCOVER, null, status, null)

    override fun onCharacteristicRead(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) = slot.complete(GattOp.READ, c.uuid, status, value.copyOf())

    // Android 12: the value is on the characteristic (API 33 added the overload above).
    @Deprecated("Android 12 callback")
    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {
            slot.complete(GattOp.READ, c.uuid, status, c.value?.copyOf())
        }
    }

    override fun onCharacteristicWrite(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        status: Int,
    ) = slot.complete(GattOp.WRITE, c.uuid, status, null)

    override fun onDescriptorWrite(
        g: BluetoothGatt,
        d: BluetoothGattDescriptor,
        status: Int,
    ) = slot.complete(GattOp.ENABLE_NOTIFICATIONS, d.characteristic.uuid, status, null)

    override fun onCharacteristicChanged(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (!isClosed()) notifications.tryEmit(GattNotification(c.uuid, value.copyOf()))
    }

    // Android 12: the value is on the characteristic (API 33 added the overload above).
    @Deprecated("Android 12 callback")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !isClosed()) {
            notifications.tryEmit(GattNotification(c.uuid, c.value?.copyOf() ?: ByteArray(0)))
        }
    }
}
