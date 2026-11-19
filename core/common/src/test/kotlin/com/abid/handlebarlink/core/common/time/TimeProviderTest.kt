package com.abid.handlebarlink.core.common.time

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TimeProviderTest {
    @Test
    fun `nanoseconds default to the monotonic milliseconds scaled`() {
        val time =
            object : TimeProvider {
                override val clock: Clock = Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC)

                override fun elapsedMillis(): Long = 1_234L
            }

        assertThat(time.elapsedNanos()).isEqualTo(1_234_000_000L)
    }
}
