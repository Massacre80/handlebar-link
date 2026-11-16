package com.abid.handlebarlink.bike.link.android.system

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.abid.handlebarlink.bike.link.android.AndroidBluetooth
import com.abid.handlebarlink.bike.link.spi.BondState
import com.abid.handlebarlink.bike.link.spi.SystemChange
import com.abid.handlebarlink.bike.link.spi.SystemFacts
import com.abid.handlebarlink.bike.link.spi.SystemSignals
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.merge

/**
 * Bluetooth state, the meter's bond, missing keys and the permission, from system broadcasts received on
 * the Bluetooth thread (docs/research/02-ble-connection.md §2.3, §4.3).
 */
internal class AndroidSystemSignals(
    private val bluetooth: AndroidBluetooth,
    private val log: Logger,
) : SystemSignals {
    private val bondFlow = MutableStateFlow(readBond())
    private val permissionChanges = MutableSharedFlow<SystemChange>(extraBufferCapacity = 4)

    @Volatile private var permissionGranted = bluetooth.hasConnectPermission()

    /** The meter's bond state, for the GATT layer (it waits while bonding, C6). */
    val bondState: StateFlow<BondState> = bondFlow.asStateFlow()

    override fun current(): SystemFacts {
        permissionGranted = bluetooth.hasConnectPermission()
        bondFlow.value = readBond()
        return SystemFacts(
            bluetoothOn = bluetooth.adapter.isEnabled,
            bonded = bondFlow.value == BondState.BONDED,
            permissionGranted = permissionGranted,
        )
    }

    override val changes: Flow<SystemChange> = merge(broadcasts(), permissionChanges)

    /**
     * Re-reads the Bluetooth permission. Android sends no broadcast when it changes, so the app calls
     * this when it comes to the foreground (permissions skill: never rely on a cached grant).
     */
    fun recheckPermission() {
        val granted = bluetooth.hasConnectPermission()
        if (granted == permissionGranted) return
        permissionGranted = granted
        permissionChanges.tryEmit(SystemChange.Permission(granted))
    }

    private fun broadcasts(): Flow<SystemChange> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        toChange(intent)?.let { trySend(it) }
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    // ACTION_KEY_MISSING exists from Android 16 (API 36); older releases never send it.
                    if (Build.VERSION.SDK_INT >= ANDROID_16) addAction(BluetoothDevice.ACTION_KEY_MISSING)
                }
            ContextCompat.registerReceiver(
                bluetooth.context,
                receiver,
                filter,
                null,
                bluetooth.handler,
                ContextCompat.RECEIVER_EXPORTED,
            )
            awaitClose { bluetooth.context.unregisterReceiver(receiver) }
        }

    private fun toChange(intent: Intent): SystemChange? =
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> SystemChange.Bluetooth(on = true)
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> SystemChange.Bluetooth(on = false)
                    else -> null
                }
            }

            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                bondChange(intent)
            }

            ACTION_KEY_MISSING -> {
                if (isMeter(intent)) SystemChange.KeyMissing.also { log.warn("bond.key_missing") } else null
            }

            else -> {
                null
            }
        }

    private fun bondChange(intent: Intent): SystemChange? {
        if (!isMeter(intent)) return null
        val state =
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> BondState.BONDED
                BluetoothDevice.BOND_BONDING -> BondState.BONDING
                else -> BondState.NONE
            }
        bondFlow.value = state
        log.info("bond.state", "state" to state)
        return if (state == BondState.BONDING) null else SystemChange.Bond(bonded = state == BondState.BONDED)
    }

    private fun isMeter(intent: Intent): Boolean {
        val device = intent.getParcelableExtraCompat()
        return device?.address == bluetooth.address
    }

    private fun Intent.getParcelableExtraCompat(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            // Android 12: the typed overload exists from API 33.
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun readBond(): BondState {
        if (!bluetooth.hasConnectPermission()) return BondState.NONE
        return try {
            when (bluetooth.device.bondState) {
                BluetoothDevice.BOND_BONDED -> BondState.BONDED
                BluetoothDevice.BOND_BONDING -> BondState.BONDING
                else -> BondState.NONE
            }
        } catch (e: SecurityException) {
            log.warn("bond.read.denied", "error" to e.message)
            BondState.NONE
        }
    }

    private companion object {
        const val ANDROID_16 = 36

        /** BluetoothDevice.ACTION_KEY_MISSING (API 36); the string lets the `when` compile on every release. */
        const val ACTION_KEY_MISSING = "android.bluetooth.device.action.KEY_MISSING"
    }
}
