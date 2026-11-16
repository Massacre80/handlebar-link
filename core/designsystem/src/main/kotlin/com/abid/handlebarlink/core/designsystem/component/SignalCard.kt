package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/** What a [SignalCard] shows. */
public enum class SignalTone {
    /** Nothing running; an offer or an empty state. */
    IDLE,

    /** Starting; [SignalCard]'s progress fills the ring. */
    PROGRESS,

    /** Running. */
    ON,

    /** Failed. */
    ERROR,
}

/** The card's button. [primary]: filled; otherwise a quiet "Cancel" style. */
public data class SignalAction(
    /** The button's text. */
    val label: String,
    /** Filled (true) or quiet. */
    val primary: Boolean,
    /** Adds a forward chevron ("Return ›"). */
    val chevron: Boolean = false,
    /** What a tap does. */
    val onClick: () -> Unit,
)

/**
 * The CarPlay card (Home): a signal ring, a title and line, and one button. Night floats it as a dark glass
 * card tinted by [tone]; Bento shows a solid inverse card.
 */
@Composable
public fun SignalCard(
    title: String,
    subtitle: String,
    tone: SignalTone,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    action: SignalAction? = null,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    val radius = if (night) HlTheme.radii.floating else HlTheme.radii.hero
    val shape = RoundedCornerShape(radius)
    val accent = if (tone == SignalTone.ERROR) colors.danger else colors.brand
    val tint =
        when (tone) {
            SignalTone.IDLE -> Color.Transparent
            SignalTone.PROGRESS -> accent.copy(alpha = TINT_PROGRESS)
            SignalTone.ON -> if (night) accent.copy(alpha = TINT_STRONG) else Color.Transparent
            SignalTone.ERROR -> accent.copy(alpha = TINT_STRONG)
        }
    val edge =
        when (tone) {
            SignalTone.IDLE -> colors.line
            SignalTone.PROGRESS -> accent.copy(alpha = EDGE_PROGRESS)
            SignalTone.ON -> colors.surfaceInverseEdge
            SignalTone.ERROR -> accent.copy(alpha = EDGE_ERROR)
        }
    val shadow = HlTheme.shadows.floating
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (night) {
                    Modifier.dropShadow(
                        shape,
                        Shadow(radius = shadow.blur, color = colors.shadow, offset = DpOffset(0.dp, shadow.offsetY)),
                    )
                } else {
                    Modifier
                },
            ).clip(shape)
            .background(colors.surfaceInverse)
            .background(Brush.linearGradient(listOf(tint, colors.inkInverse.copy(alpha = TINT_FADE))))
            .then(if (night) Modifier.border(HlTheme.strokes.hairline, edge, shape) else Modifier)
            .padding(if (night) spacing.s8 else spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        SignalBadge(tone, accent, progress)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            HlText(title, HlTheme.typography.value, colors.inkInverse, maxLines = 1)
            HlText(subtitle, HlTheme.typography.meta, colors.inkInverseMuted, maxLines = 1)
        }
        action?.let { SignalButton(it, tone, night) }
    }
}

@Composable
private fun SignalBadge(
    tone: SignalTone,
    accent: Color,
    progress: Float?,
) {
    val colors = HlTheme.colors
    val dim = colors.inkInverse.copy(alpha = DIM)
    Box(contentAlignment = Alignment.Center) {
        when (tone) {
            SignalTone.IDLE -> SignalRing(dim, dim, colors.inkInverse.copy(alpha = TINT_FADE), null)
            SignalTone.PROGRESS -> SignalRing(accent, dim, accent.copy(alpha = TINT_PROGRESS), progress ?: 0f)
            SignalTone.ON -> SignalRing(accent, accent, accent.copy(alpha = TINT_PROGRESS), null)
            SignalTone.ERROR -> SignalRing(accent, accent, accent.copy(alpha = DISC_ERROR), null)
        }
        val icon = HlTheme.sizes.iconSmall
        when (tone) {
            SignalTone.IDLE -> {
                HlIcon(HlIcons.SignalOuter, colors.inkInverse, size = icon)
                HlIcon(HlIcons.SignalInner, colors.inkInverse, size = icon)
            }

            SignalTone.PROGRESS -> {
                HlIcon(HlIcons.SignalOuter, colors.inkInverse.copy(alpha = OUTER_ARCS), size = icon)
                HlIcon(HlIcons.SignalInner, accent, size = icon)
            }

            SignalTone.ON -> {
                HlIcon(HlIcons.SignalOuter, accent, size = icon)
                HlIcon(HlIcons.SignalInner, accent, size = icon)
            }

            SignalTone.ERROR -> {
                HlIcon(HlIcons.SignalOff, accent, size = icon)
            }
        }
    }
}

@Composable
private fun SignalButton(
    action: SignalAction,
    tone: SignalTone,
    night: Boolean,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val height = if (night) HlTheme.sizes.action else HlTheme.sizes.chip
    // Artboards: an offer ("Connect") is brand in both looks, a retry is white in both, "Return" follows
    // the look's action colour (white on Night, brand on Bento).
    val fill =
        when {
            !action.primary -> colors.inkInverse.copy(alpha = QUIET_FILL)
            tone == SignalTone.IDLE -> colors.brand
            tone == SignalTone.ERROR -> colors.inkInverse
            else -> colors.action
        }
    val ink =
        when {
            !action.primary -> colors.inkInverse
            tone == SignalTone.IDLE -> colors.brandInk
            else -> colors.onAction
        }
    val style =
        if (night) HlTheme.typography.value else HlTheme.typography.label.copy(fontWeight = FontWeight.SemiBold)
    // The visual pill can be 36 dp (Bento); the touch target stays at least 48 dp.
    Box(
        Modifier
            .sizeIn(minHeight = HlTheme.sizes.touchTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = action.onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(height)
                .clip(CircleShape)
                .background(fill)
                .then(
                    if (action.primary) {
                        Modifier
                    } else {
                        Modifier.border(
                            HlTheme.strokes.hairline,
                            colors.inkInverse.copy(alpha = QUIET_EDGE),
                            CircleShape,
                        )
                    },
                ).padding(
                    start = if (night) spacing.s18 else spacing.s14,
                    end = if (action.chevron) spacing.s12 else spacing.s18,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s4),
        ) {
            HlText(action.label, style, ink, maxLines = 1)
            if (action.chevron) HlIcon(HlIcons.ChevronRight, ink, size = HlTheme.sizes.iconTiny)
        }
    }
}

// Alphas from the artboards: card tints #C8F03C24 / #C8F03C14 / #FF6B4A24, edges #C8F03C2E / #FF6B4A52,
// the fade stop #FFFFFF05, rings rgb(255 255 255 / 18%), outer arcs 35%, error disc 10%, Cancel #FFFFFF1A / 1F.
private const val TINT_STRONG = 0.14f
private const val TINT_PROGRESS = 0.08f
private const val TINT_FADE = 0.02f
private const val EDGE_PROGRESS = 0.18f
private const val EDGE_ERROR = 0.32f
private const val DIM = 0.18f
private const val OUTER_ARCS = 0.35f
private const val DISC_ERROR = 0.10f
private const val QUIET_FILL = 0.10f
private const val QUIET_EDGE = 0.12f
