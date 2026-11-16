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
}
