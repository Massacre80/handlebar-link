package com.abid.handlebarlink.di

import com.abid.handlebarlink.controls.ActionPerformer
import com.abid.handlebarlink.controls.AndroidMicrophone
import com.abid.handlebarlink.controls.ButtonPresses
import com.abid.handlebarlink.controls.CallSituations
import com.abid.handlebarlink.controls.ControlTarget
import com.abid.handlebarlink.controls.DataStoreHandlebarMapping
import com.abid.handlebarlink.controls.DataStoreMicMuteRecord
import com.abid.handlebarlink.controls.HandlebarMapping
import com.abid.handlebarlink.controls.MappedButtonPresses
import com.abid.handlebarlink.controls.MicMuteRecord
import com.abid.handlebarlink.controls.Microphone
import com.abid.handlebarlink.controls.PressFeedback
import com.abid.handlebarlink.controls.RiderActions
import com.abid.handlebarlink.controls.RoutedCallSituations
import com.abid.handlebarlink.controls.RoutedControlTarget
import com.abid.handlebarlink.ride.AndroidBikeLinkProvider
import com.abid.handlebarlink.ride.BikeLinkProvider
import com.abid.handlebarlink.ui.overlay.PressBanner
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

    /** Our own CarPlay while it is connected, else CarLink's media session. */
    @Binds
    abstract fun controlTarget(target: RoutedControlTarget): ControlTarget

    @Binds
    abstract fun buttonPresses(presses: MappedButtonPresses): ButtonPresses

    @Binds
    abstract fun handlebarMapping(mapping: DataStoreHandlebarMapping): HandlebarMapping

    @Binds
    abstract fun pressFeedback(banner: PressBanner): PressFeedback

    @Binds
    abstract fun actionPerformer(actions: RiderActions): ActionPerformer

    /** Our own CarPlay's iAP2 call states while it is connected, else the phone's audio (never ringing). */
    @Binds
    abstract fun callSituations(situations: RoutedCallSituations): CallSituations

    @Binds
    abstract fun microphone(microphone: AndroidMicrophone): Microphone

    @Binds
    abstract fun micMuteRecord(record: DataStoreMicMuteRecord): MicMuteRecord
}
