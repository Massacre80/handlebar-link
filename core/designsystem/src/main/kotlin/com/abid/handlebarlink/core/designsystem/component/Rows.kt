package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.core.designsystem.theme.HlSpacing
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.core.designsystem.theme.balanced
import com.abid.handlebarlink.core.designsystem.theme.trailingGap

/**
 * A row that opens something (a device on Add device, "Pair a new device"): the icon block of
 * [HlOptionRow], a [title] and a [detail] line, a chevron. [detailColor] marks a live or failed state
 * ("Connected" in success, "Couldn't pair" in danger); muted otherwise. Square inside an [HlGroup], like
 * every grouped row. [trailing] sits before the chevron (a battery).
 */
@Composable
public fun HlNavRow(
    @DrawableRes icon: Int,
    title: String,
    detail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detailColor: Color = Color.Unspecified,
    container: Color = Color.Transparent,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val press = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(press, HlTheme.motion)
            .fillMaxWidth()
            .clip(rowShape(HlTheme.radii.control))
            .background(container)
            .clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(start = spacing.s12, end = spacing.s14, top = spacing.s12, bottom = spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Box(
            Modifier
                .size(HlTheme.sizes.avatar)
                // Apart from the group's fill: brighter glass on Night, light grey on Bento's white card.
                .background(
                    if (HlTheme.variant == HlThemeVariant.NIGHT) colors.raisedHi else colors.raisedLo,
                    RoundedCornerShape(HlTheme.radii.nested),
                ),
            contentAlignment = Alignment.Center,
        ) {
            HlIcon(icon, colors.ink, size = HlTheme.sizes.iconSmall)
        }
        // A detail line coming or going ("Pairing…") eases the row to its new height.
        HlRowText(Modifier.animateContentSize(HlTheme.motion.layout(IntSize.VisibilityThreshold))) {
            HlText(title, HlTheme.typography.value, colors.ink, maxLines = 2)
            detail?.let {
                HlText(
                    it,
                    HlTheme.typography.meta,
                    detailColor.takeOrElse { colors.inkMuted },
                    maxLines = 1,
                )
            }
        }
        trailing?.invoke()
        HlIcon(HlIcons.ChevronRight, colors.inkMuted, size = HlTheme.sizes.iconTiny)
    }
}

/**
 * A row that acts at once instead of opening something ("Remove from app"): the icon and label, no
 * chevron. [destructive] paints both in the danger colour, so a removal reads as one before it is tapped.
 */
@Composable
public fun HlActionRow(
    @DrawableRes icon: Int,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val tint = if (destructive) colors.danger else colors.ink
    val press = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(press, HlTheme.motion)
            .fillMaxWidth()
            .sizeIn(minHeight = HlTheme.sizes.controlLarge)
            .clip(rowShape(HlTheme.radii.control))
            .clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = spacing.s16, vertical = spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        HlIcon(icon, tint, size = HlTheme.sizes.iconSmall)
        HlText(title, HlTheme.typography.value, tint, maxLines = 1)
    }
}

/**
 * The text of a row or card that has something after it (a button, a value, a chevron, a switch, a radio, a battery):
 * its lines, [HlSpacing.s2] apart, take the room the row leaves and stay [trailingGap] clear of what follows, whatever
 * the row spaces its items by ([rowGap]; the owner, 2026-11-20: subtexts ran up to the trailing control). Give a
 * subtext that may wrap [balanced] lines.
 */
@Composable
public fun RowScope.HlRowText(
    modifier: Modifier = Modifier,
    rowGap: Dp = HlTheme.spacing.s12,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = HlTheme.spacing
    Column(
        modifier.weight(1f).padding(end = (spacing.trailingGap - rowGap).coerceAtLeast(0.dp)),
        verticalArrangement = Arrangement.spacedBy(spacing.s2),
        content = content,
    )
}
