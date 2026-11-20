// UnconfinedTestDispatcher and advanceTimeBy are kotlinx-coroutines-test API.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.carplay

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.core.testing.TestTimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/** The answer to Try now shows at once; the same change without a tap waits as every ordinary change does. */
class TryNowSteadyTest {
    private val retrying =
        CarPlayStatus.Engine(
            CarPlayState.Recovering(CarPlayError.TimedOut(ConnectStep.PAIRING), 1, Instant.EPOCH.plusSeconds(8)),
        )
    private val starting = CarPlayStatus.Engine(CarPlayState.Connecting(ConnectStep.LOADING_IDENTITY))

    private fun timeline(vararg values: Pair<Long, CarPlayStatus>): Flow<CarPlayStatus> =
        flow {
            var now = 0L
            for ((at, value) in values) {
                delay(at - now)
                now = at
                emit(value)
            }
        }

    private fun TestScope.shown(answering: Boolean): List<Pair<Long, CarPlayStatus>> {
        val time = TestTimeProvider(testScheduler)
        val seen = mutableListOf<Pair<Long, CarPlayStatus>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            timeline(0L to retrying, 100L to starting, 5_000L to starting)
                .steady(time) { answering }
                .collect { seen += time.elapsedMillis() to it }
        }
        advanceTimeBy(60_000)
        return seen
    }

    @Test
    fun `after Try now the next attempt replaces Couldn't connect at once`() =
        runTest {
            assertThat(shown(answering = true)).containsExactly(0L to retrying, 100L to starting)
        }

    @Test
    fun `without a tap the same change waits until Couldn't connect has been up its minimum time`() =
        runTest {
            assertThat(shown(answering = false)).containsExactly(0L to retrying, 1_000L to starting)
        }

    @Test
    fun `only a retry's start answers Try now`() {
        assertThat(answersTryNow(retrying, starting)).isTrue()
        assertThat(answersTryNow(retrying, CarPlayStatus.Engine(CarPlayState.Idle))).isTrue()
        assertThat(answersTryNow(starting, retrying)).isFalse()
        assertThat(answersTryNow(retrying, CarPlayStatus.Held)).isFalse()
    }
}
