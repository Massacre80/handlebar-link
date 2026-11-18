package com.abid.handlebarlink.carplay.engine.android.wifi

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.android.AndroidPlatform
import com.abid.handlebarlink.carplay.engine.android.fail
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * `WifiP2pManager` as suspending calls, each bounded in time, with one long-lived `Channel`: when the last channel
 * of an app goes away the framework removes its group (AOSP `WifiP2pServiceImpl.close()`, E-connection.md #15), so
 * it is kept for the life of the process and only re-initialised after the framework dropped it. Callbacks run on the
 * platform thread. Every call checks its permission first (it can be revoked at any time).
 */
@SuppressLint("InlinedApi") // NO_PERMISSION (API 36): a plain int older releases never report; marks a missing grant.
internal class P2pFramework(
    private val platform: AndroidPlatform,
) {
    private val manager by lazy { platform.context.getSystemService(WifiP2pManager::class.java) }

    @Volatile private var liveChannel: WifiP2pManager.Channel? = null

    private val lostChannel = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the framework drops our channel (`ChannelListener.onChannelDisconnected`). */
    val channelLost: SharedFlow<Unit> = lostChannel

    /** Whether a channel is open now (nothing to remove without one). */
    val hasChannel: Boolean get() = liveChannel != null

    /** The channel; initialised on first use, again after the framework dropped it. */
    @Synchronized
    fun channel(): WifiP2pManager.Channel {
        liveChannel?.let { return it }
        val p2p = manager ?: fail(CarPlayError.WifiDirectFailed("Wi-Fi Direct is not supported"))
        val created =
            p2p.initialize(platform.context, platform.handler.looper) {
                platform.log.warn("carplay.android.wifi.channel_lost")
                liveChannel = null
                lostChannel.tryEmit(Unit)
            } ?: fail(CarPlayError.WifiDirectFailed("Wi-Fi Direct could not be initialised"))
        liveChannel = created
        return created
    }

    /** The group as the framework reports it now; null when there is none or no answer in 500 ms (GM REQUEST_POLL). */
    suspend fun groupInfo(): WifiP2pGroup? {
        val permission = ChannelPlan.wifiDirectPermission(Build.VERSION.SDK_INT)
        if (platform.context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            fail(CarPlayError.PermissionMissing(permission))
        }
        val p2p = manager ?: return null
        val channel = channel()
        return withTimeoutOrNull(INFO_ANSWER_MILLIS) {
            suspendCancellableCoroutine<WifiP2pGroup?> { continuation ->
                try {
                    p2p.requestGroupInfo(channel) { group -> continuation.resume(group) }
                } catch (_: SecurityException) {
                    continuation.resume(null)
                }
            }
        }
    }

    /** One `createGroup` ([config] null = the system's own name and channel). */
    suspend fun create(config: WifiP2pConfig?): CreateOutcome {
        val permission = ChannelPlan.wifiDirectPermission(Build.VERSION.SDK_INT)
        val p2p = manager
        if (platform.context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED || p2p == null) {
            return CreateOutcome.Rejected(
                if (p2p ==
                    null
                ) {
                    WifiP2pManager.P2P_UNSUPPORTED
                } else {
                    WifiP2pManager.NO_PERMISSION
                },
            )
        }
        val channel = channel()
        return withTimeoutOrNull(CREATE_ANSWER_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                try {
                    p2p.createGroup(channel, config, actionListener { continuation.resume(it) })
                } catch (_: SecurityException) {
                    continuation.resume(CreateOutcome.Rejected(WifiP2pManager.NO_PERMISSION))
                }
            }
        } ?: CreateOutcome.NoAnswer
    }

    /** `removeGroup`; true when the framework accepted it within 2 s. */
    suspend fun remove(): Boolean {
        val permission = ChannelPlan.wifiDirectPermission(Build.VERSION.SDK_INT)
        val p2p = manager
        if (platform.context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED ||
            p2p == null
        ) {
            return false
        }
        val channel = channel()
        val outcome =
            withTimeoutOrNull(REMOVE_ANSWER_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        p2p.removeGroup(channel, actionListener { continuation.resume(it) })
                    } catch (_: SecurityException) {
                        continuation.resume(CreateOutcome.Rejected(WifiP2pManager.NO_PERMISSION))
                    }
                }
            }
        if (outcome is CreateOutcome.Rejected) {
            platform.log.warn(
                "carplay.android.wifi.remove_rejected",
                "reason" to ChannelPlan.reasonName(outcome.reason),
            )
        }
        return outcome == CreateOutcome.Created
    }

    private fun actionListener(answer: (CreateOutcome) -> Unit) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = answer(CreateOutcome.Created)

            override fun onFailure(reason: Int) = answer(CreateOutcome.Rejected(reason))
        }

    companion object {
        const val CREATE_ANSWER_MILLIS = 10_000L
        const val INFO_ANSWER_MILLIS = 500L
        const val REMOVE_ANSWER_MILLIS = 2_000L
    }
}
