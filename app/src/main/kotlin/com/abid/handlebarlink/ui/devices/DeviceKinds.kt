package com.abid.handlebarlink.ui.devices

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.devices.DeviceKind

/** A kind's icon: the sheet's, the list's and Home's tile. */
@DrawableRes
fun kindIcon(kind: DeviceKind): Int =
    when (kind) {
        DeviceKind.INTERCOM -> HlIcons.Intercom
        DeviceKind.HEADSET -> HlIcons.Earbuds
        DeviceKind.OTHER -> HlIcons.Bluetooth
    }

/** A kind's name. */
@StringRes
fun kindName(kind: DeviceKind): Int =
    when (kind) {
        DeviceKind.INTERCOM -> R.string.device_kind_intercom
        DeviceKind.HEADSET -> R.string.device_kind_headset
        DeviceKind.OTHER -> R.string.device_kind_other
    }
