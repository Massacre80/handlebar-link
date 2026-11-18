package com.abid.handlebarlink.ride

import com.abid.handlebarlink.bike.link.BikeLink
import com.abid.handlebarlink.bike.link.ButtonEvent
import com.abid.handlebarlink.bike.link.LinkProblem
import com.abid.handlebarlink.bike.link.LinkState
import com.abid.handlebarlink.controls.ButtonPresses
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.di.ApplicationScope
import com.abid.handlebarlink.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the ride (architecture review M2.4–M3-1): one link per selected bike, run for as long as the
 * ride service calls [ride], with handlebar presses routed to CarPlay. The UI reads [link] for status.
 * Android's presence reports for the selected bike reach its link through [onPresence]. A [RideCompanion] (our own
 * CarPlay receiver, behind its beta switch) runs next to the link in its own child job.
 */
@Singleton
class RideCoordinator
    @Inject
    constructor(
        private val settings: BikeSettings,
        provider: BikeLinkProvider,
        private val buttons: ButtonPresses,
        @IoDispatcher private val io: CoroutineDispatcher,
        @ApplicationScope scope: CoroutineScope,
        logger: Logger,
    ) {
        private val log = logger.child("ride")
        private val riding = AtomicBoolean(false)
        private val companion = AtomicReference<RideCompanion?>(null)

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
         * Runs the ride: starts the link and routes presses, with [beside] (CarPlay) in its own child job, until
         * neither the link (stopped, blocked, or idle after the bike left) nor [beside] needs the service kept alive,
         * or until cancelled. A failure of [beside] never ends the ride ([RideCompanion.run] does not throw). Returns
         * the problem the rider has to act on, if any. Without a selected bike only [beside] rides, for as long as it
         * needs the service (CarPlay away from the bike); with neither, it returns at once. [presentHint]: there is a
         * fresh sign the bike is in range (connect directly instead of waiting for it).
         */
        suspend fun ride(
            presentHint: Boolean,
            beside: RideCompanion? = null,
        ): LinkProblem? {
            val bikeSelected = settings.bike.first() != null
            if (!bikeSelected && beside == null) {
                log.warn("ride.no_bike")
                return null
            }
            check(riding.compareAndSet(false, true)) { "a ride is already running" }
            try {
                companion.set(beside)
                return if (bikeSelected) rideWithBike(presentHint, beside) else rideWithoutBike(checkNotNull(beside))
            } finally {
                companion.set(null)
                riding.set(false)
            }
        }

        /** No bike selected: only [beside] runs, until it no longer needs the service or the ride is cancelled. */
        private suspend fun rideWithoutBike(beside: RideCompanion): LinkProblem? {
            log.info("ride.start_without_bike")
            coroutineScope {
                // Undispatched, so the companion counts as keeping the service from the start (as in rideWithBike).
                launch(start = CoroutineStart.UNDISPATCHED) { beside.run() }
                beside.keepAlive.first { !it }
                coroutineContext.cancelChildren()
            }
            log.info("ride.end", "problem" to null)
            return null
        }

        private suspend fun rideWithBike(
            presentHint: Boolean,
            beside: RideCompanion?,
        ): LinkProblem? {
            val current = link.filterNotNull().first()
            log.info("ride.start")
            coroutineScope {
                launch(io) { current.buttons.collect(::route) }
                launch { current.run() }
                // Undispatched, so the companion counts as keeping the service before the bike can report anything.
                beside?.let { launch(start = CoroutineStart.UNDISPATCHED) { it.run() } }
                current.start(presentHint)
                // keepAlive is current whenever a state arrives (BikeLink.keepAlive).
                val bikeDone = current.state.dropWhile { it == LinkState.Stopped }.map { !current.keepAlive.value }
                val besideAlive = beside?.keepAlive ?: flowOf(false)
                combine(bikeDone, besideAlive) { done, alive -> done && !alive }.first { it }
                coroutineContext.cancelChildren()
            }
            return current.problem.value.also { log.info("ride.end", "problem" to it) }
        }

        /** Asks a running ride to end; [ride] returns once the link and its companion have stopped. */
        fun stop() {
            if (!riding.get()) return
            link.value?.stop()
            companion.get()?.stop()
        }

        /** A handlebar press goes to the rider's mapping (Home's Handlebar card). */
        private fun route(event: ButtonEvent) = buttons.press(event.switch)
    }
