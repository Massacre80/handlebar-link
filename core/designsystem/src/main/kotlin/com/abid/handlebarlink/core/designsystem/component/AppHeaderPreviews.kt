package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/**
 * The app header ("Component · App header"): at rest, lifted over a card that has scrolled under it (the glass over
 * the backdrop), and the one-row header used on its side.
 */
@Composable
internal fun AppHeaders() {
    AppHeaderSample(lifted = false, compact = false)
    AppHeaderSample(lifted = true, compact = false)
    AppHeaderSample(lifted = false, compact = true)
}

@Composable
private fun AppHeaderSample(
    lifted: Boolean,
    compact: Boolean,
) {
    val backdrop = rememberBackdrop()
    Box(Modifier.fillMaxWidth()) {
        // What scrolled under it: a card, recorded as the glass's backdrop with its ground.
        HlBackground(Modifier.backdropSource(backdrop)) {
            Column(Modifier.fillMaxWidth().padding(top = if (lifted) HlTheme.spacing.s24 else HEADER_ROOM)) {
                HlCard(Modifier.fillMaxWidth().padding(horizontal = HlTheme.spacing.s16)) {
                    HlText(
                        "Handlebar controls",
                        HlTheme.typography.lead,
                        HlTheme.colors.ink,
                        Modifier.padding(HlTheme.spacing.s16),
                    )
                }
            }
        }
        HlAppHeader(
            wordmark = "CB350RS",
            status = "Connected",
            statusOn = true,
            actionIcon = HlIcons.Settings,
            actionLabel = "Settings",
            onAction = {},
            lifted = lifted,
            compact = compact,
            backdrop = backdrop,
        )
    }
}

// At rest the card sits below the header (its 78 dp and the page gap), clear of it.
private val HEADER_ROOM = 96.dp

@Preview(name = "Night · app header", widthDp = 411, heightDp = 480)
@Composable
private fun NightAppHeaderPreview() {
    Gallery(HlThemeVariant.NIGHT) { AppHeaders() }
}

@Preview(name = "Bento · app header", widthDp = 411, heightDp = 480)
@Composable
private fun BentoAppHeaderPreview() {
    Gallery(HlThemeVariant.BENTO) { AppHeaders() }
}
