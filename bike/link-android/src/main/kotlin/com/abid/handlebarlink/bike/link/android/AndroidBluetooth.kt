package com.abid.handlebarlink.bike.link.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.Executor

/**
 * What every adapter shares: the system Bluetooth objects for the bonded meter, and one dedicated
 * thread for Bluetooth callbacks and broadcasts (never the main thread; docs/research/02 §2.1).
 */
internal class AndroidBluetooth(
    val context: Context,
    /** The bonded meter's address. */
    val address: String,
) {
    val manager: BluetoothManager = context.getSystemService(BluetoothManager::class.java)

    val adapter: BluetoothAdapter get() = manager.adapter

    val device: BluetoothDevice get() = adapter.getRemoteDevice(address)

    private val thread = HandlerThread("bike-bluetooth").apply { start() }

    /** Callbacks and broadcast receivers run here. */
    val handler: Handler = Handler(thread.looper)

    /** The same thread, as an [Executor] (API 37 `connectGatt`). */
    val executor: Executor = Executor { handler.post(it) }

    /** Checked before every Bluetooth call: the permission can be revoked at any time (permissions skill). */
    fun hasConnectPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
