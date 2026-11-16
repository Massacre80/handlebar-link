package com.abid.handlebarlink.bike.protocol

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import org.junit.Test

class DataCommandsTest {
    @Test
    fun `the first command of a session is REQUEST_SAB_MODE with id 0`() {
        val command = DataCommands.requestSabMode(CommandRequestIds())

        assertThat(command.frame.toString()).isEqualTo("0A 00")
        assertThat(command.id).isEqualTo(DataCommandId.REQUEST_SAB_MODE)
        assertThat(command.requestId).isEqualTo(0)
    }

    @Test
    fun `music and navigation icons, as in the spec's example`() {
        // SAB_PROTOCOL.md §6.3: "music + navigation active: 03 05 00 06".
        val command =
            DataCommands.activeIndicators(
                CommandRequestIds(start = 5),
                setOf(Indicator.MUSIC, Indicator.NAVIGATION),
            )

        assertThat(command.frame.toString()).isEqualTo("03 05 00 06")
    }

    @Test
    fun `an empty set clears every icon`() {
        assertThat(
            DataCommands.activeIndicators(CommandRequestIds(), emptySet()).frame.toString(),
        ).isEqualTo("03 00 00 00")
    }

    @Test
    fun `all icons together`() {
        val command = DataCommands.activeIndicators(CommandRequestIds(), Indicator.entries.toSet())

        assertThat(command.frame.toString()).isEqualTo("03 00 00 3F")
    }

    @Test
    fun `commands share the session's id counter`() {
        val ids = CommandRequestIds()

        val first = DataCommands.requestSabMode(ids)
        val second = DataCommands.activeIndicators(ids, setOf(Indicator.PHONE))

        assertThat(first.frame.toString()).isEqualTo("0A 00")
        assertThat(second.frame.toString()).isEqualTo("03 01 00 01")
    }

    @Test
    fun `an OK receipt matches`() {
        val command = DataCommands.requestSabMode(CommandRequestIds())

        val match = DataCommands.match(command, hex("0A 00 01"))

        assertThat(match).isEqualTo(
            ReplyMatch.Matched(
                Receipt(DataCommandId.REQUEST_SAB_MODE, requestId = 0, status = 1, errorPosition = null),
            ),
        )
        assertThat((match as ReplyMatch.Matched).reply.isOk).isTrue()
    }

    @Test
    fun `a receipt with another status is delivered but not OK`() {
        val command = DataCommands.requestSabMode(CommandRequestIds())

        val match = DataCommands.match(command, hex("0A 00 0C 02"))

        val receipt = (match as ReplyMatch.Matched).reply
        assertThat(receipt.isOk).isFalse()
        assertThat(receipt.status).isEqualTo(Receipt.STATUS_INVALID_PARAMETERS)
        assertThat(receipt.errorPosition).isEqualTo(2)
    }

    @Test
    fun `receipts for other ids and echoes are ignored`() {
        val command = DataCommands.requestSabMode(CommandRequestIds())

        assertThat(DataCommands.match(command, hex("0A 01 01"))).isEqualTo(ReplyMatch.OtherRequest)
        assertThat(DataCommands.match(command, hex("0A 00"))).isEqualTo(ReplyMatch.Echo)
    }

    @Test
    fun `receipts with the right id but a bad shape are malformed`() {
        val command = DataCommands.requestSabMode(CommandRequestIds())

        listOf("0A", "09 00 01", "0A 00 01 02 03").forEach { notification ->
            assertThat(DataCommands.match(command, hex(notification))).isInstanceOf<ReplyMatch.Malformed>()
        }
    }

    @Test
    fun `a receipt that mentions firmware update is parsed, never sent`() {
        val command = DataCommands.requestSabMode(CommandRequestIds())

        val match = DataCommands.match(command, hex("0B 00 01"))

        assertThat((match as ReplyMatch.Matched).reply.command).isEqualTo(DataCommandId.FIRMWARE_UPDATE)
    }
}
