package com.abid.handlebarlink.ui.handlebar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.HANDLEBAR_DEFAULTS
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

@Preview(name = "Handlebar · Night", widthDp = 411, heightDp = 1400)
@Composable
private fun HandlebarNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) { HandlebarScreen(previewHandlebar(CallSituation.RIDING), {}, {}, {}, {}) }
    }
}

@Preview(name = "Handlebar ringing · Night", widthDp = 411, heightDp = 1450)
@Composable
private fun HandlebarRingingPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(
            Modifier.fillMaxSize(),
        ) { HandlebarScreen(previewHandlebar(CallSituation.RINGING), {}, {}, {}, {}) }
    }
}

@Preview(name = "Handlebar ringing large text · Night", widthDp = 400, heightDp = 1600, fontScale = 1.5f)
@Composable
private fun HandlebarLargeTextPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(
            Modifier.fillMaxSize(),
        ) { HandlebarScreen(previewHandlebar(CallSituation.RINGING), {}, {}, {}, {}) }
    }
}

@Preview(name = "Handlebar on a call · Night", widthDp = 411, heightDp = 1470)
@Composable
private fun HandlebarOnCallPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(
            Modifier.fillMaxSize(),
        ) { HandlebarScreen(previewHandlebar(CallSituation.ON_CALL), {}, {}, {}, {}) }
    }
}

@Preview(name = "Handlebar on a call · Bento", widthDp = 411, heightDp = 1470)
@Composable
private fun HandlebarBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) {
        HlBackground(
            Modifier.fillMaxSize(),
        ) { HandlebarScreen(previewHandlebar(CallSituation.ON_CALL), {}, {}, {}, {}) }
    }
}

@Preview(name = "Handlebar picker · Night", widthDp = 411, heightDp = 860)
@Composable
private fun HandlebarPickerNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) { HandlebarPicker(previewPicker(), {}, {}) }
}

@Preview(name = "Handlebar picker · Bento", widthDp = 411, heightDp = 860)
@Composable
private fun HandlebarPickerBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) { HandlebarPicker(previewPicker(), {}, {}) }
}

@Preview(name = "Handlebar picker riding · Night", widthDp = 411, heightDp = 1600)
@Composable
private fun HandlebarPickerRidingPreview() {
    HlTheme(HlThemeVariant.NIGHT) { HandlebarPicker(previewPicker(CallSituation.RIDING), {}, {}) }
}

@Preview(name = "Handlebar card · Night", widthDp = 411, heightDp = 320)
@Composable
private fun HandlebarCardPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(
            Modifier.fillMaxSize(),
        ) {
            HandlebarCard(
                HANDLEBAR_DEFAULTS.getValue(CallSituation.RIDING),
                {},
                Modifier.padding(HlTheme.spacing.s16),
            )
        }
    }
}
