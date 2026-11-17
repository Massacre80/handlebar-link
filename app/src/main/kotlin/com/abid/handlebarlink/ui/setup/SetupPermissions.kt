package com.abid.handlebarlink.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlCard
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.pressScale
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.settings.accessText

/**
 * The permissions on "You're set" (Paper "Setup 6"): one glass card, a row per permission with its title,
 * why it is needed and the white Allow pill; an allowed row shows a check instead. Rows are split by a
 * hairline; a row that still needs the rider is the button.
 */
@Composable
internal fun PermissionCard(items: List<PermissionItem>) {
    HlCard(Modifier.fillMaxWidth(), radius = HlTheme.radii.card) {
        Column {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = HlTheme.spacing.s16)
                            .height(HlTheme.strokes.hairline)
                            .background(HlTheme.colors.lineSoft),
                    )
                }
                PermissionRow(item)
            }
        }
    }
}

@Composable
private fun PermissionRow(item: PermissionItem) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val motion = HlTheme.motion
    val press = remember { MutableInteractionSource() }
    val open = item.status != Access.GRANTED
    Row(
        Modifier
            .pressScale(press, motion)
            .fillMaxWidth()
            .then(
                if (open) {
                    Modifier.clickable(
                        press,
                        LocalIndication.current,
                        role = Role.Button,
                        onClick = item.onAct,
                    )
                } else {
                    Modifier
                },
            ).padding(start = spacing.s16, end = spacing.s14, top = spacing.s14, bottom = spacing.s14),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            HlText(stringResource(item.title), HlTheme.typography.value, colors.ink)
            HlText(stringResource(item.why), HlTheme.typography.meta, colors.inkMuted)
        }
        AnimatedContent(
            item.status,
            transitionSpec = { fadeIn(motion.fade()) togetherWith fadeOut(motion.fade()) },
            label = "permission",
        ) { status ->
            if (status == Access.GRANTED) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.s6),
                ) {
                    HlIcon(HlIcons.Check, colors.brand, size = HlTheme.sizes.iconTiny)
                    HlText(
                        stringResource(R.string.access_granted),
                        HlTheme.typography.label,
                        colors.inkMuted,
                        maxLines = 1,
                    )
                }
            } else {
                ActionPill(stringResource(accessText(status)))
            }
        }
    }
}
