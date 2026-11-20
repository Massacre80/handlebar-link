package com.abid.handlebarlink.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.frame.pageTop
import com.abid.handlebarlink.ui.handlebar.HandlebarCard
import com.abid.handlebarlink.ui.setup.FittedBike

/**
 * Home on its side, Night (docs/PLAN.md "Landscape layout"): the bike pane (wordmark, link pill and settings in
 * their own band, then the whole bike on its ground, fitted to the height left) and the cards pane (the device
 * BikePlay card, then the device tile and the Add device slot side by side as upright, the handlebar card), which
 * scrolls on its own
 * below the status bar. The tiles are not over the bike here, so they are plain glass like the other cards.
 */
@Composable
internal fun HomeNightPanes(
    state: HomeUiState,
    actions: HomeActions,
    contentPadding: PaddingValues,
) {
    val spacing = HlTheme.spacing
    HomePanes(
        contentPadding,
        bike = {
            NightHeader(state, actions.onSettings, Modifier.fillMaxWidth())
            FittedBike(Modifier.weight(1f).fillMaxWidth().padding(top = spacing.s8))
        },
        cards = {
            // Paper "BikePlay card B — Landscape Home (Night)": the BikePlay card first, then the devices, the
            // Handlebar card.
            HomeBikePlayCard(state.bikePlay, actions)
            if (state.rideData) NightGauges(Modifier.fillMaxWidth())
            NightDevices(state.device, backdrop = null, actions.onAddDevice, actions.onDevice, Modifier)
            HandlebarCard(state.handlebar, actions.onHandlebar)
        },
        gap = spacing.s10,
    )
}

/**
 * Home on its side, Bento: the link pill and settings above the bike card, which fills the pane's height (the bike
 * inside sized to the room under its name), and the cards pane: BikePlay first as on Night, then stats, devices,
 * handlebar.
 */
@Composable
internal fun HomeBentoPanes(
    state: HomeUiState,
    actions: HomeActions,
    contentPadding: PaddingValues,
) {
    val spacing = HlTheme.spacing
    HomePanes(
        contentPadding,
        bike = {
            BentoTopRow(state.link, actions.onSettings)
            BentoBikeCard(state.rideData, Modifier.weight(1f).fillMaxWidth(), fitted = true)
        },
        cards = {
            HomeBikePlayCard(state.bikePlay, actions)
            if (state.rideData) BentoStats()
            BentoDevices(state.device, actions)
            HandlebarCard(state.handlebar, actions.onHandlebar)
        },
        gap = spacing.s8,
    )
}

/**
 * The two panes inside the page's frame (status bar, mirrored sides, then [contentPadding]: the
 * rail's room, and the navigation bar inside each pane). The panes start 16 dp in from the frame, 16 dp apart; [bike]
 * fills the pane's height, [cards] scroll, [gap] apart. Neither pane draws under the status bar or the rail.
 */
@Composable
private fun HomePanes(
    contentPadding: PaddingValues,
    bike: @Composable ColumnScope.() -> Unit,
    cards: @Composable ColumnScope.() -> Unit,
    gap: Dp,
) {
    val spacing = HlTheme.spacing
    // The bottom room (the navigation bar) goes inside each pane, so the cards scroll to the screen's edge and
    // their shadows are not cut off above the navigation bar. 8 dp above and below, like the tab rail.
    val bottom = contentPadding.calculateBottomPadding() + spacing.s8
    Row(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.pageTop)
            .padding(end = contentPadding.calculateEndPadding(LocalLayoutDirection.current))
            .padding(start = spacing.s16),
        horizontalArrangement = Arrangement.spacedBy(spacing.s16),
    ) {
        Pane(1f, Modifier.padding(bottom = bottom), content = bike)
        Pane(
            CARDS_WEIGHT,
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = spacing.s8, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(gap),
            content = cards,
        )
    }
}

@Composable
private fun RowScope.Pane(
    weight: Float,
    modifier: Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.weight(weight).fillMaxHeight().then(modifier),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * The cards pane is 1.6 times the bike's (Paper "BikePlay card B — Landscape Home (Night)": `flex-grow: 1.6`): the bike
 * is fitted to whatever its pane leaves, while the BikePlay card's meter and the cards' names need the width.
 */
private const val CARDS_WEIGHT = 1.6f
