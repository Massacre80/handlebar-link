package com.abid.handlebarlink.controls

import android.content.Context
import com.abid.handlebarlink.R
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.devices.BatteryAnnouncer
import com.abid.handlebarlink.devices.DeviceSettings
import com.abid.handlebarlink.devices.HeadsetBatteries
import com.abid.handlebarlink.devices.primary
import com.abid.handlebarlink.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Does a [RiderAction]: CarPlay's media through [ControlTarget], the battery out loud, or nothing. */
class RiderActions
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val target: ControlTarget,
        private val devices: DeviceSettings,
        private val batteries: HeadsetBatteries,
        private val announcer: BatteryAnnouncer,
        logger: Logger,
    ) {
        private val log = logger.child("control")

        /** Does [action]; never throws. */
        suspend fun perform(action: RiderAction) {
            val media =
                when (action) {
                    RiderAction.PLAY_PAUSE -> ControlAction.PLAY_PAUSE
                    RiderAction.NEXT -> ControlAction.NEXT
                    RiderAction.PREVIOUS -> ControlAction.PREVIOUS
                    RiderAction.BATTERY -> null.also { announceBattery() }
                    RiderAction.NOTHING -> null
                }
            if (media != null) {
                when (val result = target.perform(media)) {
                    ControlResult.Done -> {
                        log.info("control.done", "action" to action)
                    }

                    is ControlResult.NotDone -> {
                        log.warn(
                            "control.not_done",
                            "action" to action,
                            "reason" to result.reason,
                        )
                    }
                }
            }
        }

        private suspend fun announceBattery() {
            val device = devices.devices.first().primary(devices.primary.first()) ?: return
            val level = batteries.levels.value[device.address]
            announcer.say(
                if (level != null) {
                    context.getString(R.string.assistant_battery_said, device.name, level)
                } else {
                    context.getString(R.string.assistant_battery_unknown, device.name)
                },
            )
        }
    }

/** [ButtonPresses] through the rider's [HandlebarMapping]: each press does its action, off the link's thread. */
class MappedButtonPresses
    @Inject
    constructor(
        private val mapping: HandlebarMapping,
        private val actions: RiderActions,
        @ApplicationScope private val scope: CoroutineScope,
        logger: Logger,
    ) : ButtonPresses {
        private val log = logger.child("control")

        override fun press(switch: HandlebarSwitch) {
            val action = mapping.current.value[switch] ?: RiderAction.NOTHING
            log.info("control.press", "switch" to switch, "action" to action)
            if (action != RiderAction.NOTHING) scope.launch { actions.perform(action) }
        }
    }
