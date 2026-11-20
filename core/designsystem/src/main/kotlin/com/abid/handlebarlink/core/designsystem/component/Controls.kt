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
import com.abid.handlebarlink.core.designsystem.theme.balanced

/** The main button ("Save device"): full width, inverse fill. */
@Composable
public fun HlButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    brand: Boolean = false,
    chevron: Boolean = false,
    destructive: Boolean = false,
) {
    val colors = HlTheme.colors
    // [brand]: the setup screens' one main action (lime pill, "Start"). Otherwise Bento's artboard: inverse
    // fill; Night has no sheet artboard: a dark fill would vanish on its ground, so it uses Night's action
    // colours (white), as its CarPlay buttons do.
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    // [destructive]: the confirm of an action that removes something, in the danger colour.
    val fill =
        when {
            destructive -> colors.danger
            brand -> colors.brand
            night -> colors.action
            else -> colors.surfaceInverse
        }
    val ink =
        when {
            destructive -> colors.inkInverse
            brand -> colors.brandInk
            night -> colors.onAction
            else -> colors.inkInverse
        }
    val shape = if (brand) CircleShape else RoundedCornerShape(HlTheme.radii.control)
    val press = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(press, HlTheme.motion)
            .fillMaxWidth()
            .height(HlTheme.sizes.controlLarge)
            .clip(shape)
            .background(if (enabled) fill else colors.raisedLo)
            .clickable(press, LocalIndication.current, enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = if (brand) HlTheme.typography.lead else HlTheme.typography.value
        HlText(label, style, if (enabled) ink else colors.inkMuted, maxLines = 1)
        if (chevron) HlIcon(HlIcons.ChevronRight, if (enabled) ink else colors.inkMuted, size = HlTheme.sizes.iconTiny)
    }
}

/**
 * A secondary, ghost button ("Reset to default"; Paper "Handlebar 7"): [HlButton]'s height and press, a full pill
 * filled with the well (a tad above the page: Night white at 7 %, Bento a light grey; owner 2026-11-20, the edge
 * alone had too little contrast), a hairline edge in line (Night: white at 10 %, as drawn) and the label in ink.
 * Disabled: a fainter edge and a muted label.
 */
@Composable
public fun HlSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = HlTheme.colors
    val press = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(press, HlTheme.motion)
            .fillMaxWidth()
            .height(HlTheme.sizes.controlLarge)
            .clip(CircleShape)
            .background(colors.well)
            .border(HlTheme.strokes.hairline, if (enabled) colors.line else colors.lineSoft, CircleShape)
            .clickable(press, LocalIndication.current, enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HlText(
            label,
            HlTheme.typography.value.copy(fontWeight = FontWeight.Medium),
            if (enabled) colors.ink else colors.inkMuted,
            maxLines = 1,
        )
    }
}

/** A numbered instruction ("1 Turn the ignition on"), as on the setup screens: a glass card in Night. */
@Composable
public fun HlStepRow(
    number: Int,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    HlCard(modifier.fillMaxWidth(), radius = HlTheme.radii.card) {
        Row(
            Modifier.padding(spacing.s16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s14),
        ) {
            Box(
                Modifier.size(HlTheme.sizes.chip).background(colors.raisedLo, RoundedCornerShape(HlTheme.radii.nested)),
                contentAlignment = Alignment.Center,
            ) {
                HlText(number.toString(), HlTheme.typography.value, colors.ink, maxLines = 1)
            }
            Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                HlText(title, HlTheme.typography.value, colors.ink)
                HlText(detail, HlTheme.typography.meta.balanced(), colors.inkMuted)
            }
        }
    }
}

/** A quiet text button ("Cancel"). */
@Composable
public fun HlQuietButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(press, HlTheme.motion)
            .fillMaxWidth()
            .sizeIn(minHeight = HlTheme.sizes.touchTarget)
            .clip(RoundedCornerShape(HlTheme.radii.control))
            .clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick),
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
    val press = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(press, HlTheme.motion)
            .size(HlTheme.sizes.touchTarget)
            .clip(CircleShape)
            .clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick),
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

/**
 * A sheet's or screen's header: an icon block, a title and a line. [brand] fills the block in brand with brand
 * ink (the action pickers, Paper "Handlebar 5": the key being set).
 */
@Composable
public fun HlHeader(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    brand: Boolean = false,
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
                .background(if (brand) colors.brand else colors.raisedLo, RoundedCornerShape(HlTheme.radii.control)),
            contentAlignment = Alignment.Center,
        ) {
            HlIcon(icon, if (brand) colors.brandInk else colors.ink, size = HlTheme.sizes.iconMedium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s2)) {
            HlText(title, HlTheme.typography.title, colors.ink, maxLines = 2)
            HlText(subtitle, HlTheme.typography.label.balanced(), colors.inkMuted)
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
