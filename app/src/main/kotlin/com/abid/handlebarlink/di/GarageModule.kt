package com.abid.handlebarlink.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.abid.handlebarlink.garage.DataStoreGarageStore
import com.abid.handlebarlink.garage.GarageData
import com.abid.handlebarlink.garage.GarageSerializer
import com.abid.handlebarlink.garage.GarageStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Garage's records (docs/PLAN.md "Garage"): their own DataStore file, one instance for the process. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GarageModule {
    @Binds
    abstract fun garageStore(store: DataStoreGarageStore): GarageStore

    companion object {
        /**
         * The typed DataStore in `garage.json`. A file that cannot be parsed at all starts empty rather than
         * leaving Garage unusable (GarageSerializer reads unknown and missing keys without failing).
         */
        @Provides
        @Singleton
        fun garageData(
            @ApplicationContext context: Context,
        ): DataStore<GarageData> =
            DataStoreFactory.create(
                serializer = GarageSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { GarageData() },
                produceFile = { context.dataStoreFile("garage.json") },
            )
    }
}
