package com.abid.handlebarlink.bike.link.machine

import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkState
import java.time.Instant

/**
 * The public [LinkState] for [mode]. [now] is monotonic milliseconds and [wallNow] the wall-clock
 * time at the same moment, to turn monotonic deadlines into times the UI can show.
 */
internal fun Mode.toLinkState(
    now: Long,
    wallNow: Instant,
): LinkState =
    when (this) {
        Mode.Stopped -> LinkState.Stopped
        Mode.Dormant -> LinkState.Idle
        Mode.Arming, is Mode.Waiting -> LinkState.WaitingForBike
        is Mode.Direct -> LinkState.Connecting(attempt)
        is Mode.DirectPause -> LinkState.Connecting(attempt)
        is Mode.Linked -> LinkState.SettingUp(step)
        is Mode.Ready -> LinkState.Ready(meter, wallNow.minusMillis(now - since))
        is Mode.Backoff -> LinkState.Recovering(error, attempt, wallNow.plusMillis(until - now))
        Mode.BluetoothOff -> LinkState.Blocked(LinkError.BluetoothOff)
        Mode.BondBroken -> LinkState.Blocked(LinkError.BondLost)
        Mode.PermissionMissing -> LinkState.Blocked(LinkError.PermissionMissing)
    }
