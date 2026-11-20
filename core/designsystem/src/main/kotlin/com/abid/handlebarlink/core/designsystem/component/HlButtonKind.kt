package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.InstrumentType

/** What an action button is (Paper "BikePlay — flow", owner 2026-11-20). */
public enum class HlButtonKind {
    /** What the rider does: lime with ink (Open BikePlay, a fix, Try now). */
    PRIMARY,

    /** Dismissing (Cancel): the well with a hairline edge. */
    SECONDARY,

    /** Ending something (Disconnect): danger at 12 % with a danger edge at 40 % and danger words. */
    DESTRUCTIVE,
}

/**
 * A 52 dp full-width pill of an action group ([kind]), with an optional [icon] before its [label] (Play 14 dp, others
 * 18 dp). Stacked buttons keep [com.abid.handlebarlink.core.designsystem.theme.HlSpacing.buttonStack] apart.
 */
@Composable
public fun HlActionButton(
    label: String,
    onClick: () -> Unit,
    kind: HlButtonKind,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    val colors = HlTheme.colors
    val (fill, edge, ink) =
        when (kind) {
            HlButtonKind.PRIMARY -> {
                Triple(colors.brand, Color.Transparent, colors.brandInk)
            }

            HlButtonKind.SECONDARY -> {
                Triple(colors.well, colors.line, colors.ink)
            }

            HlButtonKind.DESTRUCTIVE -> {
                Triple(
                    colors.danger.copy(alpha = BUTTON_DANGER_FILL),
                    colors.danger.copy(alpha = BUTTON_DANGER_EDGE),
                    colors.danger,
                )
            }
        }
    val press = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(press, HlTheme.motion)
            .fillMaxWidth()
            .heightIn(min = HlTheme.sizes.controlLarge)
            .clip(CircleShape)
            .background(fill)
            .border(HlTheme.strokes.hairline, edge, CircleShape)
            .clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8, Alignment.CenterHorizontally),
    ) {
        icon?.let {
            HlIcon(
                it,
                ink,
                size =
                    if (it ==
                        HlIcons.Play
                    ) {
                        HlTheme.sizes.iconMicro
                    } else {
                        HlTheme.sizes.iconTiny
                    },
            )
        }
        HlText(
            label,
            if (kind == HlButtonKind.PRIMARY) InstrumentType.button else InstrumentType.buttonQuiet,
            ink,
            maxLines = 1,
        )
    }
}

/** Paper's Disconnect: #FF6B4A1F fill, #FF6B4A66 edge. */
internal const val BUTTON_DANGER_FILL = 0.12f
internal const val BUTTON_DANGER_EDGE = 0.40f
