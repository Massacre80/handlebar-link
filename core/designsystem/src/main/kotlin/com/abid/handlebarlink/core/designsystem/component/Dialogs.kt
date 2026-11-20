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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.core.designsystem.theme.centred

/**
 * A dialog, laid out as the platform's dialog guidance describes ("Dialog": icon, title, text, a confirm and
 * a dismiss button): the icon on top, the question as the title, what will happen below it, then Cancel and
 * the action named by what it does, side by side. [destructive] (removing, deleting) puts the icon and the confirm
 * in the danger colour. Shown as a dialog destination (Navigation 3 DialogSceneStrategy), which gives the
 * scrim, Back and a tap outside as dismiss.
 */
@Composable
public fun HlDialog(
    @DrawableRes icon: Int,
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    dismiss: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val shape = RoundedCornerShape(HlTheme.radii.hero)
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    Column(
        modifier
            .widthIn(max = HlTheme.sizes.contentMax)
            .clip(shape)
            .background(colors.ground)
            // Night's ground is the page's: an edge keeps the dialog apart from the dimmed page.
            .then(if (night) Modifier.border(HlTheme.strokes.hairline, colors.lineStrong, shape) else Modifier)
            .padding(spacing.s24),
        horizontalAlignment = Alignment.CenterHorizontally,
        // The button row 14 dp under the words (owner, 2026-11-20: an action group keeps the group gap).
        verticalArrangement = Arrangement.spacedBy(spacing.groupGap),
    ) {
        DialogWords(icon, title, text, destructive)
        // Side by side (owner, 2026-11-20; Paper "Handlebar 7"): the dismiss as the secondary button at the start,
        // the action at the end, equal widths, 12 dp apart, as Android's dialogs place them.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.buttonRow)) {
            HlSecondaryButton(dismiss, onDismiss, Modifier.weight(1f))
            HlButton(confirm, onConfirm, Modifier.weight(1f), destructive = destructive)
        }
    }
}

/** The dialog's icon in its circle, then its title and text. */
@Composable
private fun DialogWords(
    @DrawableRes icon: Int,
    title: String,
    text: String,
    destructive: Boolean,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s16),
    ) {
        Box(
            Modifier
                .size(HlTheme.sizes.avatar)
                .background(
                    if (destructive) colors.danger.copy(alpha = DANGER_TINT) else colors.raisedLo,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            HlIcon(icon, if (destructive) colors.danger else colors.ink, size = HlTheme.sizes.iconSmall)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            HlText(
                title,
                HlTheme.typography.title.centred(),
                colors.ink,
                Modifier.semantics { heading() },
            )
            HlText(text, HlTheme.typography.body.centred(), colors.inkMuted)
        }
    }
}

/** The danger colour's tint behind a destructive dialog's icon. */
private const val DANGER_TINT = 0.16f
