package com.abid.handlebarlink.ui.garage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlOptionRow
import com.abid.handlebarlink.core.designsystem.component.HlSwitchRow
import com.abid.handlebarlink.core.designsystem.component.HlTextField
import com.abid.handlebarlink.core.designsystem.component.HlValueRow
import com.abid.handlebarlink.garage.ServiceInput
import com.abid.handlebarlink.garage.ServiceKind
import com.abid.handlebarlink.garage.ServicePlan
import com.abid.handlebarlink.garage.grouped
import com.abid.handlebarlink.garage.serviceKindName
import java.time.LocalDate

/**
 * "Log a service" (Paper "Garage 5"): the odometer at the workshop, what was done (a list that opens in place), the
 * date (today by default, never later), the optional workshop and cost (they open in place), and for a general service
 * the switch that restarts the count (on), with when the next one then falls due.
 */
@Composable
fun LogServiceSheet(
    today: LocalDate,
    plan: ServicePlan,
    lastKm: Int?,
    onSave: (ServiceInput) -> Unit,
    onCancel: () -> Unit,
) {
    val km = rememberTextFieldState(lastKm?.toString() ?: "")
    val workshop = rememberTextFieldState()
    val cost = rememberTextFieldState()
    var kind by rememberSaveable { mutableStateOf(ServiceKind.GENERAL) }
    var day by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var restart by rememberSaveable { mutableStateOf(true) }
    val date = LocalDate.ofEpochDay(day)
    GarageSheet(
        HlIcons.Garage,
        stringResource(R.string.garage_log_service),
        stringResource(R.string.garage_log_detail),
    ) {
        Labelled(stringResource(R.string.garage_odometer), stringResource(R.string.garage_odometer_help)) {
            NumberField(km, stringResource(R.string.garage_km_unit), stringResource(R.string.garage_odometer))
        }
        HlGroup {
            KindRows(kind) { kind = it }
            HlGroupDivider()
            DateRow(date, today) { day = minOf(it, today).toEpochDay() }
            HlGroupDivider()
            ExtrasRows(workshop, cost)
        }
        if (kind == ServiceKind.GENERAL) RestartRow(restart, { restart = it }, date, today, plan, km.number())
        val entered = km.number()
        SaveCancel(
            stringResource(R.string.garage_save),
            canSave = entered != null && entered > 0,
            onSave = {
                val extra =
                    workshop.text
                        .toString()
                        .trim()
                        .ifEmpty { null }
                onSave(
                    ServiceInput(
                        kind,
                        date,
                        entered ?: 0,
                        extra,
                        cost.number(),
                        restart && kind == ServiceKind.GENERAL,
                    ),
                )
            },
            stringResource(R.string.cancel),
            onCancel,
        )
    }
}

/** "Restart the service count", with when the next service then falls due. */
@Composable
private fun RestartRow(
    restart: Boolean,
    onRestart: (Boolean) -> Unit,
    date: LocalDate,
    today: LocalDate,
    plan: ServicePlan,
    km: Int?,
) {
    HlGroup {
        HlSwitchRow(
            stringResource(R.string.garage_restart),
            restart,
            onRestart,
            detail =
                stringResource(
                    R.string.garage_restart_detail,
                    shortDate(date.plusMonths(plan.months.toLong()), today),
                    grouped((km ?: 0) + plan.km),
                ),
            container = Color.Transparent,
        )
    }
}

/** "What was done" and, opened in place, the kinds to choose from. */
@Composable
private fun KindRows(
    kind: ServiceKind,
    onKind: (ServiceKind) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    HlValueRow(
        stringResource(R.string.garage_what),
        stringResource(serviceKindName(kind)),
        onClick = { choosing = !choosing },
        container = Color.Transparent,
        chevron = true,
    )
    AnimatedVisibility(choosing) {
        Column(Modifier.selectableGroup()) {
            ServiceKind.entries.forEach { option ->
                HlGroupDivider()
                HlOptionRow(
                    icon = null,
                    title = stringResource(serviceKindName(option)),
                    subtitle = stringResource(serviceKindDetail(option)),
                    selected = option == kind,
                    onClick = {
                        onKind(option)
                        choosing = false
                    },
                    container = Color.Transparent,
                )
            }
        }
    }
}

/** "Date": today by default; the platform's date picker. */
@Composable
private fun DateRow(
    date: LocalDate,
    today: LocalDate,
    onDate: (LocalDate) -> Unit,
) {
    val pick = rememberDatePicker(date, onDate)
    HlValueRow(
        stringResource(R.string.garage_date),
        if (date ==
            today
        ) {
            stringResource(R.string.garage_today_day, shortDate(date, today))
        } else {
            shortDate(date, today)
        },
        onClick = pick,
        container = Color.Transparent,
        chevron = true,
    )
}

/** "Workshop and cost" ("Optional", or what was entered) and, opened in place, its two fields. */
@Composable
private fun ExtrasRows(
    workshop: TextFieldState,
    cost: TextFieldState,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    HlValueRow(
        stringResource(R.string.garage_extras),
        extrasHint(workshop.text.toString(), cost.number()),
        onClick = { open = !open },
        container = Color.Transparent,
        chevron = true,
    )
    AnimatedVisibility(open) {
        Column {
            HlGroupDivider()
            HlTextField(
                workshop,
                hint = null,
                placeholder = stringResource(R.string.garage_workshop),
                container = Color.Transparent,
            )
            HlGroupDivider()
            NumberField(cost, stringResource(R.string.garage_rupees), stringResource(R.string.garage_cost_hint))
        }
    }
}

@Composable
private fun extrasHint(
    workshop: String,
    cost: Int?,
): String {
    val parts =
        listOfNotNull(workshop.trim().ifEmpty { null }, cost?.let { stringResource(R.string.garage_cost, grouped(it)) })
    return if (parts.isEmpty()) stringResource(R.string.garage_optional) else parts.joinToString(" · ")
}

private fun serviceKindDetail(kind: ServiceKind): Int =
    when (kind) {
        ServiceKind.GENERAL -> R.string.garage_kind_general_detail
        ServiceKind.OIL -> R.string.garage_kind_oil_detail
        ServiceKind.CHAIN -> R.string.garage_kind_chain_detail
        ServiceKind.TYRES -> R.string.garage_kind_tyres_detail
        ServiceKind.BRAKES -> R.string.garage_kind_brakes_detail
        ServiceKind.OTHER -> R.string.garage_kind_other_detail
    }
