package com.abid.handlebarlink.bike.protocol

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import java.util.UUID

/** SAB_PROTOCOL.md §8: the firmware-update service and data command 0x0B are never used. */
class SabSafetyTest {
    private val dfuControlPoint = UUID.fromString("13561ecc-f5ed-4109-95c9-2040122b5810")
    private val dfuPacket = UUID.fromString("ec2f22ba-a682-42af-b439-68fa72177719")

    @Test
    fun `nothing in the DFU service may be written`() {
        listOf(dfuControlPoint, dfuPacket, SabUuids.DFU_FIRMWARE_VERSION).forEach { characteristic ->
            assertThat(SabSafety.isWriteAllowed(SabUuids.DFU_SERVICE, characteristic, hex("01"))).isFalse()
        }
    }

    @Test
    fun `nothing in the DFU service may be subscribed to`() {
        assertThat(SabSafety.isSubscribeAllowed(SabUuids.DFU_SERVICE)).isFalse()
    }

    @Test
    fun `data command 0x0B (firmware update) may not be sent`() {
        assertThat(SabSafety.isWriteAllowed(SabUuids.DATA_SERVICE, SabUuids.DATA_CONTROL_POINT, hex("0B 00 01")))
            .isFalse()
    }

    @Test
    fun `the frames this app sends are allowed`() {
        val requestSabMode = DataCommands.requestSabMode(CommandRequestIds()).frame.toByteArray()
        val assignment =
            Assignments
                .forCb350(AssignmentRequestIds())
                .first()
                .frame
                .toByteArray()

        assertThat(SabSafety.isWriteAllowed(SabUuids.DATA_SERVICE, SabUuids.DATA_CONTROL_POINT, requestSabMode))
            .isTrue()
        assertThat(SabSafety.isWriteAllowed(SabUuids.ASSIGNMENT_SERVICE, SabUuids.ASSIGNMENT_CONTROL, assignment))
            .isTrue()
        assertThat(
            SabSafety.isWriteAllowed(
                SabUuids.ASSIGNMENT_SERVICE,
                SabUuids.ASSIGNMENT_CONTROL,
                Assignments.END.toByteArray(),
            ),
        ).isTrue()
    }

    @Test
    fun `0x0B is only blocked on the data control point`() {
        assertThat(SabSafety.isWriteAllowed(SabUuids.ASSIGNMENT_SERVICE, SabUuids.ASSIGNMENT_CONTROL, hex("0B")))
            .isTrue()
        assertThat(SabSafety.isWriteAllowed(SabUuids.DATA_SERVICE, SabUuids.DATA_CONTROL_POINT, ByteArray(0)))
            .isTrue()
    }

    @Test
    fun `the button and receipt notifications may be subscribed to`() {
        assertThat(SabSafety.isSubscribeAllowed(SabUuids.DATA_SERVICE)).isTrue()
        assertThat(SabSafety.isSubscribeAllowed(SabUuids.ASSIGNMENT_SERVICE)).isTrue()
    }

    @Test
    fun `nothing in the DFU service may be read but its firmware version`() {
        val other = UUID.fromString("13561ecc-f5ed-4109-95c9-2040122b5810")
        assertThat(SabSafety.isReadAllowed(SabUuids.DFU_SERVICE, other)).isFalse()
        assertThat(SabSafety.isReadAllowed(SabUuids.DFU_SERVICE, SabUuids.DFU_FIRMWARE_VERSION)).isTrue()
        assertThat(SabSafety.isReadAllowed(SabUuids.VEHICLE_INFO_SERVICE, SabUuids.SERIES_CODE)).isTrue()
    }
}
