package com.abid.handlebarlink.controls

import android.content.Context
import android.media.AudioManager
import android.text.format.DateFormat
import com.abid.handlebarlink.R
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.callaudio.VoipCallWatcher
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

/** Does a [RiderAction]: CarPlay's media through [ControlTarget], the battery out loud, or nothing. */
class RiderActions
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val target: ControlTarget,
        private val announcements: Announcements,
        private val brightness: ScreenBrightness,
        private val screens: ScreenSwitcher,
        logger: Logger,
    ) {
        private val log = logger.child("control")

        /**
         * Does [action]; never throws. Returns a short note on the result for the on-screen banner: the new
         * brightness, what was said; null when there is nothing to add to the action's name.
         */
        suspend fun perform(action: RiderAction): String? {
            val media = MEDIA[action] ?: return performHere(action)
            when (val result = target.perform(media)) {
                ControlResult.Done -> log.info("control.done", "action" to action)
                is ControlResult.NotDone -> log.warn("control.not_done", "action" to action, "reason" to result.reason)
            }
            return null
        }

        /** An action done on the phone itself (not CarPlay's media); its note, as [perform]. */
        private suspend fun performHere(action: RiderAction): String? {
            var note: String? = null
            when (action) {
                RiderAction.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
                RiderAction.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
                RiderAction.MUTE -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
                RiderAction.BRIGHTNESS_UP -> note = stepBrightness(up = true)
                RiderAction.BRIGHTNESS_DOWN -> note = stepBrightness(up = false)
                RiderAction.SWITCH_SCREEN -> screens.switch()
                RiderAction.OPEN_CARPLAY -> screens.openCarPlay()
                RiderAction.OPEN_APP -> screens.openApp()
                RiderAction.BATTERY -> note = announcements.battery()
                RiderAction.TIME -> note = announcements.time()
                RiderAction.PLAY_PAUSE, RiderAction.NEXT, RiderAction.PREVIOUS, RiderAction.NOTHING -> Unit
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

    /** [action] was held back because a call is on. */
    fun heldForCall(action: RiderAction)
}

/** [ButtonPresses] through the rider's [HandlebarMapping]: each press does its action, off the link's thread. */
class MappedButtonPresses
    @Inject
    constructor(
        private val mapping: HandlebarMapping,
        private val actions: RiderActions,
        private val calls: VoipCallWatcher,
        private val banner: PressFeedback,
        @ApplicationScope private val scope: CoroutineScope,
        logger: Logger,
    ) : ButtonPresses {
        private val log = logger.child("control")

        override fun press(switch: HandlebarSwitch) {
            val action = mapping.current.value[switch] ?: RiderAction.NOTHING
            // During a CarPlay call the music keys are held back (a press must not disturb the call); volume
            // keys keep working and change the call's volume, the active sound.
            if (action in MUSIC && calls.inCallNow()) {
                log.info("control.held_for_call", "switch" to switch, "action" to action)
                banner.heldForCall(action)
                return
            }
            log.info("control.press", "switch" to switch, "action" to action)
            if (action != RiderAction.NOTHING) {
                scope.launch {
                    val note = actions.perform(action)
                    banner.done(action, note)
                }
            }
        }
    }

/** The actions that drive CarPlay's music, and the media key each sends. */
private val MEDIA =
    mapOf(
        RiderAction.PLAY_PAUSE to ControlAction.PLAY_PAUSE,
        RiderAction.NEXT to ControlAction.NEXT,
        RiderAction.PREVIOUS to ControlAction.PREVIOUS,
    )

/** The actions that drive CarPlay's music. */
private val MUSIC = MEDIA.keys
