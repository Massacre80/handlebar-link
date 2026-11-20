package com.abid.handlebarlink.ui.garage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlDangerButton
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlOptionRow
import com.abid.handlebarlink.core.designsystem.component.HlSecondaryButton
import com.abid.handlebarlink.core.designsystem.component.HlSegment
import com.abid.handlebarlink.core.designsystem.component.HlSegmentedControl
import com.abid.handlebarlink.core.designsystem.component.HlSwitchRow
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.HlTextField
import com.abid.handlebarlink.core.designsystem.component.HlValueRow
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.garage.ChainPlan
import com.abid.handlebarlink.garage.Colourway
import com.abid.handlebarlink.garage.CustomReminder
import com.abid.handlebarlink.garage.DEFAULT_SERVICE_KM
import com.abid.handlebarlink.garage.DEFAULT_SERVICE_MONTHS
import com.abid.handlebarlink.garage.Lead
import com.abid.handlebarlink.garage.Paper
import com.abid.handlebarlink.garage.PaperKind
import com.abid.handlebarlink.garage.ServiceKind
import com.abid.handlebarlink.garage.ServicePlan
import com.abid.handlebarlink.garage.grouped
import com.abid.handlebarlink.garage.serviceKindName
import java.time.LocalDate

/** The odometer: what the meter reads now. */
@Composable
fun OdometerSheet(
    current: Int?,
    onSave: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val km = rememberTextFieldState(current?.toString() ?: "")
    GarageSheet(
        R.drawable.garage_ic_odometer,
        stringResource(R.string.garage_odometer),
        stringResource(R.string.garage_odometer_read),
    ) {
        Labelled(stringResource(R.string.garage_odometer), stringResource(R.string.garage_odometer_sheet_help)) {
            NumberField(km, stringResource(R.string.garage_km_unit), stringResource(R.string.garage_odometer))
        }
        val entered = km.number()
        SaveCancel(
            stringResource(R.string.garage_save),
            entered != null && entered > 0,
            { entered?.let(onSave) },
            stringResource(R.string.cancel),
            onCancel,
        )
    }
}

/** The service interval, months and km, whichever comes first; Honda's schedule as the help line. */
@Composable
fun ServiceIntervalSheet(
    plan: ServicePlan,
    onSave: (months: Int, km: Int) -> Unit,
    onCancel: () -> Unit,
) {
    val months = rememberTextFieldState(plan.months.toString())
    val km = rememberTextFieldState(plan.km.toString())
    GarageSheet(
        HlIcons.Garage,
        stringResource(R.string.garage_interval),
        stringResource(R.string.garage_interval_detail),
    ) {
        Labelled(stringResource(R.string.garage_interval_months)) {
            NumberField(
                months,
                stringResource(R.string.garage_months_unit),
                stringResource(R.string.garage_interval_months),
                maxDigits = MONTH_DIGITS,
            )
        }
        Labelled(
            stringResource(R.string.garage_interval_km),
            stringResource(
                R.string.garage_interval_help,
                pluralStringResource(R.plurals.garage_months, DEFAULT_SERVICE_MONTHS, DEFAULT_SERVICE_MONTHS),
                grouped(DEFAULT_SERVICE_KM),
            ),
        ) {
            NumberField(km, stringResource(R.string.garage_km_unit), stringResource(R.string.garage_interval_km))
        }
        val m = months.number()
        val k = km.number()
        SaveCancel(
            stringResource(R.string.garage_save),
            m != null && m > 0 && k != null && k > 0,
            { if (m != null && k != null) onSave(m, k) },
            stringResource(R.string.cancel),
            onCancel,
        )
    }
}

