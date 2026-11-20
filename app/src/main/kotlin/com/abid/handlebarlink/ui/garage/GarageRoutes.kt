package com.abid.handlebarlink.ui.garage

import androidx.navigation3.runtime.NavKey
import com.abid.handlebarlink.garage.PaperKind
import com.abid.handlebarlink.ui.navigation.OverlayRoute
import kotlinx.serialization.Serializable

// The Garage tab's pages, sheets and dialogs (its root is GarageRoute in ui/navigation/Routes.kt).

/** "Log a service" (Paper "Garage 5"): a sheet over the Garage tab. */
@Serializable
data object LogServiceRoute : OverlayRoute

/** A service log entry [id], to change or delete: the "Log a service" sheet filled in. */
@Serializable
data class EditEntryRoute(
    val id: Long,
) : OverlayRoute

/** "Delete this entry?": confirms deleting service log entry [id]. */
@Serializable
data class DeleteLogEntryRoute(
    val id: Long,
) : OverlayRoute

/** The odometer reading: a sheet over the Garage tab. */
@Serializable
data object OdometerRoute : OverlayRoute

/** The service interval: a sheet from the next service card. */
@Serializable
data object ServiceIntervalRoute : OverlayRoute

/** The chain's interval and "Done it now": a sheet from its row. */
@Serializable
data object ChainRoute : OverlayRoute

/** A paper's date and reminder (Paper "Garage 6", Insurance): a sheet from its row. */
@Serializable
data class PaperRoute(
    val kind: PaperKind,
) : OverlayRoute

/** A reminder of the rider's own: [id] null adds one. */
@Serializable
data class ReminderRoute(
    val id: Long?,
) : OverlayRoute

/** "Delete …?": confirms deleting the rider's reminder [id]. */
@Serializable
data class DeleteReminderRoute(
    val id: Long,
    val name: String,
) : OverlayRoute

/** "Colourway" (Paper "Garage 8"): a sheet from the Bike section. */
@Serializable
data object ColourwayRoute : OverlayRoute

/** "Forget this bike?" (Paper "Garage 7"): a dialog over the Garage tab. */
@Serializable
data object ForgetBikeRoute : OverlayRoute

/** Every service log entry: a page over the Garage tab. */
@Serializable
data object ServiceLogRoute : NavKey
