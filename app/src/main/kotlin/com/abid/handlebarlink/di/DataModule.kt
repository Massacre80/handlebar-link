package com.abid.handlebarlink.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.data.DataStoreBikeSettings
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

    companion object {
        @Provides
        @Singleton
        fun preferences(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("bike") }
    }
}
