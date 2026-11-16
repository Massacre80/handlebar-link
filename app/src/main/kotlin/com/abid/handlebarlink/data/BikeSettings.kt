package com.abid.handlebarlink.data

import kotlinx.coroutines.flow.Flow

/** The bike the app links to: the meter's Bluetooth address and its name ("HONDA BTU 2040"). */
data class SelectedBike(
    val address: String,
    val name: String,
)

/** Which bike is selected. Pairing through the companion-device dialog replaces the picker in M4. */
interface BikeSettings {
    /** The selected bike, or null when none is chosen. */
    val bike: Flow<SelectedBike?>

    /** Selects [bike]. */
    suspend fun select(bike: SelectedBike)

    /** Forgets the selected bike. */
    suspend fun forget()
}
