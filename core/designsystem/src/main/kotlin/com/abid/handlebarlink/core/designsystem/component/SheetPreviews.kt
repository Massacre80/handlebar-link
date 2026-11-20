package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

// The add-device sheet ("Flow · Add device — what is it?") in both looks.

@Composable
internal fun Sheet() {
    HlSheet {
        HlHeader(HlIcons.Bluetooth, "EJEAS V6 Pro+", "Paired · looks like an audio headset")
        Column(verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
            HlSectionLabel("What is it?")
            HlOptionRow(
                HlIcons.Intercom,
                "Intercom",
                "Helmet unit for calls, music and directions",
                selected = true,
                onClick = {},
            )
            HlOptionRow(
                HlIcons.Earbuds,
                "Headset or earbuds",
                "Audio only, no rider-to-rider talk",
                selected = false,
                onClick = {},
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
            HlSectionLabel("Name")
            HlTextField(rememberTextFieldState("Helmet intercom"), "Shown on Home")
        }
        Column(verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
            HlButton("Save device", onClick = {})
            HlSecondaryButton("Cancel", onClick = {})
        }
    }
}

@Preview(name = "Night · sheet", widthDp = 411)
@Composable
private fun NightSheetPreview() {
    HlTheme(HlThemeVariant.NIGHT) { Sheet() }
}

@Preview(name = "Bento · sheet", widthDp = 411)
@Composable
private fun BentoSheetPreview() {
    HlTheme(HlThemeVariant.BENTO) { Sheet() }
}
