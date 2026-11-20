package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/**
 * Paper "CarPlay — flow v2": the hero's state tags (brand, danger, quiet, each with its dot), the status list's rows
 * with icon tiles, and the switch on and off.
 */
@Composable
internal fun Switches() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
        HlTag("Connected", tone = HlTagTone.BRAND, dot = true)
        HlTag("Needs you", tone = HlTagTone.DANGER, dot = true)
        HlTag("Ready", tone = HlTagTone.QUIET, dot = true)
    }
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
            "Show presses",
            checked = true,
            onCheckedChange = {},
            detail = "A banner over CarPlay",
            container = Color.Transparent,
            icon = HlIcons.CarPlay,
        )
    }
    HlSwitchRow("Show presses", checked = false, onCheckedChange = {}, detail = "A banner over CarPlay")
}
