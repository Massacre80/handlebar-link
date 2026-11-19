package com.abid.handlebarlink.carplay.protocol.iap2

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import com.abid.handlebarlink.carplay.protocol.hex
import com.abid.handlebarlink.carplay.protocol.toHex
import org.junit.Test

/**
 * Frames are from docs/research/carplay-spec/A-iap2-link.md §7.2, which rebuilds the frames of DiPlay's
 * Iap2LinkEngineSynchronizationTest / Iap2LinkEngineFileTransferTest with DiPlay's own builder
 * (Iap2LinkEngine.kt:533-554, checksum :701; the builder and checksum were re-read against the source).
 */
class Iap2LinkTest {
    @Test
    fun `start writes the iAP2 marker and repeats it every second until the iPhone answers`() {
        val link = Iap2Link()

        link.start(0)

        assertThat(link.drainOutput().toHex()).isEqualTo(MARKER)
        assertThat(link.nextDeadline()).isEqualTo(1_000L)
        link.onTimer(999)
        assertThat(link.drainOutput()).isEmpty()
        link.onTimer(1_000)
        assertThat(link.drainOutput().toHex()).isEqualTo(MARKER)
    }

    @Test
    fun `the tunnel link sends the marker and DiPlay's zero-acknowledgement SYN at once, then repeats only the SYN`() {
        val link = Iap2Link(Iap2LinkParameters.TUNNEL, initiate = true)

        link.start(0)

        // Both in one write, as DiPlay's wired initiator (spec A §2: "Both land in one send()").
        assertThat(link.drainOutput().toHex()).isEqualTo(MARKER + TUNNEL_SYN)
        assertThat(link.state).isEqualTo(Iap2Link.State.NEGOTIATING)
        assertThat(link.nextDeadline()).isEqualTo(500L)
        link.onTimer(499)
        assertThat(link.drainOutput()).isEmpty()
        link.onTimer(500)
        assertThat(link.drainOutput().toHex()).isEqualTo(TUNNEL_SYN)
        link.onTimer(1_000)
        assertThat(link.drainOutput().toHex()).isEqualTo(TUNNEL_SYN)
    }

    @Test
    fun `the tunnel link connects on the iPhone's SYN+ACK and skips a marker the iPhone sends first`() {
        val link = Iap2Link(Iap2LinkParameters.TUNNEL, initiate = true).apply { start(0) }
        link.drainOutput()

        link.feed(MARKER + PEER_SYN_ACK)

        assertThat(link.drainOutput().toHex()).isEqualTo(ACK_OF_PEER_SYN)
        assertThat(link.state).isEqualTo(Iap2Link.State.CONNECTED)
        assertThat(link.drainEvents().single()).isInstanceOf<Iap2LinkEvent.Connected>()
        // Zero acknowledgements both ways: the iPhone's data is delivered and never acknowledged.
        link.feed(PEER_DATA_145)
        assertThat(link.drainOutput()).isEmpty()
        assertThat(link.nextDeadline()).isNull()
    }

    @Test
    fun `the iPhone's marker answer makes the link send DiPlay's wireless SYN`() {
        val link = started()

        link.feed(MARKER)

        assertThat(link.state).isEqualTo(Iap2Link.State.NEGOTIATING)
        assertThat(link.drainOutput().toHex()).isEqualTo(WIRELESS_SYN)
    }

    @Test
    fun `bytes before the marker are skipped`() {
        val link = started()

        link.feed("00 11 ff 55 $MARKER")

        assertThat(link.drainOutput().toHex()).isEqualTo(WIRELESS_SYN)
    }

    @Test
    fun `the SYN repeats every 500 ms until the iPhone answers`() {
        val link = started()
        link.feed(MARKER, now = 100)
        link.drainOutput()

        link.onTimer(600)

        assertThat(link.drainOutput().toHex()).isEqualTo(WIRELESS_SYN)
    }

