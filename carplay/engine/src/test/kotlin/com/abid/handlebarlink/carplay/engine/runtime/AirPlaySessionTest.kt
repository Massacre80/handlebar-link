// Virtual-time controls of kotlinx-coroutines-test (runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.carplay.engine.runtime

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.session.AudioStreamSetup
import com.abid.handlebarlink.carplay.engine.session.AudioStreamSink
import com.abid.handlebarlink.carplay.engine.session.EventCommands
import com.abid.handlebarlink.carplay.engine.session.MediaContext
import com.abid.handlebarlink.carplay.engine.session.MediaFactory
import com.abid.handlebarlink.carplay.engine.session.MediaSeams
import com.abid.handlebarlink.carplay.engine.session.ScreenStreamSetup
import com.abid.handlebarlink.carplay.engine.spi.TcpConnection
import com.abid.handlebarlink.carplay.engine.spi.UdpSocket
import com.abid.handlebarlink.carplay.protocol.crypto.ChaCha20Poly1305
import com.abid.handlebarlink.carplay.protocol.crypto.Ed25519KeyPair
import com.abid.handlebarlink.carplay.protocol.crypto.Hkdf
import com.abid.handlebarlink.carplay.protocol.crypto.X25519KeyPair
import com.abid.handlebarlink.carplay.protocol.iap2.iap2Message
import com.abid.handlebarlink.carplay.protocol.plist.BinaryPlistReader
import com.abid.handlebarlink.carplay.protocol.plist.Plist
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The AirPlay session after pair-verify and auth-setup, against the iPhone's request sequence (spec D §2.6): session
 * SETUP, the event channel, stream SETUP, RECORD, `/feedback`, `/command`, TEARDOWN. Every request body is a binary
 * plist written by Python 3.15's plistlib (`plistlib.dumps(value, fmt=FMT_BINARY, sort_keys=False)`), never by our
 * encoder; the keys are the ones DiPlay reads (AirPlaySession.kt:706-727, CarPlayMediaEngine.kt:177-204). Expected
 * keys are derived here from the iPhone's own shared secret with literal salts.
 */
class AirPlaySessionTest {
    private val random = SecureRandom()
    private val sink = RecordingLogSink()
    private val fake = FakePorts(random)
    private val phoneKeys = Ed25519KeyPair.generate(random)
    private val media = RecordingSeams()
    private val mediaContexts = mutableListOf<MediaContext>()

    private class RecordingSeams {
        class Timing(
            val socket: UdpSocket,
            val peer: String,
            val peerPort: Int,
        )

        class Screen(
            val stream: ScreenStreamSetup,
            val connection: TcpConnection,
            val events: EventCommands,
        )

        val timing = mutableListOf<Timing>()
        val screens = mutableListOf<Screen>()
        val audio = mutableListOf<AudioStreamSetup>()
        val audioEnded = mutableListOf<AudioStreamSetup>()
        var feedback: (AudioStreamSetup) -> Plist.Dict? = { null }

        val seams =
            MediaSeams(
                timing = { socket, peer, port ->
                    timing += Timing(socket, peer, port)
                    awaitCancellation()
                },
                screen = { stream, connection, events ->
                    screens += Screen(stream, connection, events)
                    val buffer = ByteArray(BUFFER)
                    while (connection.read(buffer) >= 0) {
                        // Drained until the iPhone closes the stream.
                    }
                },
                audio =
                    object : AudioStreamSink {
                        override suspend fun run(
                            stream: AudioStreamSetup,
                            data: UdpSocket,
                        ) {
                            audio += stream
                            try {
                                awaitCancellation()
                            } finally {
                                audioEnded += stream
                            }
                        }

                        override fun feedback(stream: AudioStreamSetup): Plist.Dict? = feedback.invoke(stream)
                    },
            )
    }

    private fun TestScope.started(): CarPlayRuntime {
        val time = TestTimeProvider(testScheduler)
        fake.now = { testScheduler.currentTime }
        val env = RuntimeEnv(time, Random(SEED), random, testLogger(sink, time))
        val factory =
            MediaFactory { context ->
                mediaContexts += context
                media.seams
            }
        val link = CarPlayRuntime(fake.ports, StandardTestDispatcher(testScheduler), env, { CarPlayConfig() }, factory)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { link.state.collect {} }
        backgroundScope.launch { link.run() }
        link.start()
        return link
    }

