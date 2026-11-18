package com.abid.handlebarlink.carplay.protocol.timing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.protocol.hex
import com.abid.handlebarlink.carplay.protocol.toHex
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer

/**
 * The timing exchange against DiPlay's NtpClock.kt read by hand, its peer test (NtpClockPeerTest.kt), and
 * clock values from src/test/resources/streams/generate_vectors.py (wall clock 1 700 000 000 250 ms,
 * monotonic clock 5 000 000 000 ns).
 */
class TimingClockTest {
    private var mono = MONO0
    private val clock = TimingClock({ mono }, WALL_MILLIS)

    @Test
    fun `the clock starts on the wall clock as NTP and follows the monotonic clock`() {
        assertThat(clock.now()).isEqualTo(0xE8FE6F8040000000UL.toLong())
        mono += 1_000_000_007

        assertThat(clock.now()).isEqualTo(0xE8FE6F814000001EUL.toLong())
        assertThat(clock.isSynced).isFalse()
    }

    @Test
    fun `a request carries the timing header and our time as its transmit stamp`() {
        // NtpClock.kt:87-97
        assertThat(clock.request().toHex()).isEqualTo("80d20007" + "00".repeat(20) + "e8fe6f8040000000")
    }

    @Test
    fun `a request is answered with its transmit time as our originate and our time twice`() {
        // NtpClock.kt:124-133
        val request = hex("80d20007" + "00".repeat(20) + "0102030405060708")

        val answer = clock.answer(request, fromPeer = false)

        assertThat(answer?.toHex())
            .isEqualTo("80d30007" + "00000000" + "0102030405060708" + "e8fe6f8040000000" + "e8fe6f8040000000")
    }

    @Test
    fun `the response builder takes its two stamps as given`() {
        val request = hex("80d20007" + "00".repeat(20) + "1111111111111111")

        assertThat(TimingClock.response(request, receiveNtp = 2L, transmitNtp = -1L)?.toHex()).isEqualTo(
            "80d30007" + "00000000" + "1111111111111111" + "0000000000000002" + "ffffffffffffffff",
        )
        assertThat(TimingClock.response(request.copyOf(31), 0, 0)).isNull()
        assertThat(TimingClock.response(hex("80d30007") + ByteArray(28), 0, 0)).isNull()
    }

    @Test
    fun `short datagrams, responses and unknown types get no answer`() {
        val request = hex("80d20007") + ByteArray(28)

        assertThat(clock.answer(request, length = 31, fromPeer = true)).isNull()
        assertThat(clock.answer(request + ByteArray(8), length = 32, fromPeer = true)).isNotNull()
        assertThat(clock.answer(hex("80d40007") + ByteArray(28), fromPeer = true)).isNull()
        assertThat(clock.answer(responseTo(clock.request(), SKEW), fromPeer = true)).isNull()
    }

    @Test
    fun `two responses from the iPhone step the clock by their offset`() {
        // NtpClockPeerTest.kt:21-31, 68-72: receive = transmit = our transmit + 3600 s.
        clock.answer(responseTo(clock.request(), SKEW), fromPeer = true)
        assertThat(clock.now()).isEqualTo(0xE8FE6F8040000000UL.toLong())

        clock.answer(responseTo(clock.request(), SKEW), fromPeer = true)

        assertThat(clock.now()).isEqualTo(0xE8FE7D9040000000UL.toLong())
        assertThat(clock.isSynced).isTrue()
    }

    @Test
    fun `responses from a stranger cannot move the clock`() {
        // NtpClockPeerTest.kt:62-66
        repeat(4) { clock.answer(responseTo(clock.request(), SKEW), fromPeer = false) }

        assertThat(clock.now()).isEqualTo(0xE8FE6F8040000000UL.toLong())
    }

    @Test
    fun `a response that does not echo our last request is ignored`() {
        val stale = clock.request()
        mono += 1_000_000_007 // so that the next request carries another stamp
        clock.request()
        clock.answer(responseTo(stale, SKEW), fromPeer = true)
        val unsolicited = responseTo(hex("80d20007") + ByteArray(28), SKEW)
        clock.answer(unsolicited, fromPeer = true)
        // The same response twice counts once: the pending request is used up.
        val request = clock.request()
        clock.answer(responseTo(request, SKEW), fromPeer = true)
        clock.answer(responseTo(request, SKEW), fromPeer = true)

        assertThat(clock.now()).isEqualTo(0xE8FE6F814000001EUL.toLong())
        assertThat(clock.isSynced).isFalse()
    }