    @Test
    fun `the iPhone's SYN+ACK is acknowledged and connects the link with its parameters`() {
        val link = negotiating()

        link.feed(PEER_SYN_ACK)

        assertThat(link.drainOutput().toHex()).isEqualTo(ACK_OF_PEER_SYN)
        assertThat(link.state).isEqualTo(Iap2Link.State.CONNECTED)
        val connected = link.drainEvents().single() as Iap2LinkEvent.Connected
        assertThat(connected.peer.maxOutgoing).isEqualTo(4)
        assertThat(connected.peer.maxFrameBytes).isEqualTo(0xFFFF)
        assertThat(connected.peer.sessions).hasSize(3)
        assertThat(link.nextDeadline()).isNull()
    }

    @Test
    fun `control data is delivered and our reply carries the next sequence and the acknowledgement`() {
        val link = connected()

        link.feed(PEER_DATA_145)
        val message = link.drainEvents().single() as Iap2LinkEvent.Message
        link.send(10, hex("40 40 00 06 1D 01"), now = 0)

        assertThat(message.sessionId).isEqualTo(10)
        assertThat(message.payload.toHex()).isEqualTo("404000061d00")
        assertThat(link.drainOutput().toHex()).isEqualTo(REPLY_100_ACK_145)
    }

    @Test
    fun `file transfer session data flows like control data`() {
        val link = negotiating()
        link.feed(FILE_TRANSFER_SYN_ACK)
        assertThat(link.drainOutput().toHex()).isEqualTo(ACK_OF_FILE_TRANSFER_SYN)
        link.drainEvents()

        link.feed(PEER_FILE_TRANSFER_DATA)
        val message = link.drainEvents().single() as Iap2LinkEvent.Message
        link.send(12, hex("81 01"), now = 0)

        assertThat(message.sessionId).isEqualTo(12)
        assertThat(link.drainOutput().toHex()).isEqualTo(FILE_TRANSFER_REPLY)
    }

    @Test
    fun `an ACK before the iPhone's SYN is ignored instead of killing the link`() {
        val link = negotiating()

        link.feedFrame(Iap2Frame(Iap2Frame.ACK, 5, 99, 0, null))

        assertThat(link.state).isEqualTo(Iap2Link.State.NEGOTIATING)
    }

    @Test
    fun `a repeated SYN+ACK before any data is acknowledged again, and ignored after data`() {
        val link = connected()

        link.feed(PEER_SYN_ACK)
        assertThat(link.drainOutput().toHex()).isEqualTo(ACK_OF_PEER_SYN)

        link.feed(PEER_DATA_145)
        link.drainOutput()
        link.feed(PEER_SYN_ACK)
        assertThat(link.drainOutput()).isEmpty()
    }

    @Test
    fun `a reset from the iPhone fails the link`() {
        val link = connected()

        assertFailure { link.feedFrame(Iap2Frame(Iap2Frame.RST, 1, 99, 0, null)) }.isInstanceOf<Iap2LinkException>()
        assertThat(link.state).isEqualTo(Iap2Link.State.FAILED)
    }

    @Test
    fun `an unacknowledged packet is retransmitted at its deadline, then the link fails`() {
        val link = connected(peerParameters(retransmitMillis = 2_000, maxRetransmissions = 2))
        link.send(10, byteArrayOf(1), now = 0)
        val original = link.drainOutput().toHex()

        link.onTimer(2_000)
        assertThat(link.drainOutput().toHex()).isEqualTo(original)
        link.onTimer(4_000)
        assertThat(link.drainOutput().toHex()).isEqualTo(original)

        assertFailure { link.onTimer(6_000) }.isInstanceOf<Iap2LinkException>()
        assertThat(link.state).isEqualTo(Iap2Link.State.FAILED)
    }

