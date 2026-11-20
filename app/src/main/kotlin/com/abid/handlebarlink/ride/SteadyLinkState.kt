package com.abid.handlebarlink.ride

import com.abid.handlebarlink.bike.link.LinkState
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.steady.stableState
import kotlinx.coroutines.flow.Flow

/**
 * The bike link's state as Home's pill and the ride notification show it (docs/PLAN.md "Stable live indicators",
 * [stableState]): a state that lasts less than the show delay never shows, and a shown one stays the minimum time
 * before the next ordinary change, so a reconnect of a moment or a burst of setup steps does not flash "Reconnecting"
 * or "Connecting". Shown at once: Ready (presses reach CarPlay), Blocked (only the rider can fix it), no bike, and
 * the first state of a bike's link (the link was only just created).
 * Connecting and setting up are one state on screen; a new attempt or step replaces it in place.
 */
fun Flow<LinkState?>.steady(time: TimeProvider): Flow<LinkState?> =
    stableState(time, stateOf = ::shownLinkState, immediate = { shown, next -> shown == null || atOnce(next) })

/** Which state [state] shows (null: no link, no bike chosen). */
internal fun shownLinkState(state: LinkState?): Any? =
    when (state) {
        null, LinkState.Stopped, LinkState.Idle, LinkState.WaitingForBike -> state
        is LinkState.Connecting, is LinkState.SettingUp -> LinkState.Connecting::class
        is LinkState.Ready -> LinkState.Ready::class
        is LinkState.Recovering -> LinkState.Recovering::class
        is LinkState.Blocked -> state.error
    }

private fun atOnce(next: LinkState?): Boolean = next == null || next is LinkState.Ready || next is LinkState.Blocked
