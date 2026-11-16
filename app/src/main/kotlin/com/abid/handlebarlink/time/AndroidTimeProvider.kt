package com.abid.handlebarlink.time

import android.os.SystemClock
import com.abid.handlebarlink.core.common.time.TimeProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The system's time: wall clock in the phone's current time zone (read on every call, so the meter's
 * clock follows a zone change), and `elapsedRealtime` as the monotonic clock (keeps counting in sleep).
 */
class AndroidTimeProvider : TimeProvider {
    override val clock: Clock = SystemZoneClock()

    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    private class SystemZoneClock : Clock() {
        private val utc = Clock.systemUTC()

        override fun getZone(): ZoneId = ZoneId.systemDefault()

        override fun withZone(zone: ZoneId): Clock = utc.withZone(zone)

        override fun instant(): Instant = utc.instant()
    }
}
