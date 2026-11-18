package com.abid.handlebarlink.carplay.engine.android.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pManager
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException

/** One `createGroup` request of the plan. */
internal sealed interface GroupRequest {
    /** A pinned operating frequency with our stable name and passphrase. */
    data class Frequency(
        val megahertz: Int,
    ) : GroupRequest

    /** `createGroup(channel, null, …)`: the system picks name, passphrase and channel. */
    data object SystemDefault : GroupRequest
}

/** What one request got from the platform. */
internal sealed interface CreateOutcome {
    /** `ActionListener.onSuccess`. */
    data object Created : CreateOutcome

    /** `ActionListener.onFailure(reason)`: ERROR, P2P_UNSUPPORTED, BUSY, NO_PERMISSION. */
    data class Rejected(
        val reason: Int,
    ) : CreateOutcome

    /** No callback in time. */
    data object NoAnswer : CreateOutcome
}

/**
 * The Wi-Fi Direct channel plan, after DiPlay's `P2pStartupRecovery` (P2pStartupRecovery.kt:40-176 on CarLink
 * upstream/main) for a phone with no Wi-Fi station (the bike has none): the channel that last produced a verified
 * session first, then non-DFS 5 GHz 36 (5180) and 149 (5745), then 2.4 GHz 6/1/11 (2437, 2412, 2462), then the
 * system's own choice. Never a DFS channel: Apple allows 36-48 and 149-161 for CarPlay (spec p.398-399;
 * E-connection.md #2).
 */
internal object ChannelPlan {
    /** The fixed order after the remembered channel (P2pStartupRecovery.kt:86-91, no station). */
    val FIXED: List<Int> = listOf(CHANNEL_36, CHANNEL_149, CHANNEL_6, CHANNEL_1, CHANNEL_11)

    /** Frequencies a remembered success may name (P2pStartupRecovery.kt:40-46): 2.4 GHz 1-11, non-DFS 5 GHz. */
    fun isRememberable(megahertz: Int): Boolean =
        (megahertz in TWO_GHZ_FIRST..TWO_GHZ_LAST && (megahertz - TWO_GHZ_FIRST) % CHANNEL_SPACING == 0) ||
            isFiveGhzNonDfs(megahertz)

    fun plan(remembered: Int?): List<GroupRequest> {
        val frequencies = LinkedHashSet<Int>()
        remembered?.takeIf(::isRememberable)?.let(frequencies::add)
        frequencies.addAll(FIXED)
        return frequencies.map { GroupRequest.Frequency(it) } + GroupRequest.SystemDefault
    }

    /**
     * Walks [plan] with [create] until a group is created (P2pStartupRecovery.kt:116-176):
     * - BUSY: once more on the same request after [BUSY_RETRY_MILLIS], then the next request;
     * - ERROR (or an unknown reason): the next request after the same pause; several firmwares refuse every
     *   5 GHz request while 2.4 GHz works;
     * - NO_PERMISSION or P2P_UNSUPPORTED: stop, no other channel can fix it;
     * - no answer: stop (a late success may still create a group; the attempt's cleanup removes it).
     *
     * @return the request that created the group.
     */
    suspend fun run(
        plan: List<GroupRequest>,
        sdkInt: Int,
        create: suspend (GroupRequest) -> CreateOutcome,
        pause: suspend (Long) -> Unit,
    ): GroupRequest {
        var lastReason = "no request made"
        for ((index, request) in plan.withIndex()) {
            val outcome = createRetryingBusy(request, create, pause)
            if (outcome == CreateOutcome.Created) return request
            stopFor(outcome, request, sdkInt)?.let { throw CarPlayException(it) }
            lastReason = "${describe(outcome)} on $request"
            if (index < plan.lastIndex) pause(BUSY_RETRY_MILLIS)
        }
        throw CarPlayException(CarPlayError.WifiDirectFailed("every channel was refused, last: $lastReason"))
    }

    /** One request; a BUSY answer gets one more try after [BUSY_RETRY_MILLIS]. */
    private suspend fun createRetryingBusy(
        request: GroupRequest,
        create: suspend (GroupRequest) -> CreateOutcome,
        pause: suspend (Long) -> Unit,
    ): CreateOutcome {
        val first = create(request)
        if (first != CreateOutcome.Rejected(WifiP2pManager.BUSY)) return first
        pause(BUSY_RETRY_MILLIS)
        return create(request)
    }

    /** The error that ends the plan at [outcome], or null to go on with the next request. */
    private fun stopFor(
        outcome: CreateOutcome,
        request: GroupRequest,
        sdkInt: Int,
    ): CarPlayError? =
        when {
            outcome == CreateOutcome.NoAnswer -> {
                CarPlayError.WifiDirectFailed("Wi-Fi Direct did not answer createGroup ($request)")
            }

            outcome == CreateOutcome.Rejected(WifiP2pManager.NO_PERMISSION) -> {
                CarPlayError.PermissionMissing(wifiDirectPermission(sdkInt))
            }

            outcome == CreateOutcome.Rejected(WifiP2pManager.P2P_UNSUPPORTED) -> {
                CarPlayError.WifiDirectFailed("Wi-Fi Direct is not supported")
            }

            else -> {
                null
            }
        }

    /** For logs. */
    fun describe(outcome: CreateOutcome): String =
        when (outcome) {
            CreateOutcome.Created -> "created"
            CreateOutcome.NoAnswer -> "no_answer"
            is CreateOutcome.Rejected -> reasonName(outcome.reason)
        }

    /** The runtime permission Wi-Fi Direct group calls need on [sdkInt] (wifi-permissions guide). */
    @SuppressLint("InlinedApi") // A plain string, returned only where sdkInt says it exists.
    fun wifiDirectPermission(sdkInt: Int): String =
        if (sdkInt >= ANDROID_13) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    fun reasonName(reason: Int): String =
        when (reason) {
            WifiP2pManager.ERROR -> "ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            WifiP2pManager.NO_PERMISSION -> "NO_PERMISSION"
            else -> "reason $reason"
        }

    /** DiPlay's pause before a retry (WifiP2pGroupManager.kt `beforeRetry`, 500 ms). */
    const val BUSY_RETRY_MILLIS = 500L

    private const val ANDROID_13 = 33
    private const val TWO_GHZ_FIRST = 2412
    private const val TWO_GHZ_LAST = 2462
    private const val CHANNEL_SPACING = 5
    private const val CHANNEL_1 = 2412
    private const val CHANNEL_6 = 2437
    private const val CHANNEL_11 = 2462
    private const val CHANNEL_36 = 5180
    private const val CHANNEL_149 = 5745

    /** Channels 36-48 and 149-165 (P2pStartupRecovery.kt:43). */
    private const val UNII_1_FIRST = CHANNEL_36
    private const val UNII_1_LAST = 5240
    private const val UNII_3_FIRST = CHANNEL_149
    private const val UNII_3_LAST = 5825
    private const val FIVE_GHZ_SPACING = 20

    private fun isFiveGhzNonDfs(megahertz: Int): Boolean =
        (megahertz in UNII_1_FIRST..UNII_1_LAST && (megahertz - UNII_1_FIRST) % FIVE_GHZ_SPACING == 0) ||
            (megahertz in UNII_3_FIRST..UNII_3_LAST && (megahertz - UNII_3_FIRST) % FIVE_GHZ_SPACING == 0)
}
