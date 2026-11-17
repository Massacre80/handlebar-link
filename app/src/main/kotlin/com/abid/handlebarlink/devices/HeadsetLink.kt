package com.abid.handlebarlink.devices

import android.content.Intent
import android.content.IntentSender

/** What asking to reconnect did. */
enum class Reconnect {
    /** Android is connecting the device's profiles (calls and music). */
    STARTED,

    /** The app needs Android's companion link to the device first (one consent). */
    NOT_LINKED,

    /** This Android does not let apps reconnect (before 17, or the platform switch is off). */
    NOT_SUPPORTED,

    /** Refused for another reason (Bluetooth off, device gone). */
    FAILED,
}

/**
 * Reconnecting a paired headset from the app (research of 2026-11-18): Android 17's public
 * `BluetoothDevice.connect()`, allowed for an app with a companion-device association with that device.
 */
interface HeadsetLink {
    /** The app has Android's companion link to [address]. */
    fun isLinked(address: String): Boolean

    /** Asks Android to link the app to the paired device at [address]: the consent to show, null if it cannot. */
    suspend fun request(address: String): IntentSender?

    /** The linked address from the consent's result, or null when the rider declined. */
    fun linkedFrom(
        resultCode: Int,
        data: Intent?,
    ): String?

    /** Asks Android to connect [address] (calls and music). */
    fun reconnect(address: String): Reconnect
}