    @Test
    fun `a negative round trip is ignored and does not count towards the pick`() {
        // receive = our transmit, transmit = receive + 1 s, arriving at our transmit time: rtt = -1 s.
        val request = clock.request()
        clock.answer(responseTo(request, receive = 0L, transmit = 1L shl 32), fromPeer = true)
        clock.answer(responseTo(clock.request(), SKEW), fromPeer = true)

        assertThat(clock.now()).isEqualTo(0xE8FE6F8040000000UL.toLong())
    }

    @Test
    fun `after the step a small offset is slewed by one eighth`() {
        stepBy3600Seconds()

        // receive = transmit = our transmit + 274877907 / 2^32 s (64 ms): applied 8 ms.
        repeat(2) { clock.answer(responseTo(clock.request(), 274_877_907L), fromPeer = true) }

        assertThat(clock.now()).isEqualTo(0xE8FE7D90420C49BAUL.toLong())
    }

    @Test
    fun `after the step an offset above 128 ms steps again`() {
        stepBy3600Seconds()

        repeat(2) { clock.answer(responseTo(clock.request(), 1L shl 32), fromPeer = true) }

        assertThat(clock.now()).isEqualTo(0xE8FE7D9140000000UL.toLong())
    }

    @Test
    fun `a pick whose round trip is longer than an earlier one in the window is not applied`() {
        stepBy3600Seconds()
        // A pick with offset 0 and round trip 0 enters the window.
        repeat(2) { clock.answer(responseTo(clock.request(), 0L), fromPeer = true) }
        // Then one with round trip 2^-8 s and offset (2^25 + 2^25 - 2^24) / 2 / 2^32 s: longer, so not used.
        repeat(2) {
            val request = clock.request()
            clock.answer(
                responseTo(request, receive = 1L shl 25, transmit = (1L shl 25) - (1L shl 24)),
                fromPeer = true,
            )
        }

        assertThat(clock.now()).isEqualTo(0xE8FE7D9040000000UL.toLong())
    }

    @Test
    fun `of two responses the one with the shorter round trip is picked`() {
        stepBy3600Seconds()
        // First: offset 1 s, round trip 2^-8 s. Second: offset 0, round trip 0. The second is applied (no change).
        val first = clock.request()
        clock.answer(
            responseTo(first, receive = (1L shl 32) + (1L shl 23), transmit = (1L shl 32) - (1L shl 23)),
            fromPeer = true,
        )
        clock.answer(responseTo(clock.request(), 0L), fromPeer = true)

        assertThat(clock.now()).isEqualTo(0xE8FE7D9040000000UL.toLong())
    }

    @Test
    fun `NTP values convert to their unsigned number`() {
        assertThat(TimingClock.unsigned(-1L)).isEqualTo(BigInteger.ONE.shiftLeft(64) - BigInteger.ONE)
        assertThat(TimingClock.unsigned(5L)).isEqualTo(BigInteger.valueOf(5))
    }

    private fun stepBy3600Seconds() {
        repeat(2) { clock.answer(responseTo(clock.request(), SKEW), fromPeer = true) }
        assertThat(clock.now()).isEqualTo(0xE8FE7D9040000000UL.toLong())
    }

    /** The iPhone's response to [request]: receive and transmit = request's transmit + [delta]. */
    private fun responseTo(
        request: ByteArray,
        delta: Long,
    ) = responseTo(request, delta, delta)

    /** As NtpClockPeerTest.kt:21-31, with the receive and transmit stamps offset separately. */
    private fun responseTo(
        request: ByteArray,
        receive: Long,
        transmit: Long,
    ): ByteArray {
        val t1 = ByteBuffer.wrap(request).getLong(24)
        val response = ByteArray(32)
        response[0] = 0x80.toByte()
        response[1] = 211.toByte()
        ByteBuffer
            .wrap(response)
            .putLong(8, t1)
            .putLong(16, t1 + receive)
            .putLong(24, t1 + transmit)
        return response
    }

    private companion object {
        const val WALL_MILLIS = 1_700_000_000_250L
        const val MONO0 = 5_000_000_000L
        const val SKEW = 3600L shl 32 // NtpClockPeerTest.kt:78
    }
}
