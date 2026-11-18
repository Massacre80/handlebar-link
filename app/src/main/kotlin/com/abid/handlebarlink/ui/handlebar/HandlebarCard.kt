package com.abid.handlebarlink.ui.handlebar

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.HANDLEBAR_DEFAULTS
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.core.designsystem.component.HlCard
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.navigation.containerContent
import com.abid.handlebarlink.ui.navigation.containerTransform
import com.abid.handlebarlink.ui.navigation.rememberContainerTransform

/**
 * Home's Handlebar card: the switch close up (handlebar_switch: an AI-generated studio render of the
 * CB350's left cube made from the owner's photos; the same in every look), "Handlebar controls", and
 * whether the rider changed anything; grows into the page. The render's corners follow the card's (radius
 * less the padding), the CarPlay card's layout.
 */
@Composable
fun HandlebarCard(
    mapping: Map<HandlebarSwitch, RiderAction>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = HlTheme.spacing
    val colors = HlTheme.colors
    val radius = HlTheme.radii.card
    val padding = spacing.s8
    val changed =
        mapping.count { (switch, action) ->
            HANDLEBAR_DEFAULTS.getValue(CallSituation.RIDING)[switch] !=
                action
        }
    val grow = rememberContainerTransform(HANDLEBAR, expanded = false)
    HlCard(modifier.containerTransform(grow), radius = radius, onClick = onOpen) {
        Row(
            Modifier.containerContent(grow).fillMaxWidth().padding(padding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s16),
        ) {
            Image(
                painterResource(R.drawable.handlebar_switch),
                contentDescription = null,
                modifier = Modifier.size(HlTheme.sizes.handlebarRender).clip(RoundedCornerShape(radius - padding)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                HlText(stringResource(R.string.handlebar_title), HlTheme.typography.value, colors.ink, maxLines = 2)
                HlText(
                    if (changed == 0) {
                        stringResource(R.string.handlebar_defaults)
                    } else {
                        pluralStringResource(R.plurals.handlebar_changed, changed, changed)
                    },
                    HlTheme.typography.meta,
                    colors.inkMuted,
                    maxLines = 2,
                )
            }
            HlIcon(
                HlIcons.ChevronRight,
                colors.inkMuted,
                Modifier.padding(end = spacing.s8),
                size = HlTheme.sizes.iconTiny,
            )
        }
    }
}
