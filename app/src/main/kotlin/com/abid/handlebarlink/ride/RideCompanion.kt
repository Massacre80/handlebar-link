package com.abid.handlebarlink.ride

import kotlinx.coroutines.flow.Flow

/**
 * Something that runs beside the bike link for the length of a ride and may keep the ride going: our own CarPlay
 * receiver ([com.abid.handlebarlink.carplay.CarPlayCoordinator]). [RideCoordinator.ride] runs it in its own child job.
 */
interface RideCompanion {
    /** Whether the ride (and its foreground service) must go on for the companion. */
    val keepAlive: Flow<Boolean>

    /** Runs until cancelled. Never throws: a failure of the companion must not end the ride or touch the bike link. */
    suspend fun run()

    /** The ride is asked to end: stop, so [keepAlive] can fall. */
    fun stop()
}
