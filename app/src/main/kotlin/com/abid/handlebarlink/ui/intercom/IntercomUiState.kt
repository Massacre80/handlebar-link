package com.abid.handlebarlink.ui.intercom

import com.abid.handlebarlink.controls.CARLINK_PACKAGES
import com.abid.handlebarlink.devices.DeviceKind
import com.abid.handlebarlink.devices.DeviceStatus
import com.abid.handlebarlink.devices.MediaKeys
import com.abid.handlebarlink.devices.MediaOutput
import com.abid.handlebarlink.devices.MediaRoute
import com.abid.handlebarlink.devices.PHONE
import com.abid.handlebarlink.devices.SavedDevice
import com.abid.handlebarlink.devices.primary

/** The Intercom page. */
data class IntercomUiState(
    /** The added devices, the primary first; empty when none is added. */
    val devices: List<IntercomDevice> = emptyList(),
    /** Where media plays now. */
    val music: Music = Music.Unknown,
    /** Where the intercom's play, pause and skip go. */
    val buttons: Buttons = Buttons.UNKNOWN,
    /** Where call audio goes: an added device, or the phone. */
    val calls: Calls = Calls.Phone,
    /** Where media can play, to choose from (Android 14+); empty where it cannot be chosen. */
    val outputs: List<Output> = emptyList(),
    /** The intercom maker's app is installed. */
    val vendorApp: Boolean = false,
    /** Reconnecting a disconnected device from the app. */
    val reconnect: Reconnecting = Reconnecting.Idle,
) {
    /** The device shown first: Home's tile, where calls go. */
    val primary: IntercomDevice? get() = devices.firstOrNull()
}

/** An added device with its live state. */
data class IntercomDevice(
    val address: String,
    val name: String,
    val model: String,
    val kind: DeviceKind,
    val connected: Boolean,
    /** Connected for calls (Headset profile). */
    val calls: Boolean,
    /** Connected for music (A2DP profile). */
    val music: Boolean,
    val battery: Int?,
)

/** A place music can play, named as the rider named the device when it is added. */
data class Output(
    val id: String,
    val name: String,
    /** The added device it is, if any. */
    val kind: DeviceKind?,
    val bluetooth: Boolean,
    val selected: Boolean,
)

/** Reconnecting a disconnected device from the page. */
sealed interface Reconnecting {
    /** Nothing asked. */
    data object Idle : Reconnecting

    /** Android's companion link is needed first: the consent to show for [address]. */
    data class Linking(
        val consent: android.content.IntentSender,
        val address: String,
    ) : Reconnecting

    /** Android is connecting [address]. */
    data class Connecting(
        val address: String,
    ) : Reconnecting

    /** This phone's Android does not let apps reconnect. */
    data object Unsupported : Reconnecting

    /** It did not connect (off, out of range, refused). */
    data object Failed : Reconnecting
}

/** Where call audio goes, as the rider chose (else the primary device). */
sealed interface Calls {
    /** The phone's earpiece or speaker. */
    data object Phone : Calls

    /** An added device. */
    data class Device(
        val address: String,
        val name: String,
    ) : Calls
}

/** Where media plays now. */
sealed interface Music {
    /** Android 12 cannot tell. */
    data object Unknown : Music

    /** On the primary device. */
    data object Primary : Music

    /** On another added device, by name. */
    data class Other(
        val name: String,
    ) : Music

    /** On a Bluetooth device that is not added. */
    data object OtherBluetooth : Music

    /** On the phone itself. */
    data object Phone : Music
}

/** Where the intercom's play, pause and skip go. */
enum class Buttons {
    /** Android 12, or no notification access: cannot tell. */
    UNKNOWN,

    /** To CarLink: they control CarPlay. */
    CARLINK,

    /** To another app: CarPlay misses them until CarLink plays again. */
    OTHER_APP,
}

/** What Android says about audio now: where media plays, where media keys go, where it could play. */
data class AudioNow(
    val route: MediaRoute,
    val keys: MediaKeys,
    val outputs: List<MediaOutput> = emptyList(),
)

/** The page from the saved devices, the chosen primary and Android's live state. */
fun intercomState(
    saved: List<SavedDevice>,
    chosen: Choices,
    statuses: Map<String, DeviceStatus>,
    audio: AudioNow,
    vendorApp: Boolean,
): IntercomUiState {
    val route = audio.route
    val keys = audio.keys
    val primary = saved.primary(chosen.primary)
    val devices =
        (listOfNotNull(primary) + saved.filter { it != primary }).map { device ->
            val status = statuses[device.address]
            IntercomDevice(
                address = device.address,
                name = device.name,
                model = device.model,
                kind = device.kind,
                connected = status?.connected == true,
                calls = status?.calls == true,
                music = status?.music == true,
                battery = status?.battery,
            )
        }
    val music = musicOf(route, saved, primary)
    val buttons =
        when (keys) {
            MediaKeys.Unknown -> Buttons.UNKNOWN
            is MediaKeys.App -> if (keys.packageName in CARLINK_PACKAGES) Buttons.CARLINK else Buttons.OTHER_APP
        }
    val calls =
        when (chosen.calls) {
            PHONE -> {
                Calls.Phone
            }

            else -> {
                (saved.firstOrNull { it.address == chosen.calls } ?: primary)?.let {
                    Calls.Device(
                        it.address,
                        it.name,
                    )
                }
            }
        } ?: Calls.Phone
    val outputs = outputsOf(audio.outputs, saved)
    return IntercomUiState(devices, music, buttons, calls, outputs, vendorApp)
}

/** The rider's choices: the primary device and where calls go (null: not chosen). */
data class Choices(
    val primary: String? = null,
    val calls: String? = null,
)

/** What the Intercom page can do; it holds no logic of its own. */
data class IntercomActions(
    val onAdd: () -> Unit,
    /** Opens "What is it?" for the device (name and kind). */
    val onEdit: (IntercomDevice) -> Unit,
    val onChoosePrimary: (String) -> Unit,
    /** Opens the chooser for where calls go. */
    val onCalls: () -> Unit,
    /** Opens the chooser for where music plays (Android 14+). */
    val onMusic: () -> Unit,
    /** Reconnects the device at the address. */
    val onReconnect: (String) -> Unit,
    val onVendorApp: () -> Unit,
    val onRemove: (IntercomDevice) -> Unit,
)

/** Where media plays, in the page's terms: the primary, another added device, another, the phone. */
private fun musicOf(
    route: MediaRoute,
    saved: List<SavedDevice>,
    primary: SavedDevice?,
): Music =
    when (route) {
        MediaRoute.Unknown -> {
            Music.Unknown
        }

        MediaRoute.Phone -> {
            Music.Phone
        }

        is MediaRoute.Bluetooth -> {
            when (val on = saved.firstOrNull { it.address == route.address }) {
                null -> Music.OtherBluetooth
                primary -> Music.Primary
                else -> Music.Other(on.name)
            }
        }
    }

/** The outputs to choose from; Telecom and MediaRouter name a device by its own name, an added one's model. */
private fun outputsOf(
    outputs: List<MediaOutput>,
    saved: List<SavedDevice>,
): List<Output> =
    outputs.map { output ->
        val device = saved.firstOrNull { output.bluetooth && it.model == output.name }
        Output(output.id, device?.name ?: output.name, device?.kind, output.bluetooth, output.selected)
    }
