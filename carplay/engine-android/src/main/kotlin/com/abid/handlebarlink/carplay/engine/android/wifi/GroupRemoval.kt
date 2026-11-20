package com.abid.handlebarlink.carplay.engine.android.wifi

import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A Wi-Fi Direct group as removal sees it (the framework's `WifiP2pGroup`, reduced to what decides). */
internal data class LiveGroup(
    val name: String?,
    val isOwner: Boolean,
)

/**
 * Removes our group (never another app's) and waits up to 2 s until it is gone (DiPlay WifiP2pGroupManager.kt:250-267,
 * 1016-1063). A group whose create was accepted but that does not show yet ([forming]) is waited for, up to
 * [FORMING_POLLS] polls, and then removed: `requestGroupInfo` reports a new group only once it has formed, so a cancel
 * during that window would otherwise leave it up with this phone as group owner until the next start.
 */
internal class GroupRemoval(
    private val info: suspend () -> LiveGroup?,
    private val remove: suspend () -> Boolean,
    private val isOurs: (String?) -> Boolean,
    private val log: Logger,
) {
    suspend fun run(forming: Boolean) {
        var live = info()
        var polls = 0
        while (live == null && forming && polls < FORMING_POLLS) {
            delay(POLL_MILLIS)
            live = info()
            polls++
        }
        if (live == null) {
            if (forming) log.info("carplay.android.wifi.forming_not_seen", "after_polls" to polls)
            return
        }
        val ours = live.isOwner && isOurs(live.name)
        if (!ours) log.info("carplay.android.wifi.remove_skipped", "ssid" to live.name)
        if (!ours || !remove()) return
        var gone = info() == null
        var left = 1
        while (!gone && left < REMOVE_POLLS) {
            delay(POLL_MILLIS)
            gone = info() == null
            left++
        }
        log.info("carplay.android.wifi.group_removed", "ssid" to live.name, "gone" to gone, "waited_forming" to polls)
    }

    companion object {
        const val POLL_MILLIS = 100L
        const val REMOVE_POLLS = 20

        /** Inside removeGroup's 3 s: a forming group shows within GROUP_POLLS x 100 ms of its create. */
        const val FORMING_POLLS = 10
    }
}

/**
 * Closes this flow with [error] when [lost] emits. Subscribed before [isLive] is asked, so a loss that happens between
 * the question and the subscription (a no-replay signal) cannot be missed.
 */
internal fun <T> ProducerScope<T>.closeWhenLost(
    lost: Flow<Unit>,
    isLive: () -> Boolean,
    error: () -> Throwable,
) {
    launch(start = CoroutineStart.UNDISPATCHED) {
        lost.first()
        close(error())
    }
    if (!isLive()) throw error()
}
