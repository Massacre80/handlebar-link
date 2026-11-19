package com.abid.handlebarlink.ui.carplay

import androidx.annotation.StringRes
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.carplay.usedIphone

// The CarPlay tab's parts beside the hero: the error titles and fixes, the four connection steps, and the status
// list's iPhone and Link rows.

/**
 * The hero's title for an [error] only the rider can fix: what is wrong, with the fix on the button (the status line's
 * "Turn on Wi-Fi" would repeat the button).
 */
@StringRes
internal fun heroTitle(error: CarPlayError): Int =
    when (error) {
        CarPlayError.WifiOff -> R.string.carplay_tab_wifi_off_title
        is CarPlayError.PermissionMissing -> R.string.carplay_tab_permission_title
        else -> errorText(error)
    }

/** The one action that fixes [error], if the rider can do it from here. */
internal fun fixFor(error: CarPlayError): TabAction? =
    when (error) {
        CarPlayError.WifiOff -> TabAction.TURN_ON_WIFI
        is CarPlayError.PermissionMissing -> TabAction.ALLOW
        is CarPlayError.BluetoothUnavailable -> TabAction.TURN_ON_BLUETOOTH
        else -> null
    }

/**
 * The engine's seven steps as the rider's four (Paper "Tab 2": "Step N of 4"): calling the iPhone over Bluetooth,
 * the CarPlay check (identification and MFi authentication), the iPhone joining the Wi-Fi Direct group, and the
 * AirPlay pairing that secures the link.
 */
internal fun connectPhase(step: ConnectStep): Pair<Int, Int> =
    when (step) {
        ConnectStep.LOADING_IDENTITY, ConnectStep.PREPARING, ConnectStep.DIALING_PHONE -> {
            1 to
                R.string.carplay_phase_calling
        }

        ConnectStep.IDENTIFYING, ConnectStep.AUTHENTICATING -> {
            2 to R.string.carplay_phase_checking
        }

        ConnectStep.WAITING_FOR_PHONE -> {
            WIFI_PHASE to R.string.carplay_phase_wifi
        }

        ConnectStep.PAIRING -> {
            CONNECT_PHASES to R.string.carplay_phase_securing
        }
    }

/** The iPhone row: the iPhone CarPlay uses, connected or paired; none yet; or none chosen. */
internal fun iphoneRow(
    status: CarPlayStatus,
    iphones: List<PairedIphone>?,
    chosen: String?,
): IphoneRow {
    val used = iphones?.let { usedIphone(it, chosen) }
    val connected = status is CarPlayStatus.Engine && status.state is CarPlayState.Connected
    return when {
        iphones == null -> {
            IphoneRow(
                null,
                R.string.carplay_iphone_unknown,
                R.string.carplay_iphone_unknown_detail,
                false,
            )
        }

        iphones.isEmpty() -> {
            IphoneRow(null, R.string.carplay_iphone_add, R.string.carplay_iphone_none_detail, false)
        }

        used == null -> {
            IphoneRow(null, R.string.carplay_iphone_choose, R.string.carplay_iphone_choose_detail, true)
        }

        connected -> {
            IphoneRow(used.name, R.string.carplay_iphone_connected, R.string.carplay_iphone_used_detail, true)
        }

        else -> {
            IphoneRow(used.name, R.string.carplay_iphone_paired, R.string.carplay_iphone_used_detail, true)
        }
    }
}

/**
 * The Link row from what the engine reports: Bluetooth while the iPhone is called and checked, Wi-Fi Direct with
 * Bluetooth from the join on. Link quality and the Wi-Fi Direct channel are not in CarPlayState, so they are not shown
 * (no guessing).
 */
internal fun linkRow(status: CarPlayStatus): LinkRow {
    val state = (status as? CarPlayStatus.Engine)?.state
    return when {
        state is CarPlayState.Connected -> {
            LinkRow(R.string.carplay_link_connected, R.string.carplay_link_both, live = true)
        }

        state is CarPlayState.Connecting && connectPhase(state.step).first >= WIFI_PHASE -> {
            LinkRow(R.string.carplay_link_connecting, R.string.carplay_link_both, live = false)
        }

        state is CarPlayState.Connecting || state == CarPlayState.Idle -> {
            LinkRow(R.string.carplay_link_connecting, R.string.carplay_link_bluetooth, live = false)
        }

        else -> {
            LinkRow(R.string.carplay_link_off, R.string.carplay_link_idle, live = false)
        }
    }
}

/** The first step that uses Wi-Fi Direct. */
private const val WIFI_PHASE = 3
