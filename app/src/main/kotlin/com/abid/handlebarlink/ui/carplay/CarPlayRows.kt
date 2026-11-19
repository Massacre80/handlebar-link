package com.abid.handlebarlink.ui.carplay

import androidx.annotation.StringRes
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.carplay.engine.android.video.CarPlayVideoStats
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
        CarPlayError.WifiOff -> {
            R.string.carplay_tab_wifi_off_title
        }

        is CarPlayError.PermissionMissing -> {
            carPlayGrant(error.permission).title
        }

        else -> {
            errorText(error)
        }
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
 * Bluetooth from the join on; connected, the Wi-Fi Direct band and channel ([CarPlayState.Connected.wifiChannel]) and
 * the link's quality from the picture's last window ([linkQuality] of [video]).
 */
internal fun linkRow(
    status: CarPlayStatus,
    video: CarPlayVideoStats? = null,
): LinkRow {
    val state = (status as? CarPlayStatus.Engine)?.state
    return when {
        state is CarPlayState.Connected -> {
            val channel = state.wifiChannel
            LinkRow(
                R.string.carplay_link_connected,
                when {
                    channel == null -> R.string.carplay_link_both
                    channel <= LAST_2_4_GHZ_CHANNEL -> R.string.carplay_link_wifi_2_4
                    else -> R.string.carplay_link_wifi_5
                },
                live = true,
                channel = channel,
                quality = linkQuality(video),
            )
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

/**
 * How well the iPhone's picture comes through, from one window of its numbers (5 s; [CarPlayVideoStats]); null
 * without a window that received anything (no verdict without data). A wireless link that struggles shows first as
 * pictures lost on the way to the decoder (the queue drops them when they arrive in bursts or too late) and as a
 * decoder that falls behind:
 * - [LinkQuality.WEAK]: at least 5 % of the pictures lost, a decoder replaced after an error, or decoding slower than
 *   [WEAK_DECODE_MILLIS] at the 90th percentile;
 * - [LinkQuality.FAIR]: any picture lost, or decoding slower than two frames at 60 fps ([FAIR_DECODE_MILLIS]);
 * - [LinkQuality.STRONG]: nothing lost and decoding within two frames.
 */
internal fun linkQuality(video: CarPlayVideoStats?): LinkQuality? {
    if (video == null || video.received <= 0) return null
    val decode = video.decodeP90Millis ?: 0.0
    val lostShare = video.dropped.toDouble() / video.received
    return when {
        video.recoveries > 0 || lostShare >= WEAK_LOSS_SHARE || decode > WEAK_DECODE_MILLIS -> LinkQuality.WEAK
        video.dropped > 0 || decode > FAIR_DECODE_MILLIS -> LinkQuality.FAIR
        else -> LinkQuality.STRONG
    }
}

/** Channels 1-14 are 2.4 GHz; the Android port creates groups on 2.4 or 5 GHz only (GroupFacts.channel). */
private const val LAST_2_4_GHZ_CHANNEL = 14
private const val WEAK_LOSS_SHARE = 0.05

/** Two frames at 60 fps. */
private const val FAIR_DECODE_MILLIS = 34.0

/** Six frames at 60 fps: the picture visibly lags the touch. */
private const val WEAK_DECODE_MILLIS = 100.0
