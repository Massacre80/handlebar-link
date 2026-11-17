package com.abid.handlebarlink

import android.app.Application
import com.abid.handlebarlink.devices.HeadsetBatteries
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** The application: the root of Hilt's dependency graph. */
@HiltAndroidApp
class HandlebarLinkApp : Application() {
    @Inject lateinit var batteries: HeadsetBatteries

    override fun onCreate() {
        super.onCreate()
        // Headsets report their battery once on connecting: listen for the whole process, not one screen.
        batteries.start()
    }
}
