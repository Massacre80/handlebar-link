package com.abid.handlebarlink.carplay.engine.session

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.spi.LocationPort
import com.abid.handlebarlink.carplay.protocol.iap2.LocationMessages
import com.abid.handlebarlink.carplay.protocol.iap2.iap2Message
import com.abid.handlebarlink.carplay.protocol.location.CarPlayLocationFix
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Test

/** The tunnel's location reporter: on/off with the iPhone's 0xFFFA/0xFFFC, the latest fix as a 0xFFFB while on. */
class LocationReporterTest {
    private class FakePort(
        private val fix: CarPlayLocationFix?,
    ) : LocationPort {
        var starts = 0
        var stops = 0

        override fun start(): Boolean {
            starts++
            return true
        }

        override fun stop() {
            stops++
        }

        override fun latestFix(): CarPlayLocationFix? = fix
    }

    private val fix = CarPlayLocationFix(48.1173, 11.516666666666667, timestampMillis = 0L)
    private val logger = testLogger(RecordingLogSink(), TestTimeProvider(TestCoroutineScheduler()))

    private fun start() = iap2Message(LocationMessages.START_LOCATION_INFORMATION) {}

    private fun stop() = iap2Message(LocationMessages.STOP_LOCATION_INFORMATION) {}

    @Test
    fun `nothing is sent before the iPhone subscribes`() {
        assertThat(LocationReporter(FakePort(fix), logger).latest()).isNull()
    }

    @Test
    fun `0xFFFA starts the source and the latest fix goes out as a 0xFFFB`() {
        val port = FakePort(fix)
        val reporter = LocationReporter(port, logger)

        assertThat(reporter.handle(start())).isTrue()
        assertThat(port.starts).isEqualTo(1)

        val sent = reporter.latest()
        assertThat(sent).isNotNull()
        assertThat(sent!!.id).isEqualTo(LocationMessages.LOCATION_INFORMATION)
        assertThat(sent.parameter(0)!!.string()).contains("GPGGA")
    }

    @Test
    fun `0xFFFC stops the source and nothing more goes out`() {
        val port = FakePort(fix)
        val reporter = LocationReporter(port, logger)
        reporter.handle(start())

        assertThat(reporter.handle(stop())).isTrue()
        assertThat(port.stops).isEqualTo(1)
        assertThat(reporter.latest()).isNull()
    }

    @Test
    fun `another message is not the reporter's`() {
        assertThat(LocationReporter(FakePort(fix), logger).handle(iap2Message(0x4300) {})).isFalse()
    }

    @Test
    fun `subscribed but no fix yet sends nothing`() {
        val reporter = LocationReporter(FakePort(null), logger)
        reporter.handle(start())
        assertThat(reporter.latest()).isNull()
    }
}
