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

        /** The selected bike's link (one per address, created once), or null when no bike is selected. */
        val link: StateFlow<BikeLink?> =
            settings.bike
                .map { it?.address }
                .distinctUntilChanged()
                .map { address -> address?.let(provider::create) }
                .stateIn(scope, SharingStarted.Eagerly, null)

        /**
         * Runs the ride: starts the link and routes presses until the link no longer needs to be kept
         * alive (stopped, blocked, or idle after the bike left), or until cancelled. Returns the problem
         * the rider has to act on, if any. Returns at once when no bike is selected.
         */
        suspend fun ride(): LinkProblem? {
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
                    current.start(presentHint = true)
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
