package com.abid.handlebarlink.carplay.protocol.rtsp

/** An RTSP/HTTP request from the iPhone. Header names are lower-case; a repeated header keeps the last value. */
public class RtspRequest(
    /** Method, e.g. "SETUP", "POST". */
    public val method: String,
    /** Path, e.g. "/pair-setup", or a full rtsp:// URL. */
    public val path: String,
    /** Protocol, "RTSP/1.0" or "HTTP/1.1"; echoed in the response. */
    public val protocol: String,
    /** Headers by lower-case name. */
    public val headers: Map<String, String>,
    /** Body bytes (TLV8, binary plist or raw). */
    public val body: ByteArray,
) {
    /** The CSeq header, or null. */
    public val cSeq: String? get() = headers["cseq"]

    /** Whether the lower-cased path ends with [suffix] (DiPlay dispatches this way, AirPlaySession.kt:586-624). */
    public fun pathEndsWith(suffix: String): Boolean = path.lowercase().endsWith(suffix)

    override fun toString(): String = "RtspRequest($method $path, ${body.size} bytes)"
}

/** A response to [RtspRequest]. */
public class RtspResponse(
    /** Status code. */
    public val status: Int = 200,
    /** Content-Type, when there is a body. */
    public val contentType: String? = null,
    /** Body bytes. */
    public val body: ByteArray = ByteArray(0),
) {
    /**
     * The wire bytes for [request] (DiPlay RtspMessage.kt:93-108): request protocol, status, reason;
     * then Content-Type, the echoed CSeq, and Content-Length (always, also 0). Nothing else.
     */
    public fun encode(request: RtspRequest): ByteArray {
        val head = StringBuilder()
        head
            .append(request.protocol)
            .append(' ')
            .append(status)
            .append(' ')
            .append(reason(status))
            .append("\r\n")
        contentType?.let { head.append("Content-Type: ").append(it).append("\r\n") }
        // A lone LF inside the request's CSeq line must not become a header of ours.
        request.cSeq?.let { cSeq ->
            head.append("CSeq: ").append(cSeq.filter { it != '\r' && it != '\n' }).append("\r\n")
        }
        head.append("Content-Length: ").append(body.size).append("\r\n\r\n")
        return head.toString().toByteArray(Charsets.US_ASCII) + body
    }

    /** Status codes and content types. */
    public companion object {
        /** 200. */
        public const val OK: Int = 200

        /** 400. */
        public const val BAD_REQUEST: Int = 400

        /** 470: a request that needs pair-verify first (DiPlay PR #1124). */
        public const val CONNECTION_AUTHORIZATION_REQUIRED: Int = 470

        /** 500. */
        public const val INTERNAL_ERROR: Int = 500

        /** Binary plist bodies. */
        public const val PLIST: String = "application/x-apple-binary-plist"

        /** Pairing bodies. */
        public const val TLV8: String = "application/pairing+tlv8"

        /** Raw bodies (auth-setup). */
        public const val OCTETS: String = "application/octet-stream"

        private fun reason(status: Int): String =
            when (status) {
                OK -> "OK"
                BAD_REQUEST -> "Bad Request"
                404 -> "Not Found"
                CONNECTION_AUTHORIZATION_REQUIRED -> "Connection Authorization Required"
                INTERNAL_ERROR -> "Internal Server Error"
                else -> "OK" // DiPlay's fallback (RtspMessage.kt:96)
            }
    }
}

/** A request that breaks the framing limits; the connection must be closed. */
public class RtspException(
    message: String,
) : Exception(message)

/**
 * Cuts the (decrypted) control stream into requests, without copying the backlog on every read: the buffer
 * grows geometrically and is only scanned once the front request can be complete (DiPlay PR #1304,
 * ControlFrameBuffer.kt). Headers are capped at 16 KiB and bodies at 1 MiB (RtspMessage.kt:32-35).
 */
public class RtspReader {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0

    /** Bytes received that are not part of a returned request yet. */
    public val buffered: Int get() = size

    /** Bytes needed before the front request is complete; -1 while its header is unfinished. */
    private var needed = -1

    /**
     * Adds [length] bytes; returns the requests they completed, in order.
     *
     * @throws RtspException when a header or body exceeds its cap.
     */
    public fun receive(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): List<RtspRequest> {
        append(bytes, offset, length)
        val requests = ArrayList<RtspRequest>()
        if (size > 0 && (needed < 0 || size >= needed)) {
            var at = 0
            var next = parse(at)
            while (next != null) {
                requests += next.request
                at = next.end
                next = if (at < size) parse(at) else null
            }
            buffer.copyInto(buffer, 0, at, size)
            size -= at
        }
        return requests
    }

    private class Parsed(
        val request: RtspRequest,
        val end: Int,
    )

    private fun append(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (length <= 0) return
        if (size + length > buffer.size) buffer = buffer.copyOf(maxOf(size + length, buffer.size * 2))
        bytes.copyInto(buffer, size, offset, offset + length)
        size += length
    }

    /** The request starting at [at], or null when it is not complete yet (then [needed] says how far to wait). */
    private fun parse(at: Int): Parsed? {
        val headerEnd = indexOfHeaderEnd(at)
        val headerLength = if (headerEnd < 0) size - at else headerEnd - at
        if (headerLength > MAX_HEADER_BYTES) throw RtspException("RTSP header over $MAX_HEADER_BYTES bytes")
        val head = if (headerEnd < 0) null else parseHead(String(buffer, at, headerEnd - at, Charsets.US_ASCII))
        val bodyLength = head?.let(::contentLength) ?: 0
        val bodyStart = headerEnd + HEADER_END.size
        val complete = head != null && size - bodyStart >= bodyLength
        needed = if (head == null || complete) -1 else bodyStart + bodyLength - at
        return head?.takeIf { complete }?.let {
            val body = buffer.copyOfRange(bodyStart, bodyStart + bodyLength)
            Parsed(RtspRequest(it.method, it.path, it.protocol, it.headers, body), bodyStart + bodyLength)
        }
    }

    private class Head(
        val method: String,
        val path: String,
        val protocol: String,
        val headers: Map<String, String>,
    )

    private fun contentLength(head: Head): Int {
        val length =
            head.headers["content-length"]
                ?.trim()
                ?.toIntOrNull()
                ?.coerceAtLeast(0) ?: 0
        if (length > MAX_BODY_BYTES) throw RtspException("RTSP body of $length bytes over $MAX_BODY_BYTES")
        return length
    }

    private fun parseHead(text: String): Head {
        val lines = text.split("\r\n")
        val requestLine = lines.first().split(' ', limit = 3)
        val headers = HashMap<String, String>()
        lines.drop(1).forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return Head(requestLine[0], requestLine.getOrElse(1) { "" }, requestLine.getOrElse(2) { "RTSP/1.0" }, headers)
    }

    private fun indexOfHeaderEnd(from: Int): Int =
        (from..size - HEADER_END.size).firstOrNull { i -> HEADER_END.indices.all { buffer[i + it] == HEADER_END[it] } }
            ?: -1

    private companion object {
        const val INITIAL_CAPACITY = 16 * 1024
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 1024 * 1024
        val HEADER_END = "\r\n\r\n".encodeToByteArray()
    }
}
