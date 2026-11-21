package com.abid.handlebarlink.data

import com.abid.handlebarlink.autostart.CompanionPresence
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.di.ApplicationScope
import com.abid.handlebarlink.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-adopts the meter after a reinstall. The companion-device association, made at setup, survives the app being
 * reinstalled; the app's own data (the chosen bike) does not — so the rider was sent back through setup even though
 * the meter was still linked (owner, 2026-11-21: "it was paired already but after reinstall needs repair"). The
 * Bluetooth bond's name is often null for a BLE meter, so the bond alone cannot identify it; the association carries
 * the address and the name chosen at setup, and keeps auto-start on ignition working.
 *
 * Once per process: if no bike is chosen but exactly one association is there, choose it. The setup screen follows
 * [BikeSettings.bike] and flips to the tabs, and `PresenceArming` then observes that association again (so ignition
 * auto-start comes back too). More than one association, or none, is left to setup.
 */
@Singleton
class AdoptPairedMeter
    @Inject
    constructor(
        private val settings: BikeSettings,
        private val presence: CompanionPresence,
        @IoDispatcher private val io: CoroutineDispatcher,
        @ApplicationScope private val scope: CoroutineScope,
        logger: Logger,
    ) {
        private val log = logger.child("setup")
        private val started = AtomicBoolean(false)

        /** Runs once per process; later calls do nothing. */
        fun start() {
            if (!started.compareAndSet(false, true)) return
            scope.launch {
                if (settings.bike.first() != null) return@launch
                // A binder call to the companion-device service: off the main thread.
                val association = withContext(io) { presence.associations() }.singleOrNull() ?: return@launch
                val name = association.name?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
                settings.select(SelectedBike(association.address, name))
                log.info("setup.readopted", "address" to association.address, "name" to name)
            }
        }

        private companion object {
            /** Shown when the association kept no name (Android 12 lists addresses only). */
            const val DEFAULT_NAME = "Honda meter"
        }
    }
