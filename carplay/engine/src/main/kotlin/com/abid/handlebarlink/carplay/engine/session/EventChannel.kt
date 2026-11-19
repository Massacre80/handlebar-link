package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.TcpConnection
import com.abid.handlebarlink.carplay.protocol.pairing.ControlCipher
import com.abid.handlebarlink.carplay.protocol.plist.BinaryPlistWriter
import com.abid.handlebarlink.carplay.protocol.plist.Plist
import com.abid.handlebarlink.carplay.protocol.rtsp.RtspException
import com.abid.handlebarlink.carplay.protocol.rtsp.RtspReader
import com.abid.handlebarlink.carplay.protocol.rtsp.RtspRequest
import com.abid.handlebarlink.carplay.protocol.rtsp.RtspResponse
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.crypto.AEADBadTagException

/**
 * The event channel the iPhone opens to the session SETUP's `eventPort` (DiPlay AirPlaySession.kt:884-1000, spec D
 * §2.7): ChaCha20-Poly1305 framed like the control channel, keyed with `ControlCipher.events` (the key names are the
 * control channel's, mirrored). We send `POST /command` requests on it; the iPhone's requests get `200` with their
 * CSeq and its responses are ignored.
 *
 * [run] lasts as long as the connection and then throws: the channel ending, or a frame that does not authenticate,
 * ends the session, as in DiPlay (AirPlaySession.kt:960-999).
 */
internal class EventChannel(
    private val connection: TcpConnection,
    sharedSecret: ByteArray,
    cipherName: String,
    private val log: Logger,
) : EventCommands {
    private val cipher = ControlCipher.events(sharedSecret, cipherName)
    private val reader = RtspReader()

    /** Encryption and the write it belongs to happen as one step: frame counters must reach the wire in order. */
    private val writeLock = Mutex()
    private var cseq = 0
    private var failed = false

    suspend fun run(): Nothing {
        val buffer = ByteArray(READ_CHUNK)
        while (true) {
            val count = connection.read(buffer)
            if (count < 0) throw CarPlayException(CarPlayError.PhoneDisconnected("event channel closed"))
            messages(buffer, count).forEach { answer(it) }
        }
    }

    /** The messages [count] new bytes complete; a frame that does not authenticate or a broken message throws. */
    private fun messages(
        buffer: ByteArray,
        count: Int,
    ): List<RtspRequest> =
        try {
            val plaintext = cipher.decrypt(buffer, 0, count)
            reader.receive(plaintext, 0, plaintext.size)
        } catch (e: AEADBadTagException) {
            throw CarPlayException(CarPlayError.PhoneDisconnected("event frame failed authentication"), e)
        } catch (e: RtspException) {
            throw CarPlayException(CarPlayError.PhoneDisconnected(e.message ?: "bad event message"), e)
        }

    /** Requests get an empty 200 with their CSeq; responses to our commands start with the protocol and are skipped. */
    private suspend fun answer(message: RtspRequest) {
        if (message.method.startsWith("RTSP/") || message.method.startsWith("HTTP/")) return
        log.debug("carplay.event.request", "method" to message.method, "path" to message.path)
        write(RtspResponse().encode(message))
    }

    /** `POST /command`, headers in DiPlay's order (AirPlaySession.kt:330-335), CSeq from 1. */
    override suspend fun send(command: Plist.Dict): Boolean {
        if (failed) return false
        val body = BinaryPlistWriter.write(command)
        return try {
            writeLock.withLock {
                cseq++
                val head =
                    "POST /command RTSP/1.0\r\n" +
                        "Content-Type: ${RtspResponse.PLIST}\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "CSeq: $cseq\r\n\r\n"
                connection.write(cipher.encrypt(head.encodeToByteArray() + body))
            }
            true
        } catch (e: CarPlayException) {
            // DiPlay closes the session when a command cannot be written (AirPlaySession.kt:343-346).
            log.warn("carplay.event.send_failed", "type" to command.text("type"), "error" to e.error)
            failed = true
            connection.close()
            false
        }
    }

    private suspend fun write(plaintext: ByteArray) = writeLock.withLock { connection.write(cipher.encrypt(plaintext)) }

    private companion object {
        /** DiPlay's read size (AirPlaySession.kt:959, READ_CHUNK_BYTES). */
        const val READ_CHUNK = 16 * 1024
    }
}
