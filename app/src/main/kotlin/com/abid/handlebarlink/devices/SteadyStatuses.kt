package com.abid.handlebarlink.devices

import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.steady.stableState
import kotlinx.coroutines.flow.Flow

/**
 * The devices' live states as Home's tile, the Intercom page and Add device show them (docs/PLAN.md "Stable live
 * indicators", [stableState]). A headset at the edge of its range, or one that drops a profile and takes it back,
 * makes Android report a disconnect and a connect a moment apart; shown raw, the card flashed "Not connected". Rules:
 * - a device connecting, or adding a profile (calls, music), shows at once;
 * - a device or profile going away waits the show delay (a drop of a moment never shows) and a shown connection
 *   stays the minimum time;
 * - a battery report replaces the old one at once (it already comes in 10 % steps, Android's `(level + 1) × 10`
 *   for Apple's report, and only on change).
 */
fun Flow<Map<String, DeviceStatus>>.steady(time: TimeProvider): Flow<Map<String, DeviceStatus>> =
    stableState(time, stateOf = ::links, immediate = { shown, next -> lost(links(shown), links(next)).not() })

/** What is connected per address: the profiles, without the battery. */
private fun links(statuses: Map<String, DeviceStatus>): Map<String, Pair<Boolean, Boolean>> =
    statuses.filterValues { it.connected }.mapValues { (_, status) -> status.calls to status.music }

/** Whether a device or a profile in [before] is missing from [after]. */
private fun lost(
    before: Map<String, Pair<Boolean, Boolean>>,
    after: Map<String, Pair<Boolean, Boolean>>,
): Boolean =
    before.any { (address, profiles) ->
        val now = after[address]
        now == null || (profiles.first && !now.first) || (profiles.second && !now.second)
    }
