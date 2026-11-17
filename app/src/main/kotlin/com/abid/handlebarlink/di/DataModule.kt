package com.abid.handlebarlink.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.data.DataStoreBikeSettings
import com.abid.handlebarlink.devices.AndroidAssistantButton
import com.abid.handlebarlink.devices.AndroidAudioRouting
import com.abid.handlebarlink.devices.AndroidBluetoothDevices
import com.abid.handlebarlink.devices.AndroidBluetoothPairing
import com.abid.handlebarlink.devices.AndroidHeadsetLink
import com.abid.handlebarlink.devices.AssistantButton
import com.abid.handlebarlink.devices.AudioRouting
import com.abid.handlebarlink.devices.BluetoothDevices
import com.abid.handlebarlink.devices.BluetoothPairing
import com.abid.handlebarlink.devices.DataStoreDeviceSettings
import com.abid.handlebarlink.devices.DeviceSettings
import com.abid.handlebarlink.devices.HeadsetLink
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Settings storage: one DataStore per file, created once (DataStore requires a single instance). */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bikeSettings(settings: DataStoreBikeSettings): BikeSettings

    @Binds
    abstract fun deviceSettings(settings: DataStoreDeviceSettings): DeviceSettings

    @Binds
    abstract fun bluetoothDevices(devices: AndroidBluetoothDevices): BluetoothDevices

    @Binds
    abstract fun bluetoothPairing(pairing: AndroidBluetoothPairing): BluetoothPairing

    @Binds
    abstract fun audioRouting(routing: AndroidAudioRouting): AudioRouting

    @Binds
    abstract fun headsetLink(link: AndroidHeadsetLink): HeadsetLink

    @Binds
    abstract fun assistantButton(button: AndroidAssistantButton): AssistantButton

    companion object {
        @Provides
        @Singleton
        fun preferences(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
    }
}
