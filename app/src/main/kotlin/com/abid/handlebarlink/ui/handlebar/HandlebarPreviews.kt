package com.abid.handlebarlink.ui.handlebar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.HANDLEBAR_DEFAULTS
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/** A remapped left press next to a free hold. */
private val PreviewMapping = HANDLEBAR_DEFAULTS + (HandlebarSwitch.LEFT to RiderAction.BATTERY)

@Preview(name = "Handlebar · Night", widthDp = 411, heightDp = 1000)
@Composable
private fun HandlebarNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) { HandlebarScreen(PreviewMapping, {}, {}, {}) }
    }
}

@Preview(name = "Handlebar · Bento", widthDp = 411, heightDp = 1000)
@Composable
private fun HandlebarBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) {
        HlBackground(Modifier.fillMaxSize()) { HandlebarScreen(PreviewMapping, {}, {}, {}) }
    }
}

@Preview(name = "Handlebar card · Night", widthDp = 411, heightDp = 320)
@Composable
private fun HandlebarCardPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(
            Modifier.fillMaxSize(),
        ) { HandlebarCard(HANDLEBAR_DEFAULTS, {}, Modifier.padding(HlTheme.spacing.s16)) }
    }
}
