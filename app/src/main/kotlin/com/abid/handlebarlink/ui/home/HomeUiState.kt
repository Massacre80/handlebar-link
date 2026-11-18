package com.abid.handlebarlink.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkState
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.HANDLEBAR_DEFAULTS
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.devices.DeviceKind
import com.abid.handlebarlink.ui.devices.kindName

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
    /** The device tile: the intercom if one is added, else the first added; null for the empty tile. */
    val device: HomeDevice? = null,
    /**
     * The meter reports speed and odometer: shows the service and trip gauges. False on the CB350 (it sends
     * neither, SAB_PROTOCOL.md), so the gauges stay hidden until a meter sends them.
     */
    val rideData: Boolean = false,
    /** What each handlebar press does (the Handlebar card). */
    val handlebar: Map<HandlebarSwitch, RiderAction> = HANDLEBAR_DEFAULTS.getValue(CallSituation.RIDING),
)

/** An added device on Home. [battery] in percent, null until it reports one. */
data class HomeDevice(
    val name: String,
    val model: String,
    val kind: DeviceKind,
    val connected: Boolean,
    val battery: Int?,
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

/**
 * Night's tile line (Paper "Home 3"): the model while connected; else why there is nothing live, not set up
 * or not connected to this phone (it may be on another).
 */
@Composable
fun nightDeviceDetail(device: HomeDevice?): String =
    when {
        device == null -> stringResource(R.string.intercom_none_detail)

        // The model under the rider's name; "Connected" when the name is the model (nothing to add).
        device.connected -> if (device.model != device.name) device.model else stringResource(R.string.device_connected)

        else -> stringResource(R.string.device_not_connected)
    }

/** Bento's row line (Paper "Home 4"): kind and battery while connected ("Intercom · 72%"), else as Night. */
@Composable
fun bentoDeviceDetail(device: HomeDevice?): String {
    if (device == null || !device.connected) return nightDeviceDetail(device)
    val kind = stringResource(kindName(device.kind))
    return device.battery?.let {
        stringResource(R.string.device_state_both, kind, stringResource(R.string.device_battery, it))
    }
        ?: kind
}
