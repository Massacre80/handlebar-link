package com.abid.handlebarlink.carplay.engine.android.bluetooth

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException

/** A bonded Bluetooth device, as the choice needs it. */
internal data class BondedDevice(
    val address: String,
    val name: String?,
)

/**
 * Which bonded device is the iPhone to dial, after DiPlay's `selectWirelessBluetoothDevice`
 * (CarPlayController.kt:3044-3085 on CarLink upstream/main):
 * 1. the iPhone the rider chose, if it is still bonded (else an error, never another phone);
 * 2. otherwise the bonded devices named "iPhone…": the one with a live Bluetooth link if exactly one has it, the only
 *    one if there is one, and an error when the choice is ambiguous.
 *
 * Two deliberate differences: "connected" comes from ACL broadcasts, not DiPlay's reflection on the hidden
 * `isConnected` (AGENTS.md: no hidden APIs); and DiPlay's last rule, "the only bonded device of any name", is left
 * out: on the bike the meter and the intercom are bonded too, so it would dial a device that is not a phone.
 *
 * iPhones the rider [forgot] in the app (upper-case addresses, pairing/IphoneChoices) are left out of the automatic
 * choice: they stay paired with Android, which offers apps no public way to unpair.
 */
internal object IphoneChoice {
    fun choose(
        chosenAddress: String?,
        bonded: List<BondedDevice>,
        connected: Set<String>,
        forgot: Set<String> = emptySet(),
    ): BondedDevice {
        val (device, problem) = pick(chosenAddress, bonded.filterNot { it.address.uppercase() in forgot }, connected)
        return device ?: throw CarPlayException(CarPlayError.BluetoothUnavailable(problem))
    }

    /** The device, or null and why. */
    private fun pick(
        chosenAddress: String?,
        bonded: List<BondedDevice>,
        connected: Set<String>,
    ): Pair<BondedDevice?, String> {
        if (chosenAddress != null) {
            return bonded.firstOrNull { it.address.equals(chosenAddress, ignoreCase = true) } to
                "the chosen iPhone is no longer paired"
        }
        val iphones = bonded.filter { it.name?.contains(IPHONE, ignoreCase = true) == true }
        val linked = iphones.filter { device -> connected.any { it.equals(device.address, ignoreCase = true) } }
        return when {
            linked.size == 1 -> linked.single() to ""
            linked.size > 1 -> null to "${linked.size} iPhones are connected: choose one"
            iphones.size == 1 -> iphones.single() to ""
            iphones.size > 1 -> null to "${iphones.size} iPhones are paired and none is connected: choose one"
            else -> null to "no paired iPhone"
        }
    }

    private const val IPHONE = "iPhone"
}
