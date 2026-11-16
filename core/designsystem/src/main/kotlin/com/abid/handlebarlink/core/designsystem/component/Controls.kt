package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/** The main button ("Save device"): full width, inverse fill. */
@Composable
public fun HlButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = HlTheme.colors
    // Bento's artboard: inverse fill. Night has no sheet artboard: a dark fill would vanish on its ground,
    // so it uses Night's action colours (white), as its CarPlay buttons do.
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    val fill = if (night) colors.action else colors.surfaceInverse
    val ink = if (night) colors.onAction else colors.inkInverse
    Box(
        modifier
            .fillMaxWidth()
            .height(HlTheme.sizes.controlLarge)
            .clip(RoundedCornerShape(HlTheme.radii.control))
            .background(if (enabled) fill else colors.raisedLo)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        HlText(label, HlTheme.typography.value, if (enabled) ink else colors.inkMuted, maxLines = 1)
    }
}

/** A quiet text button ("Cancel"). */
@Composable
public fun HlQuietButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .sizeIn(minHeight = HlTheme.sizes.touchTarget)
            .clip(RoundedCornerShape(HlTheme.radii.control))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        HlText(label, HlTheme.typography.value, HlTheme.colors.inkMuted, maxLines = 1)
    }
}

/** A round icon button (settings): a glass circle on Night, a bare icon on Bento; 48 dp to touch. */
@Composable
public fun HlIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    Box(
        modifier
            .size(HlTheme.sizes.touchTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (night) {
            Box(
                Modifier
                    .size(HlTheme.sizes.avatar)
                    .background(colors.well, CircleShape)
                    .border(HlTheme.strokes.hairline, colors.line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                HlIcon(icon, colors.ink, size = HlTheme.sizes.iconSmall, contentDescription = contentDescription)
            }
        } else {
            HlIcon(icon, colors.ink, size = HlTheme.sizes.iconMedium, contentDescription = contentDescription)
        }
    }
}

/** A bottom sheet's surface: rounded top, a handle, and [content] with the design's 20 dp rhythm. */
@Composable
public fun HlSheet(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val radii = HlTheme.radii
    val spacing = HlTheme.spacing
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = radii.hero, topEnd = radii.hero))
            .background(HlTheme.colors.ground)
            .padding(start = spacing.s16, end = spacing.s16, bottom = spacing.s24, top = spacing.s10),
        verticalArrangement = Arrangement.spacedBy(spacing.s20),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .width(HlTheme.sizes.handleWidth)
                .height(HlTheme.sizes.handleHeight)
                .background(HlTheme.colors.lineStrong, RoundedCornerShape(radii.handle)),
        )
        content()
    }
}

/** A sheet's or screen's header: an icon block, a title and a line. */
@Composable
public fun HlHeader(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    Row(
        modifier.padding(horizontal = HlTheme.spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s12),
    ) {
        Box(
            Modifier
                .size(HlTheme.sizes.controlLarge)
                .background(colors.raisedLo, RoundedCornerShape(HlTheme.radii.control)),
            contentAlignment = Alignment.Center,
        ) {
            HlIcon(icon, colors.ink, size = HlTheme.sizes.iconMedium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s2)) {
            HlText(title, HlTheme.typography.title, colors.ink, maxLines = 2)
            HlText(subtitle, HlTheme.typography.label, colors.inkMuted)
        }
    }
}

/** A small section label ("What is it?"). */
@Composable
public fun HlSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    HlText(
        text,
        HlTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
        HlTheme.colors.ink,
        modifier.padding(horizontal = HlTheme.spacing.s4),
    )
}

/**
 * One choice of a single-choice list: optional icon, title, line and a radio (a check when [selected]).
 * [container]: the unselected fill; the design's sheet uses raisedLo, a page on the screen colour uses panel.
 */
@Composable
public fun HlOptionRow(
    @DrawableRes icon: Int?,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = HlTheme.colors.raisedLo,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    val shape = RoundedCornerShape(HlTheme.radii.control)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.raisedHi else container)
            .then(if (selected) Modifier.border(HlTheme.strokes.outline, colors.ink, shape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = spacing.s12, end = spacing.s16, top = spacing.s12, bottom = spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(HlTheme.sizes.avatar)
                    .background(
                        if (selected) colors.raisedLo else colors.raisedHi,
                        RoundedCornerShape(HlTheme.radii.nested),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                HlIcon(icon, colors.ink, size = HlTheme.sizes.iconSmall)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            HlText(title, HlTheme.typography.value, colors.ink)
            HlText(subtitle, HlTheme.typography.meta, colors.inkMuted)
        }
        if (selected) {
            Box(
                // Bento's artboard: ink disc, brand tick. On Night's light ink a brand tick would vanish: brand disc.
                Modifier
                    .size(HlTheme.sizes.indicator)
                    .background(if (night) colors.brand else colors.ink, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                HlIcon(HlIcons.Check, if (night) colors.brandInk else colors.brand, size = HlTheme.sizes.iconMicro)
            }
        } else {
            Box(
                Modifier
                    .size(HlTheme.sizes.indicator)
                    .border(HlTheme.strokes.outline, colors.lineStrong, CircleShape),
            )
        }
    }
}

/** A value row (the sheet's "Name" field): value and an optional [detail] line on the left, a hint on the right. */
@Composable
public fun HlValueRow(
    value: String,
    hint: String?,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    container: Color = HlTheme.colors.raisedLo,
) {
    val colors = HlTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .sizeIn(minHeight = HlTheme.sizes.controlLarge)
            .clip(RoundedCornerShape(HlTheme.radii.control))
            .background(container)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = HlTheme.spacing.s16, vertical = HlTheme.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s12),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s2)) {
            HlText(value, HlTheme.typography.value.copy(fontWeight = FontWeight.Medium), colors.ink)
            detail?.let { HlText(it, HlTheme.typography.meta, colors.inkMuted) }
        }
        hint?.let { HlText(it, HlTheme.typography.meta, colors.inkMuted, maxLines = 1) }
    }
}
