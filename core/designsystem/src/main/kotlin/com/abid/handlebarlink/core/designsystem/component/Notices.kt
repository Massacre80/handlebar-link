package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/**
 * A small status tag beside a value or a title ("SOON": works once our own CarPlay runs; Paper "Handlebar —
 * Smart buttons"), upper-cased here. Night: brand text on a faint brand pill with a brand edge, as drawn. Bento:
 * brand text would vanish on white (docs/LESSONS.md 31), so the pill is brand with brand ink.
 */
@Composable
public fun HlTag(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    Box(
        modifier
            .background(if (night) colors.brand.copy(alpha = TAG_FILL) else colors.brand, CircleShape)
            .then(
                if (night) {
                    Modifier.border(HlTheme.strokes.hairline, colors.brand.copy(alpha = TAG_EDGE), CircleShape)
                } else {
                    Modifier
                },
            ).padding(horizontal = HlTheme.spacing.s8, vertical = HlTheme.spacing.s4),
        contentAlignment = Alignment.Center,
    ) {
        HlText(
            text.uppercase(),
            HlTheme.typography.caps.copy(fontWeight = FontWeight.SemiBold),
            if (night) colors.brand else colors.brandInk,
            maxLines = 1,
        )
    }
}

/**
 * A notice on a page: an icon and a [title] on one row, then the [body] from the notice's left edge, not
 * indented under the title (owner, Paper "Handlebar 3 — Smart · Ringing"). A faint card with a brand edge, its
 * icon in a brand circle (Night: brand on a faint brand disc; Bento: brand ink on brand, lesson 31).
 */
@Composable
public fun HlNotice(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    val shape = RoundedCornerShape(HlTheme.radii.floating)
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.panel, shape)
            .border(
                HlTheme.strokes.hairline,
                if (night) colors.brand.copy(alpha = NOTICE_EDGE) else colors.brand,
                shape,
            ).padding(horizontal = spacing.s16, vertical = spacing.s14),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s10)) {
            Box(
                Modifier
                    .size(HlTheme.sizes.indicator)
                    .background(if (night) colors.brand.copy(alpha = TAG_FILL) else colors.brand, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                HlIcon(icon, if (night) colors.brand else colors.brandInk, size = HlTheme.sizes.iconMicro + spacing.s2)
            }
            HlText(title, HlTheme.typography.body.copy(fontWeight = FontWeight.SemiBold), colors.ink)
        }
        HlText(body, HlTheme.typography.label.copy(fontWeight = FontWeight.Normal), colors.inkMuted)
    }
}

/** The tag's and the notice icon's faint brand fill (Paper: #C8F03C1F). */
private const val TAG_FILL = 0.12f

/** The tag's brand edge (Paper: #C8F03C47). */
private const val TAG_EDGE = 0.28f

/** The notice's brand edge (Paper: #C8F03C33). */
private const val NOTICE_EDGE = 0.2f
