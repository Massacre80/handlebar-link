package com.abid.handlebarlink.devices

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.setup.cleanName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * [BluetoothPairing] as "Find Bluetooth devices" describes it: startDiscovery() and ACTION_FOUND (an inquiry of
 * about 12 s, names arriving with the page scan or later as ACTION_NAME_CHANGED), cancelDiscovery() before
 * pairing (discovery takes the adapter's resources), and createBond() followed through
 * ACTION_BOND_STATE_CHANGED. Searching needs BLUETOOTH_SCAN, pairing BLUETOOTH_CONNECT (both "Nearby
 * devices"), checked before each call; the broadcasts come from the Bluetooth app, so the receivers are
 * exported ("Broadcasts overview").
 */
class AndroidBluetoothPairing
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) : BluetoothPairing {
        private val log = logger.child("pairing")
        private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

        override fun discover(): Flow<Discovery> =
            callbackFlow {
                val adapter = adapter
                if (adapter == null || !allowed(Manifest.permission.BLUETOOTH_SCAN) ||
                    !allowed(Manifest.permission.BLUETOOTH_CONNECT)
                ) {
                    trySend(Discovery.NotAllowed)
                    awaitClose {}
                    return@callbackFlow
                }
                if (!adapter.isEnabled) {
                    trySend(Discovery.Off)
                    awaitClose {}
                    return@callbackFlow
                }
                // Already paired devices are on Add device's own list.
                val paired =
                    try {
                        adapter.bondedDevices
                            .orEmpty()
                            .map { it.address }
                            .toSet()
                    } catch (e: SecurityException) {
                        log.warn("pairing.paired.denied", "error" to e.message)
                        emptySet()
                    }
                val found = linkedMapOf<String, NearbyDevice>()
                var searching = true

                fun publish() = trySend(Discovery.Found(found.values.toList(), searching))
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            context: Context,
                            intent: Intent,
                        ) {
                            if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                                searching = false
                            } else {
                                found(intent, found, paired)?.let { found[it.address] = it }
                            }
                            publish()
                        }
                    }
                ContextCompat.registerReceiver(context, receiver, SEARCH, ContextCompat.RECEIVER_EXPORTED)
                val started =
                    try {
                        allowed(Manifest.permission.BLUETOOTH_SCAN) && adapter.startDiscovery()
                    } catch (e: SecurityException) {
                        log.warn("pairing.discover.denied", "error" to e.message)
                        false
                    }
                log.info("pairing.discover", "started" to started)
                searching = started
                publish()
                awaitClose {
                    context.unregisterReceiver(receiver)
                    try {
                        if (allowed(Manifest.permission.BLUETOOTH_SCAN)) adapter.cancelDiscovery()
                    } catch (e: SecurityException) {
                        log.warn("pairing.cancel.denied", "error" to e.message)
                    }
                }
            }

        /** A named, not yet paired device from ACTION_FOUND or ACTION_NAME_CHANGED (no permission needed). */
        private fun found(
            intent: Intent,
            found: Map<String, NearbyDevice>,
            paired: Set<String>,
        ): NearbyDevice? {
            val device =
                IntentCompat.getParcelableExtra(
                    intent,
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                )
            val address = device?.address?.takeIf { it !in paired }
            val known = address?.let { found[it] }
            val name = address?.let { cleanName(intent.getStringExtra(BluetoothDevice.EXTRA_NAME)) ?: known?.name }
            // ACTION_NAME_CHANGED carries no class: keep what ACTION_FOUND said.
            val type = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
            val audio =
                type?.let { it.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO } ?: known?.audio ?: false
            val suggested = type?.let { suggestedKind(it.deviceClass, audio) } ?: known?.suggested ?: DeviceKind.OTHER
            return if (address != null && name != null) NearbyDevice(address, name, audio, suggested) else null
        }

        override fun pair(address: String): Flow<PairState> =
            callbackFlow {
                val adapter = adapter
                if (adapter == null || !allowed(Manifest.permission.BLUETOOTH_CONNECT)) {
                    trySend(PairState.FAILED)
                    close()
                    awaitClose {}
                    return@callbackFlow
                }
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            context: Context,
                            intent: Intent,
                        ) {
                            val device =
                                IntentCompat.getParcelableExtra(
                                    intent,
                                    BluetoothDevice.EXTRA_DEVICE,
                                    BluetoothDevice::class.java,
                                )
                            if (device?.address != address) return
                            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                                BluetoothDevice.BOND_BONDED -> {
                                    trySend(PairState.PAIRED)
                                    close()
                                }

                                BluetoothDevice.BOND_BONDING -> {
                                    trySend(PairState.PAIRING)
                                }

                                else -> {
                                    trySend(PairState.FAILED)
                                    close()
                                }
                            }
                        }
                    }
                ContextCompat.registerReceiver(context, receiver, BONDS, ContextCompat.RECEIVER_EXPORTED)
                val result =
                    try {
                        // Discovery first stops: it would slow pairing down ("Find Bluetooth devices").
                        if (allowed(Manifest.permission.BLUETOOTH_SCAN)) adapter.cancelDiscovery()
                        val device = adapter.getRemoteDevice(address)
                        when {
                            device.bondState == BluetoothDevice.BOND_BONDED -> PairState.PAIRED
                            device.createBond() -> PairState.PAIRING
                            else -> PairState.FAILED
                        }
                    } catch (e: SecurityException) {
                        log.warn("pairing.bond.denied", "error" to e.message)
                        PairState.FAILED
                    } catch (e: IllegalArgumentException) {
                        log.warn("pairing.bond.address", "error" to e.message)
                        PairState.FAILED
                    }
                log.info("pairing.bond", "result" to result.name)
                trySend(result)
                if (result != PairState.PAIRING) close()
                awaitClose { context.unregisterReceiver(receiver) }
            }

        private fun allowed(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        private companion object {
            val SEARCH =
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
            val BONDS = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
    }
