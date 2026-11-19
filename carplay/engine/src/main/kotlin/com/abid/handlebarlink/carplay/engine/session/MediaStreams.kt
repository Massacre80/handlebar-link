package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.protocol.plist.Plist
import com.abid.handlebarlink.carplay.protocol.stream.StreamKeys
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The stream SETUP (`{streams: [...]}`), TEARDOWN and `/feedback` (DiPlay AirPlaySession.kt:736-792, 823-848;
 * CarPlayMediaEngine.kt:134-277, 489-544; spec D §5.2). Accepted streams are answered in request order; one that is
 * declined or unknown is left out of the reply and the request still succeeds. A stream replaces an open one with the
 * same type (screen) or the same type and audio type (audio).
 *
 * Declined here: 103 (buffered audio is not offered in `/info`, AirPlaySession.kt:768-776) and 130 (the iAP2 tunnel,
 * docs/PLAN.md C5). At most [MAX_OPEN_STREAMS] streams are open at once: audio types are free text, so without a cap
 * one SETUP could open sockets without limit (DiPlay has none).
 */
internal class MediaStreams(
    private val context: SessionContext,
    private val commands: EventCommands,
) {
    private class Open(
        val type: Int,
        val job: Job,
        val audio: AudioStreamSetup?,
    )

    private val open = LinkedHashMap<String, Open>()

    /** Sets up [entries]; returns the reply. A port that cannot be opened throws (the request then gets 500). */
    suspend fun setup(entries: List<Plist>): Plist.Dict {
        val accepted =
            entries.mapNotNull { entry ->
                val stream = entry as? Plist.Dict
                val type = stream?.long("type")?.toInt()
                when {
                    stream == null || type == null -> null
                    type == MAIN_SCREEN || type == ALT_SCREEN -> screen(type, stream)
                    type == MAIN_AUDIO || type == ALT_AUDIO || type == MAIN_HIGH_AUDIO -> audio(type, stream)
                    else -> declined(type, if (type in KNOWN_DECLINED) "not offered" else "unsupported")
                }
            }
        return Plist.dict("streams" to accepted)
    }

    /** Without an id there is no key, and DiPlay declines before replacing the open stream (ME:134-135, 172). */
    private suspend fun screen(
        type: Int,
        stream: Plist.Dict,
    ): Plist? {
        val id = stream["streamConnectionID"] as? Plist.Integer
        if (id != null) open.remove(type.toString())?.job?.cancel()
        return when {
            id == null -> declined(type, NO_ID)
            open.size >= MAX_OPEN_STREAMS -> declined(type, TOO_MANY)
            else -> openScreen(type, stream, id)
        }
    }

    /** `{type, dataPort}` on a TCP listener (ScreenStream.kt:47-55). */
    private suspend fun openScreen(
        type: Int,
        stream: Plist.Dict,
        id: Plist.Integer,
    ): Plist {
        val setup = ScreenStreamSetup(type, StreamKeys.output(context.sharedSecret, id.bits), stream)
        context.seams.screen.prepare(setup)
        val listener = context.platform.network.listen(context.platform.addresses, 0)
        val job =
            context.launchVital("screen stream $type") {
                val connection = context.acceptFromPeer(listener, "screen $type")
                listener.close()
                context.log.info("carplay.airplay.screen_connected", "type" to type, "peer" to connection.remoteAddress)
                try {
                    context.seams.screen.run(setup, connection, commands)
                } finally {
                    connection.close()
                }
            }
        track(type.toString(), Open(type, job, null)) { listener.close() }
        context.log.info("carplay.airplay.stream", "type" to type, "dataPort" to listener.port)
        return Plist.dict("type" to type, "dataPort" to listener.port)
    }

    /** As DiPlay, a new SETUP of the same audio stream ends the old one before anything is checked (ME:183-189). */
    private suspend fun audio(
        type: Int,
        stream: Plist.Dict,
    ): Plist? {
        val audioType = stream.text("audioType")?.lowercase() ?: "default"
        open.remove("$type/$audioType")?.job?.cancel()
        val id = stream["streamConnectionID"] as? Plist.Integer
        return when {
            id == null -> {
                declined(type, NO_ID)
            }

            open.size >= MAX_OPEN_STREAMS -> {
                declined(type, TOO_MANY)
            }

            else -> {
                openAudio(
                    AudioStreamSetup(
                        type,
                        audioType,
                        stream.long("audioFormat") ?: 0L,
                        id,
                        StreamKeys.output(context.sharedSecret, id.bits),
                        stream,
                    ),
                )
            }
        }
    }

    /**
     * `{type, dataPort, controlPort, streamConnectionID}` on two UDP sockets (CarPlayMediaEngine.kt:177-277); what
     * arrives on the control (RTCP) socket is read and dropped (AudioStream.kt:180-190). The id is echoed as received:
     * an 8-byte id comes back as the same 8 bytes, which is what DiPlay's `unsignedPlistInteger` puts on the wire
     * (its codec writes values from 2^63 as 8-byte ints, spec D §3 deviation 2); written as an unsigned value, our
     * Apple-format writer would send 16 bytes instead.
     */
    private suspend fun openAudio(setup: AudioStreamSetup): Plist {
        val data = context.platform.network.bindUdp()
        val control = context.bindUdpAfter(data)
        val name = "${setup.type}/${setup.audioType}"
        val job =
            context.launchPart("audio stream $name") {
                coroutineScope {
                    val rtcp =
                        launch {
                            while (control.receive() != null) {
                                // RTCP is not used (DiPlay reads and drops it).
                            }
                        }
                    try {
                        context.seams.audio.run(setup, data)
                    } finally {
                        rtcp.cancel()
                    }
                }
            }
        track(name, Open(setup.type, job, setup)) {
            data.close()
            control.close()
        }
        context.log.info(
            "carplay.airplay.stream",
            "type" to setup.type,
            "audioType" to setup.audioType,
            "audioFormat" to "0x" + setup.audioFormat.toString(HEX),
            "dataPort" to data.port,
            "controlPort" to control.port,
        )
        return Plist.dict(
            "type" to setup.type,
            "dataPort" to data.port,
            "controlPort" to control.port,
            "streamConnectionID" to setup.streamConnectionId,
        )
    }

    private fun declined(
        type: Int,
        reason: String,
    ): Plist? {
        context.log.info("carplay.airplay.stream_declined", "type" to type, "reason" to reason)
        return null
    }

    private fun track(
        name: String,
        stream: Open,
        release: () -> Unit,
    ) {
        open[name] = stream
        stream.job.invokeOnCompletion {
            release()
            if (open[name] === stream) open.remove(name)
        }
    }

    /** Closes the streams of [types], or all of them when null; the connection stays (AirPlaySession.kt:823-848). */
    fun teardown(types: List<Int>?) {
        val closing = open.filterValues { types == null || it.type in types }
        closing.forEach { (name, stream) ->
            open.remove(name)
            stream.job.cancel()
        }
        context.log.info("carplay.airplay.teardown", "types" to (types ?: "all"), "closed" to closing.keys.toList())
    }

    /** The open audio streams' `/feedback` entries (CarPlayMediaEngine.kt:489-523). */
    fun feedback(): List<Plist.Dict> =
        open.values.mapNotNull { stream -> stream.audio?.let { context.seams.audio.feedback(it) } }

    fun close() {
        open.values.forEach { it.job.cancel() }
        open.clear()
    }

    companion object {
        const val MAIN_SCREEN = 110
        const val ALT_SCREEN = 111
        const val MAIN_AUDIO = 100
        const val ALT_AUDIO = 101
        const val MAIN_HIGH_AUDIO = 102
        const val MAIN_BUFFERED_AUDIO = 103
        const val DATA = 130

        /** Kinds DiPlay knows (AirPlaySession.kt:1015-1021) that this receiver does not take yet. */
        private val KNOWN_DECLINED = setOf(MAIN_BUFFERED_AUDIO, DATA)

        /** Ten audio kinds (`/info` audioFormats) plus the screen and spares. */
        const val MAX_OPEN_STREAMS = 16
        private const val NO_ID = "no streamConnectionID"
        private const val TOO_MANY = "too many streams"
        private const val HEX = 16

        /**
         * The stream types a TEARDOWN body names, or null for all (DiPlay `teardownStreamTypes`,
         * AirPlaySession.kt:1066-1079): a type-130 entry with a streamID other than 1 is a video-in-car data stream,
         * which must not close the iAP2 tunnel. A body that is not a plist counts as none.
         */
        fun teardownTypes(body: Plist?): List<Int>? {
            val entries =
                ((body as? Plist.Dict)?.array("streams") ?: emptyList()).mapNotNull { entry ->
                    (entry as? Plist.Dict)?.let { stream ->
                        stream.long("type")?.let { type -> type.toInt() to stream.long("streamID") }
                    }
                }
            return entries
                .takeIf { it.isNotEmpty() }
                ?.filterNot { (type, streamId) -> type == DATA && streamId != null && streamId != 1L }
                ?.map { it.first }
        }
    }
}
