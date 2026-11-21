package com.abid.handlebarlink.carplay.engine.android.audio

import com.abid.handlebarlink.core.common.time.TimeProvider

/**
 * Remembers when music last stopped so the next song's stream — which the iPhone opens as a fresh stream on a track
 * change — starts on the smaller warm buffer instead of a cold 300 ms. Owner, 2026-11-21: every song's first play
 * buffered; the ride logs showed each song is a new stream (`carplay.audio.ended` then a `playing queuedMs≈300`).
 * Only a stream that opens within [WINDOW_MILLIS] of the last one ending is warm; a fresh listening session after a
 * longer gap stays cold, so the first play of a cold link still gets the full cushion (DiPlay's 300 ms floor).
 */
internal class MediaWarmth(
    private val time: TimeProvider,
) {
    @Volatile private var lastEndedMillis = Long.MIN_VALUE / 2

    /** A media stream just ended (a track finished or was skipped). */
    fun ended() {
        lastEndedMillis = time.elapsedMillis()
    }

    /** Whether music stopped recently enough that a new stream should start warm (a track change, not a cold start). */
    fun recent(): Boolean = time.elapsedMillis() - lastEndedMillis < WINDOW_MILLIS

    private companion object {
        /** A new media stream within this of the last one ending is a track change on a warm link. */
        const val WINDOW_MILLIS = 10_000L
    }
}
