package com.abid.handlebarlink.carplay.engine.android.pairing

import java.util.Locale
import java.util.UUID

/**
 * What proves a Bluetooth device is an iPhone, after CarLink's `PhoneClassifier.classifyBluetooth`
 * (carlink-core/.../detect/PhoneClassifier.kt on upstream/main): the iAP2 RFCOMM service record, which only Apple
 * devices publish, or an "iPhone" name (the default name, and DiPlay's own picker rule). An iPad also publishes iAP2
 * but CarPlay runs only on an iPhone; the name rule leaves iPads out, the service rule cannot tell them apart.
 */
internal object IphoneEvidence {
    /**
     * The iPhone's iAP2 RFCOMM service (DiPlay CarPlayController.kt:1465; CarLink PhoneClassifier.IAP2_BLUETOOTH_UUID).
     */
    val IAP2: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")

    /** Whether [name] or [uuids] prove an iPhone. */
    fun isIphone(
        name: String?,
        uuids: Set<UUID>,
    ): Boolean = IAP2 in uuids || clean(name)?.lowercase(Locale.ROOT)?.contains(IPHONE) == true

    /**
     * [name] without the NUL padding some devices send and outer spaces; null when nothing is left
     * (docs/LESSONS.md 33: the meter's name carried two NULs).
     */
    fun clean(name: String?): String? = name?.replace(NUL, "")?.trim()?.takeIf { it.isNotEmpty() }

    /** The name to show for a device: its clean name, else its address. */
    fun display(
        name: String?,
        address: String,
    ): String = clean(name) ?: address

    private const val IPHONE = "iphone"
    private const val NUL = "\u0000"
}
