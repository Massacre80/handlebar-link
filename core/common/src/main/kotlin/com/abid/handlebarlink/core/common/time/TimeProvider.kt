package com.abid.handlebarlink.core.common.time

import java.time.Clock

/**
 * The only source of time (docs/research/01-architecture.md §5, rule 8). Production uses the system
 * clock and the monotonic clock; tests use virtual time.
 */
public interface TimeProvider {
    /** Wall-clock time, for timestamps shown to people and in logs. */
    public val clock: Clock

    /** Monotonic milliseconds for measuring durations and timeouts; unaffected by clock changes. */
    public fun elapsedMillis(): Long

    /**
     * The same monotonic clock in nanoseconds, for the CarPlay timing exchange (sub-millisecond round trips). The
     * default only scales [elapsedMillis]; a platform with a finer clock overrides it.
     */
    public fun elapsedNanos(): Long = elapsedMillis() * NANOS_PER_MILLI

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
