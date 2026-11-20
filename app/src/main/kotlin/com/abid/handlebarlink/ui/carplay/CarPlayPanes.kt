package com.abid.handlebarlink.ui.carplay

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.abid.handlebarlink.ui.frame.HeroPanes

/**
 * The tab on its side (docs/PLAN.md "Landscape layout"): the hero's state (dial, tag, title, detail) in the first
 * pane, its actions and the status list in the second ([HeroPanes]).
 */
@Composable
internal fun CarPlayPanes(
    state: CarPlayUiState,
    onAction: (TabAction) -> Unit,
    onIphone: () -> Unit,
    onScreen: () -> Unit,
    contentPadding: PaddingValues,
) {
    HeroPanes(
        contentPadding,
        hero = { HeroState(state.hero) },
        side = {
            HeroActions(state.hero, onAction, Modifier)
            StatusList(state, onIphone, onScreen)
        },
    )
}
