package com.abid.handlebarlink.ui.garage

import androidx.annotation.DrawableRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlRowText
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.pressScale
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/**
 * A Garage row (Paper "Garage 2" lists): a 36 dp icon tile, the [title] over one muted line ([detail], one line, ends
 * with an ellipsis: owner, row subtexts stay on one line), then [trailing] 16 dp clear of the text ([HlRowText]) and a
 * chevron when it opens something. [swatch] replaces the icon by a colour disc (the colourway). Square inside a group,
 * as the design system's rows.
 */
@Composable
internal fun GarageRow(
    @DrawableRes icon: Int?,
    title: String,
    detail: String?,
    trailing: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tone: RowTone = RowTone.PLAIN,
    trailingColor: Color = Color.Unspecified,
    swatch: Color? = null,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val press = remember { MutableInteractionSource() }
    val interaction =
        if (onClick != null) {
            Modifier.clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick)
        } else {
            Modifier.semantics(mergeDescendants = true) {}
        }
    Row(
        modifier
            .pressScale(press, HlTheme.motion)
            .fillMaxWidth()
            .sizeIn(minHeight = HlTheme.sizes.controlLarge)
            .background(if (tone == RowTone.DANGER) colors.danger.copy(alpha = DANGER_ROW) else Color.Transparent)
            .then(interaction)
            .padding(horizontal = spacing.s16, vertical = spacing.s14),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s14),
    ) {
        if (swatch != null) {
            Tile(RowTone.PLAIN) {
                Box(
                    Modifier
                        .size(HlTheme.sizes.iconTiny)
                        .background(swatch, CircleShape)
                        .border(HlTheme.strokes.hairline, colors.lineStrong, CircleShape),
                )
            }
        } else if (icon != null) {
            Tile(tone) { HlIcon(icon, tileInk(tone), size = HlTheme.sizes.iconTiny) }
        }
        HlRowText(rowGap = spacing.s14) {
            HlText(title, HlTheme.typography.value, colors.ink, maxLines = 1)
            detail?.let { HlText(it, HlTheme.typography.label, colors.inkMuted, maxLines = 1) }
        }
        trailing?.let {
            HlText(
                it,
                HlTheme.typography.label,
                trailingColor.takeOrElse { colors.inkMuted },
                maxLines = 1,
            )
        }
        if (onClick != null) HlIcon(HlIcons.ChevronRight, colors.inkMuted, size = HlTheme.sizes.iconTiny)
    }
}

/** The 36 dp tile (radius 12): white at 10 % on Night; brand for an add row; danger at 15 % when overdue. */
@Composable
private fun Tile(
    tone: RowTone,
    content: @Composable () -> Unit,
) {
    val colors = HlTheme.colors
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    val fill =
        when (tone) {
            RowTone.PLAIN -> colors.raisedLo

            // Bento: brand ink on brand (lime on white vanishes, docs/LESSONS.md 31).
            RowTone.ADD -> if (night) colors.brand.copy(alpha = ADD_TILE) else colors.brand

            RowTone.DANGER -> colors.danger.copy(alpha = DANGER_TILE)
        }
    Box(
        Modifier.size(HlTheme.sizes.chip).background(fill, RoundedCornerShape(HlTheme.radii.nested)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun tileInk(tone: RowTone): Color {
    val colors = HlTheme.colors
    return when (tone) {
        RowTone.PLAIN -> colors.ink
        RowTone.ADD -> if (HlTheme.variant == HlThemeVariant.NIGHT) colors.brand else colors.brandInk
        RowTone.DANGER -> colors.danger
    }
}

/** Paper "Garage 4", the overdue PUC row: #FF6B4A14 behind the row, #FF6B4A26 behind its icon. */
private const val DANGER_ROW = 0.08f
private const val DANGER_TILE = 0.15f

/** Paper "Garage 2", Add a reminder: #C8F03C24. */
private const val ADD_TILE = 0.14f
