package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/**
 * One choice of a single-choice list: optional icon, title, line and a radio (a check when [selected]).
 * [container]: the unselected fill; the design's sheet uses raisedLo, a page on the screen colour uses panel.
 * [tag]: an [HlTag] after the title ("SOON"). [destructive]: the icon in the danger colour (End call).
 * [accent]: the chosen row in brand, a faint brand fill and a brand edge (the action pickers, Paper "Handlebar
 * 5"); otherwise grey.
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
    enabled: Boolean = true,
    tag: String? = null,
    destructive: Boolean = false,
    accent: Boolean = false,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    val shape = rowShape(HlTheme.radii.control)
    val accented = selected && accent
    val press = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(press, HlTheme.motion)
            // A choice that is not available yet: dimmed and not selectable.
            .then(if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA))
            .fillMaxWidth()
            .clip(shape)
            // The chosen row is marked by a grey fill and its check, no outline (owner, 2026-11-17: the
            // artboard's ink ring read as a hard black border): Night's brighter glass; Bento's line grey,
            // since its raisedLo is the page's own colour and the row vanished into it. [accent]: brand instead.
            .background(
                when {
                    !selected -> container
                    accent -> colors.brandTint
                    night -> colors.raisedHi
                    else -> colors.line
                },
            ).then(
                if (accented) {
                    Modifier.border(HlTheme.strokes.hairline, colors.brand.copy(alpha = ACCENT_EDGE), shape)
                } else {
                    Modifier
                },
            ).selectable(selected, press, LocalIndication.current, enabled, Role.RadioButton, onClick)
            .padding(start = spacing.s12, end = spacing.s16, top = spacing.s12, bottom = spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        if (icon != null) OptionIcon(icon, selected, destructive)
        OptionText(title, subtitle, tag, Modifier.weight(1f))
        OptionIndicator(selected, night)
    }
}

/** An option's icon block: brighter than the row, or a faint danger fill when [destructive]. */
@Composable
private fun OptionIcon(
    @DrawableRes icon: Int,
    selected: Boolean,
    destructive: Boolean,
) {
    val colors = HlTheme.colors
    Box(
        Modifier
            .size(HlTheme.sizes.avatar)
            .background(
                when {
                    destructive -> colors.danger.copy(alpha = DANGER_FILL)
                    selected -> colors.raisedLo
                    else -> colors.raisedHi
                },
                RoundedCornerShape(HlTheme.radii.nested),
            ),
        contentAlignment = Alignment.Center,
    ) {
        HlIcon(icon, if (destructive) colors.danger else colors.ink, size = HlTheme.sizes.iconSmall)
    }
}

/** An option's title (with its [tag]) and line. */
@Composable
private fun OptionText(
    title: String,
    subtitle: String,
    tag: String?,
    modifier: Modifier,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
        if (tag == null) {
            HlText(title, HlTheme.typography.value, colors.ink)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                HlText(title, HlTheme.typography.value, colors.ink, Modifier.weight(1f, fill = false))
                HlTag(tag)
            }
        }
        HlText(subtitle, HlTheme.typography.meta, colors.inkMuted)
    }
}

/** An option's radio: a check disc when [selected]. */
@Composable
private fun OptionIndicator(
    selected: Boolean,
    night: Boolean,
) {
    val colors = HlTheme.colors
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

/** The accented chosen row's brand edge (Paper "Handlebar 5": #C8F03C59). */
private const val ACCENT_EDGE = 0.35f

/** A destructive choice's icon block: a faint danger fill (Paper "Handlebar 5": #FF6B4A26). */
private const val DANGER_FILL = 0.15f

/** How far a choice that is not available yet is dimmed. */
private const val DISABLED_ALPHA = 0.4f
