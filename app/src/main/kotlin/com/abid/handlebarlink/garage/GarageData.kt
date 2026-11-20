package com.abid.handlebarlink.garage

import kotlinx.serialization.Serializable
import java.time.LocalDate

// What the rider keeps in Garage (docs/PLAN.md "Garage"), stored as one JSON document (GarageStore). Days are
// `LocalDate.toEpochDay()`, moments `Instant.toEpochMilli()`; the meter sends no distance, so every kilometre here is
// one the rider typed.

/** Everything Garage stores. Each field has a default, so a file written by an older version still reads. */
@Serializable
data class GarageData(
    /** The rider's odometer readings, oldest first; km-based reminders use the latest ([latestReading]). */
    val odometer: List<OdometerReading> = emptyList(),
    /** The service log, as entered (shown newest first). */
    val log: List<ServiceEntry> = emptyList(),
    /** When the next service is due. */
    val service: ServicePlan = ServicePlan(),
    /** Cleaning and lubing the chain. */
    val chain: ChainPlan = ChainPlan(),
    /** The papers' expiry dates; a paper without a date is not added yet. */
    val papers: Map<PaperKind, Paper> = emptyMap(),
    /** The rider's own reminders ("Add a reminder"). */
    val custom: List<CustomReminder> = emptyList(),
    /** The colourway the rider chose ([Colourway] name); null: the default. */
    val colourway: String? = null,
    /** What the daily check has already notified, per reminder ([planAlerts]). */
    val alerts: Map<String, AlertMark> = emptyMap(),
    /** The next id for a log entry or a custom reminder. */
    val nextId: Long = 1,
)

/** What "Log a service" collects: [kind], [day], the odometer's [km], the optional extras, and the switch. */
data class ServiceInput(
    val kind: ServiceKind,
    val day: LocalDate,
    val km: Int,
    val workshop: String?,
    val cost: Int?,
    val restart: Boolean,
)

/** An odometer reading the rider entered: [km] on the meter at [atMillis]; [entry] the log entry that carried it. */
@Serializable
data class OdometerReading(
    val km: Int,
    val atMillis: Long,
    val entry: Long? = null,
)

/** What a service log entry was. */
@Serializable
enum class ServiceKind {
    GENERAL,
    OIL,
    CHAIN,
    TYRES,
    BRAKES,
    OTHER,
}

/** One service log entry: [day], the odometer's [km] then, what was done, and the optional workshop and cost. */
@Serializable
data class ServiceEntry(
    val id: Long,
    val day: Long,
    val km: Int,
    val kind: ServiceKind,
    val workshop: String? = null,
    /** In whole rupees. */
    val cost: Int? = null,
    /** This general service restarted the service count (the sheet's switch was on and it was the newest). */
    val restarted: Boolean = false,
)

/**
 * The service interval: every [months] or [km], whichever comes first, counted from the last general service
 * ([startDay], [startKm]; null until one is logged). Defaults: Honda's schedule (docs/PLAN.md "Garage", owner to
 * confirm). Due soon [soonDays] or [soonKm] ahead.
 */
@Serializable
data class ServicePlan(
    val months: Int = DEFAULT_SERVICE_MONTHS,
    val km: Int = DEFAULT_SERVICE_KM,
    val startDay: Long? = null,
    val startKm: Int? = null,
    val soonDays: Int = SERVICE_SOON_DAYS,
    val soonKm: Int = SERVICE_SOON_KM,
)

/** Clean and lube the chain every [km], counted from [startKm] (null until the rider first marks it done). */
@Serializable
data class ChainPlan(
    val km: Int = DEFAULT_CHAIN_KM,
    val startKm: Int? = null,
    val soonKm: Int = CHAIN_SOON_KM,
)

/** The papers Garage reminds about. */
@Serializable
enum class PaperKind {
    INSURANCE,
    PUC,
    RC,
}

/** How long before a paper runs out Garage reminds. */
@Serializable
enum class Lead(
    val days: Int,
) {
    MONTH(MONTH_DAYS),
    TWO_WEEKS(TWO_WEEKS_DAYS),
    WEEK(WEEK_DAYS),
}

/** A paper valid until [expiryDay] (its last valid day), reminded [lead] ahead. */
@Serializable
data class Paper(
    val expiryDay: Long,
    val lead: Lead = Lead.MONTH,
)

/** A reminder of the rider's own: [name], due on [dueDay] or at [dueKm] (exactly one is set). */
@Serializable
data class CustomReminder(
    val id: Long,
    val name: String,
    val dueDay: Long? = null,
    val dueKm: Int? = null,
)

/** The furthest alert stage notified for one due target (a date or km; a new target starts again). */
@Serializable
data class AlertMark(
    val target: String,
    val stage: AlertStage,
)

/** The notifications a reminder gets, in order: when due soon, a week before (long leads), when overdue. */
@Serializable
enum class AlertStage {
    SOON,
    WEEK,
    OVERDUE,
}

/**
 * The latest reading by time (a past service logged later does not replace a newer reading), the last entered among
 * readings of the same moment; null when none.
 */
val GarageData.latestReading: OdometerReading?
    get() = odometer.maxOfOrNull { it.atMillis }?.let { newest -> odometer.last { it.atMillis == newest } }

/** Whether the rider has added anything Garage reminds about (the empty hero otherwise). */
val GarageData.hasRecords: Boolean
    get() = log.isNotEmpty() || papers.isNotEmpty() || custom.isNotEmpty() || service.startDay != null

/** Honda's periodic service interval in months: the owner's manual, every 6,000 km or 6 months after the first. */
const val DEFAULT_SERVICE_MONTHS = 6

/** Honda's periodic service interval in km (owner's manual, docs/PLAN.md "Garage"). */
const val DEFAULT_SERVICE_KM = 6_000

/**
 * The chain's inspect-and-lubricate interval in km: Honda's owner's manual,
 * "Drive Chain … Every 1000 km (600mi): I L"
 * (docs/PLAN.md "Garage"; the artboard's "Every 600 km" was the manual's miles).
 */
const val DEFAULT_CHAIN_KM = 1_000

/** The service counts as due soon two weeks ahead (owner to confirm). */
const val SERVICE_SOON_DAYS = 14

/** … or 500 km ahead (owner to confirm). */
const val SERVICE_SOON_KM = 500

/** The chain is due soon 100 km ahead (owner to confirm). */
const val CHAIN_SOON_KM = 100

/** A custom reminder by date is due soon a week ahead. */
const val CUSTOM_SOON_DAYS = 7

/** A custom reminder by km is due soon 100 km ahead. */
const val CUSTOM_SOON_KM = 100

private const val MONTH_DAYS = 30
private const val TWO_WEEKS_DAYS = 14
private const val WEEK_DAYS = 7
