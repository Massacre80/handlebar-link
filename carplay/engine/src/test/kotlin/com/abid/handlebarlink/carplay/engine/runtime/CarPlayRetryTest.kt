@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.carplay.engine.runtime

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.SecureRandom
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * When the runtime retries, waits and lets the ride go: the service is released only after every attempt has closed
 * (also for an attempt cancelled before it ran, docs/LESSONS.md 111), the backoff, and the wait for Wi-Fi.
 */
class CarPlayRetryTest {
    private val random = SecureRandom()
    private val sink = RecordingLogSink()
    private val fake = FakePorts(random)

    private fun TestScope.runtime(): CarPlayRuntime {
        val time = TestTimeProvider(testScheduler)
        fake.now = { testScheduler.currentTime }
        val env = RuntimeEnv(time, Random(SEED), random, testLogger(sink, time))
        return CarPlayRuntime(fake.ports, StandardTestDispatcher(testScheduler), env, { CarPlayConfig() })
    }

    private fun TestScope.started(): Pair<CarPlayRuntime, MutableList<CarPlayState>> {
        val link = runtime()
        val seen = mutableListOf<CarPlayState>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { link.state.collect { seen += it } }
        backgroundScope.launch { link.run() }
        link.start()
        return link to seen
    }

    private fun failure(link: CarPlayRuntime): CarPlayError? = (link.state.value as? CarPlayState.Recovering)?.error

    /** The pause before each retry, in order, from the log. */
    private fun pauses(): List<Long> = sink.events("carplay.recovering").map { it.fields.toMap()["pause_ms"] as Long }

    @Test
    fun `a start and a stop queued together release the service`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (link, _) = started()
            // No runCurrent in between: the attempt is cancelled before it ever ran (docs/LESSONS.md 110).
            link.stop()
            runCurrent()

            assertThat(link.keepAlive.value).isFalse()
            assertThat(link.state.value).isEqualTo(CarPlayState.Idle)
            assertThat(fake.groupsCreated).isEqualTo(0)
        }

    @Test
    fun `a second stop while the first attempt is still closing keeps the service until it has closed`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.removeGroupMillis = 500
            val (link, _) = started()
            fake.dialed.receive()
            link.stop()
            link.start()
            runCurrent() // The second attempt now waits for the first one's teardown.

            link.stop()
            runCurrent()
            val duringTeardown = link.keepAlive.value
            advanceTimeBy(501)

            assertThat(duringTeardown).isTrue()
            assertThat(link.keepAlive.value).isFalse()
            assertThat(fake.groupsCreated).isEqualTo(1)
        }

    @Test
    fun `cancelling run while an older attempt is still closing waits for it`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.removeGroupMillis = 500
            val link = runtime()
            val job = backgroundScope.launch { link.run() }
            link.start()
            fake.dialed.receive()
            link.stop()
            link.start()
            runCurrent()

            job.cancel()
            runCurrent()
            val duringTeardown = link.keepAlive.value
            advanceTimeBy(501)

            assertThat(duringTeardown).isTrue()
            assertThat(link.keepAlive.value).isFalse()
            assertThat(fake.groupsRemoved).isEqualTo(1)
        }

    @Test
    fun `the backoff doubles from 2 s and stops at exactly 30 s`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.createGroupError = CarPlayError.WifiDirectFailed("busy")
            started()

            while (pauses().size < 7) advanceTimeBy(100)

            // DiPlay HOST:6298, 2 s doubling to 30 s, each with the runtime's +-20 % jitter drawn from the same seed.
            val draws = Random(SEED)
            val expected =
                listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L).map {
                    (it * (1 + 0.2 * (2 * draws.nextDouble() - 1))).roundToLong()
                }
            assertThat(pauses().take(7)).isEqualTo(expected)
        }

    @Test
    fun `with Wi-Fi off nothing is retried, no Bluetooth page either, until Wi-Fi turns on, then at once`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.createGroupError = CarPlayError.WifiOff
            val (link, _) = started()
            runCurrent()
            check(failure(link) == CarPlayError.WifiOff)

            advanceTimeBy(600_000)
            val callsWhileOff = fake.createGroupCalls
            val dialsWhileOff = fake.dialStarts.size
            val keptAlive = link.keepAlive.value
            fake.createGroupError = null
            val onAt = currentTime
            fake.wifiOn.emit(Unit)
            runCurrent()

            assertThat(callsWhileOff).isEqualTo(1)
            assertThat(dialsWhileOff).isLessThanOrEqualTo(1)
            assertThat(keptAlive).isTrue()
            assertThat(fake.createGroupCalls).isEqualTo(2)
            assertThat(fake.dialStarts.last()).isEqualTo(onAt)
            assertThat(sink.events("carplay.wifi.on_retry")).hasSize(1)
        }

    @Test
    fun `Wi-Fi turning on while the attempt was failing for Wi-Fi off retries at once instead of waiting`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.createGroupError = CarPlayError.WifiOff
            fake.wifiTurnsOnDuringCreate = true
            started()
            runCurrent()
            advanceTimeBy(600_000)

            // The first attempt saw Wi-Fi turn on while it failed, so the second ran at once; that one waits.
            assertThat(fake.createGroupCalls).isEqualTo(2)
            assertThat(sink.events("carplay.wifi.on_during_attempt")).hasSize(1)
        }

    @Test
    fun `stop while waiting for Wi-Fi releases the service, and Wi-Fi turning on then starts nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.createGroupError = CarPlayError.WifiOff
            val (link, _) = started()
            runCurrent()

            link.stop()
            runCurrent()
            fake.createGroupError = null
            fake.wifiOn.emit(Unit)
            runCurrent()

            assertThat(link.keepAlive.value).isFalse()
            assertThat(link.state.value).isEqualTo(CarPlayState.Idle)
            assertThat(fake.createGroupCalls).isEqualTo(1)
        }

    @Test
    fun `Wi-Fi turning on does not cut short the pause after another failure`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.createGroupError = CarPlayError.WifiDirectFailed("busy")
            started()
            runCurrent()

            fake.wifiOn.emit(Unit)
            runCurrent()

            assertThat(fake.createGroupCalls).isEqualTo(1)
        }

    private companion object {
        const val SEED = 7
        val TEST_TIMEOUT = 20.seconds
    }
}
