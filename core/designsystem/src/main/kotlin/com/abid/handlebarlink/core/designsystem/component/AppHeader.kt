package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import com.abid.handlebarlink.core.designsystem.theme.HlSizes
import com.abid.handlebarlink.core.designsystem.theme.HlSpacing
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/**
 * The app header ("Component · App header"): the bike's [wordmark], the bike link's [status] pill ([statusOn] lights
 * its dot) and the [actionIcon] button (Settings), one on every tab page, pinned like the tab bar. Pages scroll under
 * it: at rest it is clear and sits on the page's ground; [lifted] (content is under it) it fades into the tab bar's
 * frosted glass over [backdrop] with a hairline at its foot (Material's "lift on scroll"; Compose's
 * `pinnedScrollBehavior`).
 *
 * The container runs edge to edge and draws under the status bar, so the glass is the status bar's only protection
 * while lifted; [windowInsets] (the status bar and the cutout's sides) and [contentPadding] (the tab rail's room on its
 * side) keep the content clear. Upright ([compact] false) Night stacks the wordmark over the pill, centred, Settings at
 * the end; Bento puts the pill at the start and Settings at the end (its bike card names the bike). On its side
 * ([compact]) both are one 48 dp row so the panes keep their height: Night the wordmark then the pill, Bento the pill.
 */
@Composable
public fun HlAppHeader(
    wordmark: String,
    status: String,
    statusOn: Boolean,
    @DrawableRes actionIcon: Int,
    actionLabel: String,
    onAction: () -> Unit,
    lifted: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    windowInsets: WindowInsets = WindowInsets(0),
    contentPadding: PaddingValues = PaddingValues(),
    backdrop: Backdrop? = null,
) {
    val lift by animateFloatAsState(if (lifted) 1f else 0f, HlTheme.motion.fade(), label = "header lift")
    Box(
        // Read first by TalkBack, before the page under it.
        modifier.fillMaxWidth().semantics {
            isTraversalGroup = true
            traversalIndex = -1f
        },
    ) {
        if (lift > 0f) HeaderGlass(backdrop, { lift }, Modifier.matchParentSize())
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(windowInsets)
                .padding(contentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            val night = HlTheme.variant == HlThemeVariant.NIGHT
            // Exactly the height the pages leave for it (HlAppHeaderDefaults.height), on every tab.
            val band = Modifier.height(HlAppHeaderDefaults.height(compact))
            val content =
                if (compact) band.fillMaxWidth() else band.widthIn(max = HlTheme.sizes.contentMax).fillMaxWidth()
            when {
                compact -> {
                    HeaderRow(wordmark.takeIf { night }, status, statusOn, content, HlTheme.spacing.s4) {
                        Action(actionIcon, actionLabel, onAction)
                    }
                }

                night -> {
                    StackedHeader(wordmark, status, statusOn, content) {
                        Action(actionIcon, actionLabel, onAction)
                    }
                }

                else -> {
                    HeaderRow(null, status, statusOn, content, HlTheme.spacing.s8) {
                        Action(actionIcon, actionLabel, onAction)
                    }
                }
            }
        }
    }
}

/**
 * Night upright (Paper "Home 3 — Night"): the wordmark 18 dp under the status bar, the pill 4 dp under it and 12 dp
 * below the pill; Settings 10 dp under the status bar with its circle 16 dp from the edge.
 */
@Composable
private fun StackedHeader(
    wordmark: String,
    status: String,
    statusOn: Boolean,
    modifier: Modifier,
    action: @Composable () -> Unit,
) {
    val spacing = HlTheme.spacing
    Box(modifier) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = spacing.s18, bottom = spacing.s12),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.s4),
        ) {
            HlText(wordmark, HlTheme.typography.wordmark, HlTheme.colors.ink, maxLines = 1)
            StatusPill(status, statusOn)
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = spacing.s8, end = actionEnd()),
        ) { action() }
    }
}

/**
 * One row (Bento upright, and both looks on their side): the [wordmark] when given, then the pill 10 dp after it,
 * Settings at the end; a 48 dp row with [vertical] above and below, 16 dp in from the content's edges.
 */
