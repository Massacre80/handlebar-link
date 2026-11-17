package com.abid.handlebarlink.devices

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import com.abid.handlebarlink.di.ApplicationScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Headset connection changes, declared in the manifest (one of the implicit broadcasts apps may still
 * receive there, "Implicit broadcast exceptions"), so they reach the app even when it is not running:
 * - connected: the system has started the process for this, so [HeadsetBatteries] (started in
 *   Application.onCreate) is listening when the headset's battery report follows;
 * - disconnected: its stored battery is forgotten, so a stale level is never shown.
 * The action is a protected broadcast (only the system sends it) and the manifest also requires the sender
 * to hold BLUETOOTH_CONNECT (android-permissions-security); the action is checked before anything is read.
 */
class HeadsetConnectionReceiver : BroadcastReceiver() {
    /** What the receiver needs from the app's graph (Hilt entry point: receivers get no field injection here). */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun batteries(): HeadsetBatteries

        @ApplicationScope
        fun scope(): CoroutineScope
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) return
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED)
        val address =
            IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)?.address
        if (state != BluetoothProfile.STATE_DISCONNECTED || address == null) return
        val app = EntryPointAccessors.fromApplication(context, Dependencies::class.java)
        // Keep the process until the store is written (the app may have been started just for this).
        val pending = goAsync()
        app.scope().launch {
            try {
                app.batteries().forget(address)
            } finally {
                pending.finish()
            }
        }
    }
}
