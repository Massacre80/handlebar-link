// Virtual-time controls of kotlinx-coroutines-test (runCurrent, advanceTimeBy).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.carplay.engine.runtime

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.CarPlayCall
import com.abid.handlebarlink.carplay.engine.CarPlayCallStatus
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.session.IapTunnel
import com.abid.handlebarlink.carplay.engine.session.WirelessAttempt
import com.abid.handlebarlink.carplay.engine.spi.ByteStream
import com.abid.handlebarlink.carplay.protocol.crypto.ChaCha20Poly1305
import com.abid.handlebarlink.carplay.protocol.crypto.Ed25519KeyPair
import com.abid.handlebarlink.carplay.protocol.crypto.Hkdf
import com.abid.handlebarlink.carplay.protocol.crypto.X25519KeyPair
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2Message
import com.abid.handlebarlink.carplay.protocol.iap2.LocationMessages
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The iAP2 hand-over from the Bluetooth bootstrap to the AirPlay type-130 tunnel (DiPlay CarPlayMediaEngine.kt:379-487,
 * CarPlayController.kt:506-525, 1888-1957, 2064-2208; spec D §1.5), against a simulated iPhone that speaks the tunnel
 * as DiPlay reads it: `comm` records (IapTunnel.kt:189-205) in ChaCha20-Poly1305 frames keyed from the iPhone's own
 * shared secret with literal salts (IapTunnel.kt:170-187), and our messages taken from `iAPSendMessage` commands on the
 * event channel (AirPlaySession.kt:395-400). That framing is written here, not taken from the code under test.
 *
 * Every plist the iPhone sends and every reply or command compared byte for byte is plistlib's
 * (`src/test/resources/tunnel/tunnel_plists.py`, run with `python3 -I tunnel_plists.py`); the CallStateUpdates are
 * `call_state_update.py`'s. Every read of something the receiver must send is bounded in virtual time (lesson 85).
 */
class TunnelHandoffTest {
    private val random = SecureRandom()
    private val sink = RecordingLogSink()
    private val fake = FakePorts(random)
    private val phoneKeys = Ed25519KeyPair.generate(random)

    /** The iPhone's ends: Bluetooth iAP2, the AirPlay control connection and the event channel. */
    private class Phone(
        val bluetooth: PhoneIap2,
        val identification: Iap2Message,
        val airPlay: PhoneAirPlay,
        val eventChannel: PhoneEvents?,
    ) {
        val events: PhoneEvents get() = checkNotNull(eventChannel)
    }

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

    /** Bluetooth bootstrap, pair-verify, auth-setup, the session SETUP and the event channel. */
    private suspend fun TestScope.connected(
        record: Boolean = true,
        connectEvents: Boolean = true,
    ): Phone {
        val bluetooth = PhoneIap2(fake.dialed.receive())
        bluetooth.handshake()
        bluetooth.send(iap2Message(0x1D00))
        val identification = within { bluetooth.expect(0x1D01) }
        bluetooth.send(iap2Message(0x1D02))
        bluetooth.send(iap2Message(0xAA00))
        within { bluetooth.expect(0xAA01) }
        bluetooth.send(iap2Message(0xAA02) { bytes(0, ByteArray(32) { it.toByte() }) })
        within { bluetooth.expect(0xAA03) }
        bluetooth.send(iap2Message(0xAA05))
        within { bluetooth.expect(START_CALL_STATE_UPDATES) }
        bluetooth.send(iap2Message(0x5702))
        within { bluetooth.expect(0x5703) }
        bluetooth.send(iap2Message(0x4300))
        within { bluetooth.expect(0x4301) }
        fake.pairings.save(PHONE_ID, phoneKeys.publicKey)
        val airPlay = PhoneAirPlay(fake.listeners.single().connect(), random)
        check(airPlay.pairVerify(PHONE_ID, phoneKeys))
        val auth = airPlay.request("POST", "/auth-setup", byteArrayOf(1) + X25519KeyPair.generate(random).publicKey)
        check(auth.status == 200)
        val session = airPlay.request("SETUP", SETUP_PATH, hex(SESSION_SETUP), contentType = PLIST)
        val eventPort = checkNotNull((BinaryPlistReader.read(session.body) as Plist.Dict).long("eventPort")).toInt()
        val events =
            if (connectEvents) PhoneEvents(listener(eventPort).connect(), checkNotNull(airPlay.sharedSecret)) else null
        // The session is the live one from its RECORD (DiPlay onSessionActive); only then does disableBluetooth count.
        if (record) check(airPlay.request("RECORD", SETUP_PATH).status == 200)
        runCurrent()
        return Phone(bluetooth, identification, airPlay, events)
    }

