package com.abid.handlebarlink.ui.carplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.frame.pageSides
import com.abid.handlebarlink.ui.frame.rememberPageScrollState

/**
 * The tab on its side (Paper "BikePlay — flow", Tab 1L–9L; docs/PLAN.md "Landscape layout", "App header"): the meter
 * in the first pane, centred in the height under the app header; the buttons 8 dp under the header in the second pane,
 * the status list 14 dp under them with 12 dp rows. Each pane scrolls on its own under the header and the status bar,
 * clear of the rail ([contentPadding]: the header's room, the rail's, the navigation bar inside each pane).
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
    val top = contentPadding.calculateTopPadding() + spacing.headerGap
    val statusBar = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
    Row(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.pageSides)
            .padding(end = contentPadding.calculateEndPadding(LocalLayoutDirection.current))
            .padding(start = spacing.s16),
        horizontalArrangement = Arrangement.spacedBy(spacing.s16),
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberPageScrollState())
                    .heightIn(min = maxHeight)
                    .windowInsetsPadding(statusBar)
                    // Centred between the header and the navigation bar (the header now takes the room the 56 dp
                    // under Paper "Tab 1L"'s meter balanced).
                    .padding(top = top, bottom = bottom + spacing.s16),
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
                .verticalScroll(rememberPageScrollState())
                .windowInsetsPadding(statusBar)
                .padding(top = top, bottom = bottom + spacing.s8),
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
