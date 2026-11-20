package com.abid.handlebarlink.garage

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// When each thing in Garage is due (docs/PLAN.md "Garage"). Pure functions of the stored data and today's date: the
// caller reads the injected clock once and passes it in, so tests set any day.

/** What a reminder is about. */
enum class ReminderKind {
    SERVICE,
    CHAIN,
    INSURANCE,
    PUC,
    RC,
    CUSTOM,
}

/** How a reminder stands today. */
enum class DueState {
    /** Nothing to do yet. */
    OK,

    /** Due within its lead (days or km), or due today / at this reading. */
    SOON,

    /** Past its date or distance. */
    OVERDUE,

    /** Nothing entered yet (a paper without a date). */
    UNSET,
}

/**
 * One reminder today: due on [dueDay] or at [dueKm] (whichever comes first when both are set), [daysLeft] and [kmLeft]
 * from today and the latest reading (negative when past; [kmLeft] null without a reading). [id] is stable across
 * edits ("service", "chain", "paper:PUC", "custom:3"); [name] is the rider's own name for a custom reminder.
 */
data class Due(
    val id: String,
    val kind: ReminderKind,
    val state: DueState,
    val dueDay: LocalDate? = null,
    val dueKm: Int? = null,
    val daysLeft: Long? = null,
    val kmLeft: Int? = null,
    val name: String? = null,
    /** A paper's reminder lead in days, for the "a week before" alert; null for the others. */
    val leadDays: Int? = null,
    /** How much of the interval is used, 0..1 (the service gauge); null without a start. */
    val used: Float? = null,
)

/** Garage today: the service, the other reminders in the order they come up, and the latest reading. */
data class GarageStatus(
    /** The next service; null until a general service is logged. */
    val service: Due?,
    /** Chain, papers and custom reminders, soonest first ([byUrgency]); papers not added yet last. */
    val upcoming: List<Due>,
    val latest: OdometerReading?,
    /** Average km per day from the readings, when they span at least a day; used only to order km against dates. */
    val kmPerDay: Double?,
)

/** Garage's state on [today]. */
fun garageStatus(
    data: GarageData,
    today: LocalDate,
): GarageStatus {
    val latest = data.latestReading
    val rate = kmPerDay(data.odometer)
    val upcoming =
        buildList {
            add(chainDue(data.chain, latest))
            PaperKind.entries.forEach { add(paperDue(it, data.papers[it], today)) }
            data.custom.forEach { add(customDue(it, today, latest)) }
        }
    return GarageStatus(
        service = serviceDue(data.service, today, latest),
        upcoming = upcoming.sortedWith(byUrgency(rate)),
        latest = latest,
        kmPerDay = rate,
    )
}

/** The service: every [ServicePlan.months] or [ServicePlan.km] from the last general service, whichever first. */
fun serviceDue(
    plan: ServicePlan,
    today: LocalDate,
    latest: OdometerReading?,
): Due? {
    val startDay = plan.startDay ?: return null
    val start = LocalDate.ofEpochDay(startDay)
    val dueDay = start.plusMonths(plan.months.toLong())
    val dueKm = plan.startKm?.let { it + plan.km }
    val daysLeft = ChronoUnit.DAYS.between(today, dueDay)
    val kmLeft = if (dueKm != null && latest != null) dueKm - latest.km else null
    val intervalDays = ChronoUnit.DAYS.between(start, dueDay).coerceAtLeast(1)
    val byTime = ChronoUnit.DAYS.between(start, today).toFloat() / intervalDays
    val byKm = if (kmLeft != null) (plan.km - kmLeft).toFloat() / plan.km.coerceAtLeast(1) else 0f
    return Due(
        id = SERVICE_ID,
        kind = ReminderKind.SERVICE,
        state = worst(dateState(daysLeft, plan.soonDays), kmState(kmLeft, plan.soonKm)),
        dueDay = dueDay,
        dueKm = dueKm,
        daysLeft = daysLeft,
        kmLeft = kmLeft,
        used = maxOf(byTime, byKm).coerceIn(0f, 1f),
    )
}