    private suspend fun PhoneAirPlay.setup(body: String): PhoneResponse =
        request("SETUP", SETUP_PATH, hex(body), contentType = PLIST)

    private suspend fun PhoneAirPlay.command(body: String): PhoneResponse =
        request("POST", "/command", hex(body), contentType = PLIST)

    private fun listener(port: Int) = fake.listeners.single { it.port == port }

    private fun PhoneResponse.plist(): Plist.Dict = BinaryPlistReader.read(body) as Plist.Dict

    private fun PhoneResponse.dataPort(): Int =
        checkNotNull((checkNotNull(plist().array("streams")).single() as Plist.Dict).long("dataPort"))
            .toInt()

    /** The tunnel's read key, from the iPhone's secret and the SETUP's seed as a literal decimal (spec C table). */
    private fun tunnelKey(
        phone: Phone,
        seed: String = REAL_SEED,
    ) = Hkdf.sha512(
        checkNotNull(phone.airPlay.sharedSecret),
        "DataStream-Salt$seed",
        "DataStream-Output-Encryption-Key",
        32,
    )

    /** The iPhone's SETUP of the tunnel and its connection to the data port; returns its end of the tunnel. */
    private suspend fun TestScope.tunnel(phone: Phone): PhoneTunnel {
        val port = phone.airPlay.setup(TUNNEL_SETUP).dataPort()
        val tunnel = PhoneTunnel(listener(port).connect(), phone.events, tunnelKey(phone))
        runCurrent()
        return tunnel
    }

    /** The iPhone's ends of a ready tunnel, and the identification the receiver sent over it. */
    private class ReadyTunnel(
        val tunnel: PhoneTunnel,
        val iap2: PhoneIap2,
        val identification: Iap2Message,
    )

    /** The link handshake, identification, authentication and the subscriptions over the tunnel. */
    private suspend fun TestScope.readyTunnel(phone: Phone): ReadyTunnel {
        val tunnel = tunnel(phone)
        val iap2 = PhoneIap2(tunnel)
        within { iap2.handshake() }
        iap2.send(iap2Message(0x1D00))
        val identification = within { iap2.expect(0x1D01) }
        iap2.send(iap2Message(0x1D02))
        iap2.send(iap2Message(0xAA00))
        within { iap2.expect(0xAA01) }
        iap2.send(iap2Message(0xAA02) { bytes(0, ByteArray(32) { it.toByte() }) })
        within { iap2.expect(0xAA03) }
        iap2.send(iap2Message(0xAA05))
        within { iap2.expect(START_CALL_STATE_UPDATES) }
        runCurrent()
        return ReadyTunnel(tunnel, iap2, identification)
    }

    private fun bluetoothClosed(): Boolean =
        fake.rfcomm
            .single()
            .ours.closed

    private fun events(name: String) = sink.events(name)

    private suspend fun <T> within(block: suspend () -> T): T = withTimeout(READ_TIMEOUT) { block() }

    @Test
    fun `the iPhone's tunnel SETUP gets DiPlay's reply and a listener for its data connection`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val phone = connected()

