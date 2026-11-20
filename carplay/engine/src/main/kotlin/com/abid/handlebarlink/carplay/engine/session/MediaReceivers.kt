package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.ScreenPort
import com.abid.handlebarlink.carplay.engine.spi.TcpConnection
import com.abid.handlebarlink.carplay.engine.spi.UdpSocket
import com.abid.handlebarlink.carplay.protocol.plist.Plist
import com.abid.handlebarlink.carplay.protocol.stream.MediaStreamException
import com.abid.handlebarlink.carplay.protocol.stream.ScreenStreamReader
import com.abid.handlebarlink.carplay.protocol.timing.TimingClock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The media seams of one session around its own timing clock, which `/feedback` reads (CarPlayMediaEngine.kt:514). */
internal class SessionMedia(
    context: MediaContext,
    screen: ScreenPort,
    audio: AudioOutputs,
) {
    /** Started from the wall clock, then steered onto the iPhone's clock (NtpClock.kt:30). */
    private val clock = TimingClock(context.time::elapsedNanos, context.time.clock.millis())

    val seams =
        MediaSeams(
            timing = TimingExchange(clock, context),
            screen = ScreenReceiver(context, screen),
            audio = AudioReceiver(context, clock, audio),
        )
}

/**
 * The timing port (DiPlay NtpClock.kt:60-133): every datagram goes to [clock]; a request's answer goes back to its
 * sender, whoever it is, and responses steer the clock only from the control connection's peer (NtpClock.kt:117-121).
 * With a peer port, a request goes out at once and then every [TimingClock.REQUEST_INTERVAL_MILLIS] of the injected
 * clock. A send that fails is skipped, as DiPlay's sender does (NtpClock.kt:70-82).
 */
internal class TimingExchange(
    private val clock: TimingClock,
    private val context: MediaContext,
) : TimingResponder {
    private var sendFailures = 0L

    override suspend fun run(
        socket: UdpSocket,
        peer: String,
        peerPort: Int,
    ) {
        coroutineScope {
            val requests = if (peerPort > 0) launch { requests(socket, peer, peerPort) } else null
            try {
                answer(socket, peer)
            } finally {
                requests?.cancel()
            }
        }
    }

    private suspend fun answer(
        socket: UdpSocket,
        peer: String,
    ) {
        // Once per session: a repeated session SETUP keeps the clock it already steered.
        var synced = clock.isSynced
        while (true) {
            val datagram = socket.receive() ?: return
            val fromPeer = datagram.address.substringBefore('%') == peer.substringBefore('%')
            clock.answer(datagram.bytes, datagram.bytes.size, fromPeer)?.let {
                send(socket, it, datagram.address, datagram.port)
            }
            if (!synced && clock.isSynced) {
                synced = true
                context.log.info("carplay.timing.synced", "port" to socket.port)
            }
        }
    }

    private suspend fun requests(
        socket: UdpSocket,
        peer: String,
        peerPort: Int,
    ) {
        var next = context.time.elapsedMillis()
        while (true) {
            val wait = next - context.time.elapsedMillis()
            if (wait > 0) {
                delay(wait)
            } else {
                send(socket, clock.request(), peer, peerPort)
                next = context.time.elapsedMillis() + TimingClock.REQUEST_INTERVAL_MILLIS
            }
        }
    }

    private suspend fun send(
        socket: UdpSocket,
        bytes: ByteArray,
        address: String,
        port: Int,
    ) {
        try {
            socket.send(bytes, address, port)
        } catch (e: CarPlayException) {
            if (sendFailures++ == 0L) context.log.warn("carplay.timing.send_failed", "error" to e.error)
        }
    }
}

