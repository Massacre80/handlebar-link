package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import kotlin.math.roundToInt

/** One destination of the tab bar. */
public data class HlTab(
    /** Its icon (an [HlIcons] entry). */
    @param:DrawableRes val icon: Int,
    /** Its label. */
    val label: String,
)

/**
 * The floating tab bar ("Component · Tab bar"): the active tab sits on a raised chip, the others share the
 * remaining width. On a change the chip springs to the new tab while the tabs resize and recolour
 * (Motion-style "layoutId" sliding, done with a spring offset and `animateBounds`). With a [backdrop] (the
 * page it floats over), the bar is frosted glass like the design.
 */
@Composable
public fun HlTabBar(
    tabs: List<HlTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
) {
    val colors = HlTheme.colors
    val sizes = HlTheme.sizes
    val bar = HlTheme.shadows.bar
    val inset = HlTheme.spacing.s6
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(sizes.tabBar)
            .dropShadow(
                CircleShape,
                Shadow(radius = bar.blur, color = colors.shadow, offset = DpOffset(0.dp, bar.offsetY)),
            ).clip(CircleShape)
            // The design's glass: the page behind, blurred and saturated, under the glass tint.
            .then(backdrop?.let { Modifier.backdropBlur(it, GLASS_BLUR, GLASS_SATURATION) } ?: Modifier)
            .background(colors.glass)
            .border(HlTheme.strokes.hairline, colors.glassEdge, CircleShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Where the chip ends up: the tabs before the selected one share what the chip leaves.
        val others = (maxWidth - inset * 2 - sizes.tabWidth) / (tabs.size - 1).coerceAtLeast(1)
        val target = with(LocalDensity.current) { (inset + others * selected).toPx() }
        val chipX by animateFloatAsState(target, HlTheme.motion.layout(), label = "tab chip")
        Chip(DpSize(sizes.tabWidth, sizes.tab), Modifier.offset { IntOffset(chipX.roundToInt(), 0) })
        LookaheadScope {
            Row(
                Modifier.fillMaxSize().padding(horizontal = inset).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    BarItem(tab, index == selected, this@LookaheadScope) { onSelect(index) }
                }
            }
        }
    }
}

/**
 * The floating tab rail ("Component · Tab bar" stood on its end, for landscape): the same glass pill, tabs, labels
 * and raised chip as [HlTabBar]. Every tab and the chip are the rail's inner width ([HlSizes.tabRail] less the bar's
 * 6 dp inset each side, so the chip sits 6 dp from every edge like the bar's) and a tab's height; the chip only
 * slides (the same spring). Given a height (the app fills the height between the page's top and bottom margins, so
 * the rail is the same on every page), the tabs spread evenly over it, never closer than 4 dp; without one it wraps
 * them. The app floats it on the screen's end edge, where CarPlay keeps its dock (owner, 2026-11-20).
 */
@Composable
public fun HlTabRail(
    tabs: List<HlTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
) {
    val colors = HlTheme.colors
    val sizes = HlTheme.sizes
    val bar = HlTheme.shadows.bar
    val inset = HlTheme.spacing.s6
    BoxWithConstraints(
        modifier
            .width(sizes.tabRail)
            .dropShadow(
                CircleShape,
                Shadow(radius = bar.blur, color = colors.shadow, offset = DpOffset(0.dp, bar.offsetY)),
            ).clip(CircleShape)
            .then(backdrop?.let { Modifier.backdropBlur(it, GLASS_BLUR, GLASS_SATURATION) } ?: Modifier)
            .background(colors.glass)
            .border(HlTheme.strokes.hairline, colors.glassEdge, CircleShape),
        contentAlignment = Alignment.TopCenter,
    ) {
        val tab = DpSize(sizes.tabRail - inset * 2, sizes.tab)
        val gap = railGap(maxHeight, inset, tab.height, tabs.size, HlTheme.spacing.s4)
        val target = with(LocalDensity.current) { (inset + (tab.height + gap) * selected).toPx() }
        val chipY by animateFloatAsState(target, HlTheme.motion.layout(), label = "tab chip")
        Chip(tab, Modifier.offset { IntOffset(0, chipY.roundToInt()) })
        Column(
            Modifier.padding(vertical = inset).selectableGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            tabs.forEachIndexed { index, item ->
                TabItem(item, index == selected, Modifier.size(tab)) { onSelect(index) }
            }
        }
    }
}

/**
 * The gap between the rail's [count] tabs of [tab] height: what [height] leaves after them and the [inset] at both
 * ends, shared evenly, never less than [minimum]; [minimum] when the height is unbounded.
 */
internal fun railGap(
    height: Dp,
    inset: Dp,
    tab: Dp,
    count: Int,
    minimum: Dp,
): Dp {
    if (height == Dp.Infinity || count < 2) return minimum
    return maxOf(minimum, (height - inset * 2 - tab * count) / (count - 1))
}

/** The raised chip under the active tab: one shape that moves, never one per tab. */
@Composable
private fun Chip(
    size: DpSize,
    modifier: Modifier,
) {
    val colors = HlTheme.colors
    val chip = HlTheme.shadows.chip
    Box(
        modifier
            .size(size)
            .then(
                if (HlTheme.variant == HlThemeVariant.BENTO) {
                    Modifier.dropShadow(
                        CircleShape,
                        Shadow(radius = chip.blur, color = colors.shadow, offset = DpOffset(0.dp, chip.offsetY)),
                    )
                } else {
                    Modifier
                },
            ).clip(CircleShape)
            .background(colors.raisedHi),
    )
}

/** A tab of the bar: the active one takes the chip's width, the others share the rest (both animated). */
@Composable
private fun RowScope.BarItem(
    tab: HlTab,
    active: Boolean,
    lookahead: LookaheadScope,
    onClick: () -> Unit,
) {
    val sizes = HlTheme.sizes
    val motion = HlTheme.motion
    val bounds = BoundsTransform { _: Rect, _: Rect -> motion.layout() }
    val size =
        if (active) {
            Modifier.animateBounds(lookahead, Modifier.size(sizes.tabWidth, sizes.tab), bounds)
        } else {
            Modifier.weight(1f).animateBounds(lookahead, Modifier.height(sizes.tab), bounds)
        }
    TabItem(tab, active, size, onClick = onClick)
}

/**
 * A tab's icon and label, sized by [modifier], recoloured when it becomes [active]; shared by the bar and the rail.
 */
@Composable
private fun TabItem(
    tab: HlTab,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = HlTheme.colors
    val motion = HlTheme.motion
    val iconTint by animateColorAsState(if (active) colors.ink else colors.inkSoft, motion.fade(), label = "tab icon")
    val labelTint by animateColorAsState(
        if (active) colors.ink else colors.inkMuted,
        motion.fade(),
        label = "tab label",
    )
    val press = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(press, motion)
            // A pill clip would shave long labels at large text; the press area stays rounded.
            .clip(RoundedCornerShape(HlTheme.radii.control))
            .selectable(active, press, LocalIndication.current, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        // One 2 dp gap between icon and label on every tab (owner, 2026-11-17: the artboard's 4 dp on inactive
        // tabs made the label jump when a tab became active, and sat low in the chip).
        verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s2, Alignment.CenterVertically),
    ) {
        HlIcon(tab.icon, tint = { iconTint })
        HlText(
            tab.label,
            if (active) HlTheme.typography.tab.copy(fontWeight = FontWeight.SemiBold) else HlTheme.typography.tab,
            color = { labelTint },
            maxLines = 1,
        )
    }
}
