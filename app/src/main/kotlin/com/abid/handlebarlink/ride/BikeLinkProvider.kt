package com.abid.handlebarlink.ride

import android.content.Context
import com.abid.handlebarlink.bike.link.BikeLink
import com.abid.handlebarlink.bike.link.android.AndroidBikeLink
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.di.BikeLinkDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/** Creates the link for a bike; tests replace it with one that returns a FakeBikeLink. */
fun interface BikeLinkProvider {
    /** A link to the meter at [address]. */
    fun create(address: String): BikeLink
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
        override fun create(address: String): BikeLink =
            AndroidBikeLink.create(context, address, dispatcher, time, logger.child("bike"))
    }