    /** Bluetooth bootstrap (spec B §3), the iPhone joins, connects, pair-verifies and runs auth-setup. */
    private suspend fun TestScope.verified(): PhoneAirPlay {
        val phone = PhoneIap2(fake.dialed.receive())
        phone.handshake()
        phone.send(iap2Message(0x1D00))
        phone.expect(0x1D01)
        phone.send(iap2Message(0x1D02))
        phone.send(iap2Message(0xAA00))
        phone.expect(0xAA01)
        phone.send(iap2Message(0xAA02) { bytes(0, ByteArray(32) { it.toByte() }) })
        phone.expect(0xAA03)
        phone.send(iap2Message(0xAA05))
        phone.send(iap2Message(0x5702))
        phone.expect(0x5703)
        phone.send(iap2Message(0x4300))
        phone.expect(0x4301)
        fake.pairings.save(PHONE_ID, phoneKeys.publicKey)
        val airPlay = PhoneAirPlay(fake.listeners.single().connect(), random)
        check(airPlay.pairVerify(PHONE_ID, phoneKeys))
        val auth = airPlay.request("POST", "/auth-setup", byteArrayOf(1) + X25519KeyPair.generate(random).publicKey)
        check(auth.status == 200)
        runCurrent()
        return airPlay
    }

    private suspend fun PhoneAirPlay.setup(body: String): PhoneResponse =
        request("SETUP", "rtsp://192.168.49.1/4575630486994831421", hex(body), contentType = PLIST)

    private fun PhoneResponse.plist(): Plist.Dict = BinaryPlistReader.read(body) as Plist.Dict

    private fun Plist.Dict.port(key: String): Int = checkNotNull(long(key)).toInt()

    private fun listener(port: Int) = fake.listeners.single { it.port == port }

    private fun udp(port: Int) = fake.udp.single { it.port == port }

    private fun TestScope.failure(link: CarPlayRuntime): CarPlayError? =
        (link.state.value as? CarPlayState.Recovering)?.error

    private fun streamKey(
        airPlay: PhoneAirPlay,
        decimalId: String,
    ) = Hkdf.sha512(
        checkNotNull(airPlay.sharedSecret),
        "DataStream-Salt$decimalId",
        "DataStream-Output-Encryption-Key",
        32,
    )

    @Test
    fun `the session SETUP answers DiPlay's keys and opens the timing, event and keep-alive ports`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val airPlay = verified()

            val response = airPlay.setup(SESSION_SETUP)
            runCurrent()

