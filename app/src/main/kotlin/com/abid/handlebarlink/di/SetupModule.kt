package com.abid.handlebarlink.di

import com.abid.handlebarlink.setup.AndroidMeterAssociation
import com.abid.handlebarlink.setup.AndroidMeterBonding
import com.abid.handlebarlink.setup.AndroidMeterScanner
import com.abid.handlebarlink.setup.AndroidSetupConditions
import com.abid.handlebarlink.setup.MeterAssociation
import com.abid.handlebarlink.setup.MeterBonding
import com.abid.handlebarlink.setup.MeterScanner
import com.abid.handlebarlink.setup.SetupConditions
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** First-time setup: the Android scanner, companion-device link and pairing behind small interfaces. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SetupModule {
    @Binds
    abstract fun conditions(conditions: AndroidSetupConditions): SetupConditions

    @Binds
    abstract fun scanner(scanner: AndroidMeterScanner): MeterScanner

    @Binds
    abstract fun association(association: AndroidMeterAssociation): MeterAssociation

    @Binds
    abstract fun bonding(bonding: AndroidMeterBonding): MeterBonding
}