    @Test
    fun `an acknowledgement stops retransmission`() {
        val link = connected(peerParameters(retransmitMillis = 2_000, maxRetransmissions = 2))
        link.send(10, byteArrayOf(1), now = 0)
        link.drainOutput()

        link.feedFrame(Iap2Frame(Iap2Frame.ACK, PEER_SEQ, 100, 0, null))
        link.onTimer(10_000)

        assertThat(link.drainOutput()).isEmpty()
        assertThat(link.nextDeadline()).isNull()
    }

    @Test
    fun `an acknowledgement for a packet never sent is ignored`() {
        val link = connected(peerParameters(retransmitMillis = 2_000, maxRetransmissions = 2))
        link.send(10, byteArrayOf(1), now = 0)

        link.feedFrame(Iap2Frame(Iap2Frame.ACK, PEER_SEQ, 120, 0, null))

        assertThat(link.nextDeadline()).isEqualTo(2_000L)
    }

    @Test
    fun `no more than maxOutgoing packets are in flight, and an acknowledgement releases the rest`() {
        val link = connected(peerParameters(maxOutgoing = 2, retransmitMillis = 2_000, maxRetransmissions = 2))

        repeat(3) { link.send(10, byteArrayOf(it.toByte()), now = 0) }
        assertThat(framesIn(link.drainOutput()).map { it.sequence }).containsExactly(100, 101)

        link.feedFrame(Iap2Frame(Iap2Frame.ACK, PEER_SEQ, 100, 0, null))
        assertThat(framesIn(link.drainOutput()).map { it.sequence }).containsExactly(102)
    }

    @Test
    fun `an EAK resends the packets it lists`() {
        val link = connected(peerParameters(retransmitMillis = 2_000, maxRetransmissions = 2))
        link.send(10, byteArrayOf(1), now = 0)
        link.send(10, byteArrayOf(2), now = 0)
        link.drainOutput()

        link.feedFrame(Iap2Frame(Iap2Frame.EAK, PEER_SEQ, 99, 0, byteArrayOf(101)))

        assertThat(framesIn(link.drainOutput()).map { it.sequence }).containsExactly(101)
    }

    @Test
    fun `out-of-order data waits for the gap, then everything is delivered in order`() {
        val link = connected()

        link.feedFrame(data(PEER_SEQ + 2, byteArrayOf(2)))
        assertThat(link.drainEvents()).isEmpty()
        link.feedFrame(data(PEER_SEQ + 1, byteArrayOf(1)))

        val payloads = link.drainEvents().map { (it as Iap2LinkEvent.Message).payload.single().toInt() }
        assertThat(payloads).containsExactly(1, 2)
    }

    @Test
    fun `a gap of a whole window is reported with an EAK listing the missing sequences`() {
        val link = connected(peerParameters(maxOutgoing = 2, retransmitMillis = 2_000, maxRetransmissions = 2))
        link.drainOutput()

        link.feedFrame(data(PEER_SEQ + 3, byteArrayOf(3)))

        val eak = framesIn(link.drainOutput()).single()
        assertThat(eak.control).isEqualTo(Iap2Frame.EAK)
        assertThat(eak.payload!!.toHex()).isEqualTo("9192")
    }

    @Test
    fun `a duplicate packet is acknowledged again and not delivered twice`() {
        val link = connected()
        link.feed(PEER_DATA_145)
        link.drainEvents()
        link.drainOutput()

        link.feed(PEER_DATA_145)

        assertThat(link.drainEvents()).isEmpty()
        assertThat(framesIn(link.drainOutput()).single().control).isEqualTo(Iap2Frame.ACK)
    }

