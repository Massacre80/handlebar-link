// Virtual-time controls of kotlinx-coroutines-test (runCurrent, advanceTimeBy).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.carplay.engine.runtime

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.spi.AudioStreamInfo
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneFormat
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneOffer
import com.abid.handlebarlink.carplay.protocol.audio.AudioCodec
import com.abid.handlebarlink.carplay.protocol.audio.AudioFormat
import com.abid.handlebarlink.carplay.protocol.crypto.Ed25519KeyPair
import com.abid.handlebarlink.carplay.protocol.crypto.X25519KeyPair
import com.abid.handlebarlink.carplay.protocol.iap2.iap2Message
import com.abid.handlebarlink.carplay.protocol.plist.BinaryPlistReader
import com.abid.handlebarlink.carplay.protocol.plist.Plist
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Test
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * CarPlay audio and the microphone (C5) behind the AirPlay session, against a simulated iPhone that seals and opens its
 * media with BouncyCastle (HKDF-SHA512, ChaCha20-Poly1305), never with our protocol code. Downlink layout:
 * AudioStream.kt:114-154; uplink layout: MicrophonePacketizer.kt:95-139; SETUP and command bodies from Python's
 * plistlib; `/info` input formats from DiPlay's AirPlayInfoPlistTest (spec D §8: 0x70004154 with Opus, 0x4154 without).
 */
class AudioStreamsTest {
    private val random = SecureRandom()
    private val sink = RecordingLogSink()
    private val fake = FakePorts(random)
    private val phoneKeys = Ed25519KeyPair.generate(random)
    private val players get() = fake.audio.players

    private fun TestScope.started(): CarPlayRuntime {
        val time = TestTimeProvider(testScheduler)
        fake.now = { testScheduler.currentTime }
        val env = RuntimeEnv(time, Random(SEED), random, testLogger(sink, time))
        val link = CarPlayRuntime(fake.ports, StandardTestDispatcher(testScheduler), env, { CarPlayConfig() })
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { link.state.collect {} }
        backgroundScope.launch { link.run() }
        link.start()
        return link
    }

