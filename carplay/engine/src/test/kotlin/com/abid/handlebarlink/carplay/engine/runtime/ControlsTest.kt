// Virtual-time controls of kotlinx-coroutines-test (runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.carplay.engine.runtime

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import com.abid.handlebarlink.carplay.engine.CarPlayCall
import com.abid.handlebarlink.carplay.engine.CarPlayCallStatus
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayKey
import com.abid.handlebarlink.carplay.engine.CarPlayState
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
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The handlebar's keys, Siri and the iPhone's calls through the simulated iPhone.
 *
 * Keys and Siri: every expected byte comes from a Python transcription of DiPlay's AirPlayHid reports,
 * `sendMedia`/`sendTelephony`, `sendHidReport` and `invokeSiri` with plistlib
 * (`src/test/resources/keys/diplay_keys.py`, run with `python3 -I diplay_keys.py`), never from our encoder.
 *
 * Calls: the CallStateUpdates the iPhone sends are bytes built with Python's struct
 * (`carplay/protocol/src/test/resources/iap2/call_state_update.py`), sent on the Bluetooth link as raw messages.
 */
class ControlsTest {
    private val random = SecureRandom()
    private val sink = RecordingLogSink()
    private val fake = FakePorts(random)
    private val phoneKeys = Ed25519KeyPair.generate(random)

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

    /** Identification and authentication; the receiver then sends its subscriptions, 0x4154 among them. */
    private suspend fun authenticated(): PhoneIap2 {
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
        phone.expect(START_CALL_STATE_UPDATES)
        return phone
    }

    /** The Wi-Fi offer and StartSession, then pair-verify: CarPlay is connected. */
    private suspend fun TestScope.connected(phone: PhoneIap2): PhoneAirPlay {
        phone.send(iap2Message(0x5702))
        phone.expect(0x5703)
        phone.send(iap2Message(0x4300))
        phone.expect(0x4301)
        fake.pairings.save(PHONE_ID, phoneKeys.publicKey)
        val airPlay = PhoneAirPlay(fake.listeners.single().connect(), random)
        check(airPlay.pairVerify(PHONE_ID, phoneKeys))
        runCurrent()
        return airPlay
    }

    /** Auth-setup and the session SETUP; returns its event port. */
    private suspend fun TestScope.session(airPlay: PhoneAirPlay): Int {
        val auth = airPlay.request("POST", "/auth-setup", byteArrayOf(1) + X25519KeyPair.generate(random).publicKey)
        check(auth.status == 200)
        runCurrent()
        val reply = airPlay.request("SETUP", "rtsp://192.168.49.1/1", hex(SESSION_SETUP), contentType = PLIST)
        return checkNotNull((BinaryPlistReader.read(reply.body) as Plist.Dict).long("eventPort")).toInt()
    }

    private suspend fun TestScope.connectedEvents(): PhoneEvents {
        val airPlay = connected(authenticated())
        val eventPort = session(airPlay)
        val events =
            PhoneEvents(fake.listeners.single { it.port == eventPort }.connect(), checkNotNull(airPlay.sharedSecret))
        runCurrent()
        return events
    }

    @Test
    fun `each key goes to the iPhone as DiPlay's one-byte report, then its release`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val events = connectedEvents()
            val keys =
                listOf(
                    CarPlayKey.PLAY_PAUSE to MEDIA_PLAY_PAUSE,
                    CarPlayKey.NEXT to MEDIA_NEXT,
                    CarPlayKey.PREVIOUS to MEDIA_PREVIOUS,
                    CarPlayKey.PLAY to MEDIA_PLAY,
                    CarPlayKey.PAUSE to MEDIA_PAUSE,
                    CarPlayKey.HOOK_SWITCH to TELEPHONY_HOOK_SWITCH,
                    CarPlayKey.DROP to TELEPHONY_DROP,
                )

            keys.forEach { (key, _) -> link.press(key) }
            runCurrent()
            val bodies = List(keys.size * 2) { events.next().body.toHex() }