/** The chain: its interval, and "Done it now" at the latest reading (needs one). */
@Composable
fun ChainSheet(
    plan: ChainPlan,
    latestKm: Int?,
    onSave: (intervalKm: Int, doneNow: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val km = rememberTextFieldState(plan.km.toString())
    var done by rememberSaveable { mutableStateOf(latestKm != null && plan.startKm == null) }
    GarageSheet(
        R.drawable.garage_ic_chain,
        stringResource(R.string.garage_chain),
        stringResource(R.string.garage_chain_detail),
    ) {
        Labelled(stringResource(R.string.garage_chain_every)) {
            NumberField(km, stringResource(R.string.garage_km_unit), stringResource(R.string.garage_chain_every))
        }
        HlGroup {
            HlSwitchRow(
                stringResource(R.string.garage_chain_now),
                done && latestKm != null,
                { if (latestKm != null) done = it },
                detail =
                    if (latestKm != null) {
                        stringResource(R.string.garage_chain_now_detail, grouped(latestKm))
                    } else {
                        stringResource(R.string.garage_chain_needs_odometer)
                    },
                container = Color.Transparent,
            )
        }
        val interval = km.number()
        SaveCancel(
            stringResource(R.string.garage_save),
            interval != null && interval > 0,
            { interval?.let { onSave(it, done && latestKm != null) } },
            stringResource(R.string.cancel),
            onCancel,
        )
    }
}

/** A paper (Paper "Garage 6"): the day it runs out (Change opens the date picker) and how early to remind. */
@Composable
fun PaperSheet(
    kind: PaperKind,
    paper: Paper?,
    today: LocalDate,
    onSave: (LocalDate, Lead) -> Unit,
    onCancel: () -> Unit,
) {
    var expiry by rememberSaveable { mutableStateOf(paper?.expiryDay) }
    var lead by rememberSaveable { mutableStateOf(paper?.lead ?: Lead.MONTH) }
    val day = expiry?.let(LocalDate::ofEpochDay)
    val pick = rememberDatePicker(day ?: today.plusYears(1)) { expiry = it.toEpochDay() }
    val (icon, title, help) =
        when (kind) {
            PaperKind.INSURANCE -> {
                Triple(
                    R.drawable.garage_ic_shield,
                    R.string.garage_insurance,
                    R.string.garage_paper_help_insurance,
                )
            }

            PaperKind.PUC -> {
                Triple(R.drawable.garage_ic_leaf, R.string.garage_puc, R.string.garage_paper_help_puc)
            }

            PaperKind.RC -> {
                Triple(R.drawable.garage_ic_paper, R.string.garage_rc, R.string.garage_paper_help_rc)
            }
        }
    GarageSheet(icon, stringResource(title), stringResource(R.string.garage_paper_detail)) {
        Labelled(stringResource(R.string.garage_runs_out), stringResource(help)) {
            DateField(
                day?.let(::fullDate),
                stringResource(R.string.garage_choose_date),
                stringResource(if (day != null) R.string.garage_change else R.string.garage_add),
                pick,
            )
        }
        Labelled(stringResource(R.string.garage_remind_me)) {
            HlGroup(Modifier.selectableGroup()) {
                Lead.entries.forEachIndexed { index, option ->
                    if (index > 0) HlGroupDivider()
                    HlOptionRow(
                        icon = null,
                        title = stringResource(leadName(option)),
                        subtitle = leadDetail(option, day, today),
                        selected = option == lead,
                        onClick = { lead = option },
                        container = Color.Transparent,
                        accent = true,
                    )
                }
            }
        }
        SaveCancel(
            stringResource(R.string.garage_save),
            day != null,
            { day?.let { onSave(it, lead) } },
            stringResource(R.string.cancel),
            onCancel,
        )
    }
}

/** When the reminder comes: "On 12 Feb, and again a week before", "On 28 Feb"; without a date, how far ahead. */
@Composable
private fun leadDetail(
    lead: Lead,
    day: LocalDate?,
    today: LocalDate,
): String {
    val on = day?.minusDays(lead.days.toLong())?.let { shortDate(it, today) }
    return when {
        on == null -> pluralStringResource(R.plurals.garage_lead_days, lead.days, lead.days)
        lead == Lead.MONTH -> stringResource(R.string.garage_lead_month_detail, on)
        else -> stringResource(R.string.garage_on_day, on)
    }
}

private fun leadName(lead: Lead): Int =
    when (lead) {
        Lead.MONTH -> R.string.garage_lead_month
        Lead.TWO_WEEKS -> R.string.garage_lead_two_weeks
        Lead.WEEK -> R.string.garage_lead_week
    }

/**
 * A reminder of the rider's own: its name, and a date or a km on the meter. An existing one also has its red-tinted
 * Delete, which asks first ([onDelete]).
 */
@Composable
fun ReminderSheet(
    reminder: CustomReminder?,
    today: LocalDate,
    onSave: (name: String, day: LocalDate?, km: Int?) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    val name = rememberTextFieldState(reminder?.name ?: "")
    val km = rememberTextFieldState(reminder?.dueKm?.toString() ?: "")
    var byKm by rememberSaveable { mutableStateOf(reminder?.dueKm != null) }
    var dueDay by rememberSaveable { mutableStateOf(reminder?.dueDay) }
    val day = dueDay?.let(LocalDate::ofEpochDay)
    val pick = rememberDatePicker(day ?: today.plusMonths(1)) { dueDay = it.toEpochDay() }
    GarageSheet(HlIcons.Bell, stringResource(R.string.garage_custom), stringResource(R.string.garage_reminder_detail)) {
        Labelled(stringResource(R.string.garage_reminder_name)) {
            HlTextField(name, hint = null, placeholder = stringResource(R.string.garage_reminder_name_hint))
        }
        HlSegmentedControl(
            listOf(
                HlSegment(HlIcons.Time, stringResource(R.string.garage_by_date)),
                HlSegment(R.drawable.garage_ic_odometer, stringResource(R.string.garage_by_km)),
            ),
            selected = if (byKm) 1 else 0,
            onSelect = { byKm = it == 1 },
        )
        if (byKm) {
            Labelled(stringResource(R.string.garage_due_at)) {
                NumberField(km, stringResource(R.string.garage_km_unit), stringResource(R.string.garage_due_at))
            }
        } else {
            Labelled(stringResource(R.string.garage_due_on)) {
                DateField(
                    day?.let(::fullDate),
                    stringResource(R.string.garage_choose_date),
                    stringResource(if (day != null) R.string.garage_change else R.string.garage_add),
                    pick,
                )
            }
        }
        val title = name.text.toString().trim()
        val kmValue = km.number()
        val complete = title.isNotEmpty() && if (byKm) kmValue != null && kmValue > 0 else day != null
        SaveCancel(
            stringResource(R.string.garage_save),
            complete,
            { if (complete) onSave(title, if (byKm) null else day, if (byKm) kmValue else null) },
            stringResource(R.string.cancel),
            onCancel,
        )
        onDelete?.let { HlDangerButton(stringResource(R.string.garage_delete_reminder), it) }
    }
}

/**
 * "Colourway" (Paper "Garage 8"): the colourways with a render, each with its picture; the rider's is checked.
 * Colourways without a render are left out until their pictures exist (docs/PLAN.md "Garage").
 */
@Composable
fun ColourwaySheet(
    current: Colourway,
    onChoose: (Colourway) -> Unit,
    onCancel: () -> Unit,
) {
    GarageSheet(
        HlIcons.Bike,
        stringResource(R.string.garage_colourway),
        stringResource(R.string.garage_colourway_sheet_detail),
    ) {
        HlGroup(Modifier.selectableGroup()) {
            Colourway.available.forEachIndexed { index, colourway ->
                if (index > 0) HlGroupDivider()
                Column(verticalArrangement = Arrangement.Center) {
                    HlOptionRow(
                        icon = null,
                        title = stringResource(colourway.label),
                        subtitle =
                            stringResource(
                                if (colourway ==
                                    current
                                ) {
                                    R.string.garage_your_bike
                                } else {
                                    colourway.short
                                },
                            ),
                        selected = colourway == current,
                        onClick = { onChoose(colourway) },
                        container = Color.Transparent,
                    )
                }
            }
        }
        HlText(
            stringResource(R.string.garage_colourway_more),
            HlTheme.typography.label,
            HlTheme.colors.inkMuted,
            Modifier.padding(horizontal = HlTheme.spacing.s4),
        )
        HlSecondaryButton(stringResource(R.string.cancel), onCancel)
    }
}

private const val MONTH_DIGITS = 2
