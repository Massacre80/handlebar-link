package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioManager
import com.abid.handlebarlink.core.common.log.Logger

/** One focus request the platform holds for us. */
internal interface FocusHandle {
    /** Whether the platform granted it at once. */
    val granted: Boolean

    /** Gives it up; idempotent. */
    fun abandon()
}

/** Android's audio focus, behind a seam (`AudioManager.requestAudioFocus(AudioFocusRequest)`). */
internal fun interface FocusSystem {
    /** Asks for [gain] with [route]'s attributes; [onChange] gets the platform's changes, on any thread. */
    fun request(
        gain: Int,
        route: AudioRoute,
        onChange: (Int) -> Unit,
    ): FocusHandle
}

/**
 * One focus request for all of CarPlay's playing tracks, and music's volume (DiPlay AudioFocusCoordinator,
 * AndroidMediaSink.kt:41-193, with CarLink's prompt duck #83):
 * - the request follows the highest playing route (music, then a call, then Siri) with its gain; directions take no
 *   focus and play over the rest;
 * - a transient loss that may duck lowers music to [DUCKED_VOLUME], another transient loss mutes it, a gain restores
 *   it; a permanent loss keeps CarPlay playing (some systems never give focus back);
 * - while the iPhone ducks for a prompt (`duckAudio`) music is at most [DUCKED_VOLUME] too, so directions are heard;
 * - only music's own track volume changes, never Android's stream volume; calls and Siri always stay audible.
 */
internal class AudioFocus(
    private val system: FocusSystem,
    private val log: Logger,
) {
    private class Entry(
        val route: AudioRoute,
        val volume: (Float) -> Unit,
        var applied: Float = FULL_VOLUME,
    )

    private val active = LinkedHashMap<Any, Entry>()
    private var request: FocusHandle? = null
    private var requested: AudioChannel? = null
    private var focusVolume = FULL_VOLUME
    private var promptDucked = false
    private var generation = 0L
    private var closed = false

    /** [key]'s track of [route] started playing; [volume] sets its volume (music only). */
    @Synchronized
    fun acquire(
        key: Any,
        route: AudioRoute,
        volume: (Float) -> Unit,
    ) {
        if (closed || route.focusGain == null) return
        val entry = Entry(route, volume)
        active[key] = entry
        refresh()
        apply(entry)
    }

    @Synchronized
    fun release(key: Any) {
        if (active.remove(key) != null) refresh()
    }

    /** `duckAudio` (true) and `unduckAudio`. */
    @Synchronized
    fun setPromptDuck(ducked: Boolean) {
        if (closed || promptDucked == ducked) return
        promptDucked = ducked
        active.values.forEach(::apply)
        log.info("carplay.audio.prompt_duck", "ducked" to ducked, "music" to musicVolume())
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        active.clear()
        refresh()
    }

    private fun refresh() {
        val primary = active.values.maxByOrNull { it.route.focusPriority }
        if (primary == null) {
            generation++
            request?.abandon()
            request = null
            requested = null
            focusVolume = FULL_VOLUME
            return
        }
        if (request != null && requested == primary.route.channel) return
        generation++
        request?.abandon()
        val gain = checkNotNull(primary.route.focusGain)
        val mine = generation
        val next = system.request(gain, primary.route) { change -> onChange(mine, change) }
        request = next
        requested = primary.route.channel
        if (next.granted) focusVolume = FULL_VOLUME
        log.info(
            "carplay.audio.focus",
            "channel" to primary.route.channel,
            "gain" to gain,
            "granted" to next.granted,
            "tracks" to active.size,
        )
    }

    /** A focus change; one queued for a request already replaced or abandoned is only logged. */
    @Synchronized
    private fun onChange(
        from: Long,
        change: Int,
    ) {
        if (from != generation || request == null || active.isEmpty()) {
            log.info("carplay.audio.focus_change", "change" to change, "stale" to true)
            return
        }
        log.info("carplay.audio.focus_change", "change" to change, "tracks" to active.size)
        val volume =
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> DUCKED_VOLUME
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> MUTED
                AudioManager.AUDIOFOCUS_GAIN -> FULL_VOLUME
                else -> return
            }
        focusVolume = volume
        active.values.forEach(::apply)
    }

    private fun musicVolume(): Float = if (promptDucked) minOf(focusVolume, DUCKED_VOLUME) else focusVolume

    private fun apply(entry: Entry) {
        if (!entry.route.isMedia) return
        val volume = musicVolume()
        if (entry.applied == volume) return
        entry.volume(volume)
        entry.applied = volume
    }

    companion object {
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
        const val MUTED = 0f
    }
}