/**
 * Reads a screen stream with [ScreenStreamReader] and hands each frame of the main screen (110) to [screen], the
 * platform's decoder, which it [ScreenPort.configure]s at the stream SETUP with the canvas `/info` reported. Frames of
 * another screen are read and dropped (`/info` declares no other display). It logs the first frame, then a summary
 * about every [SUMMARY_MILLIS] while frames arrive, and the totals at the end. A frame that is too large or does not
 * authenticate ends the stream, and so the session, as DiPlay (ScreenStream.kt:85, 92-98;
 * CarPlayMediaEngine.kt:160-169).
 *
 * No key frame is asked for at the start: DiPlay sends `forceKeyFrame` (no params for the main screen) only when its
 * decoder asks (CarPlayMediaEngine.kt:146-157; AndroidMediaSink.kt:1121-1124, 1636-1640), so each
 * [ScreenPort.keyFrameRequests] emission while the stream runs sends one.
 */
internal class ScreenReceiver(
    private val context: MediaContext,
    private val screen: ScreenPort,
) : ScreenStreamSink {
    override fun prepare(stream: ScreenStreamSetup) {
        if (stream.type != MediaStreams.MAIN_SCREEN) return
        val config = context.config
        context.log.info(
            "carplay.screen.configure",
            "width" to config.widthPixels,
            "height" to config.heightPixels,
            "fps" to config.reportedFps,
        )
        screen.configure(config.widthPixels, config.heightPixels, config.reportedFps)
    }

    override suspend fun run(
        stream: ScreenStreamSetup,
        connection: TcpConnection,
        events: EventCommands,
    ) {
        if (stream.type != MediaStreams.MAIN_SCREEN) {
            read(stream, connection, ::ByteArray) { _, _, _, _ -> }
            return
        }
        coroutineScope {
            val keyFrames = launch { screen.keyFrameRequests.collect { forceKeyFrame(events) } }
            try {
                read(stream, connection, screen::pictureBuffer, screen::frame)
            } finally {
                keyFrames.cancel()
            }
        }
    }

    private suspend fun forceKeyFrame(events: EventCommands) {
        val sent = events.send(FORCE_KEY_FRAME)
        context.log.info("carplay.screen.force_key_frame", "sent" to sent)
    }

    private suspend fun read(
        stream: ScreenStreamSetup,
        connection: TcpConnection,
        buffers: (Int) -> ByteArray,
        frames: (isConfig: Boolean, senderNanos: Long, payload: ByteArray, length: Int) -> Unit,
    ) {
        val reader = ScreenStreamReader(stream.key, context.cipherName, buffers)
        var count = 0L
        var configs = 0L
        var bytes = 0L
        var summaryAt = 0L
        val read: suspend (ByteArray) -> Int = { buffer -> connection.read(buffer).also { if (it > 0) bytes += it } }
        try {
            while (true) {
                val frame = reader.next(read) ?: break
                count++
                if (frame.isConfig) configs++
                val now = context.time.elapsedMillis()
                if (count == 1L) {
                    context.log.info(
                        "carplay.screen.receiving",
                        "type" to stream.type,
                        "config" to frame.isConfig,
                        "bytes" to frame.length,
                    )
                    summaryAt = now + SUMMARY_MILLIS
                } else if (now >= summaryAt) {
                    context.log.info(
                        "carplay.screen.frames",
                        "type" to stream.type,
                        "frames" to count,
                        "configs" to configs,
                        "bytes" to bytes,
                    )
                    summaryAt = now + SUMMARY_MILLIS
                }
                frames(frame.isConfig, frame.senderNanos, frame.payload, frame.length)
            }
        } catch (e: MediaStreamException) {
            throw CarPlayException(CarPlayError.PhoneDisconnected("screen stream ${stream.type}: ${e.message}"), e)
        } finally {
            context.log.info(
                "carplay.screen.ended",
                "type" to stream.type,
                "frames" to count,
                "configs" to configs,
                "bytes" to bytes,
            )
        }
    }

    private companion object {
        const val SUMMARY_MILLIS = 5_000L

        /** The main screen's key frame request (CarPlayMediaEngine.kt:152). */
        val FORCE_KEY_FRAME = Plist.dict("type" to "forceKeyFrame")
    }
}
