package com.abid.handlebarlink.steady

import com.abid.handlebarlink.core.common.time.TimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * How long a new state must last before it is shown: shorter ones never reach the screen. AndroidX's
 * `ContentLoadingProgressBar` waits 500 ms (`MIN_DELAY_MS`) before showing progress for the same reason; a little
 * less here, because these are words that replace other words, not a spinner over nothing (docs/PLAN.md "Stable live
 * indicators").
 */
const val STATE_SHOW_DELAY_MS: Long = 400L

/**
 * How long a shown state stays before an ordinary change replaces it: long enough to read two or three words.
 * `ContentLoadingProgressBar` keeps progress up for at least 500 ms (`MIN_SHOW_TIME_MS`); a status line is read, not
 * glanced at, so a second.
 */
const val STATE_MIN_SHOW_MS: Long = 1_000L

/**
 * The values of this flow as a screen should show them: a state that lasts less than [showDelayMillis] is never shown,
 * and a shown state stays for at least [minShowMillis] before an ordinary change replaces it (AndroidX's
 * `ContentLoadingProgressBar` rule, applied to any state; docs/PLAN.md "Stable live indicators").
 *
 * - The first value shows at once (a screen opening shows where things stand).
 * - [stateOf] says which values are the same state: a new value of the shown state replaces it at once and keeps its
 *   time on screen (a countdown's seconds, a battery, a channel learnt later). Values are compared with `==`.
 * - [immediate] picks the changes that never wait, from the shown value to the next: errors, things only the rider can
 *   fix, connected, the answer to a tap. Everything else waits.
 * - A change that waits shows once the first of the waiting values is [showDelayMillis] old and the shown state has
 *   been up for [minShowMillis]; whatever is current then is shown (a burst of short states shows its latest, never
 *   the stale one forever). Going back to the shown state before then shows nothing new.
 *
 * Time comes from [time] and the collector's dispatcher, so tests run on one virtual clock (AGENTS.md).
 */
@OptIn(ExperimentalCoroutinesApi::class) // select's onTimeout
fun <T> Flow<T>.stableState(
    time: TimeProvider,
    showDelayMillis: Long = STATE_SHOW_DELAY_MS,
    minShowMillis: Long = STATE_MIN_SHOW_MS,
    stateOf: (T) -> Any? = { it },
    immediate: (shown: T, next: T) -> Boolean = { _, _ -> false },
): Flow<T> {
    val upstream = this
    return channelFlow {
        // Every value, in order, so an error that lasts a moment is still seen (it shows at once).
        val input = Channel<T>(Channel.UNLIMITED)
        launch {
            upstream.collect { input.send(it) }
            input.close()
        }
        val first = input.receiveCatching()
        if (first.isClosed) return@channelFlow
        val screen =
            Screen(first.getOrThrow(), time.elapsedMillis(), showDelayMillis, minShowMillis, stateOf, immediate)
        send(screen.shown)
        while (true) {
            val due = screen.waiting
            val next: ChannelResult<T>? =
                if (due == null) {
                    input.receiveCatching()
                } else {
                    // One select: a value that arrives just as the wait ends is never lost (prompt cancellation).
                    select {
                        input.onReceiveCatching { it }
                        onTimeout((due.at - time.elapsedMillis()).coerceAtLeast(0)) { null }
                    }
                }
            when {
                // The wait is over: the latest waiting value shows.
                next == null -> {
                    send(screen.showWaiting(time.elapsedMillis()))
                }

                next.isClosed -> {
                    // The source ended: a waiting value still keeps its time, then shows.
                    if (due != null) {
                        delay((due.at - time.elapsedMillis()).coerceAtLeast(0))
                        send(due.value)
                    }
                    return@channelFlow
                }

                else -> {
                    screen.take(next.getOrThrow(), time.elapsedMillis())?.let { send(it) }
                }
            }
        }
    }
}

/** What is on screen ([shown], since [shownAt]) and the change [waiting] to replace it; [stableState]'s rules. */
private class Screen<T>(
    var shown: T,
    private var shownAt: Long,
    private val showDelayMillis: Long,
    private val minShowMillis: Long,
    private val stateOf: (T) -> Any?,
    private val immediate: (shown: T, next: T) -> Boolean,
) {
    var waiting: Waiting<T>? = null
        private set

    /** Takes [value] arriving at [now]; returns it when it shows at once, null when it waits or changes nothing. */
    fun take(
        value: T,
        now: Long,
    ): T? =
        when {
            stateOf(value) == stateOf(shown) -> {
                waiting = null
                if (value != shown) value.also { shown = it } else null
            }

            immediate(shown, value) -> {
                show(value, now)
            }

            else -> {
                val at = waiting?.at ?: maxOf(now + showDelayMillis, shownAt + minShowMillis)
                waiting = Waiting(value, at)
                null
            }
        }

    /** The wait is over at [now]: the waiting value shows. */
    fun showWaiting(now: Long): T = show(checkNotNull(waiting).value, now)

    private fun show(
        value: T,
        now: Long,
    ): T {
        waiting = null
        shown = value
        shownAt = now
        return value
    }
}

/** A change waiting to be shown: the latest [value], shown at [at] (elapsed millis) if still current. */
private class Waiting<T>(
    val value: T,
    val at: Long,
)