/** The chain: every [ChainPlan.km] from when it was last done; [DueState.UNSET] until it is first marked done. */
fun chainDue(
    plan: ChainPlan,
    latest: OdometerReading?,
): Due {
    val start = plan.startKm ?: return Due(CHAIN_ID, ReminderKind.CHAIN, DueState.UNSET)
    val dueKm = start + plan.km
    val kmLeft = latest?.let { dueKm - it.km }
    return Due(
        id = CHAIN_ID,
        kind = ReminderKind.CHAIN,
        state = kmState(kmLeft, plan.soonKm) ?: DueState.OK,
        dueKm = dueKm,
        kmLeft = kmLeft,
    )
}

/** A paper: due on its last valid day, due soon its lead ahead; [DueState.UNSET] without a date. */
fun paperDue(
    kind: PaperKind,
    paper: Paper?,
    today: LocalDate,
): Due {
    val reminderKind =
        when (kind) {
            PaperKind.INSURANCE -> ReminderKind.INSURANCE
            PaperKind.PUC -> ReminderKind.PUC
            PaperKind.RC -> ReminderKind.RC
        }
    if (paper == null) return Due(paperId(kind), reminderKind, DueState.UNSET)
    val dueDay = LocalDate.ofEpochDay(paper.expiryDay)
    val daysLeft = ChronoUnit.DAYS.between(today, dueDay)
    return Due(
        id = paperId(kind),
        kind = reminderKind,
        state = dateState(daysLeft, paper.lead.days),
        dueDay = dueDay,
        daysLeft = daysLeft,
        leadDays = paper.lead.days,
    )
}

/** The rider's own reminder, by date or by km. */
fun customDue(
    reminder: CustomReminder,
    today: LocalDate,
    latest: OdometerReading?,
): Due {
    val dueDay = reminder.dueDay?.let(LocalDate::ofEpochDay)
    val daysLeft = dueDay?.let { ChronoUnit.DAYS.between(today, it) }
    val kmLeft = if (reminder.dueKm != null && latest != null) reminder.dueKm - latest.km else null
    return Due(
        id = customId(reminder.id),
        kind = ReminderKind.CUSTOM,
        state = worst(daysLeft?.let { dateState(it, CUSTOM_SOON_DAYS) }, kmState(kmLeft, CUSTOM_SOON_KM)),
        dueDay = dueDay,
        dueKm = reminder.dueKm,
        daysLeft = daysLeft,
        kmLeft = kmLeft,
        name = reminder.name,
    )
}

private fun dateState(
    daysLeft: Long,
    soonDays: Int,
): DueState =
    when {
        daysLeft < 0 -> DueState.OVERDUE
        daysLeft <= soonDays -> DueState.SOON
        else -> DueState.OK
    }

/** Unknown without a reading: never more urgent than OK. */
private fun kmState(
    kmLeft: Int?,
    soonKm: Int,
): DueState? =
    when {
        kmLeft == null -> null
        kmLeft < 0 -> DueState.OVERDUE
        kmLeft <= soonKm -> DueState.SOON
        else -> DueState.OK
    }

/** Whichever comes first: the more urgent of the date's and the km's state. */
private fun worst(vararg states: DueState?): DueState =
    states.filterNotNull().maxByOrNull { SEVERITY.indexOf(it) } ?: DueState.OK

/** The id of [kind]'s reminder. */
fun paperId(kind: PaperKind): String = "paper:${kind.name}"

/** The id of a custom reminder. */
fun customId(id: Long): String = "custom:$id"

/** The service's reminder id. */
const val SERVICE_ID = "service"

/** The chain's reminder id. */
const val CHAIN_ID = "chain"

private val SEVERITY = listOf(DueState.UNSET, DueState.OK, DueState.SOON, DueState.OVERDUE)
