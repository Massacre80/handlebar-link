package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioDeviceInfo
import com.abid.handlebarlink.carplay.engine.android.CarPlayHeadset

/** One of `AudioManager.getAvailableCommunicationDevices()`, as the route choice sees it. */
internal data class CommunicationDevice(
    /** `AudioDeviceInfo.getId()`: finds the same device again for `setCommunicationDevice`/`setPreferredDevice`. */
    val id: Int,
    /** `AudioDeviceInfo.getType()`. */
    val type: Int,
    /** `AudioDeviceInfo.getAddress()`: the Bluetooth address for a Bluetooth device. */
    val address: String,
    /** `AudioDeviceInfo.getProductName()`: the Bluetooth name for a Bluetooth device. */
    val name: String,
) {
    /** A hands-free headset: classic Bluetooth SCO (HFP) or an LE Audio headset. */
    val isHeadset: Boolean get() = type in HEADSET_TYPES

    override fun toString(): String = "CommunicationDevice(id=$id, type=$type, name=$name)"

    companion object {
        /** HFP (the EJEAS V6 Pro+ and most intercoms) and LE Audio (BLE audio guide: TYPE_BLE_HEADSET). */
        val HEADSET_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}

/**
 * Where each CarPlay stream plays (docs/PLAN.md "C5 audio", "Routes"):
 * - calls and Siri ([AudioChannel.PHONE], [AudioChannel.ASSISTANT]) need the helmet's hands-free link for both sound
 *   and microphone, which Android only opens on request: [voiceUsesHeadset];
 * - music and directions are plain media: Android sends them over A2DP to the connected headset by itself, and
 *   CarPlay never picks a device for them (no preferred device, no communication device).
 */
internal val AudioChannel.voiceUsesHeadset: Boolean
    get() = this == AudioChannel.PHONE || this == AudioChannel.ASSISTANT

/**
 * The hands-free device for calls and Siri among the [available] communication devices, or null to leave the route
 * to Android: [preferred] null (the rider chose the phone for calls) or no Bluetooth headset connected. Otherwise
 * the headset whose address matches, then whose name matches, then the first Bluetooth headset (as the held
 * Telecom call picks its endpoint, callaudio/IntercomCallRoute `callEndpoint`).
 */
internal fun chooseHeadset(
    available: List<CommunicationDevice>,
    preferred: CarPlayHeadset?,
): CommunicationDevice? {
    if (preferred == null) return null
    val headsets = available.filter { it.isHeadset }
    return preferred.address?.let { address -> headsets.firstOrNull { it.address.equals(address, ignoreCase = true) } }
        ?: preferred.name?.let { name -> headsets.firstOrNull { it.name == name } }
        ?: headsets.firstOrNull()
}
