package com.abid.handlebarlink.autostart

import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.di.ApplicationScope
import com.abid.handlebarlink.di.IoDispatcher
import com.abid.handlebarlink.ride.RideCoordinator
import com.abid.handlebarlink.ride.RideStarter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What Android's presence reports do (docs/research/02-ble-connection.md §3.4): the selected bike appearing
 * goes to its link, starts the ride service from the background and brings the app on screen; the bike
 * leaving only goes to the link,
 * which keeps the service while the connection is up and lets it end once the bike stays away (the linger of
 * the presence-driven policy). Reports for any other device are logged and ignored. Callbacks return at once;
 * the work runs in the application scope (the system keeps the process bound while the bike is present).
 */
class PresenceEvents
    @Inject
    constructor(
        private val presence: CompanionPresence,
        private val coordinator: RideCoordinator,
        private val rides: RideStarter,
        @IoDispatcher private val io: CoroutineDispatcher,
        @ApplicationScope private val scope: CoroutineScope,
        logger: Logger,
    ) {
        private val log = logger.child("autostart")

        /** Android 16+: an event for association [associationId]. */
        fun onAssociation(
            associationId: Int,
            present: Boolean,
        ) {
            scope.launch {
                val address =
                    withContext(io) { presence.associations().firstOrNull { it.id == associationId }?.address }
                if (address == null) {
                    log.warn("presence.unknown_association", "association" to associationId, "present" to present)
                } else {
                    handle(address, present)
                }
            }
        }

        /** API 31–35: the device at [address] appeared ([present]) or disappeared. */
        fun onAddress(
            address: String,
            present: Boolean,
        ) {
            scope.launch { handle(address, present) }
        }

        private suspend fun handle(
            address: String,
            present: Boolean,
        ) {
            if (!coordinator.onPresence(address, present)) {
                log.info("presence.other_device", "address" to address, "present" to present)
                return
            }
            log.info(if (present) "presence.appeared" else "presence.gone", "address" to address)
            if (present) {
                val started = rides.startInBackground(presentHint = true)
                log.info("presence.ride_start", "started" to started)
                // The phone is the bike's display: the app comes on screen when the bike appears.
                log.info("presence.screen", "requested" to rides.showOnScreen())
            }
        }
    }
