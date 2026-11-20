package com.abid.handlebarlink.garage

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// What comes up first, and the odometer's pace and age (docs/PLAN.md "Garage").

/** Today in [this] clock's zone: the only way Garage reads the date (the injected clock, rule 8). */
fun java.time.Clock.today(): LocalDate = instant().atZone(zone).toLocalDate()

/**
 * Soonest first: overdue, then due soon, then the rest, then what is not added yet; within a group by the days left.
 * A km reminder's days come from the riding rate ([rate] km a day); without one it cannot be placed against dates, so
 * it goes first in its group (it could come up any ride), by km left.
 */
fun byUrgency(rate: Double?): Comparator<Due> =
    compareBy<Due> { STATE_ORDER.indexOf(it.state) }
        .thenBy { estimatedDays(it, rate) ?: Double.NEGATIVE_INFINITY }
        .thenBy { it.kmLeft ?: Int.MAX_VALUE }
        .thenBy { it.kind.ordinal }

/** The days until [due]: the earlier of its date and its km at [rate]; null when neither can be told in days. */
internal fun estimatedDays(
    due: Due,
    rate: Double?,
): Double? {
    val byDate = due.daysLeft?.toDouble()
    val byKm = if (due.kmLeft != null && rate != null && rate > 0) due.kmLeft / rate else null
    return listOfNotNull(byDate, byKm).minOrNull() ?: if (due.kmLeft != null) null else Double.MAX_VALUE
}

/** The average km a day between the first and the latest reading; null below a day apart or without progress. */
fun kmPerDay(readings: List<OdometerReading>): Double? {
    if (readings.size < 2) return null
    val first = readings.minBy { it.atMillis }
    val last = readings.maxBy { it.atMillis }
    val days = Duration.ofMillis(last.atMillis - first.atMillis).toHours() / HOURS_PER_DAY
    val km = last.km - first.km
    return if (days < 1.0 || km <= 0) null else km / days
}

/** Whole days from [reading] to [today] in [zone] ("You entered it 2 days ago"). */
fun daysSince(
    reading: OdometerReading,
    today: LocalDate,
    zone: ZoneId,
): Long = ChronoUnit.DAYS.between(Instant.ofEpochMilli(reading.atMillis).atZone(zone).toLocalDate(), today)

private val STATE_ORDER = listOf(DueState.OVERDUE, DueState.SOON, DueState.OK, DueState.UNSET)
private const val HOURS_PER_DAY = 24.0
