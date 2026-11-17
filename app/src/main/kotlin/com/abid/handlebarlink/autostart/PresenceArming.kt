package com.abid.handlebarlink.autostart

import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.di.ApplicationScope
import com.abid.handlebarlink.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps Android watching exactly the selected bike (docs/PLAN.md M4.2): whenever the selection changes
 * (setup finished, another bike chosen, "Set up a different bike") and once per process start, the selected
 * bike's association is observed and every other one released ([planPresence]). Android keeps watching
 * while the app is not running ("The app doesn't need to remain running", CDM reference); running it again
 * at each process start is harmless and repairs an observation lost meanwhile.
 */
@Singleton
class PresenceArming
    @Inject
    constructor(
        private val settings: BikeSettings,
        private val presence: CompanionPresence,
        @IoDispatcher private val io: CoroutineDispatcher,
        @ApplicationScope private val scope: CoroutineScope,
        logger: Logger,
    ) {
        private val log = logger.child("autostart")
        private val started = AtomicBoolean(false)

        /** Follows the selected bike for the life of the process; later calls do nothing. */
        fun start() {
            if (!started.compareAndSet(false, true)) return
            scope.launch {
                settings.bike
                    .map { it?.address }
                    .distinctUntilChanged()
                    .collect { arm(it) }
            }
        }

        private suspend fun arm(selected: String?) {
            // Binder calls to the companion-device service: never on the main thread (anr-audit-and-fix).
            withContext(io) {
                val plan = planPresence(selected, presence.associations())
                plan.release.forEach(presence::stopObserving)
                plan.observe?.let(presence::startObserving)
                if (selected != null && plan.observe == null) {
                    // Chosen from "Paired with this phone": Android was never asked to link it, so it cannot
                    // watch for it; the ride then starts only when the app is opened.
                    log.warn("presence.not_linked", "address" to selected)
                }
            }
        }
    }
