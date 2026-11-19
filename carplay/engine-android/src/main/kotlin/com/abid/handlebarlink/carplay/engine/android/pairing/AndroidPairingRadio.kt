package com.abid.handlebarlink.carplay.engine.android.pairing

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.abid.handlebarlink.carplay.engine.android.AndroidPlatform
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * [PairingRadio] on Android's BluetoothAdapter. Every call that needs BLUETOOTH_CONNECT checks it in the same
 * function (docs/LESSONS.md 28) and answers "nothing" without it; a SecurityException (permission revoked between
 * the check and the call) is caught the same way. The broadcasts are sent by the Bluetooth app, not the system, so
 * the receiver is exported (docs/LESSONS.md 62; protected broadcasts cannot be forged by other apps), and runs on the
 * platform's callback thread.
 */
internal class AndroidPairingRadio(
    private val platform: AndroidPlatform,
) : PairingRadio {
    private val context get() = platform.context
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override fun allowed(): Boolean = granted()

    override fun isOn(): Boolean = adapter?.isEnabled == true

    override fun localName(): String? {
        if (!granted()) return null
        return try {
            adapter?.name
        } catch (e: SecurityException) {
            denied("name", e)
            null
        }
    }

    override fun rename(name: String): Boolean {
        if (!granted()) return false
        return try {
            adapter?.setName(name) == true
        } catch (e: SecurityException) {
            denied("rename", e)
            false
        }
    }

    override fun bonded(): List<RadioDevice> {
        if (!granted()) return emptyList()
        return try {
            adapter
                ?.bondedDevices
                .orEmpty()
                .map {
                    RadioDevice(
                        it.address,
                        it.name,
                        it.uuids
                            .orEmpty()
                            .map(ParcelUuid::getUuid)
                            .toSet(),
                    )
                }
        } catch (e: SecurityException) {
            denied("bonded", e)
            emptyList()
        }
    }

    override fun fetchServices(address: String) {
        if (!granted()) return
        try {
            val started = adapter?.getRemoteDevice(address)?.fetchUuidsWithSdp() == true
            platform.log.info("carplay.android.pairing.sdp", "address" to address, "started" to started)
        } catch (e: SecurityException) {
            denied("sdp", e)
        } catch (e: IllegalArgumentException) {
            platform.log.warn("carplay.android.pairing.sdp_address", "error" to e.message)
        }
    }

    override fun events(): Flow<PairingEvent> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        event(intent)?.let { trySend(it) }
                    }
                }
            ContextCompat.registerReceiver(
                context,
                receiver,
                FILTER,
                null,
                platform.handler,
                ContextCompat.RECEIVER_EXPORTED,
            )
            awaitClose { context.unregisterReceiver(receiver) }
        }

    /** The event a broadcast means, or null. */
    private fun event(intent: Intent): PairingEvent? =
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> PairingEvent.Radio(on = true, localName = localName())
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> PairingEvent.Radio(false, null)
                    else -> null
                }
            }

            BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED -> {
                PairingEvent.LocalName(intent.getStringExtra(BluetoothAdapter.EXTRA_LOCAL_NAME))
            }

            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                device(intent)?.let { device ->
                    val (name, uuids) = describe(device)
                    PairingEvent.BondChanged(device.address, name, bond(intent), uuids)
                }
            }

            BluetoothDevice.ACTION_UUID -> {
                device(intent)?.let { device ->
                    PairingEvent.Services(device.address, describe(device).first, uuids(intent))
                }
            }

            else -> {
                null
            }
        }

    /** [device]'s name and cached service records, when allowed. */
    private fun describe(device: BluetoothDevice): Pair<String?, Set<UUID>> {
        if (!granted()) return null to emptySet()
        return try {
            device.name to
                device.uuids
                    .orEmpty()
                    .map(ParcelUuid::getUuid)
                    .toSet()
        } catch (e: SecurityException) {
            denied("describe", e)
            null to emptySet()
        }
    }

    private fun granted(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun denied(
        call: String,
        e: SecurityException,
    ) {
        platform.log.warn("carplay.android.pairing.denied", "call" to call, "error" to e.message)
    }

    private companion object {
        val FILTER =
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_UUID)
            }

        fun device(intent: Intent): BluetoothDevice? =
            IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

        fun bond(intent: Intent): Bond =
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> Bond.BONDED
                BluetoothDevice.BOND_BONDING -> Bond.BONDING
                else -> Bond.NONE
            }

        fun uuids(intent: Intent): Set<UUID> =
            IntentCompat
                .getParcelableArrayExtra(intent, BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                .orEmpty()
                .filterIsInstance<ParcelUuid>()
                .map { it.uuid }
                .toSet()
    }
}
