package com.abid.handlebarlink.ui.frame

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.Backdrop
import com.abid.handlebarlink.core.designsystem.component.HlAppHeader
import com.abid.handlebarlink.core.designsystem.component.HlAppHeaderDefaults
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.home.LinkStatus
import com.abid.handlebarlink.ui.home.pillText

/**
 * The app header on the tab pages (docs/PLAN.md "App header"): the wordmark, the bike link's pill ([link], the only
 * place the bike link's state shows) and Settings, over the status bar and the mirrored sides like the pages
 * ([pageTop]). Upright it is centred in the content column like the tab bar; on its side ([WindowLayout.PANES]) it is
 * one row from the pages' start margin to their end beside the rail ([railRoom], the pages' end padding).
 */
@Composable
fun TabHeader(
    link: LinkStatus,
    lifted: Boolean,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    railRoom: Dp? = null,
    backdrop: Backdrop? = null,
) {
    // The header keeps its own 16 dp end margin; beside the rail that margin is already part of the rail's room.
    val end = railRoom?.let { (it - HlTheme.spacing.s16).coerceAtLeast(0.dp) } ?: 0.dp
    HlAppHeader(
        wordmark = stringResource(R.string.bike_model),
        status = stringResource(pillText(link)),
        statusOn = link == LinkStatus.CONNECTED,
        actionIcon = HlIcons.Settings,
        actionLabel = stringResource(R.string.settings),
        onAction = onSettings,
        lifted = lifted,
        modifier = modifier,
        compact = railRoom != null,
        windowInsets = WindowInsets.pageTop,
        contentPadding = PaddingValues(end = end),
        backdrop = backdrop,
    )
}

/**
 * The app header pinned over the tab pages (docs/PLAN.md "App header"), the tab bar's pair: it slides away upwards on
 * full pages (Settings, [visible] false) and back on tab pages, and stays with the page under a sheet or dialog.
 * [lifted] is read in a derived state, so scrolling recomposes the header only when it lifts or settles; on a new
 * [page] it starts in that page's state, without fading from the last one's.
 */
@Composable
fun BoxScope.FloatingHeader(
    visible: Boolean,
    page: String,
    link: LinkStatus,
    lifted: () -> Boolean,
    onSettings: () -> Unit,
    railRoom: Dp?,
    backdrop: Backdrop,
) {
    val motion = HlTheme.motion
    val current = rememberUpdatedState(lifted)
    val isLifted by remember { derivedStateOf { current.value() } }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = slideInVertically(motion.layout(IntOffset.VisibilityThreshold)) { -it } + fadeIn(motion.fade()),
        exit = slideOutVertically(motion.layout(IntOffset.VisibilityThreshold)) { -it } + fadeOut(motion.fade()),
    ) {
        // A new tab starts from its own state: no fade of the last tab's glass over a tab at its top.
        key(page) { TabHeader(link, isLifted, onSettings, railRoom = railRoom, backdrop = backdrop) }
    }
}

/**
 * The rail's room beside a tab page on its side ([railSpace]): the rail, its margin to the edge and a gap as wide
 * as the content's start margin (the mirrored side + 16 dp).
 */
@Composable
fun currentRailRoom(): Dp {
    val side = WindowInsets.pageSides.asPaddingValues().calculateLeftPadding(LayoutDirection.Ltr)
    return railSpace(side, HlTheme.spacing.s16, HlTheme.sizes.tabRail)
}

/**
 * What a tab page leaves free around its content: the app header's band at the top (the same on every tab), and the
 * floating tab bar's room below upright, or the rail's room ([railRoom]) at the end on its side ([panes]), with the
 * navigation bar.
 */
@Composable
fun tabPageRoom(
    panes: Boolean,
    railRoom: Dp,
): PaddingValues {
    val spacing = HlTheme.spacing
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val header = HlAppHeaderDefaults.height(compact = panes)
    return if (panes) {
        // On its side, room for the rail on the end edge and the navigation bar below. Pages pad both sides by the
        // mirrored side (WindowInsets.pageTop) and set their content 16 dp in, so the content's margin to the screen's
        // start, its gap to the rail and the rail's margin to the end are one width: side + 16 dp (owner,
        // 2026-11-20: "the right side padding is totally wrong").
        PaddingValues(top = header, end = railRoom, bottom = navigationBar)
    } else {
        // Room for the header and the floating bar: its height, its 20 dp gap and the navigation bar.
        PaddingValues(top = header, bottom = HlTheme.sizes.tabBar + spacing.s20 * 2 + navigationBar)
    }
}

/**
 * This entry with its page named around its content ([LocalHeaderPage]), so the page's scrollers
 * ([rememberPageScrollState]) lift the header for this page only.
 */
fun NavEntry<NavKey>.forPage(page: NavKey): NavEntry<NavKey> =
    NavEntry(page, contentKey, metadata) { _: NavKey ->
        CompositionLocalProvider(LocalHeaderPage provides page.toString()) { this@forPage.Content() }
    }
