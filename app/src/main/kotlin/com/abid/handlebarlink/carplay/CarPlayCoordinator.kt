package com.abid.handlebarlink.carplay

import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.ride.RideCompanion
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs our own CarPlay receiver next to the bike link during every ride (docs/PLAN.md "CarPlay receiver", C2; it
 * replaced CarLink, owner 2026-11-20, so there is no switch). The ride calls [run] in its own child job; while
 * CarPlay's permissions are granted, ports exist ([link] not null) and the rider has not stopped it ([hold]), the
 * engine's [CarPlayLink.run] runs in a child job of its own and is started; a withdrawn permission or [hold] stops it
 * and cancels that job, which closes the radio, Bluetooth and sockets. [run] never throws (except cancellation): a
 * CarPlay failure is logged and shown as [CarPlayStatus.Failed] until [start], and never reaches the bike link
 * (docs/PLAN.md "Structure").
 *
 * The rider's "CarPlay screen" ([CarPlaySettings.orientation]) goes to the [canvas] before each start; a change while
 * CarPlay runs stops the engine and starts it again (a new connection), so the iPhone is told the new size.
 */
@Singleton
class CarPlayCoordinator
    @Inject
    constructor(
        private val settings: CarPlaySettings,
        private val link: CarPlayLink?,
        private val permissions: CarPlayPermissions,
        private val canvas: CanvasOrientation,
        private val time: TimeProvider,
        logger: Logger,
    ) : RideCompanion {
        private val log = logger.child("carplay")

        /** When the rider last pressed Try now (elapsed time): the retry it starts shows at once ([steady]). */
        private val tryNowAt = AtomicLong(Long.MIN_VALUE / 2)
        private val running = AtomicBoolean(false)
        private val rechecks = MutableStateFlow(0)
        private val failed = MutableStateFlow(false)

        /** Bumped by [start] after a failure, so the same orientation starts the engine again. */
        private val restarts = MutableStateFlow(0)

        /**
         * The rider stopped CarPlay ([hold]) until [start] or the next ignition ([onBikeReady]). Kept in memory only: a
         * new ride does not clear it (the app restarts the ride whenever it comes to the front, which would undo a
         * Disconnect at once), a new process does.
         */
        private val held = MutableStateFlow(false)

        /** Whether the rider stopped CarPlay ([hold]) until [start]; the app then starts no ride for CarPlay. */
        val stoppedByRider: StateFlow<Boolean> = held.asStateFlow()

        /** Set while CarPlay wants the service: from the start of [run] until it knows it will not start, or stops. */
        private val wanted = MutableStateFlow(false)

        private val missing: Flow<List<String>> = rechecks.map { permissions.missing() }

        /**
         * Whether the ride must keep its foreground service for CarPlay: while CarPlay decides whether to start, while
         * it is meant to run, and until the engine's own [CarPlayLink.keepAlive] ends after a stop.
         */
        override val keepAlive: Flow<Boolean> =
            if (link == null) wanted else combine(wanted, link.keepAlive) { want, engine -> want || engine }

        /**
         * What the CarPlay page, its sheets and display, and Home show: steady ([steady]), so a step or a drop that
         * lasts a moment never flashes. Nothing decides on it; every collector starts from the current status.
         */
        val status: Flow<CarPlayStatus> =
            combine(
                missing,
                failed,
                held,
                link?.state ?: flowOf(CarPlayState.Stopped),
            ) { missing, failed, held, state ->
                carPlayStatus(link != null, missing, failed, state, held)
            }.steady(time) { time.elapsedMillis() - tryNowAt.get() < TRY_NOW_ANSWER_MS }

        /** Back in the foreground or after a permission answer: a permission may have changed (no broadcast tells). */
        fun recheckPermission() {
            rechecks.value++
        }

        /**
         * Stops CarPlay until [start] (the tab's Cancel while connecting, Disconnect while connected): the engine
         * stops and releases the radio and Bluetooth.
         */
        fun hold() {
            held.value = true
            log.info("carplay.held")
        }

        /**
         * Try now (Home's BikePlay card while a retry counts down): the engine cuts the pause short and tries again
         * at once ([CarPlayLink.retryNow]); nothing happens when no retry is waiting.
         */
        fun retryNow() {
            log.info("carplay.retry_now")
            tryNowAt.set(time.elapsedMillis())
            link?.retryNow()
        }

        /**
         * Start CarPlay (the tab's and the pairing's): lifts a [hold], and after a failure starts the engine again in
         * the running ride. Without a ride the caller starts the ride service, whose [run] starts afresh.
         */
        fun start() {
            held.value = false
            if (failed.value) restarts.value++
            log.info("carplay.resumed", "after_failure" to failed.value)
        }

        /**
         * The bike's meter turned Ready: the ignition is on and the rider is about to ride, so CarPlay tries the iPhone
         * now. A hold from an earlier ride is lifted, a failure starts the engine again, and a retry waiting out its
         * pause (up to 30 s) goes at once. Owner, 2026-11-21: "when I start the ignition
         * CarPlay doesn't try to connect".
         */
        override fun onBikeReady() {
            val wasHeld = held.value
            val wasFailed = failed.value
            log.info("carplay.bike_ready", "held" to wasHeld, "after_failure" to wasFailed)
            held.value = false
            // A fresh start tries at once; only a running engine has a pause to cut short.
            if (wasFailed) restarts.value++
            if (!wasHeld && !wasFailed) link?.retryNow()
        }

        /**
         * Runs CarPlay for as long as the ride does (until cancelled), following the permissions and the hold.
         * Returns at once when there are no ports. Only one [run] at a time.
         */
        override suspend fun run() {
            val link = link
            if (link == null) {
                log.info("carplay.unavailable")
                return
            }
            check(running.compareAndSet(false, true)) { "CarPlay is already running" }
            // Until the settings have been read, the ride must not end for lack of a reason to stay (keepAlive).
            wanted.value = true
            failed.value = false
            try {
                guarded { follow(link) }
            } finally {
                wanted.value = false
                // A start the engine had not taken yet must not carry over into the next ride's run().
                link.stop()
                running.set(false)
            }
        }

        /** The ride is asked to end ([com.abid.handlebarlink.ride.RideCoordinator.stop]): CarPlay stops too. */
        override fun stop() {
            if (!running.get()) return
            wanted.value = false
            link?.stop()
        }

        /**
         * Starts and stops the engine as the permissions and the hold change; a new orientation while it runs stops it
         * and starts it again in that orientation, and [start] after a failure starts it again.
         */
        private suspend fun follow(link: CarPlayLink) =
            coroutineScope {
                var session: Job? = null
                combine(missing, held, settings.orientation, restarts) { missing, held, orientation, restart ->
                    if (missing.isNotEmpty()) log.warn("carplay.permission_missing", "missing" to missing)
                    // What to run (the orientation, and which start after a failure), or null when CarPlay must not.
                    Active(orientation, restart).takeIf { missing.isEmpty() && !held }
                }.distinctUntilChanged().collect { active ->
                    // Still wanted across a restart, so the ride never ends between the stop and the new start.
                    if (active == null) wanted.value = false
                    session?.let {
                        link.stop()
                        // Cancelling the engine's run() closes everything it holds (CarPlayLink.run).
                        it.cancelAndJoin()
                        if (active ==
                            null
                        ) {
                            log.info("carplay.off")
                        } else {
                            log.info("carplay.restart", "orientation" to active.orientation)
                        }
                    }
                    session = null
                    if (active != null) {
                        failed.value = false
                        wanted.value = true
                        canvas.use(active.orientation)
                        session = launch { guarded { runEngine(link) } }
                        link.start()
                        log.info("carplay.start", "orientation" to active.orientation)
                    }
                }
            }

        /** What the engine runs with: its [orientation], and the [restart] count that a start after a failure bumps. */
        private data class Active(
            val orientation: CarPlayOrientation,
            val restart: Int,
        )

        private suspend fun runEngine(link: CarPlayLink) {
            link.run()
            // run() only returns when cancelled; anything else is a bug in the engine.
            error("CarPlayLink.run() returned")
        }

        /**
         * The boundary that keeps CarPlay away from the bike link: any failure in [block] is logged and shown, never
         * rethrown. Cancellation of our own job is propagated; a CancellationException from inside (a timeout in the
         * engine or a port) is a failure like any other (docs/LESSONS.md 54).
         */
        @Suppress("TooGenericExceptionCaught") // The isolation boundary: no CarPlay failure may end the ride.
        private suspend fun guarded(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                log.error("carplay.failed", e)
                wanted.value = false
                failed.value = true
            }
        }
    }

/**
 * How long after Try now the next attempt counts as its answer: the engine starts it within a dispatch, so a second is
 * plenty, and a later, ordinary change waits as usual.
 */
private const val TRY_NOW_ANSWER_MS = 1_000L
