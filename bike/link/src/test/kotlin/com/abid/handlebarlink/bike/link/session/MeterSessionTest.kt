// Virtual-time controls of kotlinx-coroutines-test (currentTime, advanceTimeBy, runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.bike.link.session

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkException
import com.abid.handlebarlink.bike.link.MeterInfo
import com.abid.handlebarlink.bike.link.SetupStep
import com.abid.handlebarlink.bike.link.gatt.BondState
import com.abid.handlebarlink.bike.link.gatt.FakeGattLink
import com.abid.handlebarlink.bike.link.machine.Timeouts
import com.abid.handlebarlink.bike.protocol.AssignmentStatus
import com.abid.handlebarlink.bike.protocol.DisplayCapability
import com.abid.handlebarlink.bike.protocol.FunctionEvent
import com.abid.handlebarlink.bike.protocol.FunctionId
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.bike.protocol.SabUuids
import com.abid.handlebarlink.bike.protocol.SeriesCode
import com.abid.handlebarlink.core.log.LogEntry
import com.abid.handlebarlink.core.log.LogSink
import com.abid.handlebarlink.core.log.Logger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MeterSessionTest {
    private val meter = FakeGattLink()
    private val logged = mutableListOf<LogEntry>()
    private val session =
        MeterSession(meter, Logger("test", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), listOf(LogSink(logged::add))))

    @Test
    fun `happy path - the exact frames, in RoadSync's order, and what the meter reported`() =
        runTest {
            val steps = mutableListOf<SetupStep>()

            val info = session.setUp(steps::add)

            assertThat(info).isEqualTo(
                MeterInfo(SeriesCode(0x03F5), DisplayCapability(indicator = true, popUp = false, mysteryBox = false)),
            )
            assertThat(steps).containsExactly(*SetupStep.entries.toTypedArray())
            assertThat(meter.subscribed).containsExactly(
                SabUuids.ASSIGNMENT_CONTROL,
                SabUuids.FUNCTION_SOURCE,
                SabUuids.DATA_CONTROL_POINT,
            )
            assertThat(meter.writes.map { it.second }).containsExactly(
                "01 01 00 05 01 07 80 00",
                "01 02 00 06 01 07 81 00",
                "01 03 00 01 02",
                "01 04 00 02 02",
                "01 05 00 03 02",
                "01 06 00 04 02",
                "01 07 00 07 02",
                "01 08 00 08 02",
                "01 09 00 09 02",
                "01 0A 00 0A 02",
                "03",
                "0A 00",
            )
            // The happy path on the bike took about 3.4 s; the scripted radio takes 30 ms per operation.
            assertThat(currentTime).isLessThan(1_000L)
        }

    @Test
    fun `a reply that arrives instantly is not missed`() =
        runTest {
            // No radio time: the meter answers before the write call has even returned.
            meter.opMillis = 0

            session.setUp {}

            assertThat(meter.writes.size).isEqualTo(12)
            assertThat(logged.none { it.event == "link.setup.no_reply" }).isTrue()
        }

    @Test
    fun `after a reconnect, already-registered (13) is accepted`() =
        runTest {
            meter.assignmentStatus = { 0x0D }

            session.setUp {}

            assertThat(meter.writes.count { it.first == SabUuids.ASSIGNMENT_CONTROL }).isEqualTo(11)
        }

    @Test
    fun `a failed or silent MTU exchange is tolerated`() =
        runTest {
            meter.mtu = FakeGattLink.Behaviour.Fail(4)
            session.setUp {}

            val silent = FakeGattLink().apply { mtu = FakeGattLink.Behaviour.Never }
            val start = currentTime
            MeterSession(silent, Logger("t", Clock.systemUTC(), emptyList())).setUp {}

            assertThat(currentTime - start >= Timeouts.MTU_MS).isTrue()
        }

    @Test
    fun `a device without the meter's control points is not a meter`() =
        runTest {
            meter.services = FakeGattLink.CB350_TABLE - SabUuids.ASSIGNMENT_SERVICE

            assertThat(runCatchingLink { session.setUp {} }).isEqualTo(LinkError.ServicesMissing)
            assertThat(meter.writes).containsExactly()
        }

    @Test
    fun `discovery that never answers times out`() =
        runTest {
            meter.discover = FakeGattLink.Behaviour.Never

            assertThat(runCatchingLink { session.setUp {} }).isEqualTo(LinkError.TimedOut(GattOp.DISCOVER))
        }

    @Test
    fun `unreadable vehicle information leaves the fields empty`() =
        runTest {
            meter.failingReads = setOf(SabUuids.SERIES_CODE, SabUuids.DISPLAY_CAPABILITY)

            val info = session.setUp {}

            assertThat(info).isEqualTo(MeterInfo(null, null))
        }

    @Test
    fun `a silent assignment is resent with the same frame after 2 s`() =
        runTest {
            meter.silentAssignments[FunctionId.SELECT.code] = 2
            val start = currentTime

            session.setUp {}

            val selectWrites = meter.writes.filter { it.second == "01 06 00 04 02" }
            assertThat(selectWrites.size).isEqualTo(3)
            assertThat(currentTime - start >= 2 * Timeouts.REPLY_MS).isTrue()
        }

    @Test
    fun `five silent tries fail the setup`() =
        runTest {
            meter.silentAssignments[FunctionId.MENU.code] = Timeouts.ASSIGNMENT_TRIES

            assertThat(runCatchingLink { session.setUp {} }).isEqualTo(LinkError.TimedOut(GattOp.ASSIGNMENT_REPLY))
        }

    @Test
    fun `a refused assignment fails with the function and status`() =
        runTest {
            meter.assignmentStatus = { if (it == FunctionId.NEXT.code) 0x0B else 0x01 }

            assertThat(runCatchingLink { session.setUp {} })
                .isEqualTo(LinkError.MeterRefused(FunctionId.NEXT, AssignmentStatus.INVALID_ID))
        }

    @Test
    fun `echoes and replies to other ids are ignored`() =
        runTest {
            meter.noisyReplies = true

            session.setUp {}

            assertThat(meter.writes.size).isEqualTo(12)
        }

    @Test
    fun `an unconfirmed phone-mode request is logged, not fatal`() =
        runTest {
            meter.phoneModeReceipt = null

            session.setUp {}

            assertThat(meter.writes.count { it.second == "0A 00" }).isEqualTo(Timeouts.COMMAND_TRIES)
            assertThat(logged.any { it.event == "link.setup.phone_mode.unconfirmed" }).isTrue()
        }

    @Test
    fun `a failed write ends the setup with the GATT status`() =
        runTest {
            meter.failingWrites = mapOf(SabUuids.ASSIGNMENT_CONTROL to 133)

            assertThat(runCatchingLink { session.setUp {} }).isEqualTo(LinkError.GattFailed(GattOp.WRITE, 133))
        }

    @Test
    fun `GATT work waits while bonding is in progress`() =
        runTest {
            meter.bondState.value = BondState.BONDING
            val setup = async { session.setUp {} }

            advanceTimeBy(5_000)
            assertThat(meter.operations).containsExactly()
            meter.bondState.value = BondState.BONDED
            setup.await()

            assertThat(meter.operations.first()).isEqualTo(GattOp.MTU)
        }

    @Test
    fun `bonding that never finishes times out`() =
        runTest {
            meter.bondState.value = BondState.BONDING

            assertThat(runCatchingLink { session.setUp {} }).isEqualTo(LinkError.TimedOut(GattOp.SETUP))
        }

    @Test
    fun `presses and meter modes come out of events`() =
        runTest {
            val events = async(start = CoroutineStart.UNDISPATCHED) { session.events.take(4).toList() }

            session.setUp {}
            meter.press(HandlebarSwitch.UP.function.code)
            meter.press(HandlebarSwitch.HOLD_LEFT.function.code)

            assertThat(events.await()).containsExactly(
                FunctionEvent.MeterMode(phoneMode = false),
                FunctionEvent.MeterMode(phoneMode = true),
                FunctionEvent.Press(HandlebarSwitch.UP),
                FunctionEvent.Press(HandlebarSwitch.HOLD_LEFT),
            )
        }

    @Test
    fun `cancelling the setup stops it and is not swallowed`() =
        runTest {
            meter.silentAssignments[FunctionId.PREVIOUS.code] = Timeouts.ASSIGNMENT_TRIES
            val job = launch { session.setUp {} }
            advanceTimeBy(3_000)
            val writesBefore = meter.writes.size

            job.cancel()
            runCurrent()
            advanceTimeBy(30_000)

            assertThat(job.isCancelled).isTrue()
            assertThat(meter.writes.size).isEqualTo(writesBefore)
        }

    @Test
    fun `no failure is left without a log entry`() =
        runTest {
            meter.silentAssignments[FunctionId.BACK.code] = 1

            session.setUp {}

            assertThat(logged.single { it.event == "link.setup.no_reply" }.fields)
                .containsExactly("assignment" to FunctionId.BACK, "try" to 1, "of" to Timeouts.ASSIGNMENT_TRIES)
        }

    private suspend fun TestScope.runCatchingLink(block: suspend () -> Unit): LinkError? {
        val result = runCatching { block() }
        assertThat(result).isFailure().isInstanceOf<LinkException>()
        return (result.exceptionOrNull() as? LinkException)?.error
    }

    @Test
    fun `the session never reads or writes the firmware-update service`() =
        runTest {
            session.setUp {}

            assertThat(meter.writes.none { it.first == SabUuids.DFU_FIRMWARE_VERSION }).isTrue()
            assertThat(meter.subscribed.none { it == SabUuids.DFU_FIRMWARE_VERSION }).isTrue()
            assertThat(meter).prop(FakeGattLink::failingReads).isEqualTo(emptySet())
            assertThat(logged.firstOrNull { it.level.name == "ERROR" }).isNull()
        }
}
