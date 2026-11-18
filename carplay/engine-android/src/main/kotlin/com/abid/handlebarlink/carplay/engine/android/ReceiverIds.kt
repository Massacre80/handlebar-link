package com.abid.handlebarlink.carplay.engine.android

import java.security.MessageDigest

/**
 * The receiver's two MAC-shaped ids, and where each came from (logged for device check P4).
 *
 * Android hides this phone's own addresses from apps: `BluetoothAdapter.getAddress()` returns the constant
 * 02:00:00:00:00:00 (Android 6.0 changes), `NetworkInterface.getHardwareAddress()` returns null for apps targeting
 * Android 11+ (user-data-ids, "MAC address availability changes in Android 11"), and Wi-Fi Direct erases our own
 * address from the group owner it reports unless the caller holds LOCAL_MAC_ADDRESS (AOSP `WifiP2pServiceImpl`
 * `maybeEraseOwnDeviceAddress`, packages/modules/Wifi main :7502-7514). DiPlay meets the same wall and falls back to
 * an id derived from the AirPlay identity: `DiPlayBootstrap.deviceId` (DiPlayBootstrap.kt:45-49) for the Bluetooth
 * id in identification (`DiPlayBluetooth.localAddress` → `deviceId`, CarPlayHostActivity.kt:5194) and for the
 * receiver id (CarPlayController.kt:1539-1541). So on the Poco, CarLink sends both as the derived id; we do the same.
 * The real address is still used wherever Android reveals it (a privileged build).
 */
internal object ReceiverIds {
    /** What Android returns instead of a hidden address. */
    const val PLACEHOLDER = "02:00:00:00:00:00"

    /**
     * SHA-256 of the AirPlay Ed25519 public key, first 6 bytes, with the locally-administered bit set and the
     * multicast bit cleared (DiPlayBootstrap.kt:45-49). Stable for as long as the stored identity is.
     */
    fun fromIdentity(publicKey: ByteArray): ByteArray {
        val bytes = MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(MAC_BYTES)
        bytes[0] = ((bytes[0].toInt() and UNICAST_GLOBAL_MASK) or LOCALLY_ADMINISTERED).toByte()
        return bytes
    }

    /**
     * Whether [text] is a real address: six hex bytes and not one of Android's stand-ins (DiPlay rejects every
     * `02:00:00:00:00:xx` and all zeros, DiPlayBluetooth.kt:12-15).
     */
    fun isReal(text: String?): Boolean =
        text != null &&
            MAC.matches(text) &&
            !text.startsWith(PLACEHOLDER_PREFIX, ignoreCase = true) &&
            text != ALL_ZEROS

    /** "aa:bb:…" to 6 bytes. */
    fun parse(text: String): ByteArray {
        require(MAC.matches(text)) { "Not a MAC address: $text" }
        return ByteArray(
            MAC_BYTES,
        ) { index -> text.substring(index * HEX_STRIDE, index * HEX_STRIDE + 2).toInt(HEX).toByte() }
    }

    /** 6 bytes to lower-case "aa:bb:…" (the form `WifiGroup.interfaceMac` asks for). */
    fun format(bytes: ByteArray): String = bytes.joinToString(":") { "%02x".format(it.toInt() and BYTE_MASK) }

    /** Where an id came from, for the log. */
    enum class Source {
        /** Android revealed the real address (adapter or interface). */
        HARDWARE,

        /** The Wi-Fi Direct group owner's device address. */
        GROUP_OWNER,

        /** Derived from the AirPlay identity ([fromIdentity]). */
        IDENTITY,
    }

    /**
     * The AirPlay receiver id: the group interface's hardware address, else the group owner's device address, else
     * the identity-derived id (DiPlay WifiP2pGroupManager.kt:682-683, then CarPlayController.kt:1539-1541).
     */
    fun receiverId(
        interfaceHardware: ByteArray?,
        groupOwnerAddress: String?,
        identityPublicKey: ByteArray,
    ): Pair<String, Source> {
        val hardware = interfaceHardware?.takeIf { it.size == MAC_BYTES }?.let(::format)
        return when {
            hardware != null && isReal(hardware) -> {
                hardware to Source.HARDWARE
            }

            groupOwnerAddress != null && isReal(groupOwnerAddress) -> {
                groupOwnerAddress.lowercase() to
                    Source.GROUP_OWNER
            }

            else -> {
                format(fromIdentity(identityPublicKey)) to Source.IDENTITY
            }
        }
    }

    private val MAC = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
    private const val PLACEHOLDER_PREFIX = "02:00:00:00:00:"
    private const val ALL_ZEROS = "00:00:00:00:00:00"
    private const val MAC_BYTES = 6
    private const val HEX_STRIDE = 3
    private const val HEX = 16
    private const val BYTE_MASK = 0xff
    private const val UNICAST_GLOBAL_MASK = 0xfc
    private const val LOCALLY_ADMINISTERED = 0x02
}