@Composable
private fun HeaderRow(
    wordmark: String?,
    status: String,
    statusOn: Boolean,
    modifier: Modifier,
    vertical: Dp,
    action: @Composable () -> Unit,
) {
    val spacing = HlTheme.spacing
    Row(
        modifier
            .padding(start = spacing.s16, end = actionEnd(), top = vertical, bottom = vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s10),
    ) {
        if (wordmark != null) HlText(wordmark, HlTheme.typography.wordmark, HlTheme.colors.ink, maxLines = 1)
        // The pill takes the room left before Settings, at its start; on a narrow window or at large text it gives way
        // to Settings (it never wraps).
        Box(Modifier.weight(1f)) { StatusPill(status, statusOn) }
        action()
    }
}

/**
 * How far the action's 48 dp touch box sits from the content's end: Night's 44 dp glass circle lines up with the
 * 16 dp margin; Bento's bare icon keeps its box at the margin (Paper "Home 4 — Bento").
 */
@Composable
private fun actionEnd(): Dp {
    val spacing = HlTheme.spacing
    return if (HlTheme.variant == HlThemeVariant.NIGHT) {
        spacing.s16 - (HlTheme.sizes.touchTarget - HlTheme.sizes.avatar) / 2
    } else {
        spacing.s16
    }
}

@Composable
private fun Action(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
) = HlIconButton(icon, label, onClick)

/**
 * The lifted header's glass: the tab bar's frosted glass ([backdrop] blurred and saturated under the look's glass
 * tint) and a hairline at its foot, faded by [alpha].
 */
@Composable
private fun HeaderGlass(
    backdrop: Backdrop?,
    alpha: () -> Float,
    modifier: Modifier,
) {
    val colors = HlTheme.colors
    val hairline = HlTheme.strokes.hairline
    Box(
        modifier
            // Clipped like the bar's pill: the blurred backdrop would otherwise spread below the header's foot.
            .graphicsLayer {
                this.alpha = alpha()
                clip = true
            }.then(backdrop?.let { Modifier.glassBlur(it) } ?: Modifier)
            .background(colors.glass)
            .drawBehind {
                val y = size.height - hairline.toPx() / 2
                drawLine(colors.glassLine, Offset(0f, y), Offset(size.width, y), hairline.toPx())
            },
    )
}

/** The app header's measures, for the pages that leave room for it. */
public object HlAppHeaderDefaults {
    /**
     * The header's height below the status bar ([compact]: on its side), the same on every tab: the room a tab page
     * leaves above its content before [HlSpacing.headerGap]. Night upright grows with the wordmark's line at large
     * text; the rows are the 48 dp touch target (or a taller wordmark) with their margins.
     */
    @Composable
    public fun height(compact: Boolean): Dp {
        val wordmarkLine =
            with(LocalDensity.current) {
                HlTheme.typography.wordmark.lineHeight
                    .toDp()
            }
        return appHeaderHeight(
            HlTheme.variant == HlThemeVariant.NIGHT,
            compact,
            wordmarkLine,
            HlTheme.spacing,
            HlTheme.sizes,
        )
    }
}

/**
 * The header's height below the status bar: Night upright 18 dp, the [wordmarkLine], 4 dp, the 26 dp pill and 12 dp
 * (78 dp at font 1); Bento upright a 48 dp row with 8 dp above and below (64 dp); on its side ([compact]) one row as
 * tall as the touch target, the wordmark (Night) or the pill, with 4 dp above and below (56 dp at font 1).
 */
internal fun appHeaderHeight(
    night: Boolean,
    compact: Boolean,
    wordmarkLine: Dp,
    spacing: HlSpacing,
    sizes: HlSizes,
): Dp {
    val pill = if (night) sizes.pillCompact else sizes.pill
    return when {
        compact -> maxOf(sizes.touchTarget, if (night) wordmarkLine else pill, pill) + spacing.s4 * 2
        night -> spacing.s18 + wordmarkLine + spacing.s4 + pill + spacing.s12
        else -> maxOf(sizes.touchTarget, pill) + spacing.s8 * 2
    }
}
