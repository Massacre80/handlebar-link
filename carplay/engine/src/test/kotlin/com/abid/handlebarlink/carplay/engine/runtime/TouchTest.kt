// Virtual-time controls of kotlinx-coroutines-test (runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.carplay.engine.runtime

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.TouchContact
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Touch on the CarPlay picture reaches the simulated iPhone as DiPlay sends it: `hidSendReport` commands on the
 * encrypted event channel (AirPlaySession.kt:351-370, 444-451) carrying AirPlayHid.touchReport (AirPlayHid.kt:44-55)
 * with AirPlayTouchSlots (AirPlayTouchSlots.kt:14-34). Every expected byte comes from a Python transcription of that
 * DiPlay code and plistlib (`src/test/resources/touch/diplay_touch.py`, run with `python3 -I diplay_touch.py`), never
 * from our encoder. The canvas is 1080 x 2250 so that a half pixel (0.25 x 2250 = 562.5) shows Java's round half up,
 * which DiPlay uses.
 */
class TouchTest {
    private val random = SecureRandom()
    private val sink = RecordingLogSink()
    private val fake = FakePorts(random)
    private val phoneKeys = Ed25519KeyPair.generate(random)

    private fun TestScope.started(): CarPlayRuntime {
        val time = TestTimeProvider(testScheduler)
        fake.now = { testScheduler.currentTime }
        val env = RuntimeEnv(time, Random(SEED), random, testLogger(sink, time))
        val config = CarPlayConfig(widthPixels = 1080, heightPixels = 2250, widthMillimetres = 68)
        val link = CarPlayRuntime(fake.ports, StandardTestDispatcher(testScheduler), env, { config })
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { link.state.collect {} }
        backgroundScope.launch { link.run() }
        link.start()
        return link
    }

    /** Bootstrap, pair-verify, auth-setup (as MediaReceiversTest) and the session SETUP; returns its event port. */
    private suspend fun TestScope.session(): Pair<PhoneAirPlay, Int> {
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
        val reply =
            airPlay.request(
                "SETUP",
                "rtsp://192.168.49.1/4575630486994831421",
                hex(SESSION_SETUP),
                contentType = PLIST,
            )
        val eventPort = checkNotNull((BinaryPlistReader.read(reply.body) as Plist.Dict).long("eventPort")).toInt()
        return airPlay to eventPort
    }

    private suspend fun TestScope.connectedEvents(): PhoneEvents {
        val (airPlay, eventPort) = session()
        val events =
            PhoneEvents(fake.listeners.single { it.port == eventPort }.connect(), checkNotNull(airPlay.sharedSecret))
        runCurrent()
        return events
    }

    @Test
    fun `a tap goes to the iPhone as DiPlay's hidSendReport, rounded half up on the reported canvas`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val events = connectedEvents()

            link.touch(listOf(TouchContact(7, 0.5, 0.25, down = true)))
            link.touch(listOf(TouchContact(7, 0.5, 0.25, down = false)))
            runCurrent()
            val press = events.read()
            val release = events.read()

