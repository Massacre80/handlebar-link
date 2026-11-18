package com.abid.handlebarlink.controls

import android.content.Context
import android.provider.Settings
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The screen's brightness, a step at a time (bright sun, night riding), through Android's system setting
 * (Settings.System, allowed by "Modify system settings", Permissions page). A step turns automatic
 * brightness off, as moving the system's own slider does, so the level stays where the rider put it.
 */
class ScreenBrightness
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) {
        private val log = logger.child("control")

        /** Whether the rider allowed the app to change it. */
        fun allowed(): Boolean = Settings.System.canWrite(context)

        /** One step brighter ([up]) or dimmer; returns the new level in percent, null when not allowed. */
        fun step(up: Boolean): Int? {
            if (!allowed()) {
                log.warn("brightness.not_allowed")
                return null
            }
            val resolver = context.contentResolver
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            val now = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, MAX_LEVEL / 2)
            val next = brightnessStep(now, up)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, next)
            log.info("brightness.step", "from" to now, "to" to next)
            return next * PERCENT / MAX_LEVEL
        }
    }

/**
 * The next level (1..255) a tenth of the way along a perceptual curve (gamma 2.2, as eyes see brightness), so
 * each step looks the same in the dark and in the sun; always moves by at least one level until the end.
 */
internal fun brightnessStep(
    current: Int,
    up: Boolean,
): Int {
    val seen = (current.coerceIn(MIN_LEVEL, MAX_LEVEL).toDouble() / MAX_LEVEL).pow(1 / GAMMA)
    val target = (seen + if (up) STEP else -STEP).coerceIn(0.0, 1.0)
    val level = (target.pow(GAMMA) * MAX_LEVEL).roundToInt().coerceIn(MIN_LEVEL, MAX_LEVEL)
    return when {
        up && level <= current -> (current + 1).coerceAtMost(MAX_LEVEL)
        !up && level >= current -> (current - 1).coerceAtLeast(MIN_LEVEL)
        else -> level
    }
}

private const val MIN_LEVEL = 1
private const val MAX_LEVEL = 255
private const val GAMMA = 2.2
private const val STEP = 0.1
private const val PERCENT = 100
