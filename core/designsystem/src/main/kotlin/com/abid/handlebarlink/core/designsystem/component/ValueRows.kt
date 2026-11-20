package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.core.designsystem.theme.InstrumentType
import com.abid.handlebarlink.core.designsystem.theme.balanced

/**
 * A value row (the sheet's "Name" field): value and an optional [detail] line on the left, a hint on the
 * right; [chevron] marks a row that opens another page. [tag]: an [HlTag] before the hint ("SOON");
 * [hintColor]: a hint that is a state (End call in danger), muted otherwise. [icon]: a leading icon tile
 * ([HlRowIcon]) and the status list's row (Paper "BikePlay — flow", "Devices 1"): 14 dp padding and gap, a semibold
 * title, the hint at 13 sp and a 16 dp chevron 6 dp after it; [compact] (on its side, Paper "Tab 1L") 12 dp padding.
 */
@Composable
public fun HlValueRow(
    value: String,
    hint: String?,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    container: Color = HlTheme.colors.raisedLo,
    chevron: Boolean = false,
    tag: String? = null,
    hintColor: Color = Color.Unspecified,
    @DrawableRes icon: Int? = null,
    compact: Boolean = false,
) {
    val colors = HlTheme.colors
    val press = remember { MutableInteractionSource() }
    val tile = icon != null
    RowFrame(
        modifier,
        press,
        container,
        if (onClick != null) {
            Modifier.clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick)
        } else {
            Modifier
        },
        RowStyle(tile, compact),
    ) {
        if (icon != null) HlRowIcon(icon)
        RowText(value, detail, tile)
        tag?.let { HlTag(it) }
        if (tile) {
            TileTrailing(hint, hintColor.takeOrElse { colors.inkMuted }, chevron)
        } else {
            hint?.let { HlText(it, HlTheme.typography.meta, hintColor.takeOrElse { colors.inkMuted }, maxLines = 1) }
            if (chevron) HlIcon(HlIcons.ChevronRight, colors.inkMuted, size = HlTheme.sizes.iconTiny)
        }
    }
}

/** An icon row's trailing part (Paper "BikePlay — flow"): the hint at 13 sp, then a 16 dp chevron 6 dp after it. */
@Composable
private fun TileTrailing(
    hint: String?,
    hintColor: Color,
    chevron: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s6),
    ) {
        hint?.let { HlText(it, InstrumentType.rowHint, hintColor, maxLines = 1) }
        if (chevron) HlIcon(HlIcons.ChevronRight, HlTheme.colors.inkSoft, size = HlTheme.sizes.chevron)
    }
}

/** A row's padding and gap: an icon row's [tile] (14 dp, or 12 dp when [compact]) or a plain row's 12 dp. */
private class RowStyle(
    val tile: Boolean,
    val compact: Boolean = false,
)

/**
 * A row with an on/off switch ([HlSwitch]) at the right: the whole row toggles it (one touch target, `Role.Switch`,
 * read as "on" or "off"), as [HlValueRow] lays out its [value], [detail] and leading [icon].
 */
@Composable
public fun HlSwitchRow(
    value: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    container: Color = HlTheme.colors.raisedLo,
    @DrawableRes icon: Int? = null,
) {
    val press = remember { MutableInteractionSource() }
    val tile = icon != null
    RowFrame(
        modifier,
        press,
        container,
        Modifier.toggleable(
            checked,
            press,
            LocalIndication.current,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        RowStyle(tile),
    ) {
        if (icon != null) HlRowIcon(icon)
        RowText(value, detail, tile)
        HlSwitch(checked, onCheckedChange = null)
    }
}

/** A row's frame: press scale, at least a large control's height, the group's or own shape, [container], padding. */
@Composable
private fun RowFrame(
    modifier: Modifier,
    press: MutableInteractionSource,
    container: Color,
    interaction: Modifier,
    style: RowStyle,
    content: @Composable RowScope.() -> Unit,
) {
    val spacing = HlTheme.spacing
    val vertical =
        when {
            !style.tile || style.compact -> spacing.s12
            else -> spacing.s14
        }
    Row(
        modifier
            .pressScale(press, HlTheme.motion)
            .fillMaxWidth()
            .sizeIn(minHeight = HlTheme.sizes.controlLarge)
            .clip(rowShape(HlTheme.radii.control))
            .background(container)
            .then(interaction)
            .padding(horizontal = spacing.s16, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (style.tile) spacing.s14 else spacing.s12),
        content = content,
    )
}

/** A row's value and its optional detail line (balanced), clear of what trails them ([HlRowText]). */
@Composable
private fun RowScope.RowText(
    value: String,
    detail: String?,
    tile: Boolean = false,
) {
    val colors = HlTheme.colors
    HlRowText {
        HlText(
            value,
            if (tile) InstrumentType.rowTitle else HlTheme.typography.value.copy(fontWeight = FontWeight.Medium),
            colors.ink,
        )
        detail?.let { HlText(it, HlTheme.typography.meta.balanced(), colors.inkMuted) }
    }
}

/**
 * A row's leading icon tile (Paper "CarPlay — flow v2": 36 x 36, radius 12, white 10 % on Night, an 18 dp icon in
 * ink). Bento's raisedLo is the page's own grey, as the design's light tiles.
 */
@Composable
public fun HlRowIcon(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    Box(
        modifier
            .size(HlTheme.sizes.chip)
            .background(colors.raisedLo, RoundedCornerShape(HlTheme.radii.nested)),
        contentAlignment = Alignment.Center,
    ) {
        HlIcon(icon, colors.ink, size = HlTheme.sizes.iconTiny)
    }
}

/**
 * An on/off switch (Paper "CarPlay — flow v2": a 44 x 26 track, a 20 dp thumb). On: brand track, brand-ink thumb
 * (the design's lime and dark thumb, both looks). Off: the gauge track's faint fill and a muted thumb (Night) or a
 * white one (Bento). With [onCheckedChange] it is its own 48 dp touch target with `Role.Switch`; null draws it only,
 * for a row that toggles as a whole ([HlSwitchRow]).
 */
@Composable
public fun HlSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val sizes = HlTheme.sizes
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    val track by animateColorAsState(if (checked) colors.brand else colors.track, HlTheme.motion.fade())
    val thumb by animateColorAsState(
        when {
            checked -> colors.brandInk
            night -> colors.inkMuted
            else -> colors.raisedHi
        },
        HlTheme.motion.fade(),
    )
    val inset = (sizes.switchHeight - sizes.switchThumb) / 2
    val offset by animateDpAsState(
        if (checked) sizes.switchWidth - sizes.switchThumb - inset else inset,
        HlTheme.motion.layout(),
    )
    val interaction =
        if (onCheckedChange != null) {
            Modifier
                .sizeIn(minWidth = sizes.touchTarget, minHeight = sizes.touchTarget)
                .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
        } else {
            Modifier
        }
    Box(modifier.then(interaction), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(sizes.switchWidth, sizes.switchHeight)
                .background(track, CircleShape),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    // Read while placing only: the thumb's slide never recomposes or remeasures (performance skill).
                    .offset { IntOffset(offset.roundToPx(), 0) }
                    .size(sizes.switchThumb)
                    .background(thumb, CircleShape),
            )
        }
    }
}