    @Test
    fun `data is acknowledged after the timeout, or at once after the cumulative limit`() {
        val link = connected(peerParameters(acknowledgeMillis = 500, maxCumulativeAcknowledgements = 3))

        link.feedFrame(data(PEER_SEQ + 1, byteArrayOf(1)), now = 0)
        link.feedFrame(data(PEER_SEQ + 2, byteArrayOf(2)), now = 100)
        assertThat(link.drainOutput()).isEmpty()
        assertThat(link.nextDeadline()).isEqualTo(500L)
        link.onTimer(500)
        assertThat(framesIn(link.drainOutput()).single().acknowledgement).isEqualTo(PEER_SEQ + 2)

        repeat(3) { link.feedFrame(data(PEER_SEQ + 3 + it, byteArrayOf(0)), now = 600) }
        assertThat(framesIn(link.drainOutput()).single().acknowledgement).isEqualTo(PEER_SEQ + 5)
    }

    @Test
    fun `frames arriving one byte at a time are reassembled`() {
        val link = negotiating()

        hex(PEER_SYN_ACK).forEach { link.receive(byteArrayOf(it), 0, 1, 0) }

        assertThat(link.state).isEqualTo(Iap2Link.State.CONNECTED)
    }

    @Test
    fun `a frame with a bad payload checksum is dropped and the next one still arrives`() {
        val link = connected()
        val corrupt = hex(PEER_DATA_145).also { it[it.size - 1] = 0 }

        link.receive(corrupt + hex(PEER_DATA_145), 0, corrupt.size * 2, 0)

        assertThat(link.drainEvents()).hasSize(1)
    }

    @Test
    fun `garbage between frames is skipped one byte at a time`() {
        val link = connected()

        link.feed("ff 5a 00 ff 13 $PEER_DATA_145")

        assertThat(link.drainEvents()).hasSize(1)
    }

    @Test
    fun `sending before start or after a failure is refused`() {
        assertFailure { Iap2Link().send(10, byteArrayOf(1), 0) }.isInstanceOf<IllegalStateException>()
        assertFailure { started().start(0) }.isInstanceOf<IllegalStateException>()
    }

    @Test
    fun `a failed link writes nothing more and refuses data with a link error`() {
        val link = connected(peerParameters(acknowledgeMillis = 500, maxCumulativeAcknowledgements = 3))
        link.feedFrame(data(PEER_SEQ + 1, byteArrayOf(1)), now = 0)
        assertFailure { link.feedFrame(Iap2Frame(Iap2Frame.RST, 1, 99, 0, null)) }.isInstanceOf<Iap2LinkException>()

        link.onTimer(1_000)

        // DiPlay's die() clears every timer (Iap2LinkEngine.kt:562-573).
        assertThat(link.drainOutput()).isEmpty()
        assertThat(link.nextDeadline()).isNull()
        assertFailure { link.send(10, byteArrayOf(1), 1_000) }.isInstanceOf<Iap2LinkException>()
    }

    @Test
    fun `an EAK replaces the pending delayed acknowledgement`() {
        val link =
            connected(peerParameters(maxOutgoing = 2, acknowledgeMillis = 500, maxCumulativeAcknowledgements = 3))
        link.feedFrame(data(PEER_SEQ + 1, byteArrayOf(1)), now = 0)

        link.feedFrame(data(PEER_SEQ + 4, byteArrayOf(4)), now = 100)
        val eak = framesIn(link.drainOutput()).single()
        link.onTimer(500)

        // DiPlay Iap2LinkEngine.kt:447-448: the deadline is cleared before the EAK goes out.
        assertThat(eak.control).isEqualTo(Iap2Frame.EAK)
        assertThat(link.drainOutput()).isEmpty()
    }

    @Test
    fun `a peer window of zero still lets one packet out at a time`() {
        val link = connected(peerParameters(maxOutgoing = 0, retransmitMillis = 2_000, maxRetransmissions = 2))

        link.send(10, byteArrayOf(1), now = 0)
        link.send(10, byteArrayOf(2), now = 0)

        // DiPlay Iap2LinkEngine.kt:478 queues only when more than maxOutgoing are outstanding.
        assertThat(framesIn(link.drainOutput()).map { it.sequence }).containsExactly(100)
    }

