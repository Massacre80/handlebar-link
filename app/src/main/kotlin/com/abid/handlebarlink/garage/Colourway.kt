package com.abid.handlebarlink.garage

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.abid.handlebarlink.R

/**
 * The CB350RS's colourways (docs/PLAN.md "Garage": names from Honda India, sources there): [label] Honda's name
 * (the sheet), [short] the rows' and the hero's. Only a colourway with a render ([render]) can be chosen: the app ships
 * one render today, the matte grey bike with the yellow pinstripe (Honda's 2023 "Matte Massive Grey Metallic", matched
 * by eye: owner to confirm); the others wait for their renders.
 */
enum class Colourway(
    @param:StringRes val label: Int,
    @param:StringRes val short: Int,
    @param:DrawableRes val render: Int?,
) {
    MATTE_GREY(R.string.colourway_matte_grey, R.string.colourway_matte_grey_short, R.drawable.bike_cb350rs),
    ;

    /** The colourways the rider can choose now. */
    companion object {
        /** The default: the render the app ships. */
        val DEFAULT: Colourway = MATTE_GREY

        /** Colourways with a render, in Honda's order. */
        val available: List<Colourway> get() = entries.filter { it.render != null }

        /** The stored [name]'s colourway, or the default for none or one no longer known. */
        fun of(name: String?): Colourway = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
