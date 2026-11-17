package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/**
 * A dialog, laid out as the platform's dialog guidance describes ("Dialog": icon, title, text, a confirm and
 * a dismiss button): the icon on top, the question as the title, what will happen below it, then the
 * action named by what it does and Cancel. [destructive] (removing, deleting) puts the icon and the confirm
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
                HlTheme.typography.title.copy(textAlign = TextAlign.Center),
                colors.ink,
                Modifier.semantics { heading() },
            )
            HlText(text, HlTheme.typography.body.copy(textAlign = TextAlign.Center), colors.inkMuted)
        }
        Column(
            Modifier.fillMaxWidth().padding(top = spacing.s4),
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            HlButton(confirm, onConfirm, Modifier.fillMaxWidth(), destructive = destructive)
            HlQuietButton(dismiss, onDismiss, Modifier.fillMaxWidth())
        }
    }
}

/** The danger colour's tint behind a destructive dialog's icon. */
private const val DANGER_TINT = 0.16f
