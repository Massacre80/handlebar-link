package com.abid.handlebarlink.ui.frame

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.core.designsystem.component.HlAppHeaderDefaults
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlTab
import com.abid.handlebarlink.core.designsystem.component.HlTabBar
import com.abid.handlebarlink.core.designsystem.component.HlTabRail
import com.abid.handlebarlink.core.designsystem.component.backdropSource
import com.abid.handlebarlink.core.designsystem.component.rememberBackdrop
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.home.LinkStatus
import com.abid.handlebarlink.ui.navigation.TOP_LEVEL_DESTINATIONS

/**
 * A tab page framed as AppRoot frames it, for previews and screenshot tests (no Hilt): the page with the room for the
 * app header and the floating tab bar (the rail on its side, [LocalWindowLayout]), the header over it ([link],
 * [lifted]: its glass over the page) and the bar on the [selected] tab, drawn plain as the other screenshots draw it.
 * Previews draw no system bars or cutout, so the insets are zero.
 */
@Composable
fun PreviewTabFrame(
    selected: Int,
    link: LinkStatus = LinkStatus.CONNECTED,
    lifted: Boolean = false,
    page: @Composable (PaddingValues) -> Unit,
) {
    val spacing = HlTheme.spacing
    val sizes = HlTheme.sizes
    val panes = LocalWindowLayout.current == WindowLayout.PANES
    val railRoom = railSpace(0.dp, spacing.s16, sizes.tabRail)
    val header = HlAppHeaderDefaults.height(compact = panes)
    val tabs = TOP_LEVEL_DESTINATIONS.map { HlTab(it.icon, stringResource(it.label)) }
    val backdrop = rememberBackdrop()
    Box(Modifier.fillMaxSize()) {
        HlBackground(Modifier.fillMaxSize().backdropSource(backdrop)) {
            page(
                if (panes) {
                    PaddingValues(top = header, end = railRoom)
                } else {
                    PaddingValues(top = header, bottom = sizes.tabBar + spacing.s20 * 2)
                },
            )
        }
        TabHeader(
            link,
            lifted,
            onSettings = {},
            modifier = Modifier.align(Alignment.TopCenter),
            railRoom = railRoom.takeIf { panes },
            backdrop = backdrop,
        )
        if (panes) {
            HlTabRail(
                tabs,
                selected,
                onSelect = {},
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = spacing.s16),
            )
        } else {
            HlTabBar(
                tabs,
                selected,
                onSelect = {},
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = spacing.s16, end = spacing.s16, bottom = spacing.s20)
                        .widthIn(max = sizes.contentMax),
            )
        }
    }
}
