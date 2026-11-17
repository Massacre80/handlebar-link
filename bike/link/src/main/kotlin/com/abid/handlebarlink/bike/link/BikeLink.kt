package com.abid.handlebarlink.bike.link

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The bike link: connects to the Honda meter, keeps the connection through the ride, and reports
 * handlebar presses. The app's ride coordinator calls [run] for as long as the link may work, and
 * steers it with [start], [stop] and [onPresence] (AGENTS.md, "Engine architecture").
 *
 * Command methods are non-suspending, thread-safe and may be called before [run]; they are queued.
 */
public interface BikeLink {
    /** What the link is doing. */
    public val state: StateFlow<LinkState>

    /**
     * Whether the app must keep its foreground service running for the link. Updated before [state]
     * reports the change that caused it, so a collector of [state] can read `keepAlive.value`.
     */
    public val keepAlive: StateFlow<Boolean>

    /** A problem the rider has to act on, for a notification; null when there is none. */
    public val problem: StateFlow<LinkProblem?>

    /** Handlebar presses as they arrive. Events, not state: nothing is replayed to late collectors. */
    public val buttons: Flow<ButtonEvent>

    /** Start connecting. [presentHint]: there is a fresh sign the bike is in range (connect directly). */
    public fun start(presentHint: Boolean)

    /** Stop: close the connection and the clock server. */
    public fun stop()

    /** The system reports the bike appearing ([present] true) or disappearing (companion-device presence). */
    public fun onPresence(present: Boolean)

    /**
     * Runs the link until the calling coroutine is cancelled; everything is closed on the way out.
     * Only one [run] at a time.
     */
    public suspend fun run()
}

/** Policy settings of the link. */
public data class BikeLinkConfig(
    /**
     * After the link is lost and the bike is gone, keep waiting this long before going idle. Null:
     * stay armed (until companion-device presence can wake the app, M4).
     */
    val lingerMillis: Long? = null,
    /**
     * After the first setup of each run, read every readable characteristic and descriptor the meter has
     * and log them (the GATT probe: read-only, never the DFU service but its version). For finding what
     * the meter offers beyond RoadSync's use.
     */
    val probeGatt: Boolean = false,
)
