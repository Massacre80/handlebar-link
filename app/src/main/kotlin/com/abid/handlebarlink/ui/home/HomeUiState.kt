package com.abid.handlebarlink.ui.home

import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkState

/** What the status pill says about the bike link. */
enum class LinkStatus {
    /** No meter chosen yet. */
    NO_BIKE,

    /** A bike is chosen but no ride runs. */
    NOT_RIDING,

    /** Armed: waiting for the meter (ignition off). */
    LOOKING,

    /** Connecting or setting the meter up. */
    CONNECTING,

    /** Presses reach CarPlay. */
    CONNECTED,

    /** A setup failed; retrying by itself. */
    RECONNECTING,

    /** Bluetooth is switched off. */
    BLUETOOTH_OFF,

    /** The Nearby devices permission is missing. */
    NEEDS_PERMISSION,

    /** The pairing with the meter is gone. */
    NEEDS_PAIRING,
}

/** Home's state. Odometer, trip, service, intercom and CarPlay have no data source yet: empty states. */
data class HomeUiState(
    /** The pill. */
    val link: LinkStatus,
)

/** The pill for [state] of the link to the selected bike ([bikeSelected] false: none chosen). */
fun linkStatus(
    bikeSelected: Boolean,
    state: LinkState?,
): LinkStatus =
    when {
        !bikeSelected -> {
            LinkStatus.NO_BIKE
        }

        state == null -> {
            LinkStatus.NOT_RIDING
        }

        else -> {
            when (state) {
                LinkState.Stopped, LinkState.Idle -> {
                    LinkStatus.NOT_RIDING
                }

                LinkState.WaitingForBike -> {
                    LinkStatus.LOOKING
                }

                is LinkState.Connecting, is LinkState.SettingUp -> {
                    LinkStatus.CONNECTING
                }

                is LinkState.Ready -> {
                    LinkStatus.CONNECTED
                }

                is LinkState.Recovering -> {
                    LinkStatus.RECONNECTING
                }

                is LinkState.Blocked -> {
                    when (state.error) {
                        LinkError.BluetoothOff -> LinkStatus.BLUETOOTH_OFF
                        LinkError.PermissionMissing -> LinkStatus.NEEDS_PERMISSION
                        else -> LinkStatus.NEEDS_PAIRING
                    }
                }
            }
        }
    }
