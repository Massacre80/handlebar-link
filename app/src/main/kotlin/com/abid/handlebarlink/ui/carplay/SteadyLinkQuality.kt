package com.abid.handlebarlink.ui.carplay

import com.abid.handlebarlink.carplay.CarPlayVideoStatsSource
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.steady.stableState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** The Link row's quality word, steady ([steadyQuality] of each window's [linkQuality]). */
class SteadyLinkQuality
    @Inject
    constructor(
        video: CarPlayVideoStatsSource,
        time: TimeProvider,
    ) {
        /** The quality to show; null when there is nothing to judge. */
        val quality: Flow<LinkQuality?> = video.stats().map(::linkQuality).steadyQuality(time)
    }

/**
 * The link's quality as the Link row shows it (docs/PLAN.md "Stable live indicators"). Each verdict comes from one 5 s
 * window of the picture's numbers, and one lost picture already makes a window Fair, so the word flipped Strong, Fair
 * and Strong from window to window. Worse news shows fast, better news slowly, as connection indicators do:
 * - Weak (the picture visibly struggles) shows at once, and so does the first verdict and "nothing to judge";
 * - any other change shows only once it holds into the next window ([QUALITY_SHOW_DELAY_MS], a little more than one
 *   window), so a single odd window never shows;
 * - a shown word stays two windows at least ([QUALITY_MIN_SHOW_MS]).
 */
fun Flow<LinkQuality?>.steadyQuality(time: TimeProvider): Flow<LinkQuality?> =
    distinctUntilChanged().stableState(
        time,
        showDelayMillis = QUALITY_SHOW_DELAY_MS,
        minShowMillis = QUALITY_MIN_SHOW_MS,
        immediate = { shown, next -> shown == null || next == null || next == LinkQuality.WEAK },
    )

/** A little more than one 5 s window of the picture's numbers (`CarPlayVideoStats`): the next window must agree. */
const val QUALITY_SHOW_DELAY_MS: Long = 6_000L

/** Two windows. */
const val QUALITY_MIN_SHOW_MS: Long = 10_000L

/** [this] tab with the steady [quality] in the Link row while connected (the only time the row shows one). */
internal fun CarPlayUiState.withQuality(quality: LinkQuality?): CarPlayUiState =
    if (link.live) copy(link = link.copy(quality = quality)) else this
