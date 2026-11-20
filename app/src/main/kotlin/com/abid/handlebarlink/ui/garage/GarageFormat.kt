package com.abid.handlebarlink.ui.garage

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.garage.Due
import com.abid.handlebarlink.garage.DueState
import com.abid.handlebarlink.garage.ReminderKind
import com.abid.handlebarlink.garage.grouped
import com.abid.handlebarlink.garage.reminderName
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// How Garage writes dates, distances and "when" (Paper "Garage 2": "14 Mar 2027", "Jun 2039", "2 Sep", "In 340 km",
// "In 4 months", "13 years", "4 days ago"). Formats follow the phone's language.

/** "14 Mar 2027". */
internal fun fullDate(day: LocalDate): String =
    day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))

/** "Jun 2039". */
internal fun monthYear(day: LocalDate): String =
    day.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))

/** "2 Mar", for a day within the year ahead (the service's due day). */
internal fun dayMonth(day: LocalDate): String = day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

/** "2 Sep" in [today]'s year, else "2 Sep 2025". */
internal fun shortDate(
    day: LocalDate,
    today: LocalDate,
): String =
    if (day.year == today.year) day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) else fullDate(day)

/** "15,860 km". */
@Composable
internal fun km(value: Int): String = stringResource(R.string.garage_km, grouped(value))

/** A reminder's name: its own for a custom one. */
@Composable
internal fun dueName(due: Due): String = due.name ?: stringResource(reminderName(due.kind))

/** When [due] comes, for the end of its row: "In 4 months", "In 340 km", "4 days ago", "Add". */
@Composable
internal fun dueWhen(due: Due): String {
    val days = due.daysLeft
    val kmLeft = due.kmLeft
    return when {
        due.state == DueState.UNSET -> stringResource(R.string.garage_add)
        days != null && due.dueDay != null -> relativeDays(days, due.dueDay)
        kmLeft != null && kmLeft < 0 -> stringResource(R.string.garage_km_over, grouped(-kmLeft))
        kmLeft != null -> stringResource(R.string.garage_in_km, grouped(kmLeft))
        due.dueKm != null -> stringResource(R.string.garage_at_km, grouped(due.dueKm))
        else -> ""
    }
}

/** "Today", "Tomorrow", "In 9 days" (a month or less), "In 4 months", "13 years", "4 days ago". */
@Composable
private fun relativeDays(
    days: Long,
    dueDay: LocalDate,
): String {
    val today = dueDay.minusDays(days)
    val months = ChronoUnit.MONTHS.between(today, dueDay).toInt()
    val years = ChronoUnit.YEARS.between(today, dueDay).toInt()
    return when {
        days < 0 -> pluralStringResource(R.plurals.garage_days_ago, (-days).toInt(), (-days).toInt())
        days == 0L -> stringResource(R.string.garage_today)
        days == 1L -> stringResource(R.string.garage_tomorrow)
        months < 1 -> pluralStringResource(R.plurals.garage_in_days, days.toInt(), days.toInt())
        years < 1 -> pluralStringResource(R.plurals.garage_in_months, months, months)
        else -> pluralStringResource(R.plurals.garage_years, years, years)
    }
}

/** A row's one line for [due]: "Every 600 km", "Renews 14 Mar 2027", "Valid till Jun 2039", "Ran out 16 Nov 2026". */
@Composable
internal fun dueDetail(
    due: Due,
    chainKm: Int,
): String {
    val day = due.dueDay
    val overdue = due.state == DueState.OVERDUE && day != null && due.kind != ReminderKind.CUSTOM
    return when {
        due.kind == ReminderKind.CHAIN -> stringResource(R.string.garage_every_km, grouped(chainKm))
        due.state == DueState.UNSET -> stringResource(R.string.garage_add_date)
        overdue -> stringResource(R.string.garage_ran_out, fullDate(day))
        due.kind == ReminderKind.INSURANCE && day != null -> stringResource(R.string.garage_renews, fullDate(day))
        due.kind == ReminderKind.RC && day != null -> stringResource(R.string.garage_valid_till, monthYear(day))
        due.kind == ReminderKind.PUC && day != null -> stringResource(R.string.garage_valid_till, fullDate(day))
        day != null -> stringResource(R.string.garage_on_day, fullDate(day))
        due.dueKm != null -> stringResource(R.string.garage_at_km, grouped(due.dueKm))
        else -> ""
    }
}
