package com.abid.handlebarlink.di

import com.abid.handlebarlink.autostart.AndroidCompanionPresence
import com.abid.handlebarlink.autostart.CompanionPresence
import com.abid.handlebarlink.ride.AndroidRideStarter
import com.abid.handlebarlink.ride.RideStarter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Auto-start on ignition (docs/PLAN.md M4): Android's companion-device presence and background ride starts. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AutoStartModule {
    @Binds
    abstract fun companionPresence(presence: AndroidCompanionPresence): CompanionPresence

    @Binds
    abstract fun rideStarter(starter: AndroidRideStarter): RideStarter
}
