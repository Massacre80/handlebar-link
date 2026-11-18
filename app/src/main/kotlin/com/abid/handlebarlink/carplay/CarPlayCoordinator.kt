package com.abid.handlebarlink.carplay

import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.ride.RideCompanion
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs our own CarPlay receiver next to the bike link during a ride (docs/PLAN.md "CarPlay receiver", C2), behind
 * the default-off beta switch ([CarPlaySettings]). The ride calls [run] in its own child job; while the switch is
 * on, CarPlay's permissions are granted and ports exist ([link] not null), the engine's [CarPlayLink.run] runs in
 * a child job of its own and is started; switching off stops it and cancels that job, which closes the radio,
 * Bluetooth and sockets. [run] never throws (except cancellation): a CarPlay failure is logged and shown as
 * [CarPlayStatus.Failed], and never reaches the bike link (docs/PLAN.md "Structure").
 */
@Singleton
class CarPlayCoordinator
    @Inject
    constructor(
        private val settings: CarPlaySettings,
        private val link: CarPlayLink?,
        private val permissions: CarPlayPermissions,
        logger: Logger,
    ) : RideCompanion {
        private val log = logger.child("carplay")
        private val running = AtomicBoolean(false)
        private val rechecks = MutableStateFlow(0)
        private val failed = MutableStateFlow(false)

        /** Set while CarPlay wants the service: from the start of [run] until it knows it will not start, or stops. */
        private val wanted = MutableStateFlow(false)

        private val missing: Flow<List<String>> = rechecks.map { permissions.missing() }

        /**
         * Whether the ride must keep its foreground service for CarPlay: while CarPlay decides whether to start, while
         * it is meant to run, and until the engine's own [CarPlayLink.keepAlive] ends after a stop.
         */
        override val keepAlive: Flow<Boolean> =
            if (link == null) wanted else combine(wanted, link.keepAlive) { want, engine -> want || engine }

        /** What the CarPlay page and Home show. */
        val status: Flow<CarPlayStatus> =
            combine(settings.enabled, missing, failed, link?.state ?: flowOf(CarPlayState.Stopped)) {
                enabled,
                missing,
                failed,
                state,
                ->
                carPlayStatus(enabled, link != null, missing, failed, state)
            }

        /** Back in the foreground or after a permission answer: a permission may have changed (no broadcast tells). */
        fun recheckPermission() {
            rechecks.value++
        }

        /**
         * Runs CarPlay for as long as the ride does (until cancelled), following the switch and the permissions.
         * Returns at once when there are no ports. Only one [run] at a time.
         */
        override suspend fun run() {
            val link = link
            if (link == null) {
                log.info("carplay.unavailable")
                return
            }
            check(running.compareAndSet(false, true)) { "CarPlay is already running" }
            // Until the switch has been read, the ride must not end for lack of a reason to stay (keepAlive).
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

        /** Starts and stops the engine as the switch and the permissions change. */
        private suspend fun follow(link: CarPlayLink) =
            coroutineScope {
                var session: Job? = null
                combine(settings.enabled, missing) { enabled, missing ->
                    if (enabled && missing.isNotEmpty()) log.warn("carplay.permission_missing", "missing" to missing)
                    enabled && missing.isEmpty()
                }.distinctUntilChanged().collect { active ->
                    if (active) {
                        failed.value = false
                        wanted.value = true
                        session = launch { guarded { runEngine(link) } }
                        link.start()
                        log.info("carplay.start")
                    } else {
                        wanted.value = false
                        session?.let {
                            link.stop()
                            // Cancelling the engine's run() closes everything it holds (CarPlayLink.run).
                            it.cancelAndJoin()
                            log.info("carplay.off")
                        }
                        session = null
                    }
                }
            }

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
