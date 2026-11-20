package com.abid.handlebarlink.garage

/**
 * What Garage's hero says (Paper "Garage 1–4"). The service's state is shown once, by its gauge and the card's button
 * (owner, 2026-11-20), so the hero's tag never repeats it: it speaks of the other reminders, or says all is well.
 */
sealed interface GarageHero {
    /** Nothing added yet: the intro and "Log the last service". */
    data object Empty : GarageHero

    /** Nothing needs the rider. */
    data object AllGood : GarageHero

    /** Only the service needs the rider: no tag (the gauge and "Log this service" say it). */
    data object ServiceOnly : GarageHero

    /** The most urgent other reminder ([due], due soon or overdue): its tag, and when overdue what to do. */
    data class Attention(
        val due: Due,
    ) : GarageHero
}

/** The hero for [status]; [hasRecords] false is the empty Garage. */
fun garageHero(
    hasRecords: Boolean,
    status: GarageStatus,
): GarageHero {
    if (!hasRecords) return GarageHero.Empty
    val other = status.upcoming.firstOrNull { it.state == DueState.OVERDUE || it.state == DueState.SOON }
    return when {
        other != null -> GarageHero.Attention(other)
        status.service?.state.let { it == DueState.SOON || it == DueState.OVERDUE } -> GarageHero.ServiceOnly
        else -> GarageHero.AllGood
    }
}
