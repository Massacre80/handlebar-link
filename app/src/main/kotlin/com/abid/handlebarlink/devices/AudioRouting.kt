package com.abid.handlebarlink.devices

import android.content.Intent
import kotlinx.coroutines.flow.Flow

/** Where the phone plays media now (music, CarPlay audio). */
sealed interface MediaRoute {
    /** Android 12 has no public way to ask: not shown. */
    data object Unknown : MediaRoute

    /** A Bluetooth device, by address. */
    data class Bluetooth(
        val address: String,
    ) : MediaRoute

    /** The phone itself (its speaker or a wire). */
    data object Phone : MediaRoute
}

/** A place media can play: a connected Bluetooth device or the phone itself. */
data class MediaOutput(
    /** The platform's route id, for [AudioRouting.playOn]. */
    val id: String,
    /** Its name (a Bluetooth device's own name). */
    val name: String,
    val bluetooth: Boolean,
    /** Media plays here now. */
    val selected: Boolean,
)

/** Android's public view of audio routing, for the Intercom page (research of 2026-11-18). */
interface AudioRouting {
    /** Where media plays now, while collected. */
    fun mediaRoute(): Flow<MediaRoute>

    /** Where media can play, chosen in the app (Android 14+), while collected; empty where it cannot. */
    fun mediaOutputs(): Flow<List<MediaOutput>>

    /** Moves all media (CarPlay's too) to the output [id]; false when it cannot. */
    fun playOn(id: String): Boolean

    /** Opens the intercom maker's app, when one is installed; null otherwise. */
    fun vendorApp(): Intent?
}
