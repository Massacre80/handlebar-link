package com.abid.handlebarlink.core.testing

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class TestTimeProviderTest {
    @Test
    fun `monotonic and wall time follow the test's virtual time`() =
        runTest {
            val time = TestTimeProvider(testScheduler, start = Instant.parse("2026-11-16T15:30:00Z"))

            delay(2_500)

            assertThat(time.elapsedMillis()).isEqualTo(2_500L)
            assertThat(time.elapsedNanos()).isEqualTo(2_500_000_000L)
            assertThat(time.clock.instant()).isEqualTo(Instant.parse("2026-11-16T15:30:02.500Z"))
        }

    @Test
    fun `the recording sink keeps entries in order, timestamped by virtual time`() =
        runTest {
            val sink = RecordingLogSink()
            val log = testLogger(sink, TestTimeProvider(testScheduler))

            log.info("a", "k" to 1)
            delay(1_000)
            log.warn("b")

            assertThat(sink.entries.map { it.event }).containsExactly("a", "b")
            assertThat(sink.events("b").single().at).isEqualTo(Instant.parse("2026-11-16T15:30:01Z"))
        }
}
