package com.abid.handlebarlink.devices

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.abid.handlebarlink.R
import com.abid.handlebarlink.controls.ControlAction
import com.abid.handlebarlink.controls.ControlTarget
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The intercom's assistant button. Android starts this for `ACTION_VOICE_COMMAND` when a headset asks for
 * the voice assistant (HFP AT+BVRA); it does what the rider chose on the Devices page and closes at once,
 * with nothing on screen (a translucent theme; also over the lock screen while riding). Exported for the
 * system; only that action is acted on, and no extras are read.
 */
@AndroidEntryPoint
class AssistantButtonActivity : ComponentActivity() {
    @Inject lateinit var button: AssistantButton

    @Inject lateinit var target: ControlTarget

    @Inject lateinit var devices: DeviceSettings

    @Inject lateinit var batteries: HeadsetBatteries

    @Inject lateinit var announcer: BatteryAnnouncer

    @Inject lateinit var logger: Logger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != Intent.ACTION_VOICE_COMMAND) {
            finish()
            return
        }
        lifecycleScope.launch {
            val action = button.action.first()
            logger.child("assistant").info("assistant.pressed", "action" to action.name)
            when (action) {
                AssistantAction.PLAY_PAUSE -> target.perform(ControlAction.PLAY_PAUSE)
                AssistantAction.NEXT -> target.perform(ControlAction.NEXT)
                AssistantAction.BATTERY -> announceBattery()
                AssistantAction.NOTHING -> Unit
            }
            finish()
        }
    }

    private suspend fun announceBattery() {
        val device = devices.devices.first().primary(devices.primary.first()) ?: return
        val level = batteries.levels.value[device.address]
        announcer.say(
            if (level != null) {
                getString(R.string.assistant_battery_said, device.name, level)
            } else {
                getString(R.string.assistant_battery_unknown, device.name)
            },
        )
    }
}
