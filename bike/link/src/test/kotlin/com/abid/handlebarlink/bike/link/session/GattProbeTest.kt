// Virtual-time controls of kotlinx-coroutines-test (advanceTimeBy, runCurrent), as in MeterSessionTest.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.bike.link.session

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.abid.handlebarlink.bike.link.spi.FakeGattLink
import com.abid.handlebarlink.bike.protocol.SabUuids
import com.abid.handlebarlink.core.common.log.LogEntry
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.testing.RecordingLogSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class GattProbeTest {
    private val meter = FakeGattLink()
    private val sink = RecordingLogSink()
    private val probe = GattProbe(meter, Logger("test", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), listOf(sink)))

    private fun logged(event: String): List<LogEntry> = sink.entries.filter { it.event == event }

    private fun LogEntry.field(name: String): Any? = fields.first { it.first == name }.second

    @Test
    fun `reads every readable characteristic and descriptor, logs them, and never writes or subscribes`() =
        runTest {
            val battery = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
            val level = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
            val cccd = SabUuids.CLIENT_CHARACTERISTIC_CONFIG
            meter.services = FakeGattLink.CB350_TABLE + (battery to setOf(level))
            meter.values[SabUuids.VEHICLE_TYPE] = byteArrayOf(1)
            meter.values[SabUuids.DFU_FIRMWARE_VERSION] = byteArrayOf(1, 2)
            meter.values[level] = byteArrayOf(87)
            meter.properties =
                mapOf(
                    SabUuids.ASSIGNMENT_CONTROL to WRITE_NOTIFY,
                    SabUuids.DATA_CONTROL_POINT to WRITE_NOTIFY,
                    SabUuids.FUNCTION_SOURCE to NOTIFY,
                )
            meter.descriptors = mapOf(SabUuids.FUNCTION_SOURCE to mapOf(cccd to byteArrayOf(0, 0)))

            probe.run()

            assertThat(meter.reads).containsExactlyInAnyOrder(
                SabUuids.SERIES_CODE,
                SabUuids.DISPLAY_CAPABILITY,
                SabUuids.VEHICLE_TYPE,
                SabUuids.DFU_FIRMWARE_VERSION,
                level,
                cccd,
            )
            assertThat(meter.writes).isEmpty()
            assertThat(meter.subscribed).isEmpty()
            assertThat(logged("probe.characteristic").size).isEqualTo(8)
            val battery87 = logged("probe.value").first { it.field("characteristic") == level }
            assertThat(battery87.field("hex")).isEqualTo("57")
            assertThat(logged("probe.done").size).isEqualTo(1)
        }

    @Test
    fun `the DFU service is listed but never read, except its firmware version`() =
        runTest {
            val dfuControl = UUID.fromString("13561ecc-f5ed-4109-95c9-2040122b5810")
            meter.services = mapOf(SabUuids.DFU_SERVICE to setOf(SabUuids.DFU_FIRMWARE_VERSION, dfuControl))
            meter.values[SabUuids.DFU_FIRMWARE_VERSION] = byteArrayOf(1, 2)
            meter.descriptors = mapOf(dfuControl to mapOf(SabUuids.CLIENT_CHARACTERISTIC_CONFIG to byteArrayOf(0, 0)))

            probe.run()

            assertThat(meter.reads).containsExactlyInAnyOrder(SabUuids.DFU_FIRMWARE_VERSION)
            assertThat(meter.reads).doesNotContain(SabUuids.CLIENT_CHARACTERISTIC_CONFIG)
            assertThat(logged("probe.skipped").map { it.field("characteristic") }).contains(dfuControl)
        }

    @Test
    fun `the HID service is listed but never read (a refused read wedged the link on the bike)`() =
        runTest {
            val reportMap = UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb")
            val protocolMode = UUID.fromString("00002a4e-0000-1000-8000-00805f9b34fb")
            meter.services = mapOf(GattProbe.HID_SERVICE to setOf(protocolMode, reportMap))
            meter.descriptors = mapOf(reportMap to mapOf(SabUuids.CLIENT_CHARACTERISTIC_CONFIG to byteArrayOf(0, 0)))

            probe.run()

            assertThat(meter.reads).isEmpty()
            assertThat(logged("probe.characteristic").size).isEqualTo(2)
            assertThat(
                logged("probe.skipped").map { it.field("reason") },
            ).containsExactlyInAnyOrder("android-hid", "android-hid")
        }

    @Test
    fun `a failed read is logged and the probe goes on`() =
        runTest {
            meter.values[SabUuids.VEHICLE_TYPE] = byteArrayOf(1)
            meter.values[SabUuids.DFU_FIRMWARE_VERSION] = byteArrayOf(1, 2)
            meter.properties =
                mapOf(
                    SabUuids.ASSIGNMENT_CONTROL to WRITE_NOTIFY,
                    SabUuids.DATA_CONTROL_POINT to WRITE_NOTIFY,
                    SabUuids.FUNCTION_SOURCE to NOTIFY,
                )
            meter.failingReads = setOf(SabUuids.SERIES_CODE)

            probe.run()

            assertThat(logged("probe.value.failed").map { it.field("characteristic") }).contains(SabUuids.SERIES_CODE)
            assertThat(meter.reads).contains(SabUuids.DISPLAY_CAPABILITY)
            assertThat(logged("probe.done").size).isEqualTo(1)
        }

    @Test
    fun `listening turns on exactly the vendor notifications, writes nothing else, and logs what they send`() =
        runTest {
            val vendorA = UUID.fromString("cc036c66-e0c9-42ce-b11f-257c546c5ac2")
            val vendorB = UUID.fromString("146a0176-b46f-48e7-ab74-70a7ce1a5341")
            val vendorC = UUID.fromString("e95a363a-5a67-437a-88a4-153dbe498bf1")
            val notifyA1 = UUID.fromString("85569fa7-6645-415d-8439-fb32f08e2133")
            val commandA = UUID.fromString("3bda2a5b-8267-4db0-8064-d0242117d473")
            val notifyA2 = UUID.fromString("3030ac98-582b-4c15-8ef2-e80ef8d303c8")
            val bothB1 = UUID.fromString("2573cb1a-7a02-45cd-957e-ebe6ab066539")
            val bothB2 = UUID.fromString("f7b4d188-ad35-424f-a473-000dcdcfd8fb")
            val readB = UUID.fromString("fe7964e8-a8cc-4fbf-bd1d-14f6e9262bc5")
            val commandC = UUID.fromString("96f6a339-60dc-4983-9789-8da520b7b5f7")
            meter.services =
                FakeGattLink.CB350_TABLE +
                mapOf(
                    vendorA to setOf(notifyA1, commandA, notifyA2),
                    vendorB to setOf(bothB1, bothB2, readB),
                    vendorC to setOf(commandC),
                )
            meter.properties =
                mapOf(
                    notifyA1 to NOTIFY,
                    commandA to WRITE,
                    notifyA2 to NOTIFY,
                    bothB1 to WRITE_NOTIFY,
                    bothB2 to WRITE_NOTIFY,
                    readB to READ_WRITE,
                    commandC to WRITE,
                    SabUuids.FUNCTION_SOURCE to NOTIFY,
                    SabUuids.ASSIGNMENT_CONTROL to WRITE_NOTIFY,
                    SabUuids.DATA_CONTROL_POINT to WRITE_NOTIFY,
                )

            // backgroundScope work runs on time advances (advanceUntilIdle skips it).
            val listening = backgroundScope.launch { probe.listen() }
            testScheduler.advanceTimeBy(SETTLE_MS)
            testScheduler.runCurrent()
            meter.send(notifyA1, byteArrayOf(0x12, 0x34))
            meter.send(SabUuids.FUNCTION_SOURCE, byteArrayOf(3))
            testScheduler.runCurrent()

            assertThat(meter.subscribed).containsExactlyInAnyOrder(notifyA1, notifyA2, bothB1, bothB2)
            assertThat(meter.writes).isEmpty()
            assertThat(meter.reads).isEmpty()
            assertThat(logged("probe.notify").map { it.field("hex") }).containsExactlyInAnyOrder("12 34")
            listening.cancel()
        }

    @Test
    fun `values print as text when they are text`() {
        assertThat(GattProbe.printable("HONDA BTU 2040".toByteArray() + byteArrayOf(0, 0))).isEqualTo("HONDA BTU 2040")
        assertThat(GattProbe.printable(byteArrayOf(0xF5.toByte(), 0x03))).isNull()
        assertThat(GattProbe.propertyNames(0x12)).isEqualTo("read,notify")
    }

    private companion object {
        const val WRITE_NOTIFY = 0x04 or 0x08 or 0x10
        const val NOTIFY = 0x10
        const val WRITE = 0x08
        const val READ_WRITE = 0x02 or 0x04
        const val SETTLE_MS = 1_000L
    }
}
