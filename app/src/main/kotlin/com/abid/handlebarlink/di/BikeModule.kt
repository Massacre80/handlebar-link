package com.abid.handlebarlink.di

import com.abid.handlebarlink.controls.ButtonPresses
import com.abid.handlebarlink.controls.ControlTarget
import com.abid.handlebarlink.controls.DataStoreHandlebarMapping
import com.abid.handlebarlink.controls.HandlebarMapping
import com.abid.handlebarlink.controls.MappedButtonPresses
import com.abid.handlebarlink.controls.MediaSessionControlTarget
import com.abid.handlebarlink.ride.AndroidBikeLinkProvider
import com.abid.handlebarlink.ride.BikeLinkProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The engines and outputs. Engines stay framework-free; this module is where Hilt meets them
 * (a conscious deviation from Now in Android, which puts Hilt modules next to implementations: PLAN).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BikeModule {
    @Binds
    abstract fun bikeLinkProvider(provider: AndroidBikeLinkProvider): BikeLinkProvider

    @Binds
    abstract fun controlTarget(target: MediaSessionControlTarget): ControlTarget

    @Binds
    abstract fun buttonPresses(presses: MappedButtonPresses): ButtonPresses

    @Binds
    abstract fun handlebarMapping(mapping: DataStoreHandlebarMapping): HandlebarMapping
}
