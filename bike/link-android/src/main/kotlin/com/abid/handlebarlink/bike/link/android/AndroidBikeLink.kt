package com.abid.handlebarlink.bike.link.android

import android.content.Context
import com.abid.handlebarlink.bike.link.BikeLink
import com.abid.handlebarlink.bike.link.BikeLinkConfig
import com.abid.handlebarlink.bike.link.android.gatt.AndroidGattRadio
import com.abid.handlebarlink.bike.link.android.server.AndroidClockServer
import com.abid.handlebarlink.bike.link.android.system.AndroidSystemSignals
import com.abid.handlebarlink.bike.link.android.system.AndroidWakeLock
import com.abid.handlebarlink.bike.link.createBikeLink
import com.abid.handlebarlink.bike.link.spi.LinkPorts
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.random.Random

/**
 * The bike link on Android: the engine from `:bike:link` running on this module's Bluetooth adapters.
 * The app creates one (Hilt `@Singleton`) and calls [recheckPermission] when it comes to the foreground.
 */
public class AndroidBikeLink private constructor(
    private val signals: AndroidSystemSignals,
    link: BikeLink,
) : BikeLink by link {
    /** Re-reads the Bluetooth permission; Android sends no broadcast when it changes. */
    public fun recheckPermission() {
        signals.recheckPermission()
    }

    /** Creating links. */
    public companion object {
        /**
         * A link to the bonded meter at [bikeAddress]. [dispatcher] must be single-threaded: all link
         * state is confined to it.
         */
        public fun create(
            context: Context,
            bikeAddress: String,
            dispatcher: CoroutineDispatcher,
            time: TimeProvider,
            logger: Logger,
            config: BikeLinkConfig = BikeLinkConfig(),
        ): AndroidBikeLink {
            val bluetooth = AndroidBluetooth(context.applicationContext, bikeAddress)
            val signals = AndroidSystemSignals(bluetooth, logger)
            val ports =
                LinkPorts(
                    radio = AndroidGattRadio(bluetooth, signals.bondState, logger),
                    server = AndroidClockServer(bluetooth, time, logger),
                    signals = signals,
                    wakeLock = AndroidWakeLock(context.applicationContext),
                )
            return AndroidBikeLink(signals, createBikeLink(ports, dispatcher, time, Random.Default, logger, config))
        }
    }
}