    /** Bluetooth bootstrap, the iPhone joins, pair-verifies and runs auth-setup (as MediaReceiversTest). */
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
        airPlay.request("SETUP", RTSP_URL, hex(SESSION_SETUP), contentType = PLIST)
        runCurrent()
        return airPlay
    }

    private suspend fun PhoneAirPlay.streams(body: String): List<Plist.Dict> {
        val reply = request("SETUP", RTSP_URL, hex(body), contentType = PLIST)
        return checkNotNull(
            (BinaryPlistReader.read(reply.body) as Plist.Dict).array("streams"),
        ).map { it as Plist.Dict }
    }

    private suspend fun PhoneAirPlay.command(body: String) {
        check(request("POST", "/command", hex(body), contentType = PLIST).status == 200)
    }

    private fun Plist.Dict.dataPort(): FakePorts.FakeUdp =
        fake.udp.single {
            it.port ==
                checkNotNull(long("dataPort")).toInt()
        }

    /** One datagram as the iPhone seals it: RTP header | ciphertext | tag | nonce tail (AudioStream.kt:131-139). */
    private fun PhoneAirPlay.audio(
        id: Long,
        sequence: Int,
        timestamp: Long,
        payload: String,
    ): ByteArray {
        val rtp = hex("8060%04x%08x11223344".format(sequence, timestamp))
        val tail = ByteArray(8) { (it + sequence).toByte() }
        val key = streamKey(checkNotNull(sharedSecret), id, OUTPUT_KEY)
        return rtp + seal(key, ByteArray(4) + tail, rtp.copyOfRange(4, 12), hex(payload)) + tail
    }

    private fun failure(link: CarPlayRuntime): CarPlayError? = (link.state.value as? CarPlayState.Recovering)?.error

    @Test
    fun `decrypted packets reach one player per stream in arrival order, opened at the first packet`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            val (music, high) = airPlay.streams(MUSIC_SETUP)
            runCurrent()
            assertThat(players).isEmpty()

            val data = music.dataPort()
            data.deliver(airPlay.audio(79, 7, 0xFFFFFC00, "216c4f5510"))
            data.deliver(airPlay.audio(79, 9, 0xFFFFFE00, "a1"))
            data.deliver(airPlay.audio(79, 8, 0xFFFFFD00, "b2b2").also { it[13] = (it[13] + 1).toByte() })
            data.deliver(airPlay.audio(79, 8, 0xFFFFFD00, "c3c3"))
            runCurrent()

            // DiPlay's stream identity and decode format (AudioStream.kt:221-242): 0x8000 is 48 kHz stereo PCM.
            assertThat(players.map { it.stream }).containsExactly(
                AudioStreamInfo(100, "media", AudioFormat(AudioCodec.LPCM, 48_000, 2, 48_000), 50),
            )
            // In arrival order (the player reorders); the one that did not authenticate never arrives.
            assertThat(players.single().packets).containsExactly(
                "7/4294966272/216c4f5510",
                "9/4294966784/a1",
                "8/4294966528/c3c3",
            )
            high.dataPort().deliver(airPlay.audio(80, 1, 0, "21"))
            runCurrent()
            assertThat(players[1].stream).isEqualTo(
                AudioStreamInfo(102, "media", AudioFormat(AudioCodec.AAC_LC, 48_000, 2, 48_000), 0),
            )
        }

    @Test
    fun `TEARDOWN and a replacing SETUP close the players, and nothing reaches a closed player`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            val first = airPlay.streams(MUSIC_SETUP).first().dataPort()
            first.deliver(airPlay.audio(79, 1, 0, "01"))
            runCurrent()

            val second = airPlay.streams(MUSIC_SETUP).first().dataPort()
            runCurrent()
            assertThat(players.single().closes).isEqualTo(1)
            assertThat(first.closed).isTrue()

            second.deliver(airPlay.audio(79, 2, 0, "02"))
            runCurrent()
            assertThat(players.map { it.packets.size }).containsExactly(1, 1)

            airPlay.request("TEARDOWN", RTSP_URL, hex(TEARDOWN_MAIN_AUDIO), contentType = PLIST)
            runCurrent()
            assertThat(players.map { it.closes }).containsExactly(1, 1)
            assertThat(second.closed).isTrue()
        }

    @Test
    fun `a player that fails silences only its stream, the session and feedback go on`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val airPlay = verified()
            val data = airPlay.streams(MUSIC_SETUP).first().dataPort()
            fake.audio.failPackets = true

            data.deliver(airPlay.audio(79, 1, 1000, "01"))
            data.deliver(airPlay.audio(79, 2, 2000, "02"))
            runCurrent()

            assertThat(players.single().closes).isEqualTo(1)
            assertThat(sink.events("carplay.audio.player_failed").size).isEqualTo(1)
            assertThat(failure(link)).isNull()
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
            val feedback = airPlay.request("POST", "/feedback")
            val entry = checkNotNull((BinaryPlistReader.read(feedback.body) as Plist.Dict).array("streams")).first()
            assertThat((entry as Plist.Dict).long("sampleTime") != null).isTrue()
        }

    @Test
    fun `info offers the microphone with Opus when the port can encode it`() =
        runTest(
            timeout = TEST_TIMEOUT,
        ) { assertThat(inputFormats(MicrophoneOffer.PCM_AND_OPUS)).isEqualTo(0x70004154L) }

    @Test
    fun `info offers a PCM-only microphone when the port cannot encode Opus`() =
        runTest(timeout = TEST_TIMEOUT) { assertThat(inputFormats(MicrophoneOffer.PCM)).isEqualTo(0x4154L) }

    @Test
    fun `info offers no microphone when the port offers none`() =
        runTest(timeout = TEST_TIMEOUT) { assertThat(inputFormats(MicrophoneOffer.NONE)).isNull() }

    /**
     * The one `audioInputFormats` that default, telephony and speechRecognition share in `/info` (null when absent),
     * with the port offering [offer].
     */
    private suspend fun TestScope.inputFormats(offer: MicrophoneOffer): Long? {
        fake.microphone.offer = offer
        started()
        val airPlay = verified()
        val info = BinaryPlistReader.read(airPlay.request("GET", "/info").body) as Plist.Dict
        val entries = checkNotNull(info.array("audioFormats")).map { it as Plist.Dict }
        return listOf("default", "telephony", "speechRecognition")
            .map { audioType ->
                entries
                    .single {
                        it.long(
                            "type",
                        ) == 100L && it.text("audioType") == audioType
                    }.long("audioInputFormats")
            }.distinct()
            .single()
    }

    @Test
    fun `a port whose offer fails offers no microphone and CarPlay still connects`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.microphone.offerError = CarPlayError.PermissionMissing("RECORD_AUDIO")
            val link = started()
            val airPlay = verified()
            val info = BinaryPlistReader.read(airPlay.request("GET", "/info").body) as Plist.Dict

            val telephony =
                checkNotNull(info.array("audioFormats")).map { it as Plist.Dict }.single {
                    it.text("audioType") == "telephony"
                }
            assertThat(telephony.long("audioInputFormats")).isNull()
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
            assertThat(sink.events("carplay.audio.microphone_offer").single().fields)
                .isEqualTo(listOf("offer" to MicrophoneOffer.NONE))
        }

    @Test
    fun `a call's microphone waits for its downlink, then sends DiPlay's sealed big-endian packets to the iPhone`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.microphone.offer = MicrophoneOffer.PCM_AND_OPUS
            started()
            val airPlay = verified()
            val call = airPlay.streams(TELEPHONY_SETUP).single()
            advanceTimeBy(5_000)
            runCurrent()
            assertThat(fake.microphone.formats).isEmpty()

            call.dataPort().deliver(airPlay.audio(77, 1, 0, "0102"))
            runCurrent()
            // 0x10 is 16 kHz mono PCM; 20 ms fits one MTU (MicrophonePacketizer.kt:56-67).
            assertThat(fake.microphone.formats).containsExactly(
                MicrophoneFormat("telephony", AudioCodec.LPCM, 16_000, 1, 20, 0),
            )
            val bodies = listOf("34127856", "341201", "a0b0")
            bodies.forEach { fake.microphone.bodies.send(hex(it)) }
            runCurrent()

            val uplink = fake.udp.last()
            assertThat(uplink.sent.map { it.address to it.port }.distinct()).containsExactly("192.168.49.2" to 50_123)
            // DiPlay MicrophonePacketizer: 0x80, payload type 100, sequence and timestamp (+320 per 20 ms at 16 kHz),
            // SSRC 0, the payload big-endian (MicrophonePacketizerTest.kt:45-52), nonce = counter, LE tail.
            val key = streamKey(checkNotNull(airPlay.sharedSecret), 77, INPUT_KEY)
            val wire = listOf("12345678", "123401", "b0a0")
            val expected =
                wire.mapIndexed { index, body ->
                    val header = hex("8064%04x%08x00000000".format(index, index * 320))
                    val nonce = ByteArray(4) + ByteArray(8) { if (it == 0) index.toByte() else 0 }
                    (header + seal(key, nonce, header.copyOfRange(4, 12), hex(body)) + nonce.copyOfRange(4, 12)).toHex()
                }
            assertThat(uplink.sent.map { it.bytes.toHex() }).isEqualTo(expected)
        }

    @Test
    fun `Siri's Opus microphone starts half a second after SETUP without downlink and counts at the format's clock`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.microphone.offer = MicrophoneOffer.PCM_AND_OPUS
            started()
            val airPlay = verified()
            airPlay.streams(SIRI_SETUP)
            advanceTimeBy(499)
            runCurrent()
            assertThat(fake.microphone.formats).isEmpty()
            advanceTimeBy(1)
            runCurrent()

            // 0x20000000: Opus captured at 48 kHz for 20 ms, 64 kbit/s; the RTP clock moves 480 (24 kHz) per packet.
            assertThat(fake.microphone.formats).containsExactly(
                MicrophoneFormat("speechrecognition", AudioCodec.OPUS, 48_000, 1, 20, 64_000),
            )
            fake.microphone.bodies.send(hex("f8003142"))
            fake.microphone.bodies.send(hex("f8013142"))
            runCurrent()
            val key = streamKey(checkNotNull(airPlay.sharedSecret), 78, INPUT_KEY)
            val opened =
                fake.udp.last().sent.map { datagram ->
                    val bytes = datagram.bytes
                    val tail = bytes.copyOfRange(bytes.size - 8, bytes.size)
                    bytes.copyOf(8).toHex() + "/" +
                        open(key, ByteArray(4) + tail, bytes.copyOfRange(4, 12), bytes.copyOfRange(12, bytes.size - 8))
                            .toHex()
                }
            // Opus access units go out unchanged (MicrophonePacketizerTest.kt:106-119).
            assertThat(opened).containsExactly("8064000000000000/f8003142", "80640001000001e0/f8013142")
            assertThat(sink.events("carplay.audio.microphone_silent_start").size).isEqualTo(1)
        }

    @Test
    fun `TEARDOWN stops the microphone and closes its socket`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.microphone.offer = MicrophoneOffer.PCM
            started()
            val airPlay = verified()
            airPlay.streams(VOICE_NOTE_SETUP)
            advanceTimeBy(500)
            runCurrent()
            // An app's voice note: default audio with an uplink port; 48 kHz mono PCM sends 10 ms (MTU).
            assertThat(
                fake.microphone.formats
                    .single()
                    .frameMillis,
            ).isEqualTo(10)
            assertThat(fake.microphone.recording).isEqualTo(1)
            val uplink = fake.udp.last()

            airPlay.request("TEARDOWN", RTSP_URL, hex(TEARDOWN_MAIN_AUDIO), contentType = PLIST)
            runCurrent()

            assertThat(fake.microphone.recording).isEqualTo(0)
            assertThat(uplink.closed).isTrue()
            assertThat(sink.events("carplay.audio.microphone_ended").size).isEqualTo(1)
        }

    @Test
    fun `a recorder that fails ends only the microphone, the call keeps playing`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.microphone.offer = MicrophoneOffer.PCM_AND_OPUS
            fake.microphone.failure = CarPlayError.PermissionMissing("RECORD_AUDIO")
            val link = started()
            val airPlay = verified()
            val data = airPlay.streams(TELEPHONY_SETUP).single().dataPort()
            data.deliver(airPlay.audio(77, 1, 0, "01"))
            runCurrent()
            data.deliver(airPlay.audio(77, 2, 320, "02"))
            runCurrent()

            assertThat(sink.events("carplay.audio.microphone_failed").size).isEqualTo(1)
            assertThat(fake.udp.last().closed).isTrue()
            assertThat(players.single().packets.size).isEqualTo(2)
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
        }

    @Test
    fun `music, guidance and a port that offers none never record`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.microphone.offer = MicrophoneOffer.PCM_AND_OPUS
            started()
            val airPlay = verified()
            val music = airPlay.streams(MUSIC_SETUP).first()
            val guidance = airPlay.streams(GUIDANCE_SETUP).single()
            music.dataPort().deliver(airPlay.audio(79, 1, 0, "01"))
            guidance.dataPort().deliver(airPlay.audio(81, 1, 0, "01"))
            advanceTimeBy(1_000)
            runCurrent()
            assertThat(fake.microphone.formats).isEmpty()

            fake.microphone.offer = MicrophoneOffer.NONE
            airPlay.request("TEARDOWN", RTSP_URL)
            runCurrent()
            assertThat(fake.microphone.formats).isEmpty()
        }

    @Test
    fun `duck and unduck reach the port, and a session that ends during a prompt is restored`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            airPlay.command(DUCK)
            airPlay.command(UNDUCK)
            airPlay.command(DUCK)
            runCurrent()
            assertThat(fake.audio.ducks).containsExactly(true, false, true)

            airPlay.close()
            runCurrent()
            assertThat(fake.audio.ducks).containsExactly(true, false, true, false)
        }

    @Test
    fun `a session that ends after unduck is not unducked again`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            airPlay.command(DUCK)
            airPlay.command(UNDUCK)
            runCurrent()

            airPlay.close()
            runCurrent()
            assertThat(fake.audio.ducks).containsExactly(true, false)
        }

    private companion object {
        const val SEED = 11
        const val PHONE_ID = "iphone-pairing-id"
        const val PLIST = "application/x-apple-binary-plist"
        const val RTSP_URL = "rtsp://192.168.49.1/4575630486994831421"
        const val OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val INPUT_KEY = "DataStream-Input-Encryption-Key"
        val TEST_TIMEOUT = 20.seconds

        /** {model: "iPhone15,2", timingPort: 51234}, from plistlib. */
        const val SESSION_SETUP =
            "62706c6973743030d201020304556d6f64656c5a74696d696e67506f72745a6950686f6e6531352c3211c822080d131e29" +
                "000000000000010100000000000000050000000000000000000000000000002c"

        /** {streams: [{type 100, audioType telephony, audioFormat 0x10, streamConnectionID 77, dataPort 50123}]}. */
        const val TELEPHONY_SETUP =
            "62706c6973743030d101025773747265616d73a103d50405060708090a0b0c0d547479706559617564696f547970655b6175" +
                "64696f466f726d61745f101273747265616d436f6e6e656374696f6e49445864617461506f727410645974656c6570686f" +
                "6e791010104d11c3cb080b131520252f3b50595b6567690000000000000101000000000000000e000000000000000000000" +
                "0000000006c"

        /**
         * {streams: [{type 100, audioType speechRecognition, audioFormat 0x20000000, streamConnectionID 78,
         * dataPort 50124}]}.
         */
        const val SIRI_SETUP =
            "62706c6973743030d101025773747265616d73a103d50405060708090a0b0c0d547479706559617564696f547970655b6175" +
                "64696f466f726d61745f101273747265616d436f6e6e656374696f6e49445864617461506f727410645f1011737065656368" +
                "5265636f676e6974696f6e1220000000104e11c3cc080b131520252f3b50595b6f74760000000000000101000000000000" +
                "000e00000000000000000000000000000079"

        /**
         * {streams: [{type 100, audioType media, audioFormat 0x8000, streamConnectionID 79, audioLatencyMs 50,
         * dataPort 50126}, {type 102, audioType media, audioFormat 0x800000, streamConnectionID 80}]}.
         */
        const val MUSIC_SETUP =
            "62706c6973743030d101025773747265616d73a20310d60405060708090a0b0c0d0e0f547479706559617564696f54797065" +
                "5b617564696f466f726d61745f101273747265616d436f6e6e656374696f6e49445e617564696f4c6174656e63794d7358" +
                "64617461506f72741064556d65646961118000104f103211c3ced404050607110b1213106612008000001050080b131623" +
                "28323e53626b6d7376787a7d86888d000000000000010100000000000000140000000000000000000000000000008f"

        /** {streams: [{type 100, audioType default, audioFormat 0x40000000, streamConnectionID 81}]}. */
        const val GUIDANCE_SETUP =
            "62706c6973743030d101025773747265616d73a103d40405060708090a0b547479706559617564696f547970655b61756469" +
                "6f466f726d61745f101273747265616d436f6e6e656374696f6e494410645764656661756c7412400000001051080b1315" +
                "1e232d394e50585d0000000000000101000000000000000c0000000000000000000000000000005f"

        /** {streams: [{type 100, audioType default, audioFormat 0x4000, streamConnectionID 82, dataPort 50125}]}. */
        const val VOICE_NOTE_SETUP =
            "62706c6973743030d101025773747265616d73a103d50405060708090a0b0c0d547479706559617564696f547970655b6175" +
                "64696f466f726d61745f101273747265616d436f6e6e656374696f6e49445864617461506f727410645764656661756c74" +
                "114000105211c3cd080b131520252f3b50595b6366680000000000000101000000000000000e0000000000000000000000" +
                "000000006b"

        /** {type: duckAudio} and {type: unduckAudio}. */
        const val DUCK =
            "62706c6973743030d101025474797065596475636b417564696f080b10000000000000010100000000000000030000000000" +
                "000000000000000000001a"
        const val UNDUCK =
            "62706c6973743030d1010254747970655b756e6475636b417564696f080b100000000000000101000000000000000300000000" +
                "00000000000000000000001c"

        /** {streams: [{type 100}]}. */
        const val TEARDOWN_MAIN_AUDIO =
            "62706c6973743030d101025773747265616d73a103d1040554747970651064080b1315181d00000000000001010000000000" +
                "0000060000000000000000000000000000001f"

        /** BouncyCastle HKDF-SHA512 of the iPhone's pair-verify secret (spec C §8, CarPlayMediaEngine.kt:665-677). */
        fun streamKey(
            secret: ByteArray,
            id: Long,
            label: String,
        ): ByteArray {
            val hkdf = HKDFBytesGenerator(SHA512Digest())
            hkdf.init(HKDFParameters(secret, "DataStream-Salt$id".encodeToByteArray(), label.encodeToByteArray()))
            return ByteArray(32).also { hkdf.generateBytes(it, 0, it.size) }
        }

        fun seal(
            key: ByteArray,
            nonce: ByteArray,
            aad: ByteArray,
            plaintext: ByteArray,
        ): ByteArray = crypt(true, key, nonce, aad, plaintext)

        fun open(
            key: ByteArray,
            nonce: ByteArray,
            aad: ByteArray,
            sealed: ByteArray,
        ): ByteArray = crypt(false, key, nonce, aad, sealed)

        private fun crypt(
            encrypt: Boolean,
            key: ByteArray,
            nonce: ByteArray,
            aad: ByteArray,
            input: ByteArray,
        ): ByteArray {
            val aead = ChaCha20Poly1305()
            aead.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val out = ByteArray(aead.getOutputSize(input.size))
            val n = aead.processBytes(input, 0, input.size, out, 0)
            return out.copyOf(n + aead.doFinal(out, n))
        }

        fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
