package com.abid.handlebarlink.di

import android.content.Context
import com.abid.handlebarlink.carplay.AndroidCarPlayPermissions
import com.abid.handlebarlink.carplay.CarPlayCanvas
import com.abid.handlebarlink.carplay.CarPlayPermissions
import com.abid.handlebarlink.carplay.CarPlayPortsSource
import com.abid.handlebarlink.carplay.CarPlaySettings
import com.abid.handlebarlink.carplay.DataStoreCarPlaySettings
import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.android.CarPlayScreen
import com.abid.handlebarlink.carplay.engine.android.createAndroidCarPlayPorts
import com.abid.handlebarlink.carplay.engine.android.createCarPlayScreen
import com.abid.handlebarlink.carplay.engine.createCarPlayLink
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Our own CarPlay receiver (docs/PLAN.md "CarPlay in the app", C2): the engine, its platform ports and the beta
 * switch.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CarPlayModule {
    @Binds
    abstract fun carPlaySettings(settings: DataStoreCarPlaySettings): CarPlaySettings

    @Binds
    abstract fun carPlayPermissions(permissions: AndroidCarPlayPermissions): CarPlayPermissions

    companion object {
        /**
         * The Android ports (`:carplay:engine-android`). Creating them only constructs objects, so this is safe
         * where Hilt first builds the link, even on the main thread (docs/LESSONS.md 60).
         */
        @Provides
        fun carPlayPortsSource(
            @ApplicationContext context: Context,
            time: TimeProvider,
            logger: Logger,
            screen: CarPlayScreen,
        ): CarPlayPortsSource =
            CarPlayPortsSource { createAndroidCarPlayPorts(context, time, logger.child("carplay"), screen) }

        /**
         * The CarPlay picture's hardware decoder: fed by the engine through the ports, shown by the CarPlay display.
         * One per process, so leaving the display keeps it (docs/PLAN.md C4). Creating it only constructs objects.
         */
        @Provides
        @Singleton
        fun carPlayScreen(logger: Logger): CarPlayScreen = createCarPlayScreen(logger.child("carplay"))

        /**
         * The receiver on its own single-threaded dispatcher, with the app's time and logger, or null when there are
         * no platform ports ([CarPlayPortsSource]). The canvas told to the iPhone is this phone's whole screen
         * ([CarPlayCanvas]), measured on the engine's thread at the first connection attempt. `Random.Default` jitters
         * the retry pauses, as the bike link's.
         */
        @Provides
        @Singleton
        fun carPlayLink(
            source: CarPlayPortsSource,
            @CarPlayDispatcher dispatcher: CoroutineDispatcher,
            time: TimeProvider,
            logger: Logger,
            canvas: CarPlayCanvas,
        ): CarPlayLink? =
            source.create()?.let {
                createCarPlayLink(
                    it,
                    dispatcher,
                    time,
                    Random.Default,
                    logger.child("carplay"),
                    canvas::config,
                )
            }
    }
}