    private fun started() = Iap2Link().apply { start(0) }.also { it.drainOutput() }

    private fun negotiating() = started().apply { feed(MARKER) }.also { it.drainOutput() }

    private fun connected(peerSynAck: String = PEER_SYN_ACK): Iap2Link =
        negotiating().apply {
            feed(peerSynAck)
            drainOutput()
            drainEvents()
        }

    private fun connected(parameters: Iap2LinkParameters): Iap2Link =
        connected(Iap2Frame(Iap2Frame.SYN or Iap2Frame.ACK, PEER_SEQ, 99, 0, parameters.encode()).encode().toHex())

    private fun peerParameters(
        maxOutgoing: Int = 4,
        retransmitMillis: Int = 0,
        acknowledgeMillis: Int = 0,
        maxRetransmissions: Int = 0,
        maxCumulativeAcknowledgements: Int = 0,
    ) = Iap2LinkParameters.WIRELESS.copy(
        maxOutgoing = maxOutgoing,
        retransmitMillis = retransmitMillis,
        acknowledgeMillis = acknowledgeMillis,
        maxRetransmissions = maxRetransmissions,
        maxCumulativeAcknowledgements = maxCumulativeAcknowledgements,
    )

    private fun data(
        sequence: Int,
        payload: ByteArray,
    ) = Iap2Frame(Iap2Frame.ACK, sequence and 0xFF, 99, 10, payload)

    private fun Iap2Link.feed(
        hexText: String,
        now: Long = 0,
    ) {
        val bytes = hex(hexText)
        receive(bytes, 0, bytes.size, now)
    }

    private fun Iap2Link.feedFrame(
        frame: Iap2Frame,
        now: Long = 0,
    ) {
        val bytes = frame.encode()
        receive(bytes, 0, bytes.size, now)
    }

    private fun framesIn(bytes: ByteArray): List<Iap2Frame> {
        val reader = FrameReader()
        reader.append(bytes, 0, bytes.size)
        return generateSequence { reader.nextFrame() }.toList()
    }

    private companion object {
        const val PEER_SEQ = 0x90
        const val MARKER = "ff550200ee10"

        // Spec A §7.2 A: DiPlay's wireless SYN, seq 99.
        const val WIRELESS_SYN = "ff5a001d80630000a7" + "0104ffff0fa001f404030a00020b02010c0102" + "29"

        // Spec A §7.2 B: DiPlay's wired and tunnel SYN (zero-acknowledgement config), seq 99.
        const val TUNNEL_SYN = "ff5a001d80630000a7" + "0104ffff0000000000000a00020b02010c0102" + "d4"

        // Spec A §7.2 C: peer SYN+ACK, seq 0x90, ack 99, zero-acknowledgement parameters.
        const val PEER_SYN_ACK = "ff5a001dc0906300d7" + "0104ffff0000000000000a00020b02010c0102" + "d4"

        // Spec A §7.2 D: our ACK of it.
        const val ACK_OF_PEER_SYN = "ff5a0009406390006b"

        // Spec A §7.2 E and F: peer data seq 145, our reply seq 100 ack 145.
        const val PEER_DATA_145 = "ff5a00104091630a59" + "404000061d00" + "5d"
        const val REPLY_100_ACK_145 = "ff5a00104064910a58" + "404000061d01" + "5c"

        // Spec A §7.2 J, K, L, M: file-transfer test.
        const val FILE_TRANSFER_SYN_ACK = "ff5a001ac001630069" + "0108100007d001f404030a00020c0102" + "f9"
        const val ACK_OF_FILE_TRANSFER_SYN = "ff5a000940630100fa"
        const val PEER_FILE_TRANSFER_DATA = "ff5a00164002630ce0" + "810400000000000000050002" + "74"
        const val FILE_TRANSFER_REPLY = "ff5a000c4064020ce9" + "8101" + "7e"
    }
}
