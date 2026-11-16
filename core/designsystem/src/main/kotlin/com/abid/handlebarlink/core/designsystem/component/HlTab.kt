package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/** One destination of the tab bar. */
public data class HlTab(
    /** Its icon (an [HlIcons] entry). */
    @param:DrawableRes val icon: Int,
    /** Its label. */
    val label: String,
)

/**
 * The floating tab bar ("Component · Tab bar"): the active tab sits on a raised chip, the others share the
 * remaining width.
 */
@Composable
public fun HlTabBar(
    tabs: List<HlTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val sizes = HlTheme.sizes
    val bar = HlTheme.shadows.bar
    Row(
        modifier
            .fillMaxWidth()
            .height(sizes.tabBar)
            .dropShadow(
                CircleShape,
                Shadow(radius = bar.blur, color = colors.shadow, offset = DpOffset(0.dp, bar.offsetY)),
            ).clip(CircleShape)
            .background(colors.glass)
            .border(HlTheme.strokes.hairline, colors.glassEdge, CircleShape)
            .padding(horizontal = HlTheme.spacing.s6)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab -> TabItem(tab, index == selected) { onSelect(index) } }
    }
}

@Composable
private fun RowScope.TabItem(
    tab: HlTab,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = HlTheme.colors
    val sizes = HlTheme.sizes
    val chip = HlTheme.shadows.chip
    val item =
        Modifier
            .height(sizes.tab)
            .then(
                if (active) {
                    Modifier
                        .size(sizes.tabWidth, sizes.tab)
                        .then(
                            if (HlTheme.variant == HlThemeVariant.BENTO) {
                                Modifier.dropShadow(
                                    CircleShape,
                                    Shadow(
                                        radius = chip.blur,
                                        color = colors.shadow,
                                        offset = DpOffset(0.dp, chip.offsetY),
                                    ),
                                )
                            } else {
                                Modifier
                            },
                        ).clip(CircleShape)
                        .background(colors.raisedHi)
                } else {
                    Modifier.weight(1f).clip(CircleShape)
                },
            ).selectable(selected = active, role = Role.Tab, onClick = onClick)
    Column(
        item,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement =
            Arrangement.spacedBy(
                if (active) HlTheme.spacing.s2 else HlTheme.spacing.s4,
                Alignment.CenterVertically,
            ),
    ) {
        HlIcon(tab.icon, if (active) colors.ink else colors.inkSoft)
        HlText(
            tab.label,
            if (active) {
                HlTheme.typography.tab.copy(
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                HlTheme.typography.tab
            },
            if (active) colors.ink else colors.inkMuted,
            maxLines = 1,
        )
    }
}
