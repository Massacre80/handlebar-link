package com.abid.handlebarlink.garage

import java.time.LocalDate

// The rider's changes to Garage, as pure functions of the stored data (GarageStore applies them in one transaction).

/** Adds an odometer reading of [km], taken now. */
fun GarageData.enterOdometer(
    km: Int,
    nowMillis: Long,
): GarageData = copy(odometer = odometer + OdometerReading(km, nowMillis))

/** Sets the service interval (the rider's own, or Honda's default). */
fun GarageData.setServiceInterval(
    months: Int,
    km: Int,
): GarageData = copy(service = service.copy(months = months, km = km))

/** The chain was cleaned and lubed at the latest reading (or at [km] when given); its count restarts there. */
fun GarageData.chainDone(km: Int? = latestReading?.km): GarageData =
    if (km == null) this else copy(chain = chain.copy(startKm = km))

/** Sets the chain's interval. */
fun GarageData.setChainInterval(km: Int): GarageData = copy(chain = chain.copy(km = km))

/** Sets [kind]'s expiry day and reminder lead. */
fun GarageData.setPaper(
    kind: PaperKind,
    expiry: LocalDate,
    lead: Lead,
): GarageData = copy(papers = papers + (kind to Paper(expiry.toEpochDay(), lead)))

/** Adds a reminder of the rider's own (exactly one of [day] and [km]). */
fun GarageData.addCustom(
    name: String,
    day: LocalDate?,
    km: Int?,
): GarageData {
    require((day == null) != (km == null)) { "a reminder is by date or by km" }
    return copy(custom = custom + CustomReminder(nextId, name.trim(), day?.toEpochDay(), km), nextId = nextId + 1)
}

/** Changes the rider's reminder [id]. */
fun GarageData.updateCustom(
    id: Long,
    name: String,
    day: LocalDate?,
    km: Int?,
): GarageData {
    require((day == null) != (km == null)) { "a reminder is by date or by km" }
    return copy(
        custom =
            custom.map {
                if (it.id == id) it.copy(name = name.trim(), dueDay = day?.toEpochDay(), dueKm = km) else it
            },
    )
}

/** Removes the rider's reminder [id] and what was notified for it. */
fun GarageData.removeCustom(id: Long): GarageData =
    copy(custom = custom.filterNot { it.id == id }, alerts = alerts - customId(id))

/** Chooses the colourway. */
fun GarageData.chooseColourway(colourway: Colourway): GarageData = copy(colourway = colourway.name)
