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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.core.designsystem.theme.balanced

/**
 * A small status tag beside a value or a title, upper-cased here: "SOON" (Paper "Handlebar — Smart buttons") or the
 * CarPlay tab's state (Paper "CarPlay — flow v2", with [dot]: a 6 dp dot before the words in the tag's ink).
 * Night: the [tone]'s colour as text on a faint pill of it with an edge of it, as drawn (brand and danger: 12 % fill,
 * 28 % edge); [HlTagTone.QUIET] muted text on the panel with a hairline. Bento: brand or danger text would vanish on
 * white (docs/LESSONS.md 31), so the pill is filled: brand with brand ink, danger with inverse ink, quiet raisedLo with
 * muted ink and a hairline.
 */
@Composable
public fun HlTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: HlTagTone = HlTagTone.BRAND,
    dot: Boolean = false,
) {
    val colors = HlTheme.colors
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    val accent =
        when (tone) {
            HlTagTone.BRAND -> colors.brand
            HlTagTone.DANGER -> colors.danger
            HlTagTone.QUIET -> null
        }
    val fill: Color
    val edge: Color
    val ink: Color
    when {
        accent != null && night -> {
            fill = accent.copy(alpha = TAG_FILL)
            edge = accent.copy(alpha = TAG_EDGE)
            ink = accent
        }

        tone == HlTagTone.BRAND -> {
            fill = colors.brand
            edge = Color.Transparent
            ink = colors.brandInk
        }

        accent != null -> {
            fill = accent
            edge = Color.Transparent
            ink = colors.inkInverse
        }

        else -> {
            fill = if (night) colors.panel else colors.raisedLo
            edge = colors.line
            ink = colors.inkMuted
        }
    }
    Row(
        modifier
            .background(fill, CircleShape)
            .border(HlTheme.strokes.hairline, edge, CircleShape)
            .padding(horizontal = HlTheme.spacing.s8, vertical = HlTheme.spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s6),
    ) {
        if (dot) Box(Modifier.size(HlTheme.sizes.dotSmall).background(ink, CircleShape))
        HlText(text.uppercase(), HlTheme.typography.caps.copy(fontWeight = FontWeight.SemiBold), ink, maxLines = 1)
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
        HlText(
            body,
            HlTheme.typography.label
                .copy(fontWeight = FontWeight.Normal)
                .balanced(),
            colors.inkMuted,
        )
    }
}

/** The tag's and the notice icon's faint fill (Paper: brand #C8F03C1F; the danger tag the same 12 %). */
private const val TAG_FILL = 0.12f

/** The tag's edge (Paper: brand #C8F03C47; the danger tag the same 28 %). */
private const val TAG_EDGE = 0.28f

/** The notice's brand edge (Paper: #C8F03C33). */
private const val NOTICE_EDGE = 0.2f
