// Virtual-time controls of kotlinx-coroutines-test (runCurrent, advanceTimeBy).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.carplay.engine.runtime

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.runtime.FakePorts.FakeScreen.Call.Frame
import com.abid.handlebarlink.carplay.engine.spi.Datagram
import com.abid.handlebarlink.carplay.protocol.audio.AudioCodec
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
import kotlinx.coroutines.async
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
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The real media seams (MediaReceivers.kt) behind the AirPlay session, against a simulated iPhone that seals its media
 * with BouncyCastle (HKDF-SHA512 and ChaCha20-Poly1305), never with our protocol code, in DiPlay's layouts: screen
 * frames as ScreenStream.kt:83-125, 160-170, audio as AudioStream.kt:114-154, timing as NtpClock.kt:87-133. The
 * picture and avcC bytes and their expected Annex B forms are the C3 stream vectors (generate_vectors.py,
 * ScreenCodecTest.kt); request bodies come from Python's plistlib.
 */
class MediaReceiversTest {
    private val random = SecureRandom()
    private val sink = RecordingLogSink()
    private val fake = FakePorts(random)
    private val phoneKeys = Ed25519KeyPair.generate(random)
    private val frames get() = fake.screen.frames

    /** The runtime builds its own media factory on the fake ports, as the app gets it; [config] is the canvas. */
    private fun TestScope.started(config: CarPlayConfig = CarPlayConfig()): CarPlayRuntime {
        val time = TestTimeProvider(testScheduler)
        fake.now = { testScheduler.currentTime }
        val env = RuntimeEnv(time, Random(SEED), random, testLogger(sink, time))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val link = CarPlayRuntime(fake.ports, dispatcher, env, { config })
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { link.state.collect {} }
        backgroundScope.launch { link.run() }
        link.start()
        return link
    }

    /** Bluetooth bootstrap, the iPhone joins, connects, pair-verifies and runs auth-setup (as AirPlaySessionTest). */
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

    private suspend fun PhoneAirPlay.setup(body: String): Plist.Dict =
        BinaryPlistReader.read(
            request("SETUP", "rtsp://192.168.49.1/4575630486994831421", hex(body), contentType = PLIST).body,
        ) as Plist.Dict

    private suspend fun PhoneAirPlay.streams(body: String): List<Plist.Dict> =
        checkNotNull(setup(body).array("streams")).map { it as Plist.Dict }

    private suspend fun PhoneAirPlay.feedback(): List<Plist.Dict> {
        val response = request("POST", "/feedback")
        check(response.status == 200)
        return checkNotNull((BinaryPlistReader.read(response.body) as Plist.Dict).array("streams")).map {
            it as Plist.Dict
        }
    }

    private fun PhoneAirPlay.secret(): ByteArray = checkNotNull(sharedSecret)

    private fun Plist.Dict.port(key: String): Int = checkNotNull(long(key)).toInt()

    private fun udp(port: Int) = fake.udp.single { it.port == port }

    private fun failure(link: CarPlayRuntime): CarPlayError? = (link.state.value as? CarPlayState.Recovering)?.error

    /** Our timing requests: to the iPhone's SETUP `timingPort`. */
    private fun FakePorts.FakeUdp.requests(): List<Datagram> = sent.filter { it.port == PHONE_TIMING_PORT }

    @Test
    fun `the default media answers every timing request to its sender and asks the iPhone every second`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            val timing = udp(airPlay.setup(SESSION_SETUP).port("timingPort"))
            runCurrent()

            // One request at once, then one per second of the injected clock (NtpClock.kt:70-82).
            val first = timing.requests().single()
            assertThat(first.address).isEqualTo("192.168.49.2")
            assertThat(first.bytes.size).isEqualTo(32)
            assertThat(first.bytes.copyOf(4).toHex()).isEqualTo("80d20007")
            advanceTimeBy(999)
            runCurrent()
            assertThat(timing.requests().size).isEqualTo(1)
            advanceTimeBy(1)
            runCurrent()
            assertThat(timing.requests().size).isEqualTo(2)
            advanceTimeBy(3_000)
            runCurrent()
            assertThat(timing.requests().size).isEqualTo(5)

