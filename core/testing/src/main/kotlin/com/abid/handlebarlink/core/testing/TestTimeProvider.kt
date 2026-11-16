// TestCoroutineScheduler.currentTime is part of kotlinx-coroutines-test's virtual-time API.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.core.testing

import com.abid.handlebarlink.core.common.time.TimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A [TimeProvider] driven by the test's virtual time, so code under test and `delay` share one
 * scheduler (kotlinx-coroutines-test). Wall time is [start] plus the virtual milliseconds.
 */
public class TestTimeProvider(
    private val scheduler: TestCoroutineScheduler,
    private val start: Instant = Instant.parse("2026-11-16T15:30:00Z"),
) : TimeProvider {
    override val clock: Clock = SchedulerClock(ZoneOffset.UTC)

    override fun elapsedMillis(): Long = scheduler.currentTime

    private inner class SchedulerClock(
        private val zone: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = SchedulerClock(zone)

        override fun instant(): Instant = start.plusMillis(scheduler.currentTime)
    }
}
