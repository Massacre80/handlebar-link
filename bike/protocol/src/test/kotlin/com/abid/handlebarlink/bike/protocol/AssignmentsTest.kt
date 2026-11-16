package com.abid.handlebarlink.bike.protocol

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import org.junit.Test

class AssignmentsTest {
    @Test
    fun `a fresh CB350 session sends exactly the spec's example frames`() {
        // SAB_PROTOCOL.md §5.2, "Example for a fresh process on a CB350".
        val frames = Assignments.forCb350(AssignmentRequestIds()).map { it.frame.toString() }

        assertThat(frames).containsExactly(
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
        )
        assertThat(Assignments.END.toString()).isEqualTo("03")
    }

    @Test
    fun `assignments carry their function and request id`() {
        val assignments = Assignments.forCb350(AssignmentRequestIds())

        assertThat(assignments.map { it.function }).containsExactly(
            FunctionId.VOLUME_UP,
            FunctionId.VOLUME_DOWN,
            FunctionId.PREVIOUS,
            FunctionId.NEXT,
            FunctionId.BACK,
            FunctionId.SELECT,
            FunctionId.VOLUME_MUTE,
            FunctionId.MENU,
            FunctionId.MODE_SAB,
            FunctionId.MODE_HU,
        )
        assertThat(assignments.map { it.requestId }).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    }

    @Test
    fun `request ids go on the wire little-endian, also across the wrap`() {
        val frames = Assignments.forCb350(AssignmentRequestIds(start = 0xFFFE)).take(3).map { it.frame.toString() }

        assertThat(frames).containsExactly(
            "01 FF FF 05 01 07 80 00",
            "01 00 00 06 01 07 81 00",
            "01 01 00 01 02",
        )
        val highId = Assignments.forCb350(AssignmentRequestIds(start = 0x0101)).first()
        assertThat(highId.frame.toString()).isEqualTo("01 02 01 05 01 07 80 00")
    }

    @Test
    fun `an OK result for the request matches`() {
        val request = firstRequest()

        val match = Assignments.match(request, hex("01 01 00 01"))

        assertThat(match).isEqualTo(
            ReplyMatch.Matched(
                AssignmentResult(command = 1, requestId = 1, status = AssignmentStatus.OK, errorPosition = null),
            ),
        )
    }

    @Test
    fun `already registered after a reconnect counts as accepted`() {
        val match = Assignments.match(firstRequest(), hex("01 01 00 0D"))

        assertThat(match).isInstanceOf<ReplyMatch.Matched<AssignmentResult>>()
        val result = (match as ReplyMatch.Matched).reply
        assertThat(result.status).isEqualTo(AssignmentStatus.ALREADY_REGISTERED)
        assertThat(result.status.isAccepted).isTrue()
    }

    @Test
    fun `only OK and already-registered are accepted`() {
        val accepted = AssignmentStatus.entries.filter { it.isAccepted }

        assertThat(accepted).containsExactly(AssignmentStatus.OK, AssignmentStatus.ALREADY_REGISTERED)
    }

    @Test
    fun `invalid parameters carries the error position`() {
        val match = Assignments.match(firstRequest(), hex("01 01 00 0C 03"))

        val result = (match as ReplyMatch.Matched).reply
        assertThat(result.status).isEqualTo(AssignmentStatus.INVALID_PARAMETERS)
        assertThat(result.errorPosition).isEqualTo(3)
        assertThat(result.status.isAccepted).isFalse()
    }

    @Test
    fun `a result for another request id is ignored`() {
        assertThat(Assignments.match(firstRequest(), hex("01 02 00 01"))).isEqualTo(ReplyMatch.OtherRequest)
        assertThat(Assignments.match(firstRequest(), hex("01 01 01 01"))).isEqualTo(ReplyMatch.OtherRequest)
    }

    @Test
    fun `an echo of the request is ignored`() {
        val request = firstRequest()

        assertThat(Assignments.match(request, request.frame.toByteArray())).isEqualTo(ReplyMatch.Echo)
    }

    @Test
    fun `results with the right id but a bad shape are malformed`() {
        listOf(
            "01 01", // too short to carry an id
            "01 01 00", // no status
            "01 01 00 01 00 00", // too long
            "05 01 00 01", // unknown command
            "01 01 00 07", // unknown status
        ).forEach { notification ->
            assertThat(Assignments.match(firstRequest(), hex(notification))).isInstanceOf<ReplyMatch.Malformed>()
        }
    }

    @Test
    fun `result request ids are read little-endian`() {
        val request = Assignments.forCb350(AssignmentRequestIds(start = 0x0101)).first()

        val match = Assignments.match(request, hex("01 02 01 01"))

        assertThat((match as ReplyMatch.Matched).reply.requestId).isEqualTo(0x0102)
    }

    private fun firstRequest(): Assignment = Assignments.forCb350(AssignmentRequestIds()).first()
}
