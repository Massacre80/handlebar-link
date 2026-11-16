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
    Box(
        modifier
            .fillMaxWidth()
            .height(HlTheme.sizes.controlLarge)
            .clip(RoundedCornerShape(HlTheme.radii.control))
            .background(if (enabled) colors.surfaceInverse else colors.raisedLo)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        HlText(label, HlTheme.typography.value, if (enabled) colors.inkInverse else colors.inkMuted, maxLines = 1)
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

/** One choice of a single-choice list: icon, title, line and a radio (a check when [selected]). */
@Composable
public fun HlOptionRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val shape = RoundedCornerShape(HlTheme.radii.control)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.raisedHi else colors.raisedLo)
            .then(if (selected) Modifier.border(HlTheme.strokes.outline, colors.ink, shape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = spacing.s12, end = spacing.s16, top = spacing.s12, bottom = spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            HlText(title, HlTheme.typography.value, colors.ink)
            HlText(subtitle, HlTheme.typography.meta, colors.inkMuted)
        }
        if (selected) {
            Box(
                Modifier.size(HlTheme.sizes.indicator).background(colors.ink, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                HlIcon(HlIcons.Check, colors.brand, size = HlTheme.sizes.iconMicro)
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

/** A read-only value row (the sheet's "Name" field): value on the left, a hint on the right. */
@Composable
public fun HlValueRow(
    value: String,
    hint: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = HlTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .sizeIn(minHeight = HlTheme.sizes.controlLarge)
            .clip(RoundedCornerShape(HlTheme.radii.control))
            .background(colors.raisedLo)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = HlTheme.spacing.s16, vertical = HlTheme.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s12),
    ) {
        HlText(value, HlTheme.typography.value.copy(fontWeight = FontWeight.Medium), colors.ink, Modifier.weight(1f))
        hint?.let { HlText(it, HlTheme.typography.meta, colors.inkMuted, maxLines = 1) }
    }
}
