package com.abid.handlebarlink.bike.link.android.gatt

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import androidx.annotation.RequiresPermission
import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkException
import com.abid.handlebarlink.bike.link.android.AndroidBluetooth
import com.abid.handlebarlink.bike.link.android.GattStatus
import com.abid.handlebarlink.bike.link.spi.BondState
import com.abid.handlebarlink.bike.link.spi.ConnectionChange
import com.abid.handlebarlink.bike.link.spi.GattCharacteristicInfo
import com.abid.handlebarlink.bike.link.spi.GattConnection
import com.abid.handlebarlink.bike.link.spi.GattNotification
import com.abid.handlebarlink.bike.link.spi.GattServiceInfo
import com.abid.handlebarlink.bike.protocol.SabSafety
import com.abid.handlebarlink.bike.protocol.SabUuids
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * One generation's `BluetoothGatt` (docs/research/02-ble-connection.md §1.2): a new GATT object per
 * generation (C2), one operation at a time (C1, [OperationSlot]), callbacks in [GattEvents]. Every call
 * checks the Bluetooth permission first and turns a `SecurityException` into PermissionMissing.
 */
internal class AndroidGattConnection(
    private val bluetooth: AndroidBluetooth,
    val gen: Int,
    override val bondState: StateFlow<BondState>,
    private val log: Logger,
) : GattConnection {
    private val changeChannel = Channel<ConnectionChange>(Channel.UNLIMITED)
    override val changes: Flow<ConnectionChange> = changeChannel.receiveAsFlow()

    private val notificationFlow =
        MutableSharedFlow<GattNotification>(
            extraBufferCapacity = NOTIFICATION_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val notifications: SharedFlow<GattNotification> = notificationFlow.asSharedFlow()

    @Volatile private var gatt: BluetoothGatt? = null

    @Volatile private var closed = false
    private val opLock = Mutex()
    private val slot = OperationSlot()
    private val events = GattEvents(gen, slot, changeChannel, notificationFlow, { closed }, log)

    /** Starts connecting. Throws [LinkException] when the permission is missing or the stack refuses. */
    fun connect(autoConnect: Boolean) {
        requirePermission()
        val opened =
            try {
                connectGatt(bluetooth.device, autoConnect)
            } catch (e: SecurityException) {
                throw LinkException(LinkError.PermissionMissing, e)
            }
        gatt = opened ?: throw LinkException(LinkError.ConnectFailed(GattStatus.NOT_STARTED))
        log.debug("gatt.open", "gen" to gen, "auto" to autoConnect)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectGatt(
        device: BluetoothDevice,
        autoConnect: Boolean,
    ): BluetoothGatt? =
        if (Build.VERSION.SDK_INT >= ANDROID_17) {
            val settings =
                BluetoothGattConnectionSettings
                    .Builder()
                    .setAutoConnectEnabled(autoConnect)
                    .setTransport(BluetoothDevice.TRANSPORT_LE)
                    .build()
            device.connectGatt(settings, bluetooth.executor, events)
        } else {
            // Android 12–16: the settings form does not exist yet; deprecated only from API 37.
            @Suppress("DEPRECATION")
            device.connectGatt(
                bluetooth.context,
                autoConnect,
                events,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                bluetooth.handler,
            )
        }

    // Declared for lint; each call still checks the permission first (it can be revoked at any time).
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun requestMtu(mtu: Int): Int = perform(GattOp.MTU, null) { it.requestMtu(mtu) } as Int

    // Declared for lint; each call still checks the permission first (it can be revoked at any time).
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun discoverServices() {
        perform(GattOp.DISCOVER, null) { it.discoverServices() }
    }

    override fun hasCharacteristic(
        service: UUID,
        characteristic: UUID,
    ): Boolean = gatt?.getService(service)?.getCharacteristic(characteristic) != null

    override fun services(): List<GattServiceInfo> =
        gatt?.services.orEmpty().map { service ->
            GattServiceInfo(
                service.uuid,
                service.characteristics.map { c ->
                    GattCharacteristicInfo(c.uuid, c.properties, c.descriptors.map { it.uuid })
                },
            )
        }

    // Declared for lint; each call still checks the permission first (it can be revoked at any time).
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun readDescriptor(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
    ): ByteArray {
        check(SabSafety.isReadAllowed(service, characteristic)) { "reading $characteristic is forbidden" }
        val d =
            characteristic(service, characteristic).getDescriptor(descriptor)
                ?: throw LinkException(LinkError.ServicesMissing)
        return perform(GattOp.READ_DESCRIPTOR, descriptor) { it.readDescriptor(d) } as ByteArray
    }

    // Declared for lint; each call still checks the permission first (it can be revoked at any time).
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun read(
        service: UUID,
        characteristic: UUID,
    ): ByteArray {
        check(SabSafety.isReadAllowed(service, characteristic)) { "reading $characteristic is forbidden" }
        val c = characteristic(service, characteristic)
        return perform(GattOp.READ, characteristic) { it.readCharacteristic(c) } as ByteArray
    }

    // Declared for lint; each call still checks the permission first (it can be revoked at any time).
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun enableNotifications(
        service: UUID,
        characteristic: UUID,
    ) {
        check(SabSafety.isSubscribeAllowed(service)) { "subscribing to $service is forbidden" }
        val c = characteristic(service, characteristic)
        val descriptor =
            c.getDescriptor(SabUuids.CLIENT_CHARACTERISTIC_CONFIG) ?: throw LinkException(LinkError.ServicesMissing)
        perform(GattOp.ENABLE_NOTIFICATIONS, characteristic) { g ->
            g.setCharacteristicNotification(c, true) &&
                writeDescriptor(g, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
    }

    // Declared for lint; each call still checks the permission first (it can be revoked at any time).
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun write(
        service: UUID,
        characteristic: UUID,
        value: ByteArray,
    ) {
        check(SabSafety.isWriteAllowed(service, characteristic, value)) { "writing to $characteristic is forbidden" }
        val c = characteristic(service, characteristic)
        opLock.withLock {
            val armed = slot.arm(GattOp.WRITE, characteristic)
            try {
                // A busy stack is retried until the caller's timeout (C12).
                while (true) {
                    requirePermission()
                    val code = guarded { writeCharacteristic(openGatt(), c, value) }
                    if (code == BluetoothStatusCodes.SUCCESS) break
                    if (code !=
                        ERROR_GATT_WRITE_REQUEST_BUSY
                    ) {
                        throw LinkException(GattStatus.errorFor(GattOp.WRITE, code))
                    }
                    delay(BUSY_RETRY_MS)
                }
                armed.result.await()
            } finally {
                slot.disarm(armed)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        slot.fail(0)
        changeChannel.close()
        val g = gatt
        gatt = null
        if (g != null && bluetooth.hasConnectPermission()) release(g)
    }

    private fun release(g: BluetoothGatt) {
        try {
            g.disconnect()
            g.close()
            log.debug("gatt.close", "gen" to gen)
        } catch (e: SecurityException) {
            log.warn("gatt.close.denied", "gen" to gen, "error" to e.message)
        }
    }

    /** Runs one operation: arms the reply, starts it, waits for its callback. */
    private suspend fun perform(
        op: GattOp,
        target: UUID?,
        start: (BluetoothGatt) -> Boolean,
    ): Any? =
        opLock.withLock {
            val armed = slot.arm(op, target)
            try {
                requirePermission()
                if (!guarded { start(openGatt()) }) throw LinkException(GattStatus.errorFor(op, GattStatus.NOT_STARTED))
                armed.result.await()
            } finally {
                slot.disarm(armed)
            }
        }

    private fun openGatt(): BluetoothGatt = gatt ?: throw LinkException(LinkError.Disconnected(0))

    private fun characteristic(
        service: UUID,
        characteristic: UUID,
    ): BluetoothGattCharacteristic =
        openGatt().getService(service)?.getCharacteristic(characteristic)
            ?: throw LinkException(LinkError.ServicesMissing)

    private fun requirePermission() {
        if (!bluetooth.hasConnectPermission()) throw LinkException(LinkError.PermissionMissing)
    }

    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (e: SecurityException) {
            throw LinkException(LinkError.PermissionMissing, e)
        }

    private companion object {
        const val ANDROID_17 = 37
        const val NOTIFICATION_BUFFER = 64
        const val BUSY_RETRY_MS = 25L

        /** BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY (API 33). */
        const val ERROR_GATT_WRITE_REQUEST_BUSY = 201
    }
}

/** The write with the characteristic's default write type; returns a [BluetoothStatusCodes] value. */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun writeCharacteristic(
    g: BluetoothGatt,
    c: BluetoothGattCharacteristic,
    value: ByteArray,
): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        g.writeCharacteristic(c, value, c.writeType)
    } else {
        // Android 12: the value goes on the characteristic and the call returns a boolean.
        @Suppress("DEPRECATION")
        run {
            c.value = value
            if (g.writeCharacteristic(c)) BluetoothStatusCodes.SUCCESS else GattStatus.NOT_STARTED
        }
    }

/** Writes a descriptor value; true when the stack accepted the request. */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun writeDescriptor(
    g: BluetoothGatt,
    d: BluetoothGattDescriptor,
    value: ByteArray,
): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
    } else {
        // Android 12: the value goes on the descriptor.
        @Suppress("DEPRECATION")
        run {
            d.value = value
            g.writeDescriptor(d)
        }
    }