            assertThat(press.head).isEqualTo(
                "POST /command RTSP/1.0\r\nContent-Type: ${PLIST}\r\n" +
                    "Content-Length: ${hex(TAP_DOWN).size}\r\nCSeq: 1",
            )
            assertThat(press.body.toHex()).isEqualTo(TAP_DOWN)
            assertThat(release.body.toHex()).isEqualTo(TAP_UP)
            assertThat(release.headers["cseq"]).isEqualTo("2")
            assertThat(sink.events("carplay.touch.first").single().fields).isEqualTo(listOf("contacts" to 1))
        }

    @Test
    fun `two fingers keep their slots, a third is left out, a lift releases in place, an empty list lifts all`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val events = connectedEvents()
            val first = TouchContact(3, 0.1, 0.2, down = true)
            val second = TouchContact(5, 0.9, 0.8, down = true)
            val third = TouchContact(9, 0.5, 0.5, down = true)

            link.touch(listOf(first))
            link.touch(listOf(first, second))
            link.touch(listOf(first, second, third))
            link.touch(listOf(first.copy(x = 0.12, down = false), second, third))
            link.touch(listOf(second, third))
            link.touch(emptyList())
            runCurrent()

            val reports = List(MULTI_REPORTS.size) { events.read().body.toHex() }
            assertThat(reports).isEqualTo(MULTI_REPORTS.map { COMMAND_HEAD + it + COMMAND_TAIL })
        }

    @Test
    fun `touches made before the event channel connects are dropped, never sent to the session`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val (airPlay, eventPort) = session()
            link.touch(listOf(TouchContact(1, 0.9, 0.9, down = true)))
            link.touch(listOf(TouchContact(1, 0.9, 0.9, down = false)))
            runCurrent()

            val events =
                PhoneEvents(
                    fake.listeners.single { it.port == eventPort }.connect(),
                    checkNotNull(airPlay.sharedSecret),
                )
            runCurrent()
            val next = backgroundScope.async { events.read() }
            runCurrent()
            assertThat(next.isCompleted).isFalse()

            link.touch(listOf(TouchContact(7, 0.5, 0.25, down = true)))
            runCurrent()

            assertThat(next.await().body.toHex()).isEqualTo(TAP_DOWN)
            assertThat(sink.events("carplay.touch.stale_dropped").single().fields).containsExactly("touches" to 2)
        }

    private companion object {
        val TEST_TIMEOUT = 30.seconds
        const val SEED = 7
        const val PHONE_ID = "iphone-pairing-id"
        const val PLIST = "application/x-apple-binary-plist"

        /** {model: "iPhone15,2", timingPort: 51234}, from plistlib (MediaReceiversTest). */
        const val SESSION_SETUP =
            "62706c6973743030d201020304556d6f64656c5a74696d696e67506f72745a6950686f6e6531352c3211c822080d131e29" +
                "000000000000010100000000000000050000000000000000000000000000002c"

        /*
         * diplay_touch.py, a transcription of DiPlay's AirPlayTouchSlots.assign, AirPlaySession.sendTouch's scaling
         * (x * widthPixels, y * heightPixels), AirPlayHid.touchReport (Math.round as floor(v + 0.5)) and sendHidReport
         * ({type, uuid, hidReport} in insertion order, plistlib.dumps(FMT_BINARY, sort_keys=False)).
         */

        /** Pointer 7 down at (0.5, 0.25) of 1080 x 2250: slot 0 down at (540, 563), slot 1 empty. */
        const val TAP_DOWN =
            "62706c6973743030d301020304050654747970655475756964596869645265706f72745d68696453656e645265706f7274" +
                "5832613261326132614c00011c023302010000000000080f141923313a000000000000010100000000000000070000" +
                "0000000000000000000000000047"

        /** The same finger lifted where it was. */
        const val TAP_UP =
            "62706c6973743030d301020304050654747970655475756964596869645265706f72745d68696453656e645265706f7274" +
                "5832613261326132614c00001c023302010000000000080f141923313a000000000000010100000000000000070000" +
                "0000000000000000000000000047"

        /** Every command is this plist with a 12-byte report (`4c` = data of 12 bytes) between head and tail. */
        const val COMMAND_HEAD =
            "62706c6973743030d301020304050654747970655475756964596869645265706f72745d68696453656e645265706f7274" +
                "5832613261326132614c"
        const val COMMAND_TAIL = "080f141923313a0000000000000101000000000000000700000000000000000000000000000047"

        /** The six reports of the two-finger test, in order (diplay_touch.py "two"). */
        val MULTI_REPORTS =
            listOf(
                "00016c00c201010000000000",
                "00016c00c2010101cc030807",
                "00016c00c2010101cc030807",
                "00008200c2010101cc030807",
                "00011c0265040101cc030807",
                "00001c0265040100cc030807",
            )

        fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
