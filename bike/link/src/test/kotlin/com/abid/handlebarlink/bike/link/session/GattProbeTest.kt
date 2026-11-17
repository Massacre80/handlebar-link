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
    fun `values print as text when they are text`() {
        assertThat(GattProbe.printable("HONDA BTU 2040".toByteArray() + byteArrayOf(0, 0))).isEqualTo("HONDA BTU 2040")
        assertThat(GattProbe.printable(byteArrayOf(0xF5.toByte(), 0x03))).isNull()
        assertThat(GattProbe.propertyNames(0x12)).isEqualTo("read,notify")
    }

    private companion object {
        const val WRITE_NOTIFY = 0x04 or 0x08 or 0x10
        const val NOTIFY = 0x10
    }
}