            val expected =
                keys.flatMap { (_, press) ->
                    listOf(press, if (press.startsWith(MEDIA_HEAD)) MEDIA_RELEASE else TELEPHONY_RELEASE)
                }
            assertThat(bodies).isEqualTo(expected)
            assertThat(sink.events("carplay.hid.key").map { it.fields }).isEqualTo(
                keys.map { (key, _) -> listOf("key" to key, "sent" to true) },
            )
        }

    @Test
    fun `a press goes out as POST command with DiPlay's headers`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val events = connectedEvents()

            link.press(CarPlayKey.HOOK_SWITCH)
            runCurrent()
            val press = events.next()

            assertThat(press.head).isEqualTo(
                "POST /command RTSP/1.0\r\nContent-Type: $PLIST\r\n" +
                    "Content-Length: ${hex(TELEPHONY_HOOK_SWITCH).size}\r\nCSeq: 1",
            )
            assertThat(events.next().headers["cseq"]).isEqualTo("2")
        }

    @Test
    fun `Siri is DiPlay's requestSiri with siriAction 2, then 3`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val events = connectedEvents()

            link.requestSiri()
            runCurrent()

            assertThat(List(2) { events.next().body.toHex() }).containsExactly(SIRI_START, SIRI_END)
            assertThat(sink.events("carplay.siri.requested").single().fields).containsExactly("sent" to true)
        }

    @Test
    fun `presses and Siri made before the event channel connects are dropped, never sent to the session`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val airPlay = connected(authenticated())
            val eventPort = session(airPlay)
            link.press(CarPlayKey.HOOK_SWITCH)
            link.requestSiri()
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

            link.press(CarPlayKey.NEXT)
            runCurrent()

            assertThat(withTimeout(READ_TIMEOUT) { next.await() }.body.toHex()).isEqualTo(MEDIA_NEXT)
            assertThat(sink.events("carplay.hid.stale_dropped").single().fields).containsExactly("requests" to 2)
        }

    @Test
    fun `the iPhone's call updates reach the calls flow, a ringing call and a waiting one in order`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = authenticated()

            phone.sendRaw(hex(RINGING_A))
            runCurrent()
            assertThat(link.calls.value).containsExactly(CarPlayCall(CarPlayCallStatus.RINGING))

            phone.sendRaw(hex(ACTIVE_A))
            phone.sendRaw(hex(SENDING_B))
            runCurrent()
            assertThat(link.calls.value).containsExactly(
                CarPlayCall(CarPlayCallStatus.ACTIVE),
                CarPlayCall(CarPlayCallStatus.SENDING),
            )

            phone.sendRaw(hex(DISCONNECTING_B))
            phone.sendRaw(hex(HELD_A))
            runCurrent()
            assertThat(link.calls.value).containsExactly(CarPlayCall(CarPlayCallStatus.HELD))

            phone.sendRaw(hex(DISCONNECTED_A))
            runCurrent()
            assertThat(link.calls.value).isEmpty()
        }

    @Test
    fun `an update without a status changes nothing, and an unknown status ends the call`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = authenticated()

            phone.sendRaw(hex(RINGING_A))
            phone.sendRaw(hex(NO_STATUS))
            runCurrent()
            assertThat(link.calls.value).containsExactly(CarPlayCall(CarPlayCallStatus.RINGING))
            assertThat(sink.events("carplay.calls.ignored").single().fields).containsExactly("parameters" to listOf(4))

            phone.sendRaw(hex(UNKNOWN_A))
            runCurrent()
            assertThat(link.calls.value).isEmpty()
            assertThat(sink.events("carplay.calls.unknown_status").single().fields).containsExactly("status" to 9)
        }

    @Test
    fun `a call that rings while CarPlay is connected is seen, and losing Bluetooth forgets it`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = authenticated()
            connected(phone)
            check(link.state.value is CarPlayState.Connected)

            phone.sendRaw(hex(RINGING_A))
            runCurrent()
            assertThat(link.calls.value).containsExactly(CarPlayCall(CarPlayCallStatus.RINGING))

            fake.rfcomm
                .single()
                .phone
                .close()
            runCurrent()

            assertThat(link.calls.value).isEmpty()
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
            assertThat(
                sink.events("carplay.calls.cleared").single().fields,
            ).containsExactly("reason" to "bootstrap ended")
        }

    @Test
    fun `the attempt ending forgets the calls`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = authenticated()
            val airPlay = connected(phone)
            phone.sendRaw(hex(ACTIVE_A))
            runCurrent()
            check(link.calls.value.isNotEmpty())

            airPlay.close()
            runCurrent()

            assertThat(link.state.value).isInstanceOf<CarPlayState.Recovering>()
            assertThat(link.calls.value).isEmpty()
        }

    @Test
    fun `call logs carry statuses only, never the call's number or name`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = authenticated()

            phone.sendRaw(hex(SENDING_B))
            runCurrent()

            check(link.calls.value.isNotEmpty())
            val update = sink.events("carplay.calls.update").single()
            assertThat(update.fields).isEqualTo(
                listOf("status" to 1, "calls" to listOf(CarPlayCallStatus.SENDING), "changed" to true),
            )
            assertThat(sink.entries.any { "971500" in it.fields.toString() || "Mum" in it.fields.toString() }).isFalse()
        }

    /**
     * The next command, or a failure after [READ_TIMEOUT] of virtual time: a command that never comes (a plant that
     * drops the release) fails at once instead of letting retries run in virtual time until the wall-clock limit.
     */
    private suspend fun PhoneEvents.next(): PhoneMessage = withTimeout(READ_TIMEOUT) { read() }

    private companion object {
        val TEST_TIMEOUT = 30.seconds
        const val READ_TIMEOUT = 5_000L
        const val SEED = 7
        const val PHONE_ID = "iphone-pairing-id"
        const val PLIST = "application/x-apple-binary-plist"
        const val START_CALL_STATE_UPDATES = 0x4154

        /** {model: "iPhone15,2", timingPort: 51234}, from plistlib (MediaReceiversTest). */
        const val SESSION_SETUP =
            "62706c6973743030d201020304556d6f64656c5a74696d696e67506f72745a6950686f6e6531352c3211c822080d131e29" +
                "000000000000010100000000000000050000000000000000000000000000002c"

        // diplay_keys.py output. Every HID command is {type: hidSendReport, uuid, hidReport: <one byte>}.
        private const val HID_HEAD =
            "62706c6973743030d301020304050654747970655475756964596869645265706f72745d68696453656e645265706f7274"
        private const val HID_TAIL = "080f141923313a000000000000010100000000000000070000000000000000000000000000003c"

        /** `X8 "2a2a2a2c"` then `41` (data of one byte): the media device. */
        const val MEDIA_HEAD = HID_HEAD + "583261326132613263" + "41"
        const val TELEPHONY_HEAD = HID_HEAD + "583261326132613264" + "41"

        const val MEDIA_PLAY = MEDIA_HEAD + "01" + HID_TAIL
        const val MEDIA_PAUSE = MEDIA_HEAD + "02" + HID_TAIL
        const val MEDIA_PLAY_PAUSE = MEDIA_HEAD + "03" + HID_TAIL
        const val MEDIA_NEXT = MEDIA_HEAD + "04" + HID_TAIL
        const val MEDIA_PREVIOUS = MEDIA_HEAD + "05" + HID_TAIL
        const val MEDIA_RELEASE = MEDIA_HEAD + "00" + HID_TAIL
        const val TELEPHONY_HOOK_SWITCH = TELEPHONY_HEAD + "01" + HID_TAIL
        const val TELEPHONY_DROP = TELEPHONY_HEAD + "03" + HID_TAIL
        const val TELEPHONY_RELEASE = TELEPHONY_HEAD + "00" + HID_TAIL

        const val SIRI_START =
            "62706c6973743030d201020304547479706556706172616d735b7265717565737453697269d105065a73697269416374696f6e" +
                "1002080d12192528330000000000000101000000000000000700000000000000000000000000000035"
        const val SIRI_END =
            "62706c6973743030d201020304547479706556706172616d735b7265717565737453697269d105065a73697269416374696f6e" +
                "1003080d12192528330000000000000101000000000000000700000000000000000000000000000035"

        // call_state_update.py output.
        const val RINGING_A = "4040001e4155000800014d756d0000050002020005000301000600046100"
        const val SENDING_B = "404000284155001200002b3937313530303030303030300000050002010005000301000600046200"
        const val ACTIVE_A = "4040001141550005000204000600046100"
        const val HELD_A = "4040001141550005000205000600046100"
        const val DISCONNECTED_A = "4040001141550005000200000600046100"
        const val DISCONNECTING_B = "4040001141550005000206000600046200"
        const val NO_STATUS = "4040000c4155000600046100"
        const val UNKNOWN_A = "4040001141550005000209000600046100"

        fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
