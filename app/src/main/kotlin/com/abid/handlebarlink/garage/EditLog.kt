package com.abid.handlebarlink.garage

import java.time.ZoneId

// Changing or deleting a service log entry ("All entries" › an entry): the entry, the reading it carried, and the
// counts it started follow the change.

/**
 * Changes log entry [id] to what the sheet collected ([input]): its reading moves with it, and the service count
 * starts again from the newest general service that restarted it (this one, if [input] restarts and it is the
 * newest). A chain entry that started the chain's count moves that start with it.
 */
fun GarageData.editEntry(
    id: Long,
    input: ServiceInput,
    nowMillis: Long,
    zone: ZoneId,
): GarageData {
    val old = log.firstOrNull { it.id == id } ?: return this
    val edited =
        old.copy(
            day = input.day.toEpochDay(),
            km = input.km,
            kind = input.kind,
            workshop = input.workshop?.trim()?.ifEmpty { null },
            cost = input.cost,
            restarted = input.restart && input.kind == ServiceKind.GENERAL,
        )
    val marked = withStartMarked()
    val log = marked.log.map { if (it.id == id) edited else it }
    val reading = OdometerReading(input.km, readingTime(input.day, nowMillis, zone), id)
    return marked
        .copy(log = log, odometer = odometer.filterNot { it.entry == id } + reading, chain = chainAfter(old, log))
        .withServiceStartFromLog()
}

/** Deletes log entry [id], the reading it carried, and any count it started (the one before it takes over). */
fun GarageData.deleteEntry(id: Long): GarageData {
    val old = log.firstOrNull { it.id == id } ?: return this
    val marked = withStartMarked()
    val log = marked.log.filterNot { it.id == id }
    return marked
        .copy(log = log, odometer = odometer.filterNot { it.entry == id }, chain = chainAfter(old, log))
        .withServiceStartFromLog()
}

/**
 * Entries logged before [ServiceEntry.restarted] existed carry no mark. For such a log, the general entries up to the
 * current start (its day) count as having restarted it: the sheet's switch was on by default, and a later general
 * entry that had restarted it would be the start.
 */
private fun GarageData.withStartMarked(): GarageData {
    val startDay = service.startDay
    if (log.any { it.restarted } || startDay == null) return this
    return copy(
        log = log.map { if (it.kind == ServiceKind.GENERAL && it.day <= startDay) it.copy(restarted = true) else it },
    )
}

/** The service count starts at the newest general entry that restarted it; none left: not started. */
private fun GarageData.withServiceStartFromLog(): GarageData {
    val start = log.filter { it.kind == ServiceKind.GENERAL && it.restarted }.maxWithOrNull(NEWEST)
    return copy(service = service.copy(startDay = start?.day, startKm = start?.km))
}

/**
 * The chain's plan once [old] changed and the log is [log]: when [old] started the chain's count (a chain entry at
 * its start), the newest chain entry left starts it; a start from "Done it now" (no entry) stays.
 */
private fun GarageData.chainAfter(
    old: ServiceEntry,
    log: List<ServiceEntry>,
): ChainPlan {
    if (old.kind != ServiceKind.CHAIN || chain.startKm != old.km) return chain
    return chain.copy(startKm = log.filter { it.kind == ServiceKind.CHAIN }.maxWithOrNull(NEWEST)?.km)
}

/** Newest by day; among the same day, the one entered last. */
private val NEWEST = compareBy<ServiceEntry>({ it.day }, { it.id })