            // DiPlay answers anyone's request (NtpClock.kt:117-133): back to the sender, its transmit time echoed.
            val transmit = "0102030405060708"
            timing.deliver(timingRequest(transmit), address = "192.168.49.2", from = 60_123)
            timing.deliver(timingRequest("1112131415161718"), address = "192.168.49.9", from = 6_000)
            runCurrent()

            val answers = timing.sent.filter { it.port != PHONE_TIMING_PORT }
            assertThat(answers.map { it.address to it.port }).containsExactly(
                "192.168.49.2" to 60_123,
                "192.168.49.9" to 6_000,
            )
            assertThat(answers[0].bytes.copyOf(4).toHex()).isEqualTo("80d30007")
            assertThat(answers[0].bytes.copyOfRange(8, 16).toHex()).isEqualTo(transmit)
            assertThat(answers[1].bytes.copyOfRange(8, 16).toHex()).isEqualTo("1112131415161718")
        }

    @Test
    fun `only the iPhone's responses steer the clock, synced is logged once and feedback carries the iPhone's time`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            val timing = udp(airPlay.setup(SESSION_SETUP).port("timingPort"))
            val audio = airPlay.streams(AUDIO_SETUP).first()
            runCurrent()

            // The iPhone's clock: PHONE_EPOCH_SECONDS (NTP) at virtual time 0, then counting with it.
            fun phoneNtp(): BigInteger =
                BigInteger.valueOf(PHONE_EPOCH_SECONDS).shiftLeft(32) +
                    BigInteger.valueOf(testScheduler.currentTime).shiftLeft(32).divide(BigInteger.valueOf(1_000))

            fun answerLatest(from: String) {
                val request = timing.requests().last().bytes
                timing.deliver(timingResponse(request, phoneNtp()), address = from, from = PHONE_TIMING_PORT)
                runCurrent()
                advanceTimeBy(TIMING_INTERVAL)
                runCurrent()
            }
            repeat(4) { answerLatest("192.168.49.9") }
            assertThat(sink.events("carplay.timing.synced").size).isEqualTo(0)
            repeat(4) { answerLatest("192.168.49.2") }
            assertThat(sink.events("carplay.timing.synced").size).isEqualTo(1)

            udp(audio.port("dataPort")).deliver(audioPacket(airPlay, "17212176183586094827", AUDIO_HEADER))
            runCurrent()
            advanceTimeBy(250)
            val timestamp = airPlay.feedback().first()["timestamp"] as Plist.Integer

            // 8 bytes (signed) on the wire, as DiPlay's codec writes NTP64 values (spec D §3, deviation 2).
            assertThat(timestamp.value.signum()).isEqualTo(-1)
            val unsigned = timestamp.value.and(BigInteger.ONE.shiftLeft(64) - BigInteger.ONE)
            assertThat((unsigned - phoneNtp()).abs()).isLessThan(ONE_MICROSECOND_NTP)
        }

    @Test
    fun `sealed screen frames reach the screen port in order with their config flags and frame times`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val airPlay = verified()
            airPlay.setup(SESSION_SETUP)
            val screenPort = airPlay.streams(SCREEN_SETUP).single().port("dataPort")
            val screen = fake.listeners.single { it.port == screenPort }
            val phone = screen.connect()
            runCurrent()
            val key = streamKey(airPlay, "4575630486994831421")

            val wire = sealedPicture(key, 0, PICTURE_A, T_A) + config(AVCC) + sealedPicture(key, 1, PICTURE_B, T_B)
            wire.toList().chunked(50).forEach { phone.write(it.toByteArray()) }
            runCurrent()

            assertThat(frames).containsExactly(
                Frame(false, 3_250_000_000L, "00000001651100000001419a02"),
                Frame(true, 0L, AVCC),
                Frame(false, 3_250_000_000L + 16_666_666L, "0000000141"),
            )
            assertThat(sink.events("carplay.screen.receiving").single().fields)
                .isEqualTo(listOf("type" to 110, "config" to false, "bytes" to 13))
            assertThat(sink.events("carplay.screen.frames").size).isEqualTo(0)

            // About every 5 s while frames arrive: frames, configs and the bytes read.
            advanceTimeBy(5_000)
            val third = sealedPicture(key, 2, PICTURE_B, T_B)
            phone.write(third)
            runCurrent()

            assertThat(sink.events("carplay.screen.frames").single().fields).isEqualTo(
                listOf("type" to 110, "frames" to 4L, "configs" to 1L, "bytes" to (wire.size + third.size).toLong()),
            )
            assertThat(frames.size).isEqualTo(4)
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
        }

    @Test
    fun `a screen frame that does not authenticate ends the session after the frames before it`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val airPlay = verified()
            airPlay.setup(SESSION_SETUP)
            val screenPort = airPlay.streams(SCREEN_SETUP).single().port("dataPort")
            val screen = fake.listeners.single { it.port == screenPort }
            val phone = screen.connect()
            runCurrent()
            val key = streamKey(airPlay, "4575630486994831421")
            val corrupted = sealedPicture(key, 1, PICTURE_B, T_B).also { it[it.size - 1] = (it.last() + 1).toByte() }

            phone.write(sealedPicture(key, 0, PICTURE_A, T_A))
            runCurrent()
            phone.write(corrupted)
            runCurrent()

            assertThat(frames.map { it.payload }).containsExactly("00000001651100000001419a02")
            assertThat(failure(link)).isEqualTo(
                CarPlayError.PhoneDisconnected("screen stream 110: Screen frame 1 did not authenticate"),
            )
            assertThat(sink.events("carplay.screen.ended").single().fields[1]).isEqualTo("frames" to 1L)
            assertThat(fake.udp.all { it.closed }).isTrue()
            assertThat(fake.listeners.all { it.closed }).isTrue()
        }

    @Test
    fun `the decoder is configured at the stream SETUP with the canvas info reported, before the screen connects`() =
        runTest(timeout = TEST_TIMEOUT) {
            started(CarPlayConfig(widthPixels = 1080, heightPixels = 2250, widthMillimetres = 68, maxFps = 58))
            val airPlay = verified()
            airPlay.setup(SESSION_SETUP)

            val screenPort = airPlay.streams(SCREEN_SETUP).single().port("dataPort")
            runCurrent()

            // /info's maxFPS is maxFps snapped to the nearest multiple of 5 (AirPlayInfoPlist.kt:211,
            // AirPlayDisplaySettings.kt:89-92): 58 gives 60 (diplay_display.py snap_fps(58), run with python3 -I).
            assertThat(fake.screen.calls).containsExactly(FakePorts.FakeScreen.Call.Configure(1080, 2250, 60))
            assertThat(fake.listeners.single { it.port == screenPort }.closed).isFalse()
        }

    @Test
    fun `a key frame request from the decoder goes to the iPhone as forceKeyFrame while the screen stream runs`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            val eventPort = airPlay.setup(SESSION_SETUP).port("eventPort")
            val events = PhoneEvents(fake.listeners.single { it.port == eventPort }.connect(), airPlay.secret())
            val screenPort = airPlay.streams(SCREEN_SETUP).single().port("dataPort")
            fake.listeners.single { it.port == screenPort }.connect()
            runCurrent()

            fake.screen.needKeyFrame()
            runCurrent()
            val command = events.read()

            // DiPlay's main-screen request has no params (CarPlayMediaEngine.kt:149-153); bytes from plistlib.
            assertThat(command.startLine).isEqualTo("POST /command RTSP/1.0")
            assertThat(command.body.toHex()).isEqualTo(FORCE_KEY_FRAME)
            assertThat(sink.events("carplay.screen.force_key_frame").single().fields).isEqualTo(listOf("sent" to true))
        }

    @Test
    fun `no key frame is asked for once the screen is torn down, and a new screen stream asks again`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            val eventPort = airPlay.setup(SESSION_SETUP).port("eventPort")
            val events = PhoneEvents(fake.listeners.single { it.port == eventPort }.connect(), airPlay.secret())
            val firstPort = airPlay.streams(SCREEN_SETUP).single().port("dataPort")
            fake.listeners.single { it.port == firstPort }.connect()
            runCurrent()
            airPlay.request("TEARDOWN", "rtsp://192.168.49.1/1", hex(TEARDOWN_SCREEN), contentType = PLIST)
            runCurrent()

            fake.screen.needKeyFrame()
            val next = backgroundScope.async { events.read() }
            runCurrent()
            assertThat(next.isCompleted).isFalse()

            val secondPort = airPlay.streams(SCREEN_SETUP).single().port("dataPort")
            fake.listeners.single { it.port == secondPort }.connect()
            runCurrent()
            fake.screen.needKeyFrame()
            runCurrent()

            assertThat(next.await().body.toHex()).isEqualTo(FORCE_KEY_FRAME)
            assertThat(next.await().headers["cseq"]).isEqualTo("1")
        }

    @Test
    fun `audio datagrams are decrypted and counted, broken ones dropped, and feedback follows DiPlay`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val airPlay = verified()
            airPlay.setup(SESSION_SETUP)
            val (media, speech) = airPlay.streams(AUDIO_SETUP)
            runCurrent()

            // Before any packet: type and the RTP clock rate (Opus at 16 kHz counts 16 000,
            // CarPlayMediaEngine.kt:494-497).
            assertThat(airPlay.feedback().map { it.entries }).containsExactly(
                mapOf("type" to Plist.of(100), "sampleRate" to Plist.of(48_000)),
                mapOf("type" to Plist.of(101), "sampleRate" to Plist.of(16_000)),
            )

            val data = udp(media.port("dataPort"))
            val good = audioPacket(airPlay, "17212176183586094827", AUDIO_HEADER)
            val corrupted = good.copyOf().also { it[14] = (it[14] + 1).toByte() }
            data.deliver(good)
            val startNanos = testScheduler.currentTime * 1_000_000L
            data.deliver(corrupted)
            data.deliver(ByteArray(20))
            data.deliver(audioPacket(airPlay, "17212176183586094827", "80600008fffffcf011223344"))
            data.deliver(audioPacket(airPlay, "17212176183586094827", "80600009fffffde011223344"))
            runCurrent()

            assertThat(sink.events("carplay.audio.receiving").single().fields).isEqualTo(
                listOf("type" to 100, "audioType" to "media", "codec" to AudioCodec.LPCM, "sampleRate" to 48_000),
            )
            assertThat(sink.events("carplay.audio.dropped").size).isEqualTo(1)

            // 1.05 s after the first packet, less the 50 ms audioLatencyMs: 1 s, 48 000 samples past 0xfffffc00.
            advanceTimeBy(1_050)
            val (report, quiet) = airPlay.feedback()
            assertThat(report.entries.keys.toList()).containsExactly(
                "type",
                "sampleRate",
                "streamConnectionID",
                "timestamp",
                "timestampRawNs",
                "sampleTime",
            )
            // The iPhone's 8-byte id, bit for bit, as the SETUP reply echoes it.
            assertThat(report["streamConnectionID"]).isEqualTo(Plist.Integer(BigInteger.valueOf(-1234567890123456789)))
            assertThat(report.long("timestampRawNs")).isEqualTo(startNanos + 1_050_000_000L)
            assertThat(report.long("sampleTime")).isEqualTo(46_976L) // (0xfffffc00 + 48 000) mod 2^32
            assertThat(quiet.entries.keys.toList()).containsExactly("type", "sampleRate")
            assertThat(speech.long("type")).isEqualTo(101L)

            airPlay.request("TEARDOWN", "rtsp://192.168.49.1/1")
            runCurrent()
            val ended = sink.events("carplay.audio.ended").associate { it.fields.toMap().let { f -> f["type"] to f } }
            assertThat(ended.getValue(100)["packets"] to ended.getValue(100)["dropped"]).isEqualTo(3L to 2L)
            assertThat(ended.getValue(101)["packets"]).isEqualTo(0L)
        }

    private companion object {
        const val SEED = 7
        const val PHONE_ID = "iphone-pairing-id"
        const val PLIST = "application/x-apple-binary-plist"
        const val PHONE_TIMING_PORT = 51234
        const val TIMING_INTERVAL = 1_000L
        val TEST_TIMEOUT = 20.seconds

        /** An arbitrary iPhone clock far from the test's wall clock (2026-11-16T15:30Z), in NTP seconds. */
        const val PHONE_EPOCH_SECONDS = 3_999_999_999L

        /** One microsecond in NTP 32.32 units (2^32 / 10^6, rounded up). */
        val ONE_MICROSECOND_NTP: BigInteger = BigInteger.valueOf(4_295)

        /** {model: "iPhone15,2", timingPort: 51234}, from plistlib. */
        const val SESSION_SETUP =
            "62706c6973743030d201020304556d6f64656c5a74696d696e67506f72745a6950686f6e6531352c3211c822080d131e29" +
                "000000000000010100000000000000050000000000000000000000000000002c"

        /** {streams: [{type 110}]}, from plistlib. */
        const val TEARDOWN_SCREEN =
            "62706c6973743030d101025773747265616d73a103d104055474797065106e080b1315181d0000000000000101000000" +
                "00000000060000000000000000000000000000001f"

        /** {type: "forceKeyFrame"}, from plistlib. */
        const val FORCE_KEY_FRAME =
            "62706c6973743030d1010254747970655d666f7263654b65794672616d65080b10000000000000010100000000000000" +
                "030000000000000000000000000000001e"

        /** {streams: [{type: 110, streamConnectionID: 4575630486994831421}]}, from plistlib. */
        const val SCREEN_SETUP =
            "62706c6973743030d101025773747265616d73a103d20405060754747970655f101273747265616d436f6e6e656374696f" +
                "6e4944106e133f7fe7af68c6483d080b13151a1f3436000000000000010100000000000000080000000000000000000000" +
                "000000003f"

        /**
         * {streams: [{type 100, audioType media, audioFormat 0x8000, streamConnectionID -1234567890123456789 (8 bytes,
         * 17212176183586094827 unsigned), audioLatencyMs 50}, {type 101, audioType default, audioFormat 0x10000000
         * (Opus 16 kHz), streamConnectionID 77}]}, from plistlib.
         */
        const val AUDIO_SETUP =
            "62706c6973743030d101025773747265616d73a2030ed50405060708090a0b0c0d547479706559617564696f5479706" +
                "55b617564696f466f726d61745f101273747265616d436f6e6e656374696f6e49445e617564696f4c6174656e63794d" +
                "731064556d6564696111800013eeddef0b82167eeb1032d4040506070f10111210655764656661756c741210000000" +
                "104d080b13162126303c516062686b74767f81898e000000000000010100000000000000130000000000000000000000" +
                "0000000090"

        /** generate_vectors.py: two length-prefixed NALs (ScreenCodecTest.kt), one NAL, an avcC record. */
        const val PICTURE_A = "00000002651100000003419a02"
        const val PICTURE_B = "0000000141"
        const val AVCC = "0164001fffe100046764001f01000468ee3c80"

        /** 3.25 s and one 1/60 s step later, NTP 32.32 (ScreenCodecTest.kt:69-76). */
        const val T_A = (3L shl 32) or 0x40000000L
        const val T_B = T_A + 0x04444444L

        /** RTP v2, payload type 0x60, sequence 7, timestamp 0xfffffc00, SSRC 0x11223344 (generate_vectors.py). */
        const val AUDIO_HEADER = "80600007fffffc0011223344"

        /** BouncyCastle HKDF-SHA512 of the iPhone's own pair-verify secret (spec C §8, spec D §5.3). */
        fun streamKey(
            airPlay: PhoneAirPlay,
            decimalId: String,
        ): ByteArray {
            val hkdf = HKDFBytesGenerator(SHA512Digest())
            hkdf.init(
                HKDFParameters(
                    checkNotNull(airPlay.sharedSecret),
                    "DataStream-Salt$decimalId".encodeToByteArray(),
                    "DataStream-Output-Encryption-Key".encodeToByteArray(),
                ),
            )
            return ByteArray(32).also { hkdf.generateBytes(it, 0, it.size) }
        }

        fun seal(
            key: ByteArray,
            nonce: ByteArray,
            aad: ByteArray,
            plaintext: ByteArray,
        ): ByteArray {
            val aead = ChaCha20Poly1305()
            aead.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val out = ByteArray(aead.getOutputSize(plaintext.size))
            val n = aead.processBytes(plaintext, 0, plaintext.size, out, 0)
            aead.doFinal(out, n)
            return out
        }

        /** 4 zero bytes and the counter, little-endian (AirPlayCrypto.kt:219-228). */
        fun counterNonce(counter: Long): ByteArray = ByteArray(4) + ByteArray(8) { (counter ushr (8 * it)).toByte() }

        /** 128 bytes of filler (all authenticated), body size LE32 at 0, opcode at 4, NTP time LE64 at 8. */
        fun screenHeader(
            opcode: Int,
            bodySize: Int,
            ntp: Long,
        ): ByteArray =
            ByteArray(128) { it.toByte() }.also { header ->
                for (i in 0 until 4) header[i] = (bodySize ushr (8 * i)).toByte()
                header[4] = opcode.toByte()
                for (i in 0 until 8) header[8 + i] = (ntp ushr (8 * i)).toByte()
            }

        fun sealedPicture(
            key: ByteArray,
            counter: Long,
            picture: String,
            ntp: Long,
        ): ByteArray {
            val plain = hex(picture)
            val header = screenHeader(0, plain.size + 16, ntp)
            return header + seal(key, counterNonce(counter), header, plain)
        }

        fun config(avcc: String): ByteArray = screenHeader(1, hex(avcc).size, 0) + hex(avcc)

        /** RTP header (clear) | sealed payload | tag | 8-byte nonce tail; AAD = header bytes 4..11. */
        fun audioPacket(
            airPlay: PhoneAirPlay,
            decimalId: String,
            header: String,
        ): ByteArray {
            val rtp = hex(header)
            val tail = ByteArray(8) { (it + 7).toByte() }
            val sealed =
                seal(streamKey(airPlay, decimalId), ByteArray(4) + tail, rtp.copyOfRange(4, 12), hex("216c4f5510"))
            return rtp + sealed + tail
        }

        /** 0x80, type 210, 00 07, then originate, receive and transmit (NtpClock.kt:87-97). */
        fun timingRequest(transmit: String): ByteArray = hex("80d20007" + "00".repeat(20) + transmit)

        /** The iPhone's answer (NtpClock.kt:124-133): our transmit as originate, then its receive and transmit. */
        fun timingResponse(
            request: ByteArray,
            phoneNtp: BigInteger,
        ): ByteArray {
            val stamp = ByteArray(8) { (phoneNtp.toLong() ushr (56 - 8 * it)).toByte() }
            return hex("80d30007") + ByteArray(4) + request.copyOfRange(24, 32) + stamp + stamp
        }

        fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
