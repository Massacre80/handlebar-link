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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/**
 * A row that opens something (a device on Add device, "Pair a new device"): the icon block of
 * [HlOptionRow], a [title] and a [detail] line, a chevron. [detailColor] marks a live or failed state
 * ("Connected" in success, "Couldn't pair" in danger); muted otherwise. Square inside an [HlGroup], like
 * every grouped row.
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
        Column(
            Modifier.weight(1f).animateContentSize(HlTheme.motion.layout(IntSize.VisibilityThreshold)),
            verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
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
        HlIcon(HlIcons.ChevronRight, colors.inkMuted, size = HlTheme.sizes.iconTiny)
    }
}
