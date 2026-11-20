package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.protocol.plist.BinaryPlistReader
import com.abid.handlebarlink.carplay.protocol.plist.BinaryPlistWriter
import com.abid.handlebarlink.carplay.protocol.plist.Plist
import com.abid.handlebarlink.carplay.protocol.plist.PlistException
import com.abid.handlebarlink.carplay.protocol.rtsp.RtspRequest
import com.abid.handlebarlink.carplay.protocol.rtsp.RtspResponse

/**
 * The AirPlay session behind a verified control connection: every request after pair-verify that is not pairing,
 * auth-setup or `/info` (DiPlay AirPlaySession.kt:573-660, spec D §2.4). Dispatch is DiPlay's: by method first
 * (SETUP, SET_PARAMETER, RECORD, TEARDOWN), then `POST …/command` and `POST …/feedback`, and anything else gets an
 * empty 200, as DiPlay answers GET_PARAMETER, OPTIONS, FLUSH and unknown routes.
 */
internal class AirPlaySession(
    val context: SessionContext,
) {
    private val channels = SessionChannels(context)
    private val streams = MediaStreams(context, channels.commands)

    suspend fun handle(request: RtspRequest): RtspResponse =
        when {
            request.method == "SETUP" -> setup(request)

            // Corner masks are not enabled, so the body is not read (AirPlaySession.kt:576-584).
            request.method == "SET_PARAMETER" -> RtspResponse()

            request.method == "RECORD" -> record()

            request.method == "TEARDOWN" -> teardown(request)

            request.method == "POST" && request.pathEndsWith("/command") -> command(request)

            request.method == "POST" && request.pathEndsWith("/feedback") -> feedback()

            else -> unhandled(request)
        }

    /** A session SETUP (no `streams`) or a stream SETUP (AirPlaySession.kt:690-734); 400 when the body is no dict. */
    private suspend fun setup(request: RtspRequest): RtspResponse {
        val body = decode(request.body) as? Plist.Dict ?: return RtspResponse(RtspResponse.BAD_REQUEST)
        // Key names only, never values (some carry key material, e.g. a buffered stream's shk).
        context.log.debug("carplay.airplay.setup_keys", "keys" to body.entries.keys.sorted())
        val streamEntries = body.array("streams")
        val reply =
            if (streamEntries != null) {
                streams.setup(streamEntries)
            } else {
                channels.setup(body)
            }
        return RtspResponse(RtspResponse.OK, RtspResponse.PLIST, BinaryPlistWriter.write(reply))
    }

    /** The iPhone starts sending: this session is the live one (DiPlay `onSessionActive`, AirPlaySession.kt:585). */
    private fun record(): RtspResponse {
        context.log.info("carplay.airplay.record")
        context.platform.sessions.recorded(context)
        return RtspResponse()
    }

    private fun teardown(request: RtspRequest): RtspResponse {
        streams.teardown(MediaStreams.teardownTypes(decode(request.body)))
        return RtspResponse()
    }

    /**
     * `POST /command` (AirPlaySession.kt:794-821): always 200. The type is logged; `duckAudio` and `unduckAudio` go to
     * the audio (:810-811); `disableBluetooth` from the live (RECORDed) session starts the hand-over of iAP2 to the
     * Wi-Fi tunnel (CarPlayController.kt:506-525, spec D §1.5); from any other session it is only logged, as DiPlay.
     */
    private fun command(request: RtspRequest): RtspResponse {
        val body = decode(request.body) as? Plist.Dict
        val params = body?.dictionary("params")
        val type = body?.text("type")
        context.log.info(
            "carplay.airplay.command",
            "type" to type,
            "params" to params?.entries?.keys?.sorted(),
        )
        when (type) {
            "duckAudio" -> context.seams.audio.duck(true)
            "unduckAudio" -> context.seams.audio.duck(false)
        }
        if (WirelessHandoff.isHandoffCommand(type)) {
            if (context.platform.sessions.isActive(context)) {
                context.platform.handoff.disableBluetoothRequested()
            } else {
                context.log.warn("carplay.handoff.not_active", "type" to type)
            }
        }
        return RtspResponse()
    }

    /** Empty 200 while no audio stream reports, else `{streams: [...]}` (AirPlaySession.kt:647-657). */
    private fun feedback(): RtspResponse {
        val entries = streams.feedback()
        return if (entries.isEmpty()) {
            RtspResponse()
        } else {
            RtspResponse(RtspResponse.OK, RtspResponse.PLIST, BinaryPlistWriter.write(Plist.dict("streams" to entries)))
        }
    }

    private fun unhandled(request: RtspRequest): RtspResponse {
        context.log.info("carplay.airplay.unhandled", "method" to request.method, "path" to request.path)
        return RtspResponse()
    }

    /** Closes every stream and channel; also done by the connection's scope ending. */
    fun close() {
        streams.close()
        channels.close()
        context.seams.audio.close()
    }

    private fun decode(body: ByteArray): Plist? =
        if (body.isEmpty()) {
            null
        } else {
            try {
                BinaryPlistReader.read(body)
            } catch (e: PlistException) {
                context.log.debug("carplay.airplay.bad_plist", "bytes" to body.size, "error" to e.message)
                null
            }
        }
}
