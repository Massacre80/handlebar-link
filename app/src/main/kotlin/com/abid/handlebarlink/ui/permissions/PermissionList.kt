package com.abid.handlebarlink.ui.permissions

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlActionPill
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.pressScale
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/**
 * Permissions as one group (Paper "Setup 6", reused by Settings › Permissions): a row per permission with its
 * title and why it is needed; the call to action is the white pill ("Allow", or "Open settings" once
 * refused for good), and an allowed one shows a check instead. A row that still needs the rider is the
 * button.
 */
@Composable
fun PermissionList(items: List<PermissionItem>) {
    HlGroup {
        items.forEachIndexed { index, item ->
            if (index > 0) HlGroupDivider()
            PermissionRow(item)
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
                HlActionPill(stringResource(accessText(status)))
            }
        }
    }
}

/** The status word shown on a permission row. */
@StringRes
fun accessText(status: Access): Int =
    when (status) {
        Access.GRANTED -> R.string.access_granted
        Access.ASK -> R.string.access_ask
        Access.BLOCKED -> R.string.access_blocked
    }
