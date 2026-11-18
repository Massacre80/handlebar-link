package com.abid.handlebarlink

import android.app.Application
import com.abid.handlebarlink.autostart.PresenceArming
import com.abid.handlebarlink.controls.CarPlayControls
import com.abid.handlebarlink.devices.HeadsetBatteries
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** The application: the root of Hilt's dependency graph. */
@HiltAndroidApp
class HandlebarLinkApp : Application() {
    @Inject lateinit var batteries: HeadsetBatteries

    @Inject lateinit var presence: PresenceArming

    @Inject lateinit var controls: CarPlayControls

    override fun onCreate() {
        super.onCreate()
        // Headsets report their battery once on connecting: listen for the whole process, not one screen.
        batteries.start()
        // Android watches for the selected bike, also after the app was updated (docs/PLAN.md M4.2).
        presence.start()
        // A microphone this app muted must not stay muted after the process died mid-call (controls/).
        controls.start()
    }
}
