package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/** The landscape tab rail on the second tab, beside a page edge. */
@Composable
internal fun Rail() {
    HlTabRail(PreviewTabs, selected = 1, onSelect = {})
}

@Preview(name = "Night · tab rail", widthDp = 160, heightDp = 340)
@Composable
private fun NightRailPreview() {
    Gallery(HlThemeVariant.NIGHT) { Rail() }
}

@Preview(name = "Bento · tab rail", widthDp = 160, heightDp = 340)
@Composable
private fun BentoRailPreview() {
    Gallery(HlThemeVariant.BENTO) { Rail() }
}
