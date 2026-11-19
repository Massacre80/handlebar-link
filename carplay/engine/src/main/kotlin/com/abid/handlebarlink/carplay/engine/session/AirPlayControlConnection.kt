package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.CarPlayCredentials
import com.abid.handlebarlink.carplay.engine.spi.TcpConnection
import com.abid.handlebarlink.carplay.protocol.pairing.AuthSetup
import com.abid.handlebarlink.carplay.protocol.pairing.ControlCipher
import com.abid.handlebarlink.carplay.protocol.pairing.PairSetup
import com.abid.handlebarlink.carplay.protocol.pairing.PairVerify
import com.abid.handlebarlink.carplay.protocol.plist.BinaryPlistWriter
import com.abid.handlebarlink.carplay.protocol.rtsp.RtspException
import com.abid.handlebarlink.carplay.protocol.rtsp.RtspReader
import com.abid.handlebarlink.carplay.protocol.rtsp.RtspRequest
import com.abid.handlebarlink.carplay.protocol.rtsp.RtspResponse
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * One AirPlay control connection from the iPhone (DiPlay AirPlaySession.kt:450-639, spec D §2):
 * RTSP requests, answered in order. Before pair-verify completes only `/pair-setup`, `/pair-verify` and
 * `GET /info` are served, anything else gets 470 (DiPlay PR #1124). The reply to pair-verify M4 goes out
 * in clear; every byte after it is ChaCha20-Poly1305 framed (ControlCipher).
 *
 * After pair-verify an [AirPlaySession] serves the session's requests (SETUP, RECORD, TEARDOWN, `/command`,
 * `/feedback`) and owns what they open; it lives in this connection's scope, so the connection closing ends it, and a
 * vital part of it ending (the event channel, the screen stream) ends the connection, as DiPlay's `close()`.
 */
