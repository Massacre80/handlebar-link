package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/** What one direction of an [HlDirectionPad] does; [unset] dims it (the press does nothing). */
public data class HlPadLabel(
    /** What the press does ("Next track"). */
    val text: String,
    /** The press does nothing: drawn dimmed. */
    val unset: Boolean = false,
)

/**
 * A four-way switch as a picture, each direction labelled with what it does (like the earbuds and headphone
 * apps' button screens): the arrows point out from a centre ring, an unset direction is dimmed. TalkBack
 * reads [description], one line for the whole pad.
 */
@Composable
public fun HlDirectionPad(
    up: HlPadLabel,
    down: HlPadLabel,
    left: HlPadLabel,
    right: HlPadLabel,
    description: String,
    modifier: Modifier = Modifier,
) {
    val spacing = HlTheme.spacing
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s4),
    ) {
        Direction(up, UP)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { Direction(left, LEFT, inline = true) }
            Box(
                Modifier
                    .size(HlTheme.sizes.chip)
                    .border(HlTheme.strokes.outline, HlTheme.colors.lineStrong, CircleShape),
            )
            Box(
                Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) { Direction(right, RIGHT, inline = true) }
        }
        Direction(down, DOWN, arrowLast = true)
    }
}

/** One direction: its arrow (pointing away from the centre) and what it does. */
@Composable
private fun Direction(
    label: HlPadLabel,
    angle: Float,
    inline: Boolean = false,
    arrowLast: Boolean = false,
) {
    val colors = HlTheme.colors
    val tint = if (label.unset) colors.inkMuted else colors.ink
    val arrow: @Composable () -> Unit = {
        HlIcon(HlIcons.ChevronRight, tint, Modifier.rotate(angle), size = HlTheme.sizes.iconTiny)
    }
    val text: @Composable () -> Unit = {
        HlText(
            label.text,
            HlTheme.typography.label,
            tint,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
    if (inline) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s4),
        ) {
            // Left reads "label ‹", right reads "› label": the arrow always next to the centre.
            if (angle == LEFT) {
                text()
                arrow()
            } else {
                arrow()
                text()
            }
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (arrowLast) {
                text()
                arrow()
            } else {
                arrow()
                text()
            }
        }
    }
}

private const val UP = -90f
private const val DOWN = 90f
private const val LEFT = 180f
private const val RIGHT = 0f
