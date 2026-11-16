package com.abid.handlebarlink.bike.link.machine

/**
 * Every timeout and delay of the link, in one place (docs/research/02-ble-connection.md §2.5, §2.6).
 */
internal object Timeouts {
    /** A direct connection attempt is abandoned after this (Kotlin-BLE-Library default). */
    const val DIRECT_CONNECT_MS = 10_000L

    /** Direct attempts before falling back to waiting with autoConnect. */
    const val DIRECT_ATTEMPTS = 3

    /** Pause before direct attempt n+1 is `n ×` this (300 ms, then 600 ms). */
    const val DIRECT_RETRY_STEP_MS = 300L

    /** A 133 this long after connectGatt is the stack's own connect timeout, not a transient error. */
    const val CONNECT_TIMEOUT_STATUS_AFTER_MS = 25_000L

    /** Link up → ready must finish within this; the happy path takes about 3.4 s on the CB350. */
    const val SETUP_WATCHDOG_MS = 20_000L

    /** Ready this long resets the failure counters. */
    const val READY_STABLE_MS = 60_000L

    /** Setup-failure backoff: 1 s, doubling, capped (±20 % jitter is applied by the runtime). */
    const val BACKOFF_FIRST_MS = 1_000L

    /** Upper bound of the setup-failure backoff. */
    const val BACKOFF_MAX_MS = 30_000L

    /** Two waiting connections that fail within this of being armed count as a failure. */
    const val QUICK_FAILURE_MS = 1_000L

    /** Window for counting immediate connect errors (133 storms). */
    const val EARLY_ERROR_WINDOW_MS = 120_000L

    /** Immediate connect errors in the window that mean the Bluetooth stack is stuck. */
    const val EARLY_ERRORS_FOR_STUCK = 6

    /** While the stack is stuck, wait this long between re-arms. */
    const val STUCK_REARM_MS = 60_000L

    /** Retry opening the clock (CTS) server after this when it failed. */
    const val SERVER_RETRY_MS = 1_000L

    /** Missing keys or authentication failures before the bond counts as broken. */
    const val AUTH_FAILURES_FOR_BROKEN_BOND = 2
}
