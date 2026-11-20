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
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.frame.pageTop

/**
 * The tab on its side (docs/PLAN.md "Landscape layout"): the hero's state (dial, tag, title, detail) in the first
 * pane, its actions and the status list in the second; each pane centred in the height and scrolling on its own when
 * it runs out of it, below the status bar and clear of the rail.
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
    // The navigation bar's room goes inside each pane, so the list scrolls to the screen's edge.
    val bottom = contentPadding.calculateBottomPadding() + spacing.s16
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
                    .padding(top = spacing.s16, bottom = bottom),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.s12, Alignment.CenterVertically),
            ) {
                HeroState(state.hero)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(top = spacing.s16, bottom = bottom),
                verticalArrangement = Arrangement.spacedBy(spacing.s16, Alignment.CenterVertically),
            ) {
                HeroActions(state.hero, onAction, Modifier)
                StatusList(state, onIphone, onScreen)
            }
        }
    }
}