            val response = phone.airPlay.setup(TUNNEL_SETUP)
            runCurrent()

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toHex()).isEqualTo(TUNNEL_REPLY_50003)
            assertThat(listener(50_003).closed).isFalse()
            assertThat(events("carplay.iap2.tunnel.setup").single().fields)
                .containsExactly("dataPort" to 50_003, "streamConnectionID" to false)
        }

    @Test
    fun `a lower-case client type is the tunnel too, and its streamConnectionID is echoed`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val phone = connected()

            val response = phone.airPlay.setup(TUNNEL_SETUP_WITH_ID)

            assertThat(response.body.toHex()).isEqualTo(TUNNEL_REPLY_WITH_ID_50003)
        }

    @Test
    fun `another data stream and a tunnel without seed are declined, the SETUP still succeeds`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val phone = connected()

            val other = phone.airPlay.setup(OTHER_DATA_STREAM)
            val noSeed = phone.airPlay.setup(NO_SEED)

            assertThat(other.status).isEqualTo(200)
            assertThat(other.body.toHex()).isEqualTo(EMPTY_STREAMS_REPLY)
            assertThat(noSeed.body.toHex()).isEqualTo(EMPTY_STREAMS_REPLY)
            assertThat(events("carplay.airplay.stream_declined").map { it.fields })
                .containsExactly(
                    listOf("type" to 130, "reason" to "unsupported data stream"),
                    listOf("type" to 130, "reason" to "no seed"),
                )
        }

    @Test
    fun `the tunnel link starts with the marker and the tunnel SYN in one iAPSendMessage, once the iPhone connected`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val phone = connected()
            val port = phone.airPlay.setup(TUNNEL_SETUP).dataPort()

            // Nothing goes out before the iPhone connects to the data port (AirPlayIapTunnelStream.kt:59-61).
            assertThat(withTimeoutOrNull(1.seconds) { phone.events.read() }).isNull()
            listener(port).connect()
            val first = within { phone.events.read() }

            assertThat(first.startLine).isEqualTo("POST /command RTSP/1.0")
            assertThat(first.body.toHex()).isEqualTo(IAP_SEND_START)
        }

    @Test
    fun `the tunnel identifies with location, and disableBluetooth then closes the Bluetooth bootstrap`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = connected()
            val ready = readyTunnel(phone)
            check(!bluetoothClosed())

            phone.airPlay.command(DISABLE_BLUETOOTH)
            runCurrent()

            assertThat(bluetoothClosed()).isTrue()
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
            assertThat(events("carplay.iap2.tunnel.ready").single().fields).containsExactly("handoffRequested" to false)
            assertThat(events("carplay.handoff.bluetooth_closed").single().fields)
                .containsExactly("after_request_ms" to 0L, "was_open" to true, "from" to "BLUETOOTH")
            // C6 location: the tunnel identification is the Bluetooth one plus the location messages (0xFFFB sent,
            // 0xFFFA/0xFFFC received); the Bluetooth bootstrap never advertises them.
            assertThat(ready.identification.parameter(6)!!.u16List()).contains(LocationMessages.LOCATION_INFORMATION)
            assertThat(ready.identification.parameter(7)!!.u16List())
                .contains(LocationMessages.START_LOCATION_INFORMATION)
            assertThat(phone.identification.parameter(6)!!.u16List())
                .doesNotContain(LocationMessages.LOCATION_INFORMATION)
            assertThat(events("carplay.handoff.fallback")).isEmpty()
        }

    @Test
    fun `disableBluetooth before the tunnel is ready, the iPhone's order, closes Bluetooth once it is`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val phone = connected()
            val tunnel = tunnel(phone)

            phone.airPlay.command(DISABLE_BLUETOOTH_DASHED)
            runCurrent()
            assertThat(bluetoothClosed()).isFalse()
            val iap2 = PhoneIap2(tunnel)
            within { iap2.handshake() }
            iap2.send(iap2Message(0x1D00))
            within { iap2.expect(0x1D01) }
            iap2.send(iap2Message(0x1D02))
            iap2.send(iap2Message(0xAA00))
            within { iap2.expect(0xAA01) }
            iap2.send(iap2Message(0xAA02) { bytes(0, ByteArray(32) { it.toByte() }) })
            within { iap2.expect(0xAA03) }
            assertThat(bluetoothClosed()).isFalse()
            iap2.send(iap2Message(0xAA05))
            within { iap2.expect(START_CALL_STATE_UPDATES) }
            runCurrent()

            assertThat(bluetoothClosed()).isTrue()
            assertThat(events("carplay.handoff.requested").single().fields).containsExactly("tunnel" to "STARTING")
            assertThat(events("carplay.iap2.tunnel.ready").single().fields).containsExactly("handoffRequested" to true)
        }

    @Test
    fun `calls keep arriving over the tunnel after the hand-over and are never forgotten in between`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = connected()
            phone.bluetooth.sendRaw(hex(RINGING_A))
            runCurrent()
            check(link.calls.value == listOf(CarPlayCall(CarPlayCallStatus.RINGING)))
            val iap2 = readyTunnel(phone).iap2

            phone.airPlay.command(DISABLE_BLUETOOTH)
            runCurrent()
            check(bluetoothClosed())
            assertThat(link.calls.value).containsExactly(CarPlayCall(CarPlayCallStatus.RINGING))
            iap2.sendRaw(hex(ACTIVE_A))
            runCurrent()

            assertThat(link.calls.value).containsExactly(CarPlayCall(CarPlayCallStatus.ACTIVE))
            assertThat(events("carplay.calls.cleared")).isEmpty()
            // The tunnel answers like the bootstrap (DiPlay's log: 0x5702 over the tunnel gets 0x5703).
            iap2.send(iap2Message(0x5702))
            within { iap2.expect(0x5703) }
        }

    @Test
    fun `without a tunnel, disableBluetooth falls back to Bluetooth after 45 s and calls still arrive over it`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = connected()

            phone.airPlay.command(DISABLE_BLUETOOTH)
            advanceTimeBy(44_999)
            runCurrent()
            assertThat(events("carplay.handoff.fallback")).isEmpty()
            advanceTimeBy(2)
            runCurrent()

            assertThat(bluetoothClosed()).isFalse()
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
            assertThat(events("carplay.handoff.fallback").single().fields).containsExactly(
                "reason" to "no tunnel ready after 45000ms",
                "tunnel" to "NONE",
                "bluetoothOpen" to true,
            )
            phone.bluetooth.sendRaw(hex(RINGING_A))
            runCurrent()
            assertThat(link.calls.value).containsExactly(CarPlayCall(CarPlayCallStatus.RINGING))
        }

    @Test
    fun `a tunnel that fails after the request leaves Bluetooth open and falls back at once`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = connected()
            val tunnel = tunnel(phone)
            phone.airPlay.command(DISABLE_BLUETOOTH)
            runCurrent()

            tunnel.sendForged()
            runCurrent()

            assertThat(bluetoothClosed()).isFalse()
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
            assertThat(
                events("carplay.iap2.tunnel.ended")
                    .single()
                    .fields
                    .first()
                    .second
                    .toString(),
            ).contains("failed authentication")
            assertThat(
                events("carplay.handoff.fallback")
                    .single()
                    .fields
                    .first()
                    .second
                    .toString(),
            ).contains("tunnel ended")
            assertThat(listener(50_003).closed).isTrue()
        }

    @Test
    fun `a tunnel that becomes ready after the fallback still takes over`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val phone = connected()
            phone.airPlay.command(DISABLE_BLUETOOTH)
            advanceTimeBy(45_001)
            runCurrent()
            check(events("carplay.handoff.fallback").size == 1)

            readyTunnel(phone)

            assertThat(bluetoothClosed()).isTrue()
            val closed = events("carplay.handoff.bluetooth_closed").single()
            assertThat(closed.fields.last()).isEqualTo("from" to "FALLBACK")
        }

    @Test
    fun `the tunnel ending after the hand-over ends the attempt, nothing else carries iAP2`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = connected()
            val tunnel = readyTunnel(phone).tunnel
            phone.airPlay.command(DISABLE_BLUETOOTH)
            runCurrent()
            check(bluetoothClosed())

            tunnel.sendForged()
            runCurrent()

            val error = (link.state.value as? CarPlayState.Recovering)?.error
            assertThat(error).isNotNull().isInstanceOf<CarPlayError.PhoneDisconnected>()
            assertThat(error.toString()).contains("iAP2 tunnel ended after the Bluetooth hand-over")
            assertThat(events("carplay.handoff.tunnel_lost")).hasSize(1)
        }

    @Test
    fun `an iPhone that does not connect to the tunnel within 15 s ends only the tunnel`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = connected()
            val port = phone.airPlay.setup(TUNNEL_SETUP).dataPort()

            advanceTimeBy(14_999)
            runCurrent()
            assertThat(events("carplay.iap2.tunnel.ended")).isEmpty()
            advanceTimeBy(2)
            runCurrent()

            assertThat(
                events("carplay.iap2.tunnel.ended")
                    .single()
                    .fields
                    .first()
                    .second
                    .toString(),
            ).contains("did not connect")
            assertThat(listener(port).closed).isTrue()
            assertThat(bluetoothClosed()).isFalse()
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
        }

    @Test
    fun `the iPhone closing the data connection is not the end, its next connection carries the link on`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val phone = connected()
            val tunnel = tunnel(phone)
            val iap2 = PhoneIap2(tunnel)
            within { iap2.handshake() }

            tunnel.reconnect(listener(50_003).connect())
            runCurrent()
            iap2.send(iap2Message(0x1D00))

            assertThat(within { iap2.expect(0x1D01) }.id).isEqualTo(0x1D01)
            assertThat(events("carplay.iap2.tunnel.peer_eof").size).isEqualTo(1)
            assertThat(events("carplay.iap2.tunnel.ended")).isEmpty()
        }

    @Test
    fun `a new tunnel SETUP after the hand-over replaces the old tunnel without ending the attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = connected()
            readyTunnel(phone)
            phone.airPlay.command(DISABLE_BLUETOOTH)
            runCurrent()
            check(bluetoothClosed())

            val second = phone.airPlay.setup(TUNNEL_SETUP)
            runCurrent()

            assertThat(second.status).isEqualTo(200)
            assertThat(listener(50_003).closed).isTrue()
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
            assertThat(events("carplay.handoff.tunnel_lost")).isEmpty()
        }

    @Test
    fun `a tunnel that stalls in identification ends after DiPlay's 5 minutes`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val phone = connected()
            val iap2 = PhoneIap2(tunnel(phone))
            within { iap2.handshake() }

            advanceTimeBy(IapTunnel.STEP_TIMEOUT - 1)
            runCurrent()
            val before = events("carplay.iap2.tunnel.ended").size
            advanceTimeBy(2)
            runCurrent()

            assertThat(before).isEqualTo(0)
            assertThat(
                events("carplay.iap2.tunnel.ended")
                    .single()
                    .fields
                    .first()
                    .second
                    .toString(),
            ).contains("identification took over")
        }

    @Test
    fun `a tunnel whose event channel never connects gives its first write up after 10 s, not sooner`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val phone = connected(connectEvents = false)
            val port = phone.airPlay.setup(TUNNEL_SETUP).dataPort()
            listener(port).connect()
            runCurrent()

            advanceTimeBy(9_990)
            runCurrent()
            val before = events("carplay.iap2.tunnel.ended").size
            advanceTimeBy(40)
            runCurrent()

            assertThat(before).isEqualTo(0)
            assertThat(
                events("carplay.iap2.tunnel.ended")
                    .single()
                    .fields
                    .first()
                    .second
                    .toString(),
            ).contains("did not take an iAP2 message")
        }

    @Test
    fun `a tunnel that fails during the fallback keeps the calls Bluetooth still carries`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = connected()
            phone.bluetooth.sendRaw(hex(RINGING_A))
            runCurrent()
            check(link.calls.value == listOf(CarPlayCall(CarPlayCallStatus.RINGING)))
            val tunnel = tunnel(phone)
            phone.airPlay.command(DISABLE_BLUETOOTH)
            runCurrent()

            tunnel.sendForged()
            runCurrent()

            check(events("carplay.handoff.fallback").size == 1)
            assertThat(link.calls.value).containsExactly(CarPlayCall(CarPlayCallStatus.RINGING))
            assertThat(events("carplay.calls.cleared")).isEmpty()
        }

    @Test
    fun `a tunnel the iPhone never connects to keeps the calls Bluetooth still carries`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = connected()
            phone.bluetooth.sendRaw(hex(RINGING_A))
            runCurrent()
            phone.airPlay.setup(TUNNEL_SETUP)

            advanceTimeBy(15_001)
            runCurrent()

            check(events("carplay.iap2.tunnel.ended").size == 1)
            assertThat(link.calls.value).containsExactly(CarPlayCall(CarPlayCallStatus.RINGING))
            assertThat(events("carplay.calls.cleared")).isEmpty()
        }

    @Test
    fun `a tunnel that fails after the Bluetooth bootstrap ended by itself ends the attempt, no fallback to it`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = connected()
            val tunnel = tunnel(phone)
            phone.airPlay.command(DISABLE_BLUETOOTH)
            fake.rfcomm
                .single()
                .phone
                .close()
            runCurrent()
            check(link.state.value is CarPlayState.Connected)

            tunnel.sendForged()
            runCurrent()

            val error = (link.state.value as? CarPlayState.Recovering)?.error
            assertThat(error).isNotNull().isInstanceOf<CarPlayError.PhoneDisconnected>()
            assertThat(error.toString()).contains("the Bluetooth link is gone")
            assertThat(events("carplay.handoff.fallback")).isEmpty()
            assertThat(events("carplay.handoff.tunnel_lost")).hasSize(1)
        }

    @Test
    fun `disableBluetooth from a session that has not started recording is ignored, as DiPlay`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            val phone = connected(record = false)
            readyTunnel(phone)

            phone.airPlay.command(DISABLE_BLUETOOTH)
            advanceTimeBy(50_000)

            assertThat(bluetoothClosed()).isFalse()
            assertThat(events("carplay.handoff.requested")).isEmpty()
            assertThat(events("carplay.handoff.fallback")).isEmpty()
            assertThat(events("carplay.handoff.not_active")).hasSize(1)
        }

    @Test
    fun `a superseded verified connection ending leaves the newer live session running`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = started()
            val phone = connected()
            val newer = PhoneAirPlay(listener(WirelessAttempt.AIRPLAY_PORT).connect(), random)
            check(newer.pairVerify(PHONE_ID, phoneKeys))
            check(newer.request("RECORD", SETUP_PATH).status == 200)
            runCurrent()

            phone.airPlay.close()
            runCurrent()

            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
            assertThat(events("carplay.airplay.superseded_ended")).hasSize(1)
            assertThat(events("carplay.airplay.session_replaced")).hasSize(1)

            newer.close()
            runCurrent()
            assertThat(link.state.value).isInstanceOf<CarPlayState.Recovering>()
        }

    private companion object {
        const val SEED = 7
        const val PHONE_ID = "iphone-pairing-id"
        const val PLIST = "application/x-apple-binary-plist"
        const val SETUP_PATH = "rtsp://192.168.49.1/2790904582078353154"
        const val START_CALL_STATE_UPDATES = 0x4154
        val TEST_TIMEOUT = 20.seconds
        val READ_TIMEOUT = 5.seconds

        /** The real iPhone's seed (CarLink's log, 2026-11-19 15:16:22.850), as the salt's decimal. */
        const val REAL_SEED = "6277450898808826287"

        /** AirPlaySessionTest's session SETUP (plistlib): with keepAliveLowPower, so ports 50000-50002 are taken. */
        const val SESSION_SETUP =
            "62706c6973743030da0102030405060708090a0b0c0d0e0f10111213145864657669636549445a6d6163416464726573" +
                "73556d6f64656c546e616d65596f7356657273696f6e5d736f7572636556657273696f6e5a74696d696e67506f72745e" +
                "74696d696e6750726f746f636f6c5f10116b656570416c6976654c6f77506f7765725866656174757265735f10113131" +
                "3a32323a33333a34343a35353a36365f101141413a42423a43433a30303a31313a32325a6950686f6e6531352c325b54" +
                "657374206950686f6e655432362e31573930302e372e3111c822534e545009a31516175b636f726e65724d61736b735d" +
                "766964656f506c61796261636b5c6d61696e42756666657265640008001d002600310037003c00460054005f006e0082" +
                "008b009f00b300be00ca00cf00d700da00de00df00e300ef00fd00000000000002010000000000000018000000000000" +
                "0000000000000000010a"

        // tunnel_plists.py output (run 2026-11-19).
        const val TUNNEL_SETUP =
            "62706c6973743030d101025773747265616d73a103d80405060708090a0b0c0d0e0f101111125b636f6e74726f6c5479" +
                "7065596368616e6e656c494454736565645a636c69656e745555494454747970655f101477616e747344656469636174" +
                "6564536f636b65745f100f73656e644d657373616765417349735e636c69656e74547970655555494410025f10174641" +
                "3a35363a44463a35423a45353a39412d5243532d3513571dfc554674e9af5f102437444237434137302d304138352d34" +
                "4438432d414338432d4633333239303943373839331082095f102445393435394644302d424341442d344334352d3832" +
                "30462d314537323434374546324632080b131526323c414c51687a898ba5aed5d7d80000000000000101000000000000" +
                "0013000000000000000000000000000000ff"
        const val TUNNEL_REPLY_50003 =
            "62706c6973743030d101025773747265616d73a103d304050607080954747970655873747265616d49445864617461506f" +
                "72741082100111c353080b13151c212a3335370000000000000101000000000000000a00000000000000000000000000" +
                "00003a"
        const val TUNNEL_SETUP_WITH_ID =
            "62706c6973743030d101025773747265616d73a103d40405060708090a0b54747970655e636c69656e74547970655555" +
                "494454736565645f101273747265616d436f6e6e656374696f6e494410825f102465393435396664302d626361642d34" +
                "6334352d383230662d316537323434376566326632113039104d080b13151e2332374c4e757800000000000001010000" +
                "00000000000c0000000000000000000000000000007a"
        const val TUNNEL_REPLY_WITH_ID_50003 =
            "62706c6973743030d101025773747265616d73a103d40405060708090a0b54747970655873747265616d494458646174" +
                "61506f72745f101273747265616d436f6e6e656374696f6e49441082100111c353104d080b13151e232c354a4c4e5100" +
                "00000000000101000000000000000c00000000000000000000000000000053"
        const val OTHER_DATA_STREAM =
            "62706c6973743030d101025773747265616d73a103d304050607080954747970655e636c69656e745479706555554944" +
                "547365656410825f102442423439334636312d413642382d343736392d384437342d3830433233413946373143341001" +
                "080b13151c213035375e0000000000000101000000000000000a00000000000000000000000000000060"
        const val NO_SEED =
            "62706c6973743030d101025773747265616d73a103d20405060754747970655e636c69656e74547970655555494410825f" +
                "102445393435394644302d424341442d344334352d383230462d314537323434374546324632080b13151a1f2e300000" +
                "000000000101000000000000000800000000000000000000000000000057"
        const val EMPTY_STREAMS_REPLY =
            "62706c6973743030d101025773747265616d73a0080b13000000000000010100000000000000030000000000000000000000" +
                "0000000014"
        const val DISABLE_BLUETOOTH =
            "62706c6973743030d201020304547479706556706172616d735f101064697361626c65426c7565746f6f7468d1050658" +
                "64657669636549445f101143303a31373a35343a44313a32453a4538080d12192c2f3800000000000001010000000000" +
                "0000070000000000000000000000000000004c"
        const val DISABLE_BLUETOOTH_DASHED =
            "62706c6973743030d201020304547479706556706172616d735f101144697361626c652d426c7565746f6f7468d0080d" +
                "12192d000000000000010100000000000000050000000000000000000000000000002e"
        const val IAP_SEND_START =
            "62706c6973743030d201020304547479706556706172616d735e69415053656e644d657373616765d105065464617461" +
                "4f1023ff550200ee10ff5a001d80630000a70104ffff0000000000000a00020b02010c0102d4080d1219282b30000000" +
                "0000000101000000000000000700000000000000000000000000000056"

        // call_state_update.py output (as in ControlsTest).
        const val RINGING_A = "4040001e4155000800014d756d0000050002020005000301000600046100"
        const val ACTIVE_A = "4040001141550005000204000600046100"

        fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

/**
 * The iPhone's end of the iAP2 tunnel, written from DiPlay's reader (IapTunnel.kt:170-205) rather than from ours: what
 * the iPhone's iAP2 writes goes out in `comm` records sealed with [key] (u16 LE length as AAD, nonce = 4 zero bytes +
 * LE64 counter); what the receiver sends is read from its `iAPSendMessage` commands on [events].
 */
internal class PhoneTunnel(
    private var data: Pipe.End,
    private val events: PhoneEvents,
    private val key: ByteArray,
) : ByteStream {
    private var pending = ByteArray(0)
    private var counter = 0L

    override suspend fun read(buffer: ByteArray): Int {
        while (pending.isEmpty()) {
            val body = BinaryPlistReader.read(events.read().body) as Plist.Dict
            if (body.text("type") == "iAPSendMessage") pending += checkNotNull(body.dictionary("params")?.data("data"))
        }
        val count = minOf(buffer.size, pending.size)
        pending.copyInto(buffer, 0, 0, count)
        pending = pending.copyOfRange(count, pending.size)
        return count
    }

    override suspend fun write(bytes: ByteArray) = data.write(frame(record(bytes)))

    /** A frame whose tag cannot authenticate. */
    suspend fun sendForged() = data.write(byteArrayOf(4, 0) + ByteArray(4 + ChaCha20Poly1305.TAG_BYTES) { 9 })

    /** The iPhone closes its data connection and opens [next]; the counter starts again (IapTunnel.kt:130). */
    fun reconnect(next: Pipe.End) {
        data.close()
        data = next
        counter = 0
    }

    override fun close() = data.close()

    private fun record(body: ByteArray): ByteArray {
        val size = HEADER + body.size
        val header = ByteArray(HEADER) { (0xA0 + it).toByte() } // the other header bytes are not read
        for (i in 0 until 4) header[i] = (size ushr (24 - 8 * i)).toByte()
        "comm".encodeToByteArray().copyInto(header, TYPE_AT)
        return header + body
    }

    private fun frame(plain: ByteArray): ByteArray {
        val aad = byteArrayOf(plain.size.toByte(), (plain.size ushr 8).toByte())
        val nonce = ByteArray(12).also { n -> for (i in 0 until 8) n[4 + i] = (counter ushr (8 * i)).toByte() }
        counter++
        return aad + ChaCha20Poly1305(key, PhoneAirPlay.CIPHER).seal(nonce, aad, plain)
    }

    private companion object {
        const val HEADER = 32
        const val TYPE_AT = 16
    }
}
