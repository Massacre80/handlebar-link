package com.abid.handlebarlink.bike.link.android.server

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.abid.handlebarlink.bike.link.android.AndroidBluetooth
import com.abid.handlebarlink.bike.link.spi.ClockServer
import com.abid.handlebarlink.bike.protocol.AdjustReason
import com.abid.handlebarlink.bike.protocol.CurrentTimeService
import com.abid.handlebarlink.bike.protocol.SabUuids
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's Current Time Service (SAB_PROTOCOL.md §2.2, §4.6): the meter reads it right after
 * connecting to set its clock, and is notified when the time or time zone changes.
 */
internal class AndroidClockServer(
    private val bluetooth: AndroidBluetooth,
    private val time: TimeProvider,
    private val log: Logger,
) : ClockServer {
    @Volatile private var server: BluetoothGattServer? = null
    private val subscribers: MutableSet<BluetoothDevice> = ConcurrentHashMap.newKeySet()

    private val currentTime =
        BluetoothGattCharacteristic(
            SabUuids.CURRENT_TIME,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    SabUuids.CLIENT_CHARACTERISTIC_CONFIG,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }

    private val timeChanged =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val zoneChanged = intent.action == Intent.ACTION_TIMEZONE_CHANGED
                notifySubscribers(if (zoneChanged) AdjustReason.TIME_ZONE_CHANGE else AdjustReason.MANUAL_TIME_UPDATE)
            }
        }

    override fun open(): Boolean {
        if (server == null && bluetooth.hasConnectPermission()) {
            try {
                server = openServer()?.also { registerTimeReceiver() }
                if (server != null) log.info("clock.server.open")
            } catch (e: SecurityException) {
                log.warn("clock.server.denied", "error" to e.message)
            }
        }
        return server != null
    }

    private fun registerTimeReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
        ContextCompat.registerReceiver(
            bluetooth.context,
            timeChanged,
            filter,
            null,
            bluetooth.handler,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun openServer(): BluetoothGattServer? {
        val opened = bluetooth.manager.openGattServer(bluetooth.context, callback) ?: return null
        val service = BluetoothGattService(SabUuids.CURRENT_TIME_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(currentTime)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                SabUuids.LOCAL_TIME_INFORMATION,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ),
        )
        opened.addService(service)
        return opened
    }

    override fun close() {
        val opened = server ?: return
        server = null
        subscribers.clear()
        runCatchingReceiver { bluetooth.context.unregisterReceiver(timeChanged) }
        if (!bluetooth.hasConnectPermission()) return
        try {
            opened.close()
        } catch (e: SecurityException) {
            log.warn("clock.server.close.denied", "error" to e.message)
        }
        log.info("clock.server.close")
    }

    private fun localNow() = time.clock.instant().atZone(time.clock.zone)

    private fun notifySubscribers(reason: AdjustReason) {
        val opened = server ?: return
        if (!bluetooth.hasConnectPermission()) return
        val value = CurrentTimeService.currentTime(localNow().toLocalDateTime(), reason).toByteArray()
        subscribers.forEach { device ->
            try {
                notify(opened, device, value)
            } catch (e: SecurityException) {
                log.warn("clock.notify.denied", "error" to e.message)
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun notify(
        opened: BluetoothGattServer,
        device: BluetoothDevice,
        value: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            opened.notifyCharacteristicChanged(device, currentTime, false, value)
        } else {
            // Android 12: the value goes on the characteristic.
            @Suppress("DEPRECATION")
            run {
                currentTime.value = value
                opened.notifyCharacteristicChanged(device, currentTime, false)
            }
        }
    }

    private val callback =
        object : BluetoothGattServerCallback() {
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val value =
                    when (characteristic.uuid) {
                        SabUuids.CURRENT_TIME -> {
                            CurrentTimeService.currentTime(localNow().toLocalDateTime(), AdjustReason.NONE)
                        }

                        SabUuids.LOCAL_TIME_INFORMATION -> {
                            CurrentTimeService.localTimeInformation(localNow().offset)
                        }

                        else -> {
                            null
                        }
                    }?.toByteArray()
                if (characteristic.uuid == SabUuids.CURRENT_TIME) log.info("clock.read")
                respond(device, requestId, value?.copyOfRange(minOf(offset, value.size), value.size))
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (descriptor.uuid == SabUuids.CLIENT_CHARACTERISTIC_CONFIG) {
                    val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    if (enable) subscribers += device else subscribers -= device
                }
                if (responseNeeded) respond(device, requestId, ByteArray(0))
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor,
            ) {
                val enabled = device in subscribers
                respond(
                    device,
                    requestId,
                    if (enabled) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    },
                )
            }
        }

    /** Answers a read or write request; null [value] means GATT_FAILURE (SAB §4.6). */
    private fun respond(
        device: BluetoothDevice,
        requestId: Int,
        value: ByteArray?,
    ) {
        val opened = server ?: return
        if (!bluetooth.hasConnectPermission()) return
        val status = if (value == null) BluetoothGatt.GATT_FAILURE else BluetoothGatt.GATT_SUCCESS
        try {
            opened.sendResponse(device, requestId, status, 0, value)
        } catch (e: SecurityException) {
            log.warn("clock.respond.denied", "error" to e.message)
        }
    }

    /** Unregistering a receiver that was never registered throws IllegalArgumentException. */
    private inline fun runCatchingReceiver(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            log.debug("clock.receiver.not_registered", "error" to e.message)
        }
    }
}
