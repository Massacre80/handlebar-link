package com.abid.handlebarlink.ui.handlebar

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.abid.handlebarlink.R
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.core.designsystem.component.HlIcons

/**
 * Where a key sits on handlebar_switch.webp ([x], [y]: fractions of the render, measured on the image) and
 * the corner of the diagram its callout goes to ([left], [top]).
 */
internal data class KeySpot(
    val x: Float,
    val y: Float,
    val left: Boolean,
    val top: Boolean,
)

/**
 * A key of the CB350's left switch as the rider sees it: its icon (as printed on the key), its direction,
 * the press and the hold the phone receives ([hold] null: the meter keeps it, SAB_PROTOCOL.md "Verified")
 * and its [spot] on the render. The diamond reads clockwise from the top: speaker up, plus right, check
 * down, handset left.
 */
internal enum class SwitchKey(
    @param:DrawableRes val icon: Int,
    @param:StringRes val label: Int,
    val press: HandlebarSwitch,
    val hold: HandlebarSwitch?,
    val spot: KeySpot,
) {
    UP(HlIcons.Speaker, R.string.handlebar_up, HandlebarSwitch.UP, null, KeySpot(UP_X, UP_Y, left = true, top = true)),
    RIGHT(
        HlIcons.Plus,
        R.string.handlebar_right,
        HandlebarSwitch.RIGHT,
        null,
        KeySpot(RIGHT_X, RIGHT_Y, left = false, top = true),
    ),
    DOWN(
        HlIcons.Check,
        R.string.handlebar_down,
        HandlebarSwitch.DOWN,
        HandlebarSwitch.HOLD_DOWN,
        KeySpot(DOWN_X, DOWN_Y, left = false, top = false),
    ),
    LEFT(
        HlIcons.Call,
        R.string.handlebar_left,
        HandlebarSwitch.LEFT,
        HandlebarSwitch.HOLD_LEFT,
        KeySpot(LEFT_X, LEFT_Y, left = true, top = false),
    ),
    ;

    companion object {
        /** The key [switch] is pressed on. */
        fun of(switch: HandlebarSwitch): SwitchKey = entries.first { it.press == switch || it.hold == switch }
    }
}

// Key centres on handlebar_switch.webp (512 px; marked on the image to check).
private const val UP_X = 0.475f
private const val UP_Y = 0.328f
private const val RIGHT_X = 0.666f
private const val RIGHT_Y = 0.371f
private const val DOWN_X = 0.617f
private const val DOWN_Y = 0.541f
private const val LEFT_X = 0.434f
private const val LEFT_Y = 0.508f
