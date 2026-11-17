package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/**
 * A card: glass with a hairline edge in Night, solid in Bento. Cards placed side by side share one height:
 * give their row `Modifier.height(IntrinsicSize.Min)` and each card `fillMaxHeight()` (see [CardRow]).
 * With a [backdrop], the glass blurs what lies behind it (Night's tiles over the bike).
 */
@Composable
public fun HlCard(
    modifier: Modifier = Modifier,
    radius: Dp = HlTheme.radii.hero,
    onClick: (() -> Unit)? = null,
    backdrop: Backdrop? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = HlTheme.colors
    val shape = RoundedCornerShape(radius)
    val edged =
        if (HlTheme.variant == HlThemeVariant.NIGHT) {
            Modifier.border(HlTheme.strokes.hairline, colors.line, shape)
        } else {
            Modifier
        }
    val press = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(press, HlTheme.motion)
            .clip(shape)
            .then(backdrop?.let { Modifier.backdropBlur(it, GLASS_BLUR, GLASS_SATURATION) } ?: Modifier)
            .background(colors.panel)
            .then(edged)
            .then(
                if (onClick !=
                    null
                ) {
                    Modifier.clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}

/** A device the rider added (Night: a tile). [connected] null: not known yet (an empty state). */
@Composable
public fun DeviceTile(
    kind: String,
    name: String,
    detail: String,
    connected: Boolean?,
    modifier: Modifier = Modifier,
    status: String? = null,
    backdrop: Backdrop? = null,
) {
    val colors = HlTheme.colors
    val type = HlTheme.typography
    val spacing = HlTheme.spacing
    // The design height is a minimum: with large text the tile grows instead of clipping.
    HlCard(modifier, backdrop = backdrop) {
        Column(
            Modifier.fillMaxHeight().heightIn(min = HlTheme.sizes.tile).padding(spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s12, Alignment.Bottom),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(HlTheme.sizes.chip)
                        .background(colors.raisedLo, RoundedCornerShape(HlTheme.radii.nested)),
                    contentAlignment = Alignment.Center,
                ) {
                    HlIcon(HlIcons.Intercom, colors.ink, size = HlTheme.sizes.iconSmall)
                }
                Spacer(Modifier.weight(1f))
                status?.let { HlText(it, type.meta, colors.inkMuted, maxLines = 1) }
            }
            Spacer(Modifier.weight(1f, fill = false))
            Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                HlText(kind, type.meta, colors.inkMuted, maxLines = 1)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.s8),
                ) {
                    StatusDot(
                        if (connected == true) colors.signal else colors.inkSoft,
                        size = HlTheme.sizes.dotLarge,
                        glow = connected == true,
                    )
                    HlText(name, type.lead, colors.ink, maxLines = 2)
                }
                HlText(detail, type.label, colors.inkMuted, maxLines = 2)
            }
        }
    }
}

/** A device the rider added (Bento: a row card). [connected] null: not known yet (an empty state). */
@Composable
public fun DeviceRow(
    name: String,
    detail: String,
    connected: Boolean?,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    HlCard(modifier) {
        Row(
            Modifier.fillMaxHeight().padding(spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Box(
                Modifier.size(HlTheme.sizes.avatar).background(colors.raisedLo, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                HlIcon(HlIcons.Intercom, colors.ink, size = HlTheme.sizes.iconSmall)
            }
            Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.s6),
                ) {
                    StatusDot(if (connected == true) colors.success else colors.lineStrong)
                    HlText(name, HlTheme.typography.value, colors.ink, maxLines = 1)
                }
                HlText(detail, HlTheme.typography.meta, colors.inkMuted, maxLines = 1)
            }
        }
    }
}

/** An empty slot for a new device: a dashed tile (Night) or row (Bento). Not clickable when [onClick] is null. */
@Composable
public fun AddDeviceSlot(
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val radii = HlTheme.radii
    val strokes = HlTheme.strokes
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    val press = remember { MutableInteractionSource() }
    val outline =
        modifier
            .pressScale(press, HlTheme.motion)
            .clip(RoundedCornerShape(radii.hero))
            .dashedOutline(colors.lineStrong, strokes.outline, radii.hero, strokes.dashOn, strokes.dashOff)
            .then(
                if (onClick !=
                    null
                ) {
                    Modifier.clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
    if (night) {
        Column(
            outline.heightIn(min = HlTheme.sizes.tile).padding(spacing.s16),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier.size(HlTheme.sizes.chip).background(colors.brand, RoundedCornerShape(radii.nested)),
                contentAlignment = Alignment.Center,
            ) {
                HlIcon(HlIcons.Plus, colors.brandInk, size = HlTheme.sizes.iconTiny)
            }
            Column(Modifier.padding(top = spacing.s12)) {
                HlText(title, HlTheme.typography.lead, colors.ink, maxLines = 2)
                detail?.let { HlText(it, HlTheme.typography.label, colors.inkMuted, maxLines = 2) }
            }
        }
    } else {
        Row(
            outline.padding(spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Box(
                Modifier.size(HlTheme.sizes.avatar).background(colors.surfaceInverse, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                HlIcon(HlIcons.Plus, colors.inkInverse, size = HlTheme.sizes.iconTiny)
            }
            Column {
                HlText(title, HlTheme.typography.value, colors.ink, maxLines = 1)
                detail?.let { HlText(it, HlTheme.typography.meta, colors.inkMuted, maxLines = 1) }
            }
        }
    }
}

/** The connection pill under the bike's name: compact on Night, larger on Bento. */
@Composable
public fun StatusPill(
    text: String,
    on: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val shape = CircleShape
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    Row(
        modifier
            .height(if (night) HlTheme.sizes.pillCompact else HlTheme.sizes.pill)
            .clip(shape)
            .background(colors.pill)
            .border(HlTheme.strokes.hairline, if (night) colors.line else colors.glassEdge, shape)
            .padding(start = spacing.s10, end = if (night) spacing.s10 else spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (night) spacing.s6 else spacing.s8),
    ) {
        StatusDot(
            if (on) colors.signal else colors.inkSoft,
            size = if (night) HlTheme.sizes.dotSmall else HlTheme.sizes.dot,
            glow = on && night,
        )
        HlText(
            text,
            if (night) HlTheme.typography.meta else HlTheme.typography.label,
            if (night) colors.inkMuted else colors.ink,
            maxLines = 1,
        )
    }
}

/**
 * A row of cards that all take the tallest card's height (the intercom card and the add-device slot), so
 * their edges and bottom-aligned text line up whatever the text size.
 */
@Composable
public fun CardRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        // The artboards' gaps: 10 dp between Night's tiles, 8 dp in Bento's grid.
        horizontalArrangement =
            Arrangement.spacedBy(
                if (HlTheme.variant ==
                    HlThemeVariant.NIGHT
                ) {
                    HlTheme.spacing.s10
                } else {
                    HlTheme.spacing.s8
                },
            ),
        content = content,
    )
}

/** The design's glass (`backdrop-filter: blur(24px) saturate(140%)` on Night's tiles). */
private val GLASS_BLUR = 24.dp
private const val GLASS_SATURATION = 1.4f
