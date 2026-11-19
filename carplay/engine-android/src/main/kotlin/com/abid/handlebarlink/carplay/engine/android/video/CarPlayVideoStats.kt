package com.abid.handlebarlink.carplay.engine.android.video

/**
 * One window of the picture's numbers (the same window as the `carplay.video.stats` line, 5 s by default), for the
 * app's view of the link. Only windows in which something arrived are reported.
 */
public data class CarPlayVideoStats(
    /** Pictures that arrived from the iPhone. */
    val received: Int,
    /** Pictures released to the screen. */
    val shown: Int,
    /** [shown] per second. */
    val framesPerSecond: Double,
    /**
     * Pictures lost on the way to the decoder: dropped by the queue (full, too old after a burst, or made obsolete
     * by a newer keyframe while a backlog waited) and pictures whose lengths did not add up. Pictures dropped because
     * no screen was shown, or while waiting for a keyframe after such a drop, are not counted.
     */
    val dropped: Int,
    /** 90th percentile of submit-to-output time in milliseconds; null when nothing was decoded. */
    val decodeP90Millis: Double?,
    /** Decoders replaced after an error. */
    val recoveries: Int,
)
