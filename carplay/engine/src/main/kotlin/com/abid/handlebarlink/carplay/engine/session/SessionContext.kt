package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.CarPlayPorts
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneOffer
import com.abid.handlebarlink.carplay.engine.spi.NetworkPort
import com.abid.handlebarlink.carplay.engine.spi.TcpConnection
import com.abid.handlebarlink.carplay.engine.spi.TcpListener
import com.abid.handlebarlink.carplay.engine.spi.UdpSocket
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * What the runtime plugs into every attempt: the media seams' factory, the rider's touches and key presses (sent on
 * each AirPlay session's event channel), and the iPhone's calls (fed by the Bluetooth bootstrap).
 */
internal class SessionPlugins(
    val media: MediaFactory,
    val touches: TouchInput,
    val controls: ControlInput,
    val calls: CallStates,
)

/**
 * What every AirPlay session of one wireless attempt shares: sockets, the group's addresses, the attempt's
 * configuration, the media factory and the rider's touches.
 */
internal class SessionPlatform(
    /** Sockets (`network`) and the platform's cipher name. */
    private val ports: CarPlayPorts,
    /** The addresses the AirPlay listener is bound to; the session's TCP listeners use the same. */
    val addresses: List<String>,
    val time: TimeProvider,
    val config: CarPlayConfig,
    private val plugins: SessionPlugins,
    /** The attempt's iAP2 hand-over from Bluetooth to the tunnel a session opens. */
    val handoff: WirelessHandoff,
    /** What `/info` offered the microphone this attempt; the audio streams record only with it. */
    val microphone: MicrophoneOffer = MicrophoneOffer.NONE,
) {
    /** Which verified session is the live one (DiPlay's `activeSession`). */
    val sessions: ActiveSession get() = handoff.sessions

    val network: NetworkPort get() = ports.network
    val cipherName: String get() = ports.cipherName
    val media: MediaFactory get() = plugins.media
    val touches: TouchInput get() = plugins.touches
    val controls: ControlInput get() = plugins.controls
}

/**
 * One verified control connection's session: [scope] is the connection's, so everything launched here ends with it,
 * and a vital part failing ends the connection (and with it the wireless attempt), as DiPlay's `session.close()`.
 */
internal class SessionContext(
    val scope: CoroutineScope,
    val platform: SessionPlatform,
    /** The control connection's peer; every other channel accepts only this address (AirPlayTcpAccepted.kt:25-37). */
    val peer: String,
    /** Pair-verify's X25519 secret, the source of the event and stream keys. Never log it. */
    val sharedSecret: ByteArray,
    val log: Logger,
) {
    /** This session's media seams, built once. */
    val seams: MediaSeams =
        platform.media.session(
            MediaContext(platform, log, peer),
        )

    /** A second UDP socket; [first] is closed when it cannot be bound. */
    suspend fun bindUdpAfter(first: UdpSocket): UdpSocket {
        var done = false
        try {
            return platform.network
                .bindUdp()
                .also { done = true }
        } finally {
            if (!done) first.close()
        }
    }

    /**
     * Accepts until a connection comes from [peer]; others are closed unread (DiPlay `acceptFrom`,
     * AirPlayTcpAccepted.kt:30-37). Link-local scopes are ignored: every listener is bound to the group's addresses.
     */
    suspend fun acceptFromPeer(
        listener: TcpListener,
        channel: String,
    ): TcpConnection {
        while (true) {
            val connection = listener.accept()
            if (connection.remoteAddress.substringBefore('%') == peer.substringBefore('%')) return connection
            log.warn("carplay.airplay.foreign_peer", "channel" to channel, "peer" to connection.remoteAddress)
            connection.close()
        }
    }

    /**
     * Runs a part whose end ends the session (the event channel, a screen stream): it returning or failing throws
     * [CarPlayError.PhoneDisconnected] into [scope]. Cancelling it (TEARDOWN) ends only the part.
     */
    fun launchVital(
        name: String,
        block: suspend () -> Unit,
    ): Job =
        scope.launch {
            val failure = runPart(name, block)
            throw failure as? CarPlayException
                ?: CarPlayException(
                    CarPlayError.PhoneDisconnected(if (failure == null) "$name ended" else "$name failed: $failure"),
                    failure,
                )
        }

    /** Runs a part whose end is only logged (timing, keep-alive, an audio stream; DiPlay keeps the session then). */
    fun launchPart(
        name: String,
        block: suspend () -> Unit,
    ): Job =
        scope.launch {
            val failure = runPart(name, block)
            if (failure != null) log.warn("carplay.airplay.part_failed", "part" to name, "error" to failure.toString())
        }

    /**
     * Runs [block]; returns what ended it (null when it returned). Our own cancellation propagates; a stray
     * cancellation from inside a seam (its own timeout) counts as a failure (docs/LESSONS.md 54).
     */
    @Suppress("TooGenericExceptionCaught") // A bug in a media seam must end its part, never escape as an Unexpected.
    private suspend fun runPart(
        name: String,
        block: suspend () -> Unit,
    ): Exception? =
        try {
            block()
            null
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            e
        } catch (e: CarPlayException) {
            e
        } catch (e: Exception) {
            log.error("carplay.airplay.part_crashed", e, "part" to name)
            e
        }
}