            assertThat(response.status).isEqualTo(200)
            assertThat(response.headers["content-type"]).isEqualTo(PLIST)
            val reply = response.plist()
            assertThat(reply.entries.keys.toList())
                .containsExactly("timingPort", "eventPort", "keepAlivePort", "enabledFeatures")
            // Proposed cornerMasks, videoPlayback and mainBuffered stay off: this receiver offers none of them.
            assertThat(
                reply.array("enabledFeatures"),
            ).isEqualTo(listOf(Plist.Text("iAPChannel"), Plist.Text("viewAreas")))
            val timing = media.timing.single()
            assertThat(timing.socket.port).isEqualTo(reply.port("timingPort"))
            assertThat(timing.peer).isEqualTo("192.168.49.2")
            assertThat(timing.peerPort).isEqualTo(51234)
            assertThat(udp(reply.port("timingPort")).closed).isFalse()
            assertThat(udp(reply.port("keepAlivePort")).closed).isFalse()
            assertThat(listener(reply.port("eventPort")).closed).isFalse()
            assertThat(fake.listenedOn).containsExactly("192.168.49.1", "fe80::1%p2p-wlan0-0")
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
            // Key names are logged, never values.
            assertThat(
                sink
                    .events("carplay.airplay.setup_keys")
                    .single()
                    .fields
                    .single()
                    .second
                    .toString(),
            ).isEqualTo(
                "[deviceID, features, keepAliveLowPower, macAddress, model, name, osVersion, sourceVersion, " +
                    "timingPort, timingProtocol]",
            )
        }

    @Test
    fun `a session SETUP without timingPort or keep-alive asks nothing of the iPhone's clock`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()

            val reply = airPlay.setup(SESSION_SETUP_MODEL_ONLY).plist()
            runCurrent()

            assertThat(reply.entries.keys.toList()).containsExactly("timingPort", "eventPort", "enabledFeatures")
            assertThat(media.timing.single().peerPort).isEqualTo(0)
        }

    @Test
    fun `the event channel is encrypted with the events keys in both directions`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            val eventPort = airPlay.setup(SESSION_SETUP).plist().port("eventPort")
            val screenPort =
                (
                    airPlay
                        .setup(
                            STREAM_SETUP,
                        ).plist()
                        .array("streams")!!
                        .first() as Plist.Dict
                ).port("dataPort")
            val events = PhoneEvents(listener(eventPort).connect(), checkNotNull(airPlay.sharedSecret))
            listener(screenPort).connect()
            runCurrent()

            events.send("POST /command RTSP/1.0\r\nCSeq: 41\r\nContent-Length: 0\r\n\r\n")
            val answer = events.read()
            val sent =
                backgroundScope.launch {
                    media.screens.single().events.send(
                        Plist.dict("type" to "forceKeyFrame"),
                    )
                }
            val command = events.read()
            runCurrent()

            assertThat(answer.startLine).isEqualTo("RTSP/1.0 200 OK")
            assertThat(answer.headers["cseq"]).isEqualTo("41")
            // DiPlay's request head, in its order (AirPlaySession.kt:332-335), CSeq counting from 1.
            assertThat(command.head).isEqualTo(
                "POST /command RTSP/1.0\r\nContent-Type: $PLIST\r\nContent-Length: ${hex(
                    FORCE_KEY_FRAME,
                ).size}\r\nCSeq: 1",
            )
            assertThat(command.body.toHex()).isEqualTo(FORCE_KEY_FRAME)
            assertThat(sent.isCompleted).isTrue()
        }

    @Test
    fun `the stream SETUP answers screen, audio and the iAP2 tunnel in request order and declines the rest`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            airPlay.setup(SESSION_SETUP)

            val response = airPlay.setup(STREAM_SETUP)
            runCurrent()

            assertThat(response.status).isEqualTo(200)
            val streams = checkNotNull(response.plist().array("streams")).map { it as Plist.Dict }
            assertThat(streams.map { it.long("type") }).containsExactly(110L, 100L, 101L, 130L)
            assertThat(streams[3].entries.keys.toList()).containsExactly("type", "streamID", "dataPort")
            assertThat(streams[0].entries.keys.toList()).containsExactly("type", "dataPort")
            assertThat(streams[1].entries.keys.toList())
                .containsExactly("type", "dataPort", "controlPort", "streamConnectionID")
            // The iPhone's 8-byte id comes back bit for bit.
            assertThat(
                streams[1]["streamConnectionID"],
            ).isEqualTo(Plist.Integer(BigInteger.valueOf(-1234567890123456789)))
            assertThat(streams[2].long("streamConnectionID")).isEqualTo(77L)
            assertThat(listener(streams[0].port("dataPort")).closed).isFalse()
            // The session reads and drops RTCP itself (DiPlay AudioStream.kt:180-190).
            udp(streams[1].port("controlPort")).deliver(ByteArray(8))
            runCurrent()
            assertThat(udp(streams[1].port("controlPort")).received).isEqualTo(1)
            // One media context per verified session, with the platform's cipher.
            assertThat(mediaContexts.single().cipherName).isEqualTo(ChaCha20Poly1305.Transformation.JVM)
            listOf(1, 2).forEach {
                assertThat(udp(streams[it].port("dataPort")).closed).isFalse()
                assertThat(udp(streams[it].port("controlPort")).closed).isFalse()
            }
            val (media100, alt101) = media.audio
            assertThat(media100.audioType).isEqualTo("media")
            assertThat(media100.audioFormat).isEqualTo(0x8000L)
            assertThat(media100.request.long("audioLatencyMs")).isEqualTo(50L)
            assertThat(media100.key.toHex()).isEqualTo(streamKey(airPlay, "17212176183586094827").toHex())
            assertThat(alt101.audioType).isEqualTo("default")
            assertThat(alt101.key.toHex()).isEqualTo(streamKey(airPlay, "77").toHex())
            assertThat(sink.events("carplay.airplay.stream_declined").map { it.fields.first().second })
                .containsExactly(103, 999, 110)

            listener(streams[0].port("dataPort")).connect()
            runCurrent()
            assertThat(
                media.screens
                    .single()
                    .stream.key
                    .toHex(),
            ).isEqualTo(streamKey(airPlay, "4575630486994831421").toHex())
            assertThat(
                media.screens
                    .single()
                    .stream.type,
            ).isEqualTo(110)
        }

    @Test
    fun `a screen connection from anyone but the iPhone is closed unread`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            airPlay.setup(SESSION_SETUP)
            val screenPort =
                (
                    airPlay
                        .setup(
                            STREAM_SETUP,
                        ).plist()
                        .array("streams")!!
                        .first() as Plist.Dict
                ).port("dataPort")

            val stranger = listener(screenPort).connect(from = "192.168.49.9")
            runCurrent()

            assertThat(stranger.read(ByteArray(1))).isEqualTo(-1)
            assertThat(media.screens).isEmpty()
            assertThat(sink.events("carplay.airplay.foreign_peer").size).isEqualTo(1)

            listener(screenPort).connect()
            runCurrent()
            assertThat(media.screens.size).isEqualTo(1)
        }

    @Test
    fun `TEARDOWN of the screen closes only the screen, a bare TEARDOWN closes every stream, the session goes on`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val airPlay = verified()
            val eventPort = airPlay.setup(SESSION_SETUP).plist().port("eventPort")
            val streams =
                airPlay
                    .setup(STREAM_SETUP)
                    .plist()
                    .array("streams")!!
                    .map { it as Plist.Dict }
            val screen = listener(streams[0].port("dataPort")).connect()
            runCurrent()

            val first = airPlay.request("TEARDOWN", "rtsp://192.168.49.1/1", hex(TEARDOWN_SCREEN), contentType = PLIST)
            runCurrent()

            assertThat(first.status).isEqualTo(200)
            assertThat(screen.read(ByteArray(1))).isEqualTo(-1)
            assertThat(media.audioEnded).isEmpty()
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()

            val all = airPlay.request("TEARDOWN", "rtsp://192.168.49.1/1")
            runCurrent()

            assertThat(all.status).isEqualTo(200)
            assertThat(media.audioEnded.map { it.type }).containsExactly(100, 101)
            listOf(1, 2).forEach { assertThat(udp(streams[it].port("dataPort")).closed).isTrue() }
            // The session's own channels stay (DiPlay tears down media only, AirPlaySession.kt:823-848).
            assertThat(listener(eventPort).closed).isFalse()
            assertThat(airPlay.request("GET_PARAMETER", "rtsp://192.168.49.1/1").status).isEqualTo(200)
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
        }

    @Test
    fun `a TEARDOWN of a video-in-car data stream closes nothing else`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            airPlay.setup(SESSION_SETUP)
            airPlay.setup(STREAM_SETUP)

            airPlay.request("TEARDOWN", "rtsp://192.168.49.1/1", hex(TEARDOWN_DATA_STREAM_2), contentType = PLIST)
            runCurrent()

            assertThat(media.audioEnded).isEmpty()
        }

    @Test
    fun `the screen stream ending without TEARDOWN ends the session, as DiPlay`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val airPlay = verified()
            airPlay.setup(SESSION_SETUP)
            val streams =
                airPlay
                    .setup(STREAM_SETUP)
                    .plist()
                    .array("streams")!!
                    .map { it as Plist.Dict }
            val screen = listener(streams[0].port("dataPort")).connect()
            runCurrent()

            screen.close()
            runCurrent()

            assertThat(failure(link)).isEqualTo(CarPlayError.PhoneDisconnected("screen stream 110 ended"))
            assertThat(fake.udp.all { it.closed }).isTrue()
            assertThat(fake.listeners.all { it.closed }).isTrue()
        }

    @Test
    fun `an event frame that does not authenticate ends the session`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val airPlay = verified()
            val eventPort = airPlay.setup(SESSION_SETUP).plist().port("eventPort")
            val events = PhoneEvents(listener(eventPort).connect(), checkNotNull(airPlay.sharedSecret))
            runCurrent()
            assertThat(sink.events("carplay.airplay.event_connected").size).isEqualTo(1)

            // A 4-byte frame whose ciphertext and tag are zeros cannot authenticate under any key.
            events.sendRaw(byteArrayOf(4, 0) + ByteArray(20))
            runCurrent()

            assertThat(failure(link)).isEqualTo(CarPlayError.PhoneDisconnected("event frame failed authentication"))
            assertThat(fake.udp.all { it.closed }).isTrue()
        }

    @Test
    fun `the event channel closing ends the session`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val airPlay = verified()
            val eventPort = airPlay.setup(SESSION_SETUP).plist().port("eventPort")
            val events = PhoneEvents(listener(eventPort).connect(), checkNotNull(airPlay.sharedSecret))
            runCurrent()

            events.close()
            runCurrent()

            assertThat(failure(link)).isEqualTo(CarPlayError.PhoneDisconnected("event channel closed"))
        }

    @Test
    fun `feedback is an empty 200 until an audio stream reports, then lists the reports`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            airPlay.setup(SESSION_SETUP)
            airPlay.setup(STREAM_SETUP)

            val quiet = airPlay.request("POST", "/feedback")
            media.feedback = { Plist.dict("type" to it.type, "sampleRate" to 48_000) }
            val reporting = airPlay.request("POST", "/feedback")

            assertThat(quiet.status).isEqualTo(200)
            assertThat(quiet.body.size).isEqualTo(0)
            assertThat(reporting.headers["content-type"]).isEqualTo(PLIST)
            val entries = checkNotNull(reporting.plist().array("streams")).map { (it as Plist.Dict).long("type") }
            assertThat(entries).containsExactly(100L, 101L)
        }

    @Test
    fun `RECORD, command, SET_PARAMETER, GET_PARAMETER and unknown routes get an empty 200 and keep the session`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val airPlay = verified()
            airPlay.setup(SESSION_SETUP)

            val answers =
                listOf(
                    airPlay.request("RECORD", "rtsp://192.168.49.1/1"),
                    airPlay.request("POST", "/command", hex(COMMAND), contentType = PLIST),
                    airPlay.request("SET_PARAMETER", "rtsp://192.168.49.1/1", hex(COMMAND), contentType = PLIST),
                    airPlay.request("GET_PARAMETER", "rtsp://192.168.49.1/1"),
                    airPlay.request("OPTIONS", "*"),
                    airPlay.request("POST", "/command", byteArrayOf(1, 2, 3)),
                )
            runCurrent()

            assertThat(answers.map { it.status to it.body.size }).isEqualTo(List(answers.size) { 200 to 0 })
            assertThat(sink.events("carplay.airplay.record").size).isEqualTo(1)
            assertThat(
                sink
                    .events("carplay.airplay.command")
                    .first()
                    .fields
                    .first()
                    .second,
            ).isEqualTo("disableBluetooth")
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
        }

    @Test
    fun `a SETUP that is not a plist gets 400, one whose port cannot be opened 500, and the session goes on`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val airPlay = verified()
            fake.udpFailsAfter = 1

            val garbage = airPlay.request("SETUP", "rtsp://192.168.49.1/1", byteArrayOf(1, 2, 3), contentType = PLIST)
            val noPort = airPlay.setup(SESSION_SETUP)
            runCurrent()

            assertThat(garbage.status).isEqualTo(400)
            assertThat(noPort.status).isEqualTo(500)
            // What the failed SETUP had opened (the timing socket, the event listener) is closed again.
            assertThat(fake.udp.single().closed).isTrue()
            assertThat(fake.listeners.filter { it.port != 7000 }.all { it.closed }).isTrue()
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()

            fake.udpFailsAfter = null
            assertThat(airPlay.setup(SESSION_SETUP).status).isEqualTo(200)
        }

    @Test
    fun `SETUP before pair-verify is refused with 470 and opens nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val phone = PhoneIap2(fake.dialed.receive())
            phone.handshake()
            phone.send(iap2Message(0x1D00))
            phone.expect(0x1D01)
            phone.send(iap2Message(0x1D02))
            phone.send(iap2Message(0xAA00))
            phone.expect(0xAA01)
            phone.send(iap2Message(0xAA02) { bytes(0, ByteArray(32) { it.toByte() }) })
            phone.expect(0xAA03)
            phone.send(iap2Message(0xAA05))
            phone.send(iap2Message(0x5702))
            phone.expect(0x5703)
            phone.send(iap2Message(0x4300))
            phone.expect(0x4301)
            val airPlay = PhoneAirPlay(fake.listeners.single().connect(), random)

            val refused = airPlay.setup(SESSION_SETUP)

            assertThat(refused.status).isEqualTo(470)
            assertThat(fake.udp).isEmpty()
            assertThat(fake.listeners.size).isEqualTo(1)
        }

    @Test
    fun `the control connection closing closes every port the session opened`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val airPlay = verified()
            airPlay.setup(SESSION_SETUP)
            airPlay.setup(STREAM_SETUP)
            runCurrent()

            airPlay.close()
            runCurrent()

            assertThat(failure(link)).isEqualTo(CarPlayError.PhoneDisconnected("control connection closed"))
            assertThat(fake.udp.size).isEqualTo(6)
            assertThat(fake.udp.all { it.closed }).isTrue()
            assertThat(fake.listeners.all { it.closed }).isTrue()
            assertThat(media.audioEnded.size).isEqualTo(2)
        }

    @Test
    fun `at most 16 streams are open, the rest are declined`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            airPlay.setup(SESSION_SETUP)

            val streams = checkNotNull(airPlay.setup(TWENTY_AUDIO_STREAMS).plist().array("streams"))
            runCurrent()

            assertThat(streams.size).isEqualTo(16)
            assertThat(sink.events("carplay.airplay.stream_declined").size).isEqualTo(4)
            assertThat(fake.udp.count { !it.closed }).isEqualTo(2 + 32)
        }

    @Test
    fun `a stream SETUP of the same audio stream replaces the open one`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            airPlay.setup(SESSION_SETUP)
            val first =
                (
                    airPlay
                        .setup(AUDIO_ONLY)
                        .plist()
                        .array("streams")!!
                        .single() as Plist.Dict
                ).port("dataPort")

            val second =
                (
                    airPlay
                        .setup(AUDIO_ONLY)
                        .plist()
                        .array("streams")!!
                        .single() as Plist.Dict
                ).port("dataPort")
            runCurrent()

            assertThat(udp(first).closed).isTrue()
            assertThat(udp(second).closed).isFalse()
            assertThat(media.audioEnded.size).isEqualTo(1)
        }

    @Test
    fun `the info plist declares DiPlay's audio latencies, audio formats and HID devices`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()

            val info = airPlay.request("GET", "/info").plist()

            val latencies = checkNotNull(info.array("audioLatencies")).map { it as Plist.Dict }
            assertThat(latencies.map { it.long("type") to it.text("audioType") }).containsExactly(
                100L to null,
                100L to "default",
                100L to "media",
                100L to "telephony",
                100L to "speechRecognition",
                100L to "alert",
                101L to null,
                101L to "default",
                102L to "default",
            )
            assertThat(latencies.all { it.long("inputLatencyMicros") == 0L && it.long("outputLatencyMicros") == 0L })
                .isTrue()
            val formats = checkNotNull(info.array("audioFormats")).map { it as Plist.Dict }
            // DiPlay's bits at 48 kHz without a microphone (AirPlayInfoPlist.kt:144-172; spec D §4, tests 203-253).
            assertThat(formats.map { Triple(it.long("type"), it.text("audioType"), it.long("audioOutputFormats")) })
                .containsExactly(
                    Triple(100L, "compatibility", 0xc3fcL),
                    Triple(101L, "compatibility", 0xc3fcL),
                    Triple(100L, "default", 0x7000c3fcL),
                    Triple(100L, "alert", 0x7000c3fcL),
                    Triple(100L, "media", 0xc3fcL),
                    Triple(100L, "telephony", 0x70004154L),
                    Triple(100L, "speechRecognition", 0x70004154L),
                    Triple(101L, "default", 0x7000c3fcL),
                    Triple(102L, "media", 0x800000L),
                )
            assertThat(formats.all { it["audioInputFormats"] == null }).isTrue()
            val display = (info.array("displays")!!.single() as Plist.Dict).text("uuid")
            val hid = checkNotNull(info.array("hidDevices")).map { it as Plist.Dict }
            assertThat(hid.map { it.text("uuid") }).containsExactly("2a2a2a2a", "2a2a2a2b", "2a2a2a2c", "2a2a2a2d")
            assertThat(hid.all { it.text("displayUUID") == display && it.long("hidVendorID") == 2L }).isTrue()
            assertThat(hid[0].entries.keys.toList()).containsExactly(
                "hidProductID",
                "hidVendorID",
                "hidCountryCode",
                "uuid",
                "name",
                "displayUUID",
                "hidDescriptor",
            )
            assertThat(checkNotNull(hid[0].data("hidDescriptor")).toHex()).isEqualTo(TOUCH_1280_720)
            assertThat(checkNotNull(hid[2].data("hidDescriptor")).toHex()).isEqualTo(MEDIA_KEYS)
            assertThat(
                sink
                    .events("carplay.airplay.info")
                    .single()
                    .fields
                    .map { it.second },
            ).containsExactly(9, 9, 4)
            assertThat(info["oemIcons"]).isNull()
        }

    private companion object {
        const val SEED = 7
        const val BUFFER = 4096
        const val PHONE_ID = "iphone-pairing-id"
        const val PLIST = "application/x-apple-binary-plist"
        val TEST_TIMEOUT = 20.seconds

        /**
         * {deviceID, macAddress, model, name, osVersion, sourceVersion, timingPort 51234, timingProtocol "NTP",
         * keepAliveLowPower true, features [cornerMasks, videoPlayback, mainBuffered]}: the session SETUP keys DiPlay
         * reads (AirPlaySession.kt:706-725), from plistlib.
         */
        const val SESSION_SETUP =
            "62706c6973743030da0102030405060708090a0b0c0d0e0f10111213145864657669636549445a6d6163416464726573" +
                "73556d6f64656c546e616d65596f7356657273696f6e5d736f7572636556657273696f6e5a74696d696e67506f72745e" +
                "74696d696e6750726f746f636f6c5f10116b656570416c6976654c6f77506f7765725866656174757265735f10113131" +
                "3a32323a33333a34343a35353a36365f101141413a42423a43433a30303a31313a32325a6950686f6e6531352c325b54" +
                "657374206950686f6e655432362e31573930302e372e3111c822534e545009a31516175b636f726e65724d61736b735d" +
                "766964656f506c61796261636b5c6d61696e42756666657265640008001d002600310037003c00460054005f006e0082" +
                "008b009f00b300be00ca00cf00d700da00de00df00e300ef00fd00000000000002010000000000000018000000000000" +
                "0000000000000000010a"

        /** {model: "iPhone15,2"}, from plistlib. */
        const val SESSION_SETUP_MODEL_ONLY =
            "62706c6973743030d10102556d6f64656c5a6950686f6e6531352c32080b110000000000000101000000000000000300" +
                "00000000000000000000000000001c"

        /**
         * {streams: [{type 110, streamConnectionID 4575630486994831421, timestampInfo [...]}, {type 100, audioType
         * media, audioFormat 0x8000, streamConnectionID -1234567890123456789 (8 bytes), audioLatencyMs 50}, {type 101,
         * audioType default, audioFormat 0x40000000, streamConnectionID 77}, {type 130, clientTypeUUID E9459FD0-…,
         * seed 12345}, {type 103, …}, {type 999}, {type 110 without streamConnectionID}]}, from plistlib.
         */
        const val STREAM_SETUP =
            "62706c6973743030d101025773747265616d73a7030d161b212527d304050607080954747970655f101273747265616d" +
                "436f6e6e656374696f6e49445d74696d657374616d70496e666f106e133f7fe7af68c6483da10ad10b0c546e616d6555" +
                "5375625375d5040e0f0510111213141559617564696f547970655b617564696f466f726d61745e617564696f4c617465" +
                "6e63794d731064556d6564696111800013eeddef0b82167eeb1032d4040e0f051718191a10655764656661756c741240" +
                "000000104dd3041c1d1e1f205e636c69656e745479706555554944547365656410825f102445393435394644302d4243" +
                "41442d344334352d383230462d314537323434374546324632113039d3040f05222324106712008000001009d1042611" +
                "03e7d104070008000b0013001b00220027003c004a004c00550057005a005f00650070007a008600950097009d00a000" +
                "a900ab00b400b600be00c300c500cc00db00e000e20109010c01130115011a011c011f01220000000000000201000000" +
                "000000002800000000000000000000000000000125"

        /** {streams: [{type 100, audioType media, audioFormat 0x8000, streamConnectionID 5}]}, from plistlib. */
        const val AUDIO_ONLY =
            "62706c6973743030d101025773747265616d73a103d40405060708090a0b547479706559617564696f547970655b6175" +
                "64696f466f726d61745f101273747265616d436f6e6e656374696f6e49441064556d656469611180001005080b13151e" +
                "232d394e5056590000000000000101000000000000000c0000000000000000000000000000005b"

        /** {streams: [{type 100, audioType "t0".."t19", audioFormat 0x8000, streamConnectionID 1..20}]}, plistlib. */
        const val TWENTY_AUDIO_STREAMS =
            "62706c6973743030d101025773747265616d73af1014030c0f1215181b1e2124272a2d303336393c3f42d40405060708" +
                "090a0b547479706559617564696f547970655b617564696f466f726d61745f101273747265616d436f6e6e656374696f" +
                "6e494410645274301180001001d404050607080d0a0e5274311002d40405060708100a115274321003d4040506070813" +
                "0a145274331004d40405060708160a175274341005d40405060708190a1a5274351006d404050607081c0a1d52743610" +
                "07d404050607081f0a205274371008d40405060708220a235274381009d40405060708250a26527439100ad404050607" +
                "08280a2953743130100bd404050607082b0a2c53743131100cd404050607082e0a2f53743132100dd40405060708310a" +
                "3253743133100ed40405060708340a3553743134100fd40405060708370a38537431351010d404050607083a0a3b5374" +
                "31361011d404050607083d0a3e537431371012d40405060708400a41537431381013d40405060708430a445374313910" +
                "140008000b0013002a003300380042004e006300650068006b006d00760079007b00840087008900920095009700a000" +
                "a300a500ae00b100b300bc00bf00c100ca00cd00cf00d800db00dd00e600e900eb00f400f800fa010301070109011201" +
                "160118012101250127013001340136013f01430145014e01520154015d01610163016c01700172017b017f0000000000" +
                "000201000000000000004500000000000000000000000000000181"

        /** {streams: [{type 110}]}, from plistlib. */
        const val TEARDOWN_SCREEN =
            "62706c6973743030d101025773747265616d73a103d104055474797065106e080b1315181d0000000000000101000000" +
                "00000000060000000000000000000000000000001f"

        /** {streams: [{type 130, streamID 2}]} (a video-in-car data stream), from plistlib. */
        const val TEARDOWN_DATA_STREAM_2 =
            "62706c6973743030d101025773747265616d73a103d20405060754747970655873747265616d494410821002080b1315" +
                "1a1f282a000000000000010100000000000000080000000000000000000000000000002c"

        /** {type: "disableBluetooth", params: {}}, from plistlib. */
        const val COMMAND =
            "62706c6973743030d201020304547479706556706172616d735f101064697361626c65426c7565746f6f7468d0080d12" +
                "192c000000000000010100000000000000050000000000000000000000000000002d"

        /** {type: "forceKeyFrame"}, from plistlib. */
        const val FORCE_KEY_FRAME =
            "62706c6973743030d1010254747970655d666f7263654b65794672616d65080b10000000000000010100000000000000" +
                "030000000000000000000000000000001e"

        /**
         * DiPlay's two-finger touchscreen descriptor (AirPlayHid.kt:89-104) for 1280×720, transcribed from its source:
         * logical maxima 0x0500 and 0x02d0, little-endian.
         */
        const val TOUCH_1280_720 =
            "050d0904a101050d0922a102093875089501810215002501093375019501810295078103050126000509307510950181" +
                "0226d00209318102c0050d0922a102093875089501810215002501093375019501810295078103050126000509307510" +
                "9501810226d00209318102c0c0"

        /** DiPlay's media key descriptor (AirPlayHid.kt:117-123), transcribed. */
        const val MEDIA_KEYS =
            "050c0901a10115002506050c0a00000ab0000ab1000acd000ab5000ab6000a9e02750895018100c0"

        fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
