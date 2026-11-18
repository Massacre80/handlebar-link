package com.abid.handlebarlink.di

import com.abid.handlebarlink.carplay.AndroidCarPlayPermissions
import com.abid.handlebarlink.carplay.CarPlayPermissions
import com.abid.handlebarlink.carplay.CarPlayPortsSource
import com.abid.handlebarlink.carplay.CarPlaySettings
import com.abid.handlebarlink.carplay.DataStoreCarPlaySettings
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.createCarPlayLink
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
         * Placeholder until `:carplay:engine-android` exists: no platform ports, so CarPlay shows "not available".
         * Merging that module changes only this binding.
         */
        @Provides
        fun carPlayPortsSource(): CarPlayPortsSource = CarPlayPortsSource { null }

        /** Jitters the engines' retry pauses (the bike link uses the same `Random.Default`). */
        @Provides
        fun random(): Random = Random.Default

        /**
         * The receiver on its own single-threaded dispatcher, with the app's time and logger, or null when there are
         * no platform ports ([CarPlayPortsSource]).
         */
        @Provides
        @Singleton
        fun carPlayLink(
            source: CarPlayPortsSource,
            @CarPlayDispatcher dispatcher: CoroutineDispatcher,
            time: TimeProvider,
            random: Random,
            logger: Logger,
        ): CarPlayLink? =
            source.create()?.let {
                createCarPlayLink(
                    it,
                    dispatcher,
                    time,
                    random,
                    logger.child("carplay"),
                    CarPlayConfig(),
                )
            }
    }
}
