// UnconfinedTestDispatcher and advanceTimeBy are kotlinx-coroutines-test API.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.steady

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.core.testing.TestTimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The steady-state rule (docs/PLAN.md "Stable live indicators") on a virtual clock. */
class StableStateTest {
    /** [values] at their virtual times in milliseconds, then the flow ends. */
    private fun <T> timeline(vararg values: Pair<Long, T>): Flow<T> =
        flow {
            var now = 0L
            for ((at, value) in values) {
                delay(at - now)
                now = at
                emit(value)
            }
        }

    /** What [steady] shows and when, until it ends. */
    private fun <T> TestScope.shown(steady: (TestTimeProvider) -> Flow<T>): List<Pair<Long, T>> {
        val time = TestTimeProvider(testScheduler)
        val seen = mutableListOf<Pair<Long, T>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            steady(time).collect { seen += time.elapsedMillis() to it }
        }
        // Background work never counts as pending for advanceUntilIdle: run the clock past every timeline instead.
        advanceTimeBy(60_000)
        return seen
    }

    @Test
    fun `the first value shows at once`() =
        runTest {
            val seen = shown { timeline(0L to "starting").stableState(it) }
            assertThat(seen).containsExactly(0L to "starting")
        }

    @Test
    fun `a state shorter than the show delay never shows`() =
        runTest {
            val seen =
                shown {
                    timeline(0L to "connected", 5_000L to "reconnecting", 5_399L to "connected").stableState(it)
                }
            assertThat(seen).containsExactly(0L to "connected")
        }

    @Test
    fun `a lasting change shows after the show delay`() =
        runTest {
            val seen = shown { timeline(0L to "idle", 5_000L to "connecting", 9_000L to "connecting").stableState(it) }
            assertThat(seen).containsExactly(0L to "idle", 5_400L to "connecting")
        }

    @Test
    fun `a shown state stays the minimum time before an ordinary change`() =
        runTest {
            val seen =
                shown { timeline(0L to "starting", 100L to "connecting", 3_000L to "connecting").stableState(it) }
            assertThat(seen).containsExactly(0L to "starting", 1_000L to "connecting")
        }

    @Test
    fun `an immediate change shows at once, inside both times`() =
        runTest {
            val seen =
                shown {
                    timeline(0L to "starting", 100L to "error")
                        .stableState(it, immediate = { _, next -> next == "error" })
                }
            assertThat(seen).containsExactly(0L to "starting", 100L to "error")
        }

    @Test
    fun `new values of the shown state replace it at once and keep its time on screen`() =
        runTest {
            val seen =
                shown {
                    timeline(0L to "retry 3", 300L to "retry 2", 600L to "retry 1", 700L to "connecting")
                        .stableState(it, stateOf = { value -> value.substringBefore(' ') })
                }
            // The countdown ticks in place; the next state still waits for the first tick's minimum time.
            assertThat(seen)
                .containsExactly(0L to "retry 3", 300L to "retry 2", 600L to "retry 1", 1_100L to "connecting")
        }

    @Test
    fun `a burst of short states shows its latest in steps, never stuck on the old one`() =
        runTest {
            val steps = (1..20).map { 2_000L + it * 300L to "step $it" }.toTypedArray()
            val seen = shown { timeline(0L to "starting", *steps).stableState(it) }

            // Each shown state stays at least the minimum time, and the last step is shown in the end.
            val times = seen.map { it.first }
            assertThat(times.zipWithNext().all { (a, b) -> b - a >= STATE_MIN_SHOW_MS }).isTrue()
            assertThat(seen.last().second).isEqualTo("step 20")
            assertThat(seen.size >= 5).isTrue()
            // The first step shown is the one current when the first waiting step was 400 ms old.
            assertThat(seen[1]).isEqualTo(2_700L to "step 2")
        }

    @Test
    fun `a waiting value still shows after its time when the source ends`() =
        runTest {
            val seen = shown { timeline(0L to "connected", 100L to "stopped").stableState(it) }
            assertThat(seen).containsExactly(0L to "connected", 1_000L to "stopped")
        }

    @Test
    fun `a value arriving just as a wait ends is not lost`() =
        runTest {
            val seen =
                shown { timeline(0L to "a", 100L to "b", 1_000L to "c", 5_000L to "c").stableState(it) }
            assertThat(seen.last().second).isEqualTo("c")
        }

    @Test
    fun `the source's failure reaches the collector`() =
        runTest {
            var failure: Throwable? = null
            shown {
                flow {
                    emit("a")
                    delay(100)
                    error("broken")
                }.stableState(it).catch { e -> failure = e }
            }
            assertThat(failure).isNotNull().isInstanceOf(IllegalStateException::class)
        }
}
