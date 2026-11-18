package com.abid.handlebarlink.carplay.protocol.rtsp

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test

/** Cases follow DiPlay's ControlReceiveBoundsTest.kt and RtspMessage.kt:50-108 (spec D §2.1-2.2). */
class RtspTest {
    @Test
    fun `pipelined requests are split and a partial one waits, as DiPlay's parserKeepsRealSizedMessages`() {
        val reader = RtspReader()
        val mask = ByteArray(576 * 1024) { it.toByte() }
        val setup = request(756, ByteArray(756) { it.toByte() })
        val bytes = setup + request(mask.size, mask) + "GET /in".encodeToByteArray()

        val requests = reader.receive(bytes, 0, bytes.size)
        val rest = "fo RTSP/1.0\r\nCSeq: 9\r\n\r\n".encodeToByteArray()
        val completed = reader.receive(rest, 0, rest.size)

        assertThat(requests.map { it.body.size }).containsExactly(756, mask.size)
        assertThat(requests[1].body.contentEquals(mask)).isTrue()
        assertThat(completed.single().path).isEqualTo("/info")
        assertThat(completed.single().cSeq).isEqualTo("9")
    }

    @Test
    fun `a request whose body is cut after a complete request finishes when exactly the rest arrives`() {
        val reader = RtspReader()
        val first = request(10, ByteArray(10))
        val second = request(300, ByteArray(300) { 3 })
        val cut = first.size + second.size - 100
        val bytes = first + second

        val early = reader.receive(bytes, 0, cut)
        val late = reader.receive(bytes, cut, bytes.size - cut)

        assertThat(early.size).isEqualTo(1)
        assertThat(late.single().body.size).isEqualTo(300)
    }

    @Test
    fun `a body arriving byte by byte completes once`() {
        val reader = RtspReader()
        val bytes = request(1000, ByteArray(1000) { 7 })

        val requests = bytes.indices.flatMap { reader.receive(bytes, it, 1) }

        assertThat(requests.single().body.size).isEqualTo(1000)
    }

    @Test
    fun `header names are case-insensitive and a header line without a colon is skipped`() {
        val reader = RtspReader()
        val text =
            "SETUP rtsp://10.0.0.1/123 RTSP/1.0\r\nCONTENT-LENGTH: 2\r\nnonsense\r\n" +
                "X-Apple-StreamID: 1\r\n\r\nab"
        val bytes = text.encodeToByteArray()

        val request = reader.receive(bytes, 0, bytes.size).single()

        assertThat(request.method).isEqualTo("SETUP")
        assertThat(request.headers["x-apple-streamid"]).isEqualTo("1")
        assertThat(request.body.decodeToString()).isEqualTo("ab")
        assertThat(request.cSeq).isNull()
    }

    @Test
    fun `a huge Content-Length or a header that never ends closes the connection`() {
        val huge = request(1_500_000_000)
        val endless = "GET /info RTSP/1.0\r\nX: ".encodeToByteArray() + ByteArray(64 * 1024) { 'a'.code.toByte() }

        assertFailure { RtspReader().receive(huge, 0, huge.size) }.isInstanceOf<RtspException>()
        assertFailure { RtspReader().receive(endless, 0, endless.size) }.isInstanceOf<RtspException>()
    }

    @Test
    fun `a partial body under the cap waits`() {
        val bytes = request(756, ByteArray(100))

        assertThat(RtspReader().receive(bytes, 0, bytes.size)).isEmpty()
    }

    @Test
    fun `a response echoes the protocol and CSeq and always carries Content-Length`() {
        val get = RtspRequest("GET", "/info", "RTSP/1.0", mapOf("cseq" to "5"), ByteArray(0))
        val post = RtspRequest("POST", "/pair-setup", "HTTP/1.1", emptyMap(), ByteArray(0))

        assertThat(RtspResponse().encode(get).decodeToString())
            .isEqualTo("RTSP/1.0 200 OK\r\nCSeq: 5\r\nContent-Length: 0\r\n\r\n")
        assertThat(RtspResponse(RtspResponse.OK, RtspResponse.TLV8, byteArrayOf(6, 1, 2)).encode(post).size)
            .isEqualTo(
                "HTTP/1.1 200 OK\r\nContent-Type: application/pairing+tlv8\r\nContent-Length: 3\r\n\r\n".length + 3,
            )
        assertThat(RtspResponse(RtspResponse.CONNECTION_AUTHORIZATION_REQUIRED).encode(get).decodeToString())
            .isEqualTo("RTSP/1.0 470 Connection Authorization Required\r\nCSeq: 5\r\nContent-Length: 0\r\n\r\n")
        assertThat(RtspResponse(418).encode(get).decodeToString())
            .isEqualTo("RTSP/1.0 418 OK\r\nCSeq: 5\r\nContent-Length: 0\r\n\r\n")
    }

    @Test
    fun `a line feed inside the request's CSeq cannot add a header to our response`() {
        val injected = RtspRequest("GET", "/info", "RTSP/1.0", mapOf("cseq" to "5\nX-Evil: 1"), ByteArray(0))

        val text = RtspResponse().encode(injected).decodeToString()

        assertThat(text).isEqualTo("RTSP/1.0 200 OK\r\nCSeq: 5X-Evil: 1\r\nContent-Length: 0\r\n\r\n")
    }

    @Test
    fun `bytes after the last complete request stay buffered`() {
        val reader = RtspReader()
        val bytes = request(0) + "GET /in".encodeToByteArray()

        val requests = reader.receive(bytes, 0, bytes.size)

        assertThat(requests.size).isEqualTo(1)
        assertThat(reader.buffered).isEqualTo(7)
    }

    @Test
    fun `paths are matched by lower-case suffix`() {
        val request = RtspRequest("POST", "/Pair-Verify", "RTSP/1.0", emptyMap(), ByteArray(0))

        assertThat(request.pathEndsWith("/pair-verify")).isTrue()
    }

    private fun request(
        contentLength: Int,
        body: ByteArray = ByteArray(0),
    ): ByteArray = "POST /x RTSP/1.0\r\nCSeq: 1\r\nContent-Length: $contentLength\r\n\r\n".encodeToByteArray() + body
}
