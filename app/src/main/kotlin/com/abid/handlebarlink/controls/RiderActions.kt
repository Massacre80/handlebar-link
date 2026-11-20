package com.abid.handlebarlink.controls

import android.content.Context
import android.media.AudioManager
import android.text.format.DateFormat
import com.abid.handlebarlink.R
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.devices.BatteryAnnouncer
import com.abid.handlebarlink.devices.DeviceSettings
import com.abid.handlebarlink.devices.HeadsetBatteries
import com.abid.handlebarlink.devices.primary
import com.abid.handlebarlink.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

/**
 * Does a [RiderAction]: CarPlay's music, calls and Siri, and the microphone during a call, through
 * [CarPlayControls]; volume, brightness, screens and the battery or time out loud on the phone; or nothing.
 */
class RiderActions
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val controls: CarPlayControls,
        private val announcements: Announcements,
        private val brightness: ScreenBrightness,
        private val screens: ScreenSwitcher,
        logger: Logger,
    ) : ActionPerformer {
        private val log = logger.child("control")

        /**
         * Does [action]; never throws. When done, its note on the result for the on-screen banner: the new
         * brightness, what was said, the microphone's mute; null when there is nothing to add to the action's name.
         */
        override suspend fun perform(action: RiderAction): ActionOutcome {
            val result =
                when (action) {
                    in CONTROLS, RiderAction.MUTE_MIC -> controls.perform(action)
                    else -> return ActionOutcome.Done(performHere(action))
                }
            return when (result) {
                ControlResult.Done -> {
                    log.info("control.done", "action" to action)
                    ActionOutcome.Done(if (action == RiderAction.MUTE_MIC) micNote() else null)
                }

                is ControlResult.NotDone -> {
                    log.warn("control.not_done", "action" to action, "reason" to result.reason)
                    ActionOutcome.NotDone(result.reason)
                }
            }
        }

        private fun micNote(): String =
            context.getString(if (controls.micMuted) R.string.action_mic_muted else R.string.action_mic_unmuted)

        /** An action done on the phone itself (not CarPlay's media); its note, as [perform]. */
        private suspend fun performHere(action: RiderAction): String? {
            var note: String? = null
            when (action) {
                RiderAction.VOLUME_UP -> {
                    adjustVolume(AudioManager.ADJUST_RAISE)
                }

                RiderAction.VOLUME_DOWN -> {
                    adjustVolume(AudioManager.ADJUST_LOWER)
                }

                RiderAction.MUTE -> {
                    adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
                }

                RiderAction.BRIGHTNESS_UP -> {
                    note = stepBrightness(up = true)
                }

                RiderAction.BRIGHTNESS_DOWN -> {
                    note = stepBrightness(up = false)
                }

                RiderAction.SWITCH_SCREEN -> {
                    screens.switch()
                }

                RiderAction.OPEN_CARPLAY -> {
                    screens.openCarPlay()
                }

                RiderAction.OPEN_APP -> {
                    screens.openApp()
                }

                RiderAction.BATTERY -> {
                    note = announcements.battery()
                }

                RiderAction.TIME -> {
                    note = announcements.time()
                }

                RiderAction.PLAY_PAUSE,
                RiderAction.NEXT,
                RiderAction.PREVIOUS,
                RiderAction.ANSWER_CALL,
                RiderAction.DECLINE_CALL,
                RiderAction.END_CALL,
                RiderAction.MUTE_MIC,
                RiderAction.SIRI,
                RiderAction.NOTHING,
                -> {}
            }
            return note
        }

        /** A step of screen brightness, as "70%"; says where to allow it when not allowed yet. */
        private fun stepBrightness(up: Boolean): String? {
            val percent = brightness.step(up)
            if (percent == null) announcements.brightnessNotAllowed()
            return percent?.let { context.getString(R.string.action_brightness_level, it) }
        }

        /**
         * The volume of what is playing now (the call during a call, else music), as the phone's own volume
         * keys do; the phone plays CarPlay to the helmet, so this is the helmet's volume. Shows the system's
         * volume bar on the screen as feedback.
         */
        private fun adjustVolume(direction: Int) {
            val audio = context.getSystemService(AudioManager::class.java)
            audio.adjustSuggestedStreamVolume(
                direction,
                AudioManager.USE_DEFAULT_STREAM_TYPE,
                AudioManager.FLAG_SHOW_UI,
            )
            log.info("control.volume", "direction" to direction)
        }
    }

/** What the rider hears on asking: the primary device's battery, the time. */
class Announcements
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val devices: DeviceSettings,
        private val batteries: HeadsetBatteries,
        private val announcer: BatteryAnnouncer,
        private val time: TimeProvider,
    ) {
        /** Tells the rider brightness is not allowed yet, and where to allow it. */
        fun brightnessNotAllowed() {
            announcer.say(context.getString(R.string.action_brightness_not_allowed))
        }

        /** Says the primary device's battery, or that it is unknown; returns what it said (null: no device). */
        suspend fun battery(): String? {
            val device = devices.devices.first().primary(devices.primary.first()) ?: return null
            val level = batteries.levels.value[device.address]
            val said =
                if (level != null) {
                    context.getString(R.string.assistant_battery_said, device.name, level)
                } else {
                    context.getString(R.string.assistant_battery_unknown, device.name)
                }
            announcer.say(said)
            return said
        }

        /** Says the time in the phone's format (12 or 24 hour, as set); returns what it said. */
        fun time(): String {
            val now = Date.from(time.clock.instant())
            val said = context.getString(R.string.action_time_said, DateFormat.getTimeFormat(context).format(now))
            announcer.say(said)
            return said
        }
    }

/** Shows the rider what a handlebar press did (the on-screen banner, ui/overlay). */
interface PressFeedback {
    /** [action] was done; [note] adds its result ("70%", what was said). */
    fun done(
        action: RiderAction,
        note: String?,
    )

    /**
     * [action] was not done because of [reason] (e.g. our CarPlay is not connected, or the call is over).
     */
    fun unavailable(
        action: RiderAction,
        reason: Unavailable,
    )
}

/**
 * [ButtonPresses] through the rider's [HandlebarMapping]: each press does the action of its switch in the
 * current [CallSituation]'s layer, off the link's thread, and the banner says what it did or why it could not.
 */
class MappedButtonPresses
    @Inject
    constructor(
        private val mapping: HandlebarMapping,
        private val actions: ActionPerformer,
        private val situations: CallSituations,
        private val banner: PressFeedback,
        @ApplicationScope private val scope: CoroutineScope,
        logger: Logger,
    ) : ButtonPresses {
        private val log = logger.child("control")

        override fun press(switch: HandlebarSwitch) {
            val situation = situations.current.value
            val action = mapping.current.value[situation]?.get(switch) ?: RiderAction.NOTHING
            log.info("control.press", "switch" to switch, "situation" to situation, "action" to action)
            if (action == RiderAction.NOTHING) return
            scope.launch {
                when (val outcome = actions.perform(action)) {
                    is ActionOutcome.Done -> {
                        banner.done(action, outcome.note)
                    }

                    is ActionOutcome.NotDone -> {
                        log.info("control.unavailable", "action" to action, "reason" to outcome.reason)
                        banner.unavailable(action, outcome.reason)
                    }
                }
            }
        }
    }
