package com.abid.handlebarlink.autostart

import com.abid.handlebarlink.bike.link.BikeLinkConfig
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The link policy for a bike ([linkConfig]): presence-driven when Android watches for it (it has one of this
 * app's companion-device associations), always armed otherwise.
 */
class LinkPolicy
    @Inject
    constructor(
        private val presence: CompanionPresence,
        @IoDispatcher private val io: CoroutineDispatcher,
        logger: Logger,
    ) {
        private val log = logger.child("autostart")

        /** The policy for the meter at [address]. */
        suspend fun configFor(address: String): BikeLinkConfig {
            // A binder call to the companion-device service: never on the main thread (anr-audit-and-fix).
            val watched =
                withContext(io) {
                    presence.associations().any { it.address.equals(address, ignoreCase = true) }
                }
            log.info("presence.policy", "address" to address, "watched" to watched)
            return linkConfig(watched)
        }
    }
