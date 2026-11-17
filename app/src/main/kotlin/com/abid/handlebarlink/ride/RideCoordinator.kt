package com.abid.handlebarlink.ride

import com.abid.handlebarlink.bike.link.BikeLink
import com.abid.handlebarlink.bike.link.ButtonEvent
import com.abid.handlebarlink.bike.link.LinkProblem
import com.abid.handlebarlink.bike.link.LinkState
import com.abid.handlebarlink.controls.ControlResult
import com.abid.handlebarlink.controls.ControlRouter
import com.abid.handlebarlink.controls.ControlTarget
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.di.ApplicationScope
import com.abid.handlebarlink.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the ride (architecture review M2.4–M3-1): one link per selected bike, run for as long as the
 * ride service calls [ride], with handlebar presses routed to CarPlay. The UI reads [link] for status.
 * Android's presence reports for the selected bike reach its link through [onPresence].
 */
@Singleton
class RideCoordinator
    @Inject
    constructor(
        private val settings: BikeSettings,
        provider: BikeLinkProvider,
        private val target: ControlTarget,
        @IoDispatcher private val io: CoroutineDispatcher,
        @ApplicationScope scope: CoroutineScope,
        logger: Logger,
    ) {
        private val log = logger.child("ride")
        private val riding = AtomicBoolean(false)

        private val rideLink: StateFlow<RideLink?> =
            settings.bike
                .map { it?.address }
                .distinctUntilChanged()
                .map { address -> address?.let { provider.create(it) } }
                .stateIn(scope, SharingStarted.Eagerly, null)

        /** The selected bike's link (one per address, created once), or null when no bike is selected. */
        val link: StateFlow<BikeLink?> =
            rideLink.map { it?.link }.stateIn(scope, SharingStarted.Eagerly, null)

        /** The app is back in the foreground: the Bluetooth permission may have changed meanwhile. */
        fun recheckPermission() {
            rideLink.value?.recheckPermission?.invoke()
        }

        /**
         * Android reports the device at [address] appearing ([present]) or leaving. For the selected bike
         * (matched ignoring case) its link is told, once it exists (a report can wake the process before the
         * link is created), and the link decides what follows; true then. False for any other device.
         */
        suspend fun onPresence(
            address: String,
            present: Boolean,
        ): Boolean {
            val selected = settings.bike.first()?.address
            if (selected == null || !selected.equals(address, ignoreCase = true)) return false
            link.filterNotNull().first().onPresence(present)
            return true
        }

        /**
         * Runs the ride: starts the link and routes presses until the link no longer needs to be kept
         * alive (stopped, blocked, or idle after the bike left), or until cancelled. Returns the problem
         * the rider has to act on, if any. Returns at once when no bike is selected. [presentHint]: there is a
         * fresh sign the bike is in range (connect directly instead of waiting for it).
         */
        suspend fun ride(presentHint: Boolean): LinkProblem? {
            if (settings.bike.first() == null) {
                log.warn("ride.no_bike")
                return null
            }
            check(riding.compareAndSet(false, true)) { "a ride is already running" }
            try {
                val current = link.filterNotNull().first()
                log.info("ride.start")
                coroutineScope {
                    launch(io) { current.buttons.collect(::route) }
                    launch { current.run() }
                    current.start(presentHint)
                    // keepAlive is current whenever a state arrives (BikeLink.keepAlive).
                    current.state.dropWhile { it == LinkState.Stopped }.first { !current.keepAlive.value }
                    coroutineContext.cancelChildren()
                }
                return current.problem.value.also { log.info("ride.end", "problem" to it) }
            } finally {
                riding.set(false)
            }
        }

        /** Asks a running ride to end; [ride] returns once the link has stopped. */
        fun stop() {
            if (riding.get()) link.value?.stop()
        }

        private fun route(event: ButtonEvent) {
            val action = ControlRouter.actionFor(event.switch)
            if (action == null) {
                log.debug("control.unmapped", "switch" to event.switch)
                return
            }
            when (val result = target.perform(action)) {
                ControlResult.Done -> log.info("control.done", "switch" to event.switch, "action" to action)
                is ControlResult.NotDone -> log.warn("control.not_done", "action" to action, "reason" to result.reason)
            }
        }
    }
