package com.abid.handlebarlink.ui.frame

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlTag
import com.abid.handlebarlink.core.designsystem.component.HlTagTone
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.SignalRing
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.centred

// A tab's hero (Paper "CarPlay — flow v2" Tab 1–4, "Devices 1 — No intercom yet"): the dial with the tab's mark, a
// state tag, a big title, a centred detail, then the page's actions and its list. Upright one centred column; on its
// side two panes, the hero's state in the first and the rest in the second (docs/PLAN.md "Landscape layout").

/**
 * The hero's state: the dial with [icon] ([tone], [progress]), the [tag], the [title] (a heading; [liveTitle] reads a
 * title that changes by itself politely) and the [detail], held to [heroTextMax] on its side.
 */
@Composable
fun ColumnScope.HeroState(
    @DrawableRes icon: Int,
    tone: SignalTone,
    progress: Float?,
    tag: String,
    title: String,
    detail: String,
    liveTitle: Boolean = false,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    HeroDial(icon, tone, progress)
    HlTag(tag, Modifier.padding(top = spacing.s4), tagTone(tone), dot = true)
    HlText(
        title,
        HlTheme.typography.headline.centred(),
        colors.ink,
        Modifier.semantics {
            heading()
            if (liveTitle) liveRegion = LiveRegionMode.Polite
        },
    )
    HlText(
        detail,
        HlTheme.typography.body.centred(),
        colors.inkMuted,
        Modifier.padding(horizontal = spacing.s8).widthIn(max = heroTextMax()),
    )
}

/**
 * The tab's mark in the signal ring: lit by step while starting, whole when running, danger when it needs you, the
 * quiet ring with the mark in ink when idle.
 */
@Composable
private fun HeroDial(
    @DrawableRes icon: Int,
    tone: SignalTone,
    progress: Float?,
) {
    val colors = HlTheme.colors
    val motion = HlTheme.motion
    val color by animateColorAsState(
        when (tone) {
            SignalTone.ERROR -> colors.danger
            SignalTone.IDLE -> colors.inkMuted
            SignalTone.PROGRESS, SignalTone.ON -> colors.brand
        },
        motion.fade(),
    )
    val lit by animateFloatAsState(progress ?: 1f, motion.fade())
    Box(contentAlignment = Alignment.Center) {
        // Needs you: the disc takes the ring's red at the design's 8 %, so the whole hero reads as one error state
        // (owner, 2026-11-20: the neutral or green disc inside the red ring looked half green); else the raised disc.
        val disc = if (tone == SignalTone.ERROR) color.copy(alpha = ERROR_DISC) else colors.raisedLo
        SignalRing(color, colors.track, disc, progress = lit, size = HlTheme.sizes.gauge)
        HlIcon(icon, if (tone == SignalTone.IDLE) colors.ink else color, size = HlTheme.sizes.icon * 2)
    }
}

/**
 * The hero's state tag tone (Paper "CarPlay — flow v2"): running and starting brand, needs you danger, the quiet states
 * (Ready, Not set up, Stopped) muted; drawn by the design system's [HlTag] with its dot.
 */
internal fun tagTone(tone: SignalTone): HlTagTone =
    when (tone) {
        SignalTone.ON, SignalTone.PROGRESS -> HlTagTone.BRAND
        SignalTone.ERROR -> HlTagTone.DANGER
        SignalTone.IDLE -> HlTagTone.QUIET
    }

/**
 * A hero tab upright: one column up to the content width, centred in the height and scrolling when it runs out of it
 * (docs/LESSONS.md 22), below the status bar and clear of the tab bar ([contentPadding]).
 */
@Composable
fun HeroColumn(
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = HlTheme.spacing
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.pageTop)
            .padding(contentPadding),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = spacing.s16, vertical = spacing.s16),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.s24, Alignment.CenterVertically),
            content = content,
        )
    }
}

/**
 * A hero tab on its side: the hero's state ([hero]) in the first pane, its actions and list ([side]) in the second;
 * each pane centred in the height and scrolling on its own when it runs out of it, below the status bar and clear of
 * the rail ([contentPadding]).
 */
@Composable
fun HeroPanes(
    contentPadding: PaddingValues,
    hero: @Composable ColumnScope.() -> Unit,
    side: @Composable ColumnScope.() -> Unit,
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
                content = hero,
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(top = spacing.s16, bottom = bottom),
                verticalArrangement = Arrangement.spacedBy(spacing.s16, Alignment.CenterVertically),
                content = side,
            )
        }
    }
}

/** The error disc's tint over the ring's red (Paper "Tab 4 — Needs you": danger at 8 %). */
private const val ERROR_DISC = 0.08f
