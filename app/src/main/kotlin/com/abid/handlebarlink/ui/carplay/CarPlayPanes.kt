package com.abid.handlebarlink.ui.carplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.frame.pageTop

/**
 * The tab on its side (Paper "BikePlay — flow", Tab 1L–9L; docs/PLAN.md "Landscape layout"): the meter in the first
 * pane, centred a little above the middle (56 dp under it); the buttons from the top of the second pane, the status
 * list 14 dp under them with 12 dp rows. Each pane scrolls on its own, below the status bar and clear of the rail
 * ([contentPadding], the navigation bar inside each pane).
 */
@Composable
internal fun CarPlayPanes(
    state: CarPlayUiState,
    onAction: (TabAction) -> Unit,
    onIphone: () -> Unit,
    onScreen: () -> Unit,
    contentPadding: PaddingValues,
) {
    val spacing = HlTheme.spacing
    val bottom = contentPadding.calculateBottomPadding()
    Row(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.pageTop)
            .padding(end = contentPadding.calculateEndPadding(LocalLayoutDirection.current))
            .padding(start = spacing.s16),
        horizontalArrangement = Arrangement.spacedBy(spacing.s16),
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    // Paper "Tab 1L": the hero pane's content sits 56 dp above the pane's bottom, centred above it.
                    .padding(top = spacing.s16, bottom = bottom + spacing.s28 * 2),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BikePlayHero(state, pane = true)
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(top = spacing.s16, bottom = bottom + spacing.s8),
        ) {
            val buttons = tabButtons(state.card)
            TabButtons(buttons, onAction, Modifier.fillMaxWidth())
            StatusList(
                state,
                onIphone,
                onScreen,
                Modifier.padding(top = if (buttons.isEmpty()) 0.dp else spacing.groupGap),
                compact = true,
            )
        }
    }
}
