package com.abid.handlebarlink.setup

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Pairs the linked meter (docs/PLAN.md "First-time setup"): `createBond` soon after the consent (the system
 * may then skip its own confirmation), waiting for `ACTION_BOND_STATE_CHANGED` for our address with a 30 s
 * limit as RoadSync does. The broadcast comes from Bluetooth, a privileged app rather than the system, so
 * the receiver is registered exported (broadcasts guide).
 */
class AndroidMeterBonding
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) : MeterBonding {
        private val log = logger.child("setup.bond")

        override suspend fun bond(address: String): Boolean {
            val device = remoteDevice(address) ?: return false
            return withTimeoutOrNull(BOND_TIMEOUT_MS) { awaitBond(device) } ?: false.also { log.warn("bond.timeout") }
        }

        /** The meter at [address]; null without Bluetooth. */
        private fun remoteDevice(address: String): BluetoothDevice? =
            context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)

        private suspend fun awaitBond(device: BluetoothDevice): Boolean {
            val address = device.address
            var onState: (Int) -> Unit = {}
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        val changed =
                            IntentCompat.getParcelableExtra(
                                intent,
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java,
                            )
                        if (changed?.address ==
                            address
                        ) {
                            onState(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR))
                        }
                    }
                }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            return try {
                suspendCancellableCoroutine { continuation ->
                    onState = { state ->
                        when (state) {
                            BluetoothDevice.BOND_BONDED -> {
                                if (continuation.isActive) continuation.resume(true)
                            }

                            BluetoothDevice.BOND_NONE -> {
                                if (continuation.isActive) continuation.resume(false)
                            }

                            else -> {}
                        }
                    }
                    when {
                        device.bondState == BluetoothDevice.BOND_BONDED -> {
                            continuation.resume(true)
                        }

                        !createBond(device) -> {
                            log.warn("bond.not_started")
                            continuation.resume(false)
                        }

                        else -> {
                            log.info("bond.started")
                        }
                    }
                }
            } catch (e: SecurityException) {
                log.warn("bond.denied", "error" to e.message)
                false
            } finally {
                context.unregisterReceiver(receiver)
            }
        }

        /** Starts pairing; false without BLUETOOTH_CONNECT (setup asks for it before searching). */
        private fun createBond(device: BluetoothDevice): Boolean =
            when {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED -> false

                Build.VERSION.SDK_INT >= ANDROID_17 -> device.createBond(BluetoothDevice.TRANSPORT_LE)

                else -> device.createBond()
            }

        private companion object {
            const val BOND_TIMEOUT_MS = 30_000L
            const val ANDROID_17 = 37
        }
    }
