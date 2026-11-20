package com.abid.handlebarlink.carplay.engine.android.wifi

import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.android.AndroidPlatform
import com.abid.handlebarlink.carplay.engine.android.ReceiverIds
import com.abid.handlebarlink.carplay.engine.android.bluetooth.IphoneSelector
import com.abid.handlebarlink.carplay.engine.android.credentials.IdentityStore
import com.abid.handlebarlink.carplay.engine.android.fail
import com.abid.handlebarlink.carplay.engine.spi.GroupClients
import com.abid.handlebarlink.carplay.engine.spi.WifiDirectPort
import com.abid.handlebarlink.carplay.engine.spi.WifiGroup
import com.abid.handlebarlink.carplay.protocol.iap2.WifiNetwork
import com.abid.handlebarlink.carplay.protocol.iap2.WifiSecurity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi Direct with this phone as group owner: a stable name and passphrase per iPhone ([StableNetworks]), the
 * channel plan ([ChannelPlan]), a live group of ours reused as it stands, the group's addresses and clients. The
 * framework calls and the long-lived channel are in [P2pFramework]; blocking reads (preferences, interfaces) run on
 * the I/O thread.
 */
internal class AndroidWifiDirectPort(
    private val platform: AndroidPlatform,
    private val selector: IphoneSelector,
    private val networks: StableNetworks,
    private val identities: IdentityStore,
    private val p2p: P2pFramework = P2pFramework(platform),
) : WifiDirectPort {
    /** The group we created or read, for [clients], [groupWorked] and [removeGroup]. */
    @Volatile private var current: Current? = null

    /** Names of groups this process created, including system-named ones, so they are recognised as ours. */
    private val owned = ConcurrentHashMap.newKeySet<String>()

    /** A create was accepted (or not answered) and its group has not been described yet: it may still be forming. */
    @Volatile private var forming = false

    private val removal =
        GroupRemoval(
            info = { p2p.groupInfo()?.let { LiveGroup(it.networkName, it.isGroupOwner) } },
            remove = { p2p.remove() },
            isOurs = ::isOurs,
            log = platform.log,
        )

    private class Current(
        val iphone: String,
        val ssid: String,
        val frequency: Int,
    )

    override suspend fun createGroup(): WifiGroup =
        withContext(platform.io) {
            val sdk = Build.VERSION.SDK_INT
            val permission = ChannelPlan.wifiDirectPermission(sdk)
            if (platform.context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                fail(CarPlayError.PermissionMissing(permission))
            }
            val wifi = platform.context.getSystemService(WifiManager::class.java)
            if (wifi?.isWifiEnabled != true) fail(CarPlayError.WifiOff)
            val iphone = iphoneKey()
            val network = networks.networkFor(iphone)
            val plan = ChannelPlan.plan(networks.frequency(iphone))
            platform.log.info("carplay.android.wifi.plan", "iphone" to iphone, "ssid" to network.ssid, "plan" to plan)
            val created =
                reuseLiveGroup(network, plan.first())
                    ?: ChannelPlan.run(plan, sdk, create = { createOnce(network, it) }, pause = { delay(it) })
            val group = awaitUsableGroup(if (created == GroupRequest.SystemDefault) null else network.ssid)
            describe(group, created, network, iphone)
        }

    /**
     * A live group of ours with our name on the plan's first channel is kept: removing and re-creating it re-addresses
     * the network the iPhone saved (DiPlay WifiP2pGroupManager.kt:225-249). Another group of ours is removed first
     * (GM:250-267); someone else's is an error, never removed.
     */
    private suspend fun reuseLiveGroup(
        network: StableNetwork,
        first: GroupRequest,
    ): GroupRequest? {
        val live = p2p.groupInfo()
        val fits = live != null && ((first as? GroupRequest.Frequency)?.megahertz?.let { it == live.frequency } ?: true)
        val reuse = live != null && live.isGroupOwner && live.networkName == network.ssid && fits
        when {
            live == null || reuse -> {}

            !live.isGroupOwner || !isOurs(live.networkName) -> {
                fail(CarPlayError.WifiDirectFailed("another Wi-Fi Direct group is active (${live.networkName})"))
            }

            else -> {
                platform.log.info(
                    "carplay.android.wifi.group_reclaimed",
                    "ssid" to live.networkName,
                    "mhz" to live.frequency,
                )
                removeAndWait()
            }
        }
        if (reuse) {
            platform.log.info(
                "carplay.android.wifi.group_reused",
                "ssid" to network.ssid,
                "mhz" to live.frequency,
            )
        }
        return if (reuse) first else null
    }

    private suspend fun createOnce(
        network: StableNetwork,
        request: GroupRequest,
    ): CreateOutcome {
        val config =
            (request as? GroupRequest.Frequency)?.let {
                WifiP2pConfig
                    .Builder()
                    .setNetworkName(network.ssid)
                    .setPassphrase(network.passphrase)
                    .setGroupOperatingFrequency(it.megahertz)
                    // A persistent group keeps the stable pair in the supplicant's saved groups (GM:305-316).
                    .enablePersistentMode(true)
                    .build()
            }
        forming = true
        val outcome = p2p.create(config)
        if (outcome is CreateOutcome.Rejected) forming = false
        platform.log.info(
            "carplay.android.wifi.create",
            "request" to request,
            "outcome" to ChannelPlan.describe(outcome),
        )
        return outcome
    }

    /** Polls the group until it is ours and complete: owner, name, interface, frequency (GM:612-664). */
    private suspend fun awaitUsableGroup(expectedSsid: String?): WifiP2pGroup {
        var last = "no group information"
        repeat(GROUP_POLLS) {
            val group = p2p.groupInfo()
            last =
                when {
                    group == null -> "no group information"
                    !group.isGroupOwner -> fail(CarPlayError.WifiDirectFailed("this phone is a client, not the owner"))
                    expectedSsid != null && group.networkName != expectedSsid -> "another group (${group.networkName})"
                    group.getInterface().isNullOrBlank() -> "no interface yet"
                    group.frequency <= 0 -> "no frequency yet"
                    group.passphrase.isNullOrBlank() -> "no passphrase yet"
                    else -> return group
                }
            delay(POLL_MILLIS)
        }
        fail(CarPlayError.WifiDirectFailed("the group never became usable: $last"))
    }

    private suspend fun describe(
        group: WifiP2pGroup,
        created: GroupRequest,
        network: StableNetwork,
        iphone: String,
    ): WifiGroup {
        val interfaceName = group.getInterface()
        val addresses = awaitAddresses(interfaceName)
        val channelNumber =
            GroupFacts.channel(group.frequency)
                ?: fail(CarPlayError.WifiDirectFailed("unsupported frequency ${group.frequency} MHz"))
        val security =
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.BAKLAVA
            ) {
                GroupFacts.security(group.securityType)
            } else {
                WifiSecurity.WPA_WPA2
            }
        val identity = identities.peek() ?: identities.load()
        val hardware = GroupFacts.interfaceHardware(interfaceName)
        val (receiverId, source) = ReceiverIds.receiverId(hardware, group.owner?.deviceAddress, identity.keys.publicKey)
        val stable = created != GroupRequest.SystemDefault
        current = Current(iphone, group.networkName, group.frequency)
        owned.add(group.networkName)
        forming = false
        platform.log.info(
            "carplay.android.wifi.group_ready",
            "ssid" to group.networkName,
            "stable" to stable,
            "requested" to created,
            "frequency" to group.frequency,
            "channel" to channelNumber,
            "security" to security,
            "interface" to interfaceName,
            "addresses" to addresses,
        )
        platform.log.info(
            "carplay.android.wifi.receiver_id",
            "id" to receiverId,
            "source" to source,
            "interface_mac" to hardware?.let(ReceiverIds::format),
            "owner_address" to group.owner?.deviceAddress,
        )
        val passphrase = if (stable) network.passphrase else group.passphrase
        return WifiGroup(WifiNetwork(group.networkName, passphrase, security, channelNumber), addresses, receiverId)
    }

    /** The interface's addresses; waits up to 2 s for an IPv4 one, as DiPlay does (GM:828-850). */
    private suspend fun awaitAddresses(interfaceName: String): List<String> {
        var found = GroupFacts.addresses(interfaceName, GroupFacts.interfaceAddresses(interfaceName))
        var polls = 1
        while (found.none { !it.contains(':') } && polls < ADDRESS_POLLS) {
            delay(POLL_MILLIS)
            found = GroupFacts.addresses(interfaceName, GroupFacts.interfaceAddresses(interfaceName))
            polls++
        }
        if (found.isEmpty()) fail(CarPlayError.WifiDirectFailed("the group interface $interfaceName has no address"))
        return found
    }

    /** The iPhone the stable network belongs to; [StableNetworks.ANY] when it cannot be read yet. */
    private fun iphoneKey(): String {
        val adapter = platform.context.getSystemService(BluetoothManager::class.java)?.adapter
        val address =
            selector.chosenAddress() ?: adapter?.let {
                try {
                    selector.select(it).address
                } catch (_: CarPlayException) {
                    null
                }
            }
        return address?.uppercase() ?: StableNetworks.ANY
    }

    override fun clients(): Flow<GroupClients> =
        callbackFlow {
            closeWhenLost(p2p.channelLost, isLive = { p2p.hasChannel }, error = ::lost)
            val ssid = current?.ssid
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        val info = intent.typedExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                        val group = intent.typedExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
                        if (info?.groupFormed == false) {
                            close(CarPlayException(CarPlayError.WifiDirectFailed("the Wi-Fi Direct group was removed")))
                        } else if (group != null && isOurGroup(group, ssid)) {
                            trySend(GroupFacts.clientsOf(group))
                        }
                    }
                }
            // The framework's own broadcast (system server): not exported (CarLink CarPlayAdapter.kt:350-370).
            ContextCompat.registerReceiver(
                platform.context,
                receiver,
                IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                null,
                platform.handler,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            // Broadcasts are not sticky since Android 10: start from the group as it is now.
            p2p.groupInfo()?.takeIf { isOurGroup(it, ssid) }?.let { trySend(GroupFacts.clientsOf(it)) }
            awaitClose { platform.context.unregisterReceiver(receiver) }
        }.distinctUntilChanged()

    private fun isOurGroup(
        group: WifiP2pGroup,
        ssid: String?,
    ): Boolean = group.isGroupOwner && (ssid == null || group.networkName == ssid)

    override fun groupWorked() {
        val group = current ?: return
        if (!ChannelPlan.isRememberable(group.frequency)) return
        platform.log.info("carplay.android.wifi.channel_remembered", "iphone" to group.iphone, "mhz" to group.frequency)
        // Off the engine's thread: a SharedPreferences commit is a disk write.
        platform.ioExecutor.execute { networks.rememberFrequency(group.iphone, group.frequency) }
    }

    override suspend fun removeGroup() {
        current = null
        val wasForming = forming
        forming = false
        if (!p2p.hasChannel) return
        try {
            // The attempt's teardown waits for this (NonCancellable): bounded however slow the framework answers.
            val finished =
                withTimeoutOrNull(REMOVE_TOTAL_MILLIS) { withContext(platform.io) { removal.run(wasForming) } }
            if (finished ==
                null
            ) {
                platform.log.warn("carplay.android.wifi.remove_timeout", "after_ms" to REMOVE_TOTAL_MILLIS)
            }
        } catch (e: CarPlayException) {
            platform.log.warn("carplay.android.wifi.remove_failed", "error" to e.error)
        }
    }

    /**
     * Wi-Fi turning on, from the framework's broadcast (system server): not exported, as the group broadcast below.
     * The broadcast is sticky, so a Wi-Fi that is on already reports once when collection starts.
     */
    override val wifiOn: Flow<Unit> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                        if (state == WifiManager.WIFI_STATE_ENABLED) {
                            platform.log.info("carplay.android.wifi.on")
                            trySend(Unit)
                        }
                    }
                }
            ContextCompat.registerReceiver(
                platform.context,
                receiver,
                IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
                null,
                platform.handler,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            awaitClose { platform.context.unregisterReceiver(receiver) }
        }

    /** Removes our group, never another app's ([GroupRemoval]). */
    private suspend fun removeAndWait() = removal.run(forming = false)

    private fun isOurs(ssid: String?): Boolean = StableNetworks.isOurs(ssid) || (ssid != null && ssid in owned)

    private fun lost() = CarPlayException(CarPlayError.WifiDirectFailed("the Wi-Fi Direct channel was lost"))

    private companion object {
        const val POLL_MILLIS = 100L
        const val GROUP_POLLS = 30
        const val ADDRESS_POLLS = 20
        const val REMOVE_TOTAL_MILLIS = 3_000L
    }
}

private fun <T> Intent.typedExtra(
    name: String,
    type: Class<T>,
): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, type)
    } else {
        // Android 12: the typed overload exists from API 33.
        @Suppress("DEPRECATION")
        type.cast(getParcelableExtra(name))
    }
