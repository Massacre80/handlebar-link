package com.abid.handlebarlink.garage

import java.time.LocalDate
import java.time.ZoneId

// Logging a service (Paper "Garage 5"): the entry, its reading, and the counts it restarts.

/**
 * Logs a service: the entry, the odometer reading it carries, and, for a general service with [restart] (the sheet's
 * switch, on by default), the service count restarts from it. A count never moves back: an entry older than the
 * current start is logged without restarting. A chain-and-sprockets entry restarts the chain's count the same way.
 */
@Suppress("LongParameterList") // One entry's fields, as the sheet collects them.
fun GarageData.logService(
    kind: ServiceKind,
    day: LocalDate,
    km: Int,
    restart: Boolean,
    nowMillis: Long,
    zone: ZoneId,
    workshop: String? = null,
    cost: Int? = null,
): GarageData {
    val entry = ServiceEntry(nextId, day.toEpochDay(), km, kind, workshop?.trim()?.ifEmpty { null }, cost)
    val restarts = restart && kind == ServiceKind.GENERAL && (service.startDay ?: Long.MIN_VALUE) <= entry.day
    val chainRestarts = kind == ServiceKind.CHAIN && (chain.startKm ?: Int.MIN_VALUE) <= km
    return copy(
        log = log + entry,
        odometer = odometer + OdometerReading(km, readingTime(day, nowMillis, zone)),
        service = if (restarts) service.copy(startDay = entry.day, startKm = km) else service,
        chain = if (chainRestarts) chain.copy(startKm = km) else chain,
        nextId = nextId + 1,
    )
}

/** The moment a reading taken on [day] is stored at: now when [day] is today, else the start of that day. */
internal fun readingTime(
    day: LocalDate,
    nowMillis: Long,
    zone: ZoneId,
): Long {
    val startOfDay = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val startOfNextDay =
        day
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    return if (nowMillis in startOfDay until startOfNextDay) nowMillis else minOf(startOfDay, nowMillis)
}

/** Logs what "Log a service" collected ([ServiceInput]); see the overload with each field. */
fun GarageData.logService(
    input: ServiceInput,
    nowMillis: Long,
    zone: ZoneId,
): GarageData = logService(input.kind, input.day, input.km, input.restart, nowMillis, zone, input.workshop, input.cost)
