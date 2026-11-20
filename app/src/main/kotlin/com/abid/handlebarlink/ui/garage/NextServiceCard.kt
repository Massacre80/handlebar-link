package com.abid.handlebarlink.ui.garage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlButton
import com.abid.handlebarlink.core.designsystem.component.HlCard
import com.abid.handlebarlink.core.designsystem.component.HlDueArc
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.balanced
import com.abid.handlebarlink.garage.Due
import com.abid.handlebarlink.garage.DueState
import com.abid.handlebarlink.garage.grouped

/**
 * The next service (Paper "Garage 2", "Garage 3", "Garage 2L"): the gauge of the interval used, the days left over
 * the km left, the due date and the interval at its feet (upright; on its side the date joins the caption). Due soon
 * or overdue it asks for the service with the brand "Log this service" (owner: primary is what the rider must do); the
 * gauge and that button are the service's status, shown once. The card opens the service interval.
 */
@Composable
internal fun NextServiceCard(
    state: GarageUiState,
    actions: GarageActions,
    landscape: Boolean,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val service = state.status.service
    HlCard(Modifier.fillMaxWidth(), onClick = actions.onServiceInterval) {
        Column(Modifier.padding(spacing.s16), verticalArrangement = Arrangement.spacedBy(spacing.s14)) {
            if (service == null) {
                CardTitle()
                HlText(
                    stringResource(R.string.garage_service_none),
                    HlTheme.typography.body.balanced(),
                    colors.inkMuted,
                )
                HlButton(stringResource(R.string.garage_log_last), actions.onLogService, brand = true)
                return@Column
            }
            // The corner on its own row and the gauge under it, as on the BikePlay card: nothing overlaps.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CardTitle(Modifier.weight(1f))
                HlIcon(HlIcons.ChevronRight, colors.inkMuted, size = HlTheme.sizes.iconSmall)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (landscape) Arrangement.Center else Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                if (!landscape) {
                    Foot(
                        service.dueDay?.let(::dayMonth) ?: "",
                        stringResource(R.string.garage_due_by),
                        Alignment.Start,
                    )
                }
                Gauge(service, state, landscape)
                if (!landscape) {
                    Foot(
                        stringResource(R.string.garage_months_short, state.data.service.months),
                        stringResource(R.string.garage_every),
                        Alignment.End,
                    )
                }
            }
            if (service.state == DueState.SOON || service.state == DueState.OVERDUE) {
                HlButton(stringResource(R.string.garage_log_this), actions.onLogService, brand = true)
            }
        }
    }
}

/** The card's corner: the wrench and "Next service". */
@Composable
private fun CardTitle(modifier: Modifier = Modifier) {
    val colors = HlTheme.colors
    Row(
        modifier.padding(start = HlTheme.spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s6),
    ) {
        HlIcon(HlIcons.Garage, colors.inkMuted, size = HlTheme.sizes.iconMicro)
        HlText(
            stringResource(R.string.garage_next_service),
            HlTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
            colors.inkMuted,
            maxLines = 1,
        )
    }
}

/** The gauge: lit by the share of the interval used, brand; overdue full in danger; due soon with its end dot. */
@Composable
private fun Gauge(
    service: Due,
    state: GarageUiState,
    landscape: Boolean,
) {
    val colors = HlTheme.colors
    val days = service.daysLeft ?: 0
    val kmLeft = service.kmLeft
    val overdue = service.state == DueState.OVERDUE
    val value =
        when {
            overdue -> stringResource(R.string.garage_overdue)
            days == 0L -> stringResource(R.string.garage_today)
            else -> pluralStringResource(R.plurals.garage_days, days.toInt(), days.toInt())
        }
    val day = service.dueDay?.let(::dayMonth) ?: ""
    val caption =
        when {
            overdue && days < 0 -> stringResource(R.string.garage_since, day)
            overdue && kmLeft != null -> stringResource(R.string.garage_km_over, grouped(-kmLeft))
            kmLeft != null && landscape -> stringResource(R.string.garage_or_km_by, grouped(kmLeft), day)
            kmLeft != null -> stringResource(R.string.garage_or_km, grouped(kmLeft))
            else -> stringResource(R.string.garage_by_day, day)
        }
    HlDueArc(
        value = value,
        caption = caption,
        used = if (overdue) 1f else service.used,
        color = if (overdue) colors.danger else colors.brand,
        endDot = service.state == DueState.SOON || overdue,
        ground = colors.ground,
    )
}

/** A foot under the gauge's ends: a figure over its label (Paper: 72 dp wide). */
@Composable
private fun Foot(
    value: String,
    label: String,
    alignment: Alignment.Horizontal,
) {
    val colors = HlTheme.colors
    val align = if (alignment == Alignment.End) TextAlign.End else TextAlign.Start
    Column(
        Modifier.width(FOOT_DP.dp),
        horizontalAlignment = alignment,
        verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s2),
    ) {
        HlText(value, HlTheme.typography.lead, colors.ink, maxLines = 1, textAlign = align)
        HlText(label, HlTheme.typography.label, colors.inkMuted, maxLines = 1, textAlign = align)
    }
}

/** Paper "Garage 2": each foot is 72 dp. */
private const val FOOT_DP = 72
