package com.abid.handlebarlink.carplay.engine.android.wifi

import android.net.wifi.p2p.WifiP2pGroup
import android.os.Build
import androidx.annotation.RequiresApi
import com.abid.handlebarlink.carplay.engine.android.Settings
import com.abid.handlebarlink.carplay.engine.spi.GroupClients
import com.abid.handlebarlink.carplay.protocol.iap2.WifiSecurity
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.security.SecureRandom
import java.util.Collections

/** A Wi-Fi Direct name and passphrase. [toString] never prints the passphrase. */
internal class StableNetwork(
    val ssid: String,
    val passphrase: String,
) {
    override fun toString(): String = "StableNetwork(ssid=$ssid)"
}

/**
 * One Wi-Fi Direct name and passphrase per iPhone, created once and reused, and the channel that last produced a
 * verified session. The iPhone stores the network and rejoins it by itself on the next Bluetooth reconnect (Apple
 * spec p.397, p.405); a new name each time makes it set up a new network (DiPlay #464; CarLink
 * P2pStableIdentity.kt:1-80, which keeps one pair per install). Ours is keyed by the iPhone's Bluetooth address
 * ([ANY] when it cannot be read), so a second iPhone never sees the first one's passphrase.
 */
internal class StableNetworks(
    private val settings: Settings,
    private val random: SecureRandom,
) {
    /** The stored network for [iphone], or a new one stored before it is returned. */
    fun networkFor(iphone: String): StableNetwork {
        val key = NETWORK_PREFIX + iphone.uppercase()
        settings.string(key)?.split('\n')?.let { parts ->
            if (parts.size == 2 && isOurs(parts[0]) &&
                isValidPassphrase(parts[1])
            ) {
                return StableNetwork(parts[0], parts[1])
            }
        }
        val network = StableNetwork(SSID_START + token(SSID_TOKEN) + SSID_END, token(PASSPHRASE_LENGTH))
        settings.put(key, network.ssid + "\n" + network.passphrase)
        return network
    }

    /** The frequency that last reached a verified session with [iphone], if any. */
    fun frequency(iphone: String): Int? = settings.string(FREQUENCY_PREFIX + iphone.uppercase())?.toIntOrNull()

    fun rememberFrequency(
        iphone: String,
        megahertz: Int,
    ) {
        settings.put(FREQUENCY_PREFIX + iphone.uppercase(), megahertz.toString())
    }

    private fun token(length: Int): String =
        buildString(length) {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

    companion object {
        /** The key when the iPhone is not known (no Bluetooth permission yet). */
        const val ANY = "ANY"

        private const val NETWORK_PREFIX = "wifi.network."
        private const val FREQUENCY_PREFIX = "wifi.frequency."

        /** `WifiP2pConfig.Builder.setNetworkName` needs "DIRECT-" and two alphanumerics, at most 32 bytes. */
        private const val SSID_START = "DIRECT-"
        private const val SSID_END = "-HandlebarLink"
        private const val SSID_TOKEN = 2
        private const val PASSPHRASE_LENGTH = 16
        private const val MIN_PASSPHRASE = 8
        private const val MAX_PASSPHRASE = 63
        private const val PRINTABLE_FIRST = 0x21
        private const val PRINTABLE_LAST = 0x7E

        /** DiPlay's token alphabet, without look-alike characters (WifiP2pGroupManager.kt TOKEN_ALPHABET). */
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

        private val OURS = Regex("^DIRECT-[A-Za-z0-9]{2}-HandlebarLink$")

        /** Whether [ssid] is a name this module creates. */
        fun isOurs(ssid: String?): Boolean = ssid != null && OURS.matches(ssid)

        /** WPA2 passphrase rules (DiPlay P2pStableIdentity.kt:105-106). */
        fun isValidPassphrase(passphrase: String): Boolean =
            passphrase.length in MIN_PASSPHRASE..MAX_PASSPHRASE &&
                passphrase.all { it.code in PRINTABLE_FIRST..PRINTABLE_LAST }
    }
}

/** Facts read off a created group. */
internal object GroupFacts {
    /**
     * Our addresses on the group interface as `WifiGroup.addresses` wants them: IPv4 first, then IPv6; a link-local
     * IPv6 address carries the interface as its scope ("fe80::1%p2p-wlan0-0"); loopback and wildcard are skipped.
     * Never hard-coded: tethering picks a random range (AOSP WifiP2pServiceImpl, E-connection.md #7).
     */
    fun addresses(
        interfaceName: String,
        addresses: List<InetAddress>,
    ): List<String> {
        val usable = addresses.filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
        val v4 = usable.filterIsInstance<Inet4Address>().map { it.hostAddress.orEmpty() }
        val v6 =
            usable.filterIsInstance<Inet6Address>().map { address ->
                val bare = address.hostAddress.orEmpty().substringBefore('%')
                if (address.isLinkLocalAddress) "$bare%$interfaceName" else bare
            }
        return (v4 + v6).filter { it.isNotEmpty() }.distinct()
    }

    /** The Wi-Fi channel number of [megahertz], or null outside 2.4 and 5 GHz. */
    fun channel(megahertz: Int): Int? =
        when (megahertz) {
            in TWO_GHZ_FIRST..TWO_GHZ_LAST -> (megahertz - TWO_GHZ_BASE) / SPACING
            CHANNEL_14 -> CHANNEL_14_NUMBER
            in FIVE_GHZ_FIRST..FIVE_GHZ_LAST -> (megahertz - FIVE_GHZ_BASE) / SPACING
            else -> null
        }

    /**
     * The group's security as iAP2 names it (DiPlay WifiP2pGroupManager.kt:905-925): WPA2-PSK, WPA3 transition or
     * WPA3-SAE; UNKNOWN (vendor supplicants) is the plain WPA2-PSK group Android creates. Before Android 16 the
     * framework does not report it and the group is WPA2-PSK.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun security(securityType: Int): WifiSecurity =
        when (securityType) {
            WifiP2pGroup.SECURITY_TYPE_WPA3_COMPATIBILITY -> WifiSecurity.WPA3_TRANSITION
            WifiP2pGroup.SECURITY_TYPE_WPA3_SAE -> WifiSecurity.WPA3_ONLY
            else -> WifiSecurity.WPA_WPA2
        }

    /** The interface's current addresses (empty when it is gone). */
    fun interfaceAddresses(name: String): List<InetAddress> =
        try {
            NetworkInterface
                .getByName(name)
                ?.inetAddresses
                ?.let { Collections.list(it) }
                .orEmpty()
        } catch (_: SocketException) {
            emptyList()
        }

    /** The interface's MAC: null for apps targeting Android 11+ (user-data-ids); kept for a privileged build. */
    fun interfaceHardware(name: String): ByteArray? =
        try {
            NetworkInterface.getByName(name)?.hardwareAddress
        } catch (_: SocketException) {
            null
        }

    /** Joined stations; leases from Android 15, where clients carry their IP (CarLink CarPlayAdapter.kt:361-364). */
    fun clientsOf(group: WifiP2pGroup): GroupClients {
        val clients = group.clientList.orEmpty()
        val leased =
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.VANILLA_ICE_CREAM
            ) {
                clients.count { it.ipAddress != null }
            } else {
                0
            }
        return GroupClients(clients.size, leased)
    }

    private const val TWO_GHZ_FIRST = 2412
    private const val TWO_GHZ_LAST = 2472
    private const val TWO_GHZ_BASE = 2407
    private const val CHANNEL_14 = 2484
    private const val CHANNEL_14_NUMBER = 14
    private const val FIVE_GHZ_FIRST = 5150
    private const val FIVE_GHZ_LAST = 5895
    private const val FIVE_GHZ_BASE = 5000
    private const val SPACING = 5
}
