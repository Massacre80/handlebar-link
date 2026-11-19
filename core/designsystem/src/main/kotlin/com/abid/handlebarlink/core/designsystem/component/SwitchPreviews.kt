package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Paper "CarPlay — flow v2" status list: rows with icon tiles, and the switch on and off. */
@Composable
internal fun Switches() {
    HlGroup {
        HlValueRow(
            "Abid's iPhone",
            "Connected",
            detail = "CarPlay connects to this iPhone",
            onClick = {},
            container = Color.Transparent,
            chevron = true,
            icon = HlIcons.Phone,
        )
        HlGroupDivider()
        HlValueRow("Link", "Connected", detail = "Wi-Fi Direct · 5 GHz ch 36 · Strong", icon = HlIcons.Wifi)
        HlGroupDivider()
        HlSwitchRow(
            "Handlebar Link CarPlay",
            checked = true,
            onCheckedChange = {},
            detail = "Beta · instead of CarLink",
            container = Color.Transparent,
            icon = HlIcons.CarPlay,
        )
    }
    HlSwitchRow("Handlebar Link CarPlay", checked = false, onCheckedChange = {}, detail = "Beta · instead of CarLink")
}
