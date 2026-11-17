package com.abid.handlebarlink.ride

import android.content.Context
import com.abid.handlebarlink.bike.link.BikeLink
import com.abid.handlebarlink.bike.link.BikeLinkConfig
import com.abid.handlebarlink.bike.link.android.AndroidBikeLink
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.di.BikeLinkDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/** A link and what the app must tell it from outside: Android sends no broadcast when a permission changes. */
class RideLink(
    /** The link. */
    val link: BikeLink,
    /** Re-reads the Bluetooth permission (the app calls it when it comes back to the foreground). */
    val recheckPermission: () -> Unit,
)

/** Creates the link for a bike; tests replace it with one that returns a FakeBikeLink. */
fun interface BikeLinkProvider {
    /** A link to the meter at [address]. */
    fun create(address: String): RideLink
}

/** The Android link from `:bike:link-android`, on the confined bike-link dispatcher. */
class AndroidBikeLinkProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @BikeLinkDispatcher private val dispatcher: CoroutineDispatcher,
        private val time: TimeProvider,
        private val logger: Logger,
    ) : BikeLinkProvider {
        override fun create(address: String): RideLink {
            // probeGatt: log everything the meter offers once per run (owner, 2026-11-17: find all the data
            // it has, also what RoadSync does not use). Read-only; see GattProbe.
            val link =
                AndroidBikeLink.create(
                    context,
                    address,
                    dispatcher,
                    time,
                    logger.child("bike"),
                    BikeLinkConfig(probeGatt = true),
                )
            return RideLink(link, link::recheckPermission)
        }
    }