internal class AirPlayControlConnection(
    private val connection: TcpConnection,
    private val credentials: CarPlayCredentials,
    private val receiver: AirPlayReceiver,
    private val random: SecureRandom,
    private val platform: SessionPlatform,
    private val log: Logger,
) {
    private val cipherName = platform.cipherName
    private val reader = RtspReader()
    private val pairSetup = PairSetup(credentials.airPlay, credentials.pairings, random, cipherName)
    private val pairVerify = PairVerify(credentials.airPlay, credentials.pairings, random, cipherName)
    private var cipher: ControlCipher? = null
    private var session: AirPlaySession? = null

    /** The iPhone's pairing id once pair-verify succeeded. */
    val verifiedPhone: String? get() = pairVerify.controllerId.takeIf { cipher != null }

    /**
     * Serves requests until the iPhone closes the connection (or a vital part of the session ends); [onVerified] runs
     * once, after pair-verify. Everything the session opened is closed before this returns.
     */
    suspend fun run(onVerified: (String) -> Unit): Nothing =
        coroutineScope {
            try {
                serve(this, onVerified)
            } finally {
                session?.close()
            }
        }

    private suspend fun serve(
        scope: CoroutineScope,
        onVerified: (String) -> Unit,
    ): Nothing {
        val buffer = ByteArray(READ_CHUNK)
        while (true) {
            val count = connection.read(buffer)
            if (count < 0) throw CarPlayException(CarPlayError.PhoneDisconnected("control connection closed"))
            val requests = parse(decrypt(buffer, count))
            requests.forEachIndexed { index, request ->
                respond(request)
                refusePlaintextBehindVerify(more = index < requests.lastIndex || reader.buffered > 0)
                switchOnEncryption(scope, onVerified)
            }
        }
    }

    private fun parse(plaintext: ByteArray): List<RtspRequest> =
        try {
            reader.receive(plaintext, 0, plaintext.size)
        } catch (e: RtspException) {
            throw CarPlayException(CarPlayError.PhoneDisconnected(e.message ?: "bad RTSP"), e)
        }

    /**
     * Bytes that came in clear behind pair-verify M3 were never authenticated: the connection is dropped before
     * it counts as verified, rather than serving them as if they were encrypted (spec C §10.8).
     */
    private fun refusePlaintextBehindVerify(more: Boolean) {
        val verifiedNow = cipher == null && pairVerify.controlKeys != null
        if (verifiedNow && more) throw CarPlayException(CarPlayError.PhoneDisconnected("plaintext after pair-verify"))
    }

    private fun decrypt(
        buffer: ByteArray,
        count: Int,
    ): ByteArray {
        val active = cipher ?: return buffer.copyOf(count)
        return try {
            active.decrypt(buffer, 0, count)
        } catch (e: AEADBadTagException) {
            throw CarPlayException(CarPlayError.PhoneDisconnected("control frame failed authentication"), e)
        }
    }

    private suspend fun respond(request: RtspRequest) {
        val response = handle(request)
        log.debug(
            "carplay.airplay.request",
            "method" to request.method,
            "path" to request.path,
            "status" to response.status,
        )
        val wire = response.encode(request)
        connection.write(cipher?.encrypt(wire) ?: wire)
    }

    private fun switchOnEncryption(
        scope: CoroutineScope,
        onVerified: (String) -> Unit,
    ) {
        val keys = pairVerify.controlKeys
        if (cipher != null || keys == null) return
        cipher = ControlCipher(keys.readKey, keys.writeKey, cipherName)
        val phone = checkNotNull(pairVerify.controllerId)
        val context =
            SessionContext(scope, platform, connection.remoteAddress, checkNotNull(pairVerify.sharedSecret), log)
        session = AirPlaySession(context)
        log.info("carplay.airplay.verified", "phone" to phone)
        onVerified(phone)
    }

    /**
     * A handler failure answers 500 and keeps the connection, as DiPlay (AirPlaySession.kt:501-519). A cancellation is
     * propagated only when this connection is ending; a stray one (a port's own timeout) is a failure like any other.
     */
    @Suppress("TooGenericExceptionCaught") // Any bug in one handler must not end the AirPlay connection.
    private suspend fun handle(request: RtspRequest): RtspResponse =
        try {
            route(request)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            failed(log, request, e)
        } catch (e: Exception) {
            failed(log, request, e)
        }

    /** DiPlay's order (AirPlaySession.kt:573-660): the session's methods first, then pairing, auth-setup and /info. */
    private suspend fun route(request: RtspRequest): RtspResponse =
        when {
            cipher == null && !allowedBeforeVerify(request) -> {
                RtspResponse(RtspResponse.CONNECTION_AUTHORIZATION_REQUIRED)
            }

            request.method in SESSION_METHODS -> {
                checkNotNull(session).handle(request)
            }

            request.pathEndsWith("/pair-setup") -> {
                RtspResponse(RtspResponse.OK, RtspResponse.TLV8, pairSetup.handle(request.body))
            }

            request.pathEndsWith("/pair-verify") -> {
                RtspResponse(RtspResponse.OK, RtspResponse.TLV8, pairVerify.handle(request.body))
            }

            request.pathEndsWith("/auth-setup") -> {
                authSetup(request)
            }

            request.pathEndsWith("/info") -> {
                // With what DiPlay logs of it (AirPlaySession.kt:630-635).
                val info = receiver.info()
                log.info(
                    "carplay.airplay.info",
                    "audioFormats" to info.array("audioFormats")?.size,
                    "audioLatencies" to info.array("audioLatencies")?.size,
                    "hidDevices" to info.array("hidDevices")?.size,
                )
                RtspResponse(RtspResponse.OK, RtspResponse.PLIST, BinaryPlistWriter.write(info))
            }

            else -> {
                // Only verified requests get here: the gate above lets nothing else through before pair-verify.
                checkNotNull(session).handle(request)
            }
        }

    private fun authSetup(request: RtspRequest): RtspResponse {
        val body = AuthSetup.handle(request.body, credentials.mfi, random)
        return if (body ==
            null
        ) {
            RtspResponse(RtspResponse.BAD_REQUEST)
        } else {
            RtspResponse(RtspResponse.OK, RtspResponse.OCTETS, body)
        }
    }

    private companion object {
        /** DiPlay's control read size (AirPlaySession.kt READ_CHUNK_BYTES). */
        const val READ_CHUNK = 16 * 1024

        /** Dispatched by method whatever the path (AirPlaySession.kt:574-590). */
        val SESSION_METHODS = setOf("SETUP", "SET_PARAMETER", "RECORD", "TEARDOWN")

        fun failed(
            log: Logger,
            request: RtspRequest,
            e: Exception,
        ): RtspResponse {
            log.error("carplay.airplay.handler_failed", e, "method" to request.method, "path" to request.path)
            return RtspResponse(RtspResponse.INTERNAL_ERROR)
        }

        /** DiPlay PR #1124 (CarLink AirPlaySession.kt allowedBeforePairVerify). */
        private fun allowedBeforeVerify(request: RtspRequest): Boolean =
            when (request.method) {
                "POST" -> request.pathEndsWith("/pair-setup") || request.pathEndsWith("/pair-verify")
                "GET" -> request.pathEndsWith("/info")
                else -> false
            }
    }
}
