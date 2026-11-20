package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.core.common.log.Logger

/**
 * Which verified AirPlay session of one wireless attempt is the live one, DiPlay's `activeSession`
 * (CarPlayController.kt:406-451): a session becomes active when it RECORDs (`onSessionActive`, AirPlaySession.kt:
 * 585-588), a newer one replaces it, and only the active session's commands and end count:
 * - `disableBluetooth` is honoured only from the active session (`activeSession === session`, :510-514);
 * - a verified connection that ends decides the attempt only while it is the active one, or while none is active yet
 *   (DiPlay's wireless wrapper fails only a start still waiting for its session, `wasActive`, :2012-2018); a superseded
 *   connection that the iPhone left behind only logs its end, so it cannot take the newer live session down with it.
 *
 * One per attempt; touched only on the engine's dispatcher.
 */
internal class ActiveSession(
    private val log: Logger,
) {
    private var active: SessionContext? = null

    /** [session] RECORDed: it is the live one now. */
    fun recorded(session: SessionContext) {
        if (active != null && active !== session) log.info("carplay.airplay.session_replaced", "peer" to session.peer)
        active = session
    }

    fun isActive(session: SessionContext): Boolean = active === session

    /** The verified [session] ended; true when that ends the attempt (it was live, or no session is live yet). */
    fun ended(session: SessionContext?): Boolean {
        val decides = active == null || active === session
        if (active === session) active = null
        return decides
    }
}
