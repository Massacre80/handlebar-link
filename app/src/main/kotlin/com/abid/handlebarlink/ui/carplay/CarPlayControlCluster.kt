package com.abid.handlebarlink.ui.carplay

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/**
 * The floating control cluster over the CarPlay picture (Paper "BikePlay · Overlay controls"): collapsed to a single
 * handle, or expanded to Back, Home and Settings with a handle to collapse it again. The caller shows it only while the
 * iPhone is connected, since there is nothing to navigate otherwise.
 */
@Composable
internal fun CarPlayControlCluster(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ClusterPanel(expanded, { expanded = !expanded }, onBack, onHome, onSettings, modifier)
}

/**
 * The cluster's look for a given [expanded] state. A dark ink-glass panel so it stays legible over any picture
 * underneath, bright map or album art; it consumes its own taps so the rest of the screen still reaches CarPlay.
 */
@Composable
private fun ClusterPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    Column(
        modifier
            .consumeTaps()
            .background(colors.surfaceInverse.copy(alpha = PANEL_ALPHA), CircleShape)
            .border(HlTheme.strokes.hairline, colors.inkInverse.copy(alpha = EDGE_ALPHA), CircleShape)
            .padding(HlTheme.spacing.s8)
            .animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8),
    ) {
        if (expanded) {
            ClusterButton(HlIcons.ChevronRight, stringResource(R.string.back), colors.inkInverse, onBack, BACK_TURN)
            ClusterButton(HlIcons.Home, stringResource(R.string.carplay_home), colors.inkInverse, onHome)
            ClusterButton(HlIcons.Settings, stringResource(R.string.settings), colors.inkInverse, onSettings)
            Box(
                Modifier
                    .width(HlTheme.sizes.iconMedium)
                    .height(1.dp)
                    .background(colors.inkInverse.copy(alpha = EDGE_ALPHA)),
            )
            ClusterButton(
                HlIcons.ChevronRight,
                stringResource(R.string.carplay_hide_controls),
                colors.inkInverseMuted,
                onToggle,
                COLLAPSE_TURN,
            )
        } else {
            ClusterButton(
                HlIcons.ChevronRight,
                stringResource(R.string.carplay_controls),
                colors.inkInverse,
                onToggle,
                EXPAND_TURN,
            )
        }
    }
}

/** One round button on the glass cluster: an icon in [tint] on a transparent circle, [turn] degrees rotated. */
@Composable
private fun ClusterButton(
    @DrawableRes icon: Int,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    turn: Float = 0f,
) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(HlTheme.sizes.touchTarget)
            .clip(CircleShape)
            .clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        HlIcon(
            icon,
            tint,
            size = HlTheme.sizes.iconMedium,
            modifier = Modifier.rotate(turn),
            contentDescription = description,
        )
    }
}

/** Keeps the panel's own taps from falling through to the CarPlay picture underneath; its buttons still take theirs. */
private fun Modifier.consumeTaps(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent().changes.forEach { it.consume() }
            }
        }
    }

@Suppress("MagicNumber") // The preview's stand-in for a CarPlay picture behind the glass.
@Preview(name = "BikePlay controls", widthDp = 360, heightDp = 320)
@Composable
private fun ClusterPreview() =
    HlTheme(HlThemeVariant.NIGHT) {
        Box(Modifier.fillMaxSize().background(Color(0xFF3B4030)), contentAlignment = Alignment.BottomStart) {
            Row(
                Modifier.padding(HlTheme.spacing.s16),
                horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s24),
                verticalAlignment = Alignment.Bottom,
            ) {
                ClusterPanel(expanded = false, {}, {}, {}, {})
                ClusterPanel(expanded = true, {}, {}, {}, {})
            }
        }
    }

private const val PANEL_ALPHA = 0.82f
private const val EDGE_ALPHA = 0.12f
private const val BACK_TURN = 180f
private const val COLLAPSE_TURN = 90f
private const val EXPAND_TURN = 270f
