// Virtual-time controls of kotlinx-coroutines-test (currentTime, advanceTimeBy, runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.carplay.engine.runtime

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.protocol.crypto.Ed25519KeyPair
import com.abid.handlebarlink.carplay.protocol.crypto.X25519KeyPair
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2Message
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
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class CarPlayRuntimeTest {
    private val random = SecureRandom()
    private val sink = RecordingLogSink()
    private val fake = FakePorts(random)
    private val phoneKeys = Ed25519KeyPair.generate(random)
    private val phoneId = "iphone-pairing-id"

    private fun TestScope.runtime(): CarPlayRuntime {
        val time = TestTimeProvider(testScheduler)
        val env = RuntimeEnv(time, Random(SEED), random, testLogger(sink, time))
        return CarPlayRuntime(fake.ports, StandardTestDispatcher(testScheduler), env, CarPlayConfig())
    }

    private fun TestScope.started(): Pair<CarPlayRuntime, MutableList<CarPlayState>> {
        val link = runtime()
        val seen = mutableListOf<CarPlayState>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { link.state.collect { seen += it } }
        backgroundScope.launch { link.run() }
        link.start()
        return link to seen
    }

    /** The iPhone's Bluetooth bootstrap up to StartSession, as DiPlay's peer runs it (spec B §3). */
    private suspend fun bootstrap(phone: PhoneIap2): Iap2Message {
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
        return phone.expect(0x4301)
    }

    /** Bootstrap, join, connect and pair-verify; returns the AirPlay connection. */
    private suspend fun TestScope.connect(): PhoneAirPlay {
        bootstrap(PhoneIap2(fake.dialed.receive()))
        fake.pairings.save(phoneId, phoneKeys.publicKey)
        val airPlay = PhoneAirPlay(fake.listeners.last().connect(), random)
        check(airPlay.pairVerify(phoneId, phoneKeys))
        runCurrent()
        return airPlay
    }

    @Test
    fun `the iPhone is bootstrapped over Bluetooth, joins, pair-verifies and authenticates`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (link, _) = started()
            val phone = PhoneIap2(fake.dialed.receive())

            val startSession = bootstrap(phone)
            val wireless = startSession.parameter(1)!!.group()
            assertThat(wireless.first { it.id == 0 }.string()).isEqualTo(fake.group.network.ssid)
            assertThat(wireless.first { it.id == 3 }.string()).isEqualTo("192.168.49.1")
            assertThat(startSession.parameter(2)!!.payload.toList()).isEqualTo(listOf<Byte>(0, 0, 0x1B, 0x58))
            assertThat(startSession.parameter(3)!!.string()).isEqualTo("aa:bb:cc:dd:ee:01")
            assertThat(startSession.parameter(4)!!.string()).isEqualTo(fake.airPlayIdentity.publicKeyHex)

            val airPlay = PhoneAirPlay(fake.listeners.single().connect(), random)
            val info = BinaryPlistReader.read(airPlay.request("GET", "/info").body) as Plist.Dict
            val early = airPlay.request("POST", "/auth-setup", byteArrayOf(1) + ByteArray(32))
            fake.pairings.save(phoneId, phoneKeys.publicKey)
            val verified = airPlay.pairVerify(phoneId, phoneKeys)
            val auth = airPlay.request("POST", "/auth-setup", byteArrayOf(1) + X25519KeyPair.generate(random).publicKey)
            runCurrent()

            assertThat(info.text("deviceID")).isEqualTo("aa:bb:cc:dd:ee:01")
            assertThat(early.status).isEqualTo(470)
            assertThat(verified).isTrue()
            assertThat(auth.status).isEqualTo(200)
            assertThat(auth.headers["content-type"]).isEqualTo("application/octet-stream")
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
            assertThat((link.state.value as CarPlayState.Connected).phone).isEqualTo(phoneId)
            assertThat(link.keepAlive.value).isTrue()
            // Every step is logged (the state flow conflates fast transitions).
            assertThat(sink.events("carplay.step").map { it.fields.single().second }).containsExactly(
                ConnectStep.LOADING_IDENTITY,
                ConnectStep.PREPARING,
                ConnectStep.DIALING_PHONE,
                ConnectStep.IDENTIFYING,
                ConnectStep.AUTHENTICATING,
                ConnectStep.WAITING_FOR_PHONE,
                ConnectStep.PAIRING,
            )
            val txt =
                fake.published
                    .single()
                    .txt
                    .toMap()
            assertThat(txt["pk"]).isEqualTo(fake.airPlayIdentity.publicKeyHex)
            assertThat(txt["pi"]).isEqualTo(fake.airPlayIdentity.pairingId)
            assertThat(txt["features"]).isEqualTo("0x5653aee2,0x61")
        }

    @Test
    fun `when the iPhone disconnects everything is closed and a new attempt starts after a jittered pause`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (link, _) = started()
            val airPlay = connect()

            airPlay.close()
            runCurrent()
            val failedAt = currentTime
            val recovering = link.state.value as CarPlayState.Recovering
            fake.dialed.receive()

            assertThat(recovering.error).isInstanceOf<CarPlayError.PhoneDisconnected>()
            assertThat(fake.groupsRemoved).isEqualTo(1)
            assertThat(fake.listeners.first().closed).isTrue()
            assertThat(fake.unpublished).isEqualTo(1)
            assertThat(
                fake.rfcomm
                    .first()
                    .ours.closed,
            ).isTrue()
            assertThat(currentTime - failedAt - fake.dialMillis).isBetween(1_600L, 2_400L)
        }

    @Test
    fun `a failed dial is retried with growing pauses, and what was opened is closed each time`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.dialError = CarPlayError.DialFailed("page timeout")
            val (link, seen) = started()

            advanceTimeBy(30_000)

            val failures = seen.filterIsInstance<CarPlayState.Recovering>()
            assertThat(failures.first().error).isEqualTo(CarPlayError.DialFailed("page timeout"))
            assertThat(failures.map { it.attempt }.take(3)).containsExactly(1, 2, 3)
            // Each pause doubles: 2 s, 4 s, 8 s, each ±20 % (retryAt to retryAt also holds the 100 ms dial).
            val gaps =
                failures.zipWithNext { a, b ->
                    b.retryAt.toEpochMilli() - a.retryAt.toEpochMilli() -
                        fake.dialMillis
                }
            assertThat(gaps[0]).isBetween(3_200L, 4_800L)
            assertThat(gaps[1]).isBetween(6_400L, 9_600L)
            assertThat(fake.groupsRemoved).isEqualTo(fake.groupsCreated)
            assertThat(fake.listeners.all { it.closed }).isTrue()
            assertThat(link.keepAlive.value).isTrue()
        }

    @Test
    fun `an iPhone that never answers the marker fails identification after 30 s instead of hanging`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (link, _) = started()
            fake.dialed.receive()

            advanceTimeBy(30_000 + fake.dialMillis + 1)

            assertThat(
                (link.state.value as CarPlayState.Recovering).error,
            ).isEqualTo(CarPlayError.TimedOut(ConnectStep.IDENTIFYING))
        }

    @Test
    fun `a connection that never pair-verifies times out the pairing step`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (link, _) = started()
            bootstrap(PhoneIap2(fake.dialed.receive()))
            fake.listeners.single().connect()

            advanceTimeBy(30_001)

            assertThat(
                (link.state.value as CarPlayState.Recovering).error,
            ).isEqualTo(CarPlayError.TimedOut(ConnectStep.PAIRING))
        }

    @Test
    fun `stop closes everything, keeps the service until teardown is done, and starts nothing more`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (link, _) = started()
            fake.dialed.receive()
            runCurrent()

            link.stop()
            runCurrent()
            advanceTimeBy(120_000)

            assertThat(link.state.value).isEqualTo(CarPlayState.Idle)
            assertThat(link.keepAlive.value).isFalse()
            assertThat(fake.groupsRemoved).isEqualTo(1)
            assertThat(
                fake.rfcomm
                    .single()
                    .ours.closed,
            ).isTrue()
            assertThat(fake.groupsCreated).isEqualTo(1)
        }

    @Test
    fun `the service is kept until the Wi-Fi Direct group is really removed`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.removeGroupMillis = 500
            val (link, _) = started()
            fake.dialed.receive()

            link.stop()
            runCurrent()
            val duringTeardown = link.keepAlive.value
            advanceTimeBy(501)

            assertThat(duringTeardown).isTrue()
            assertThat(link.keepAlive.value).isFalse()
        }

    @Test
    fun `stop, start, stop leaves nothing running from either attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (link, _) = started()
            fake.dialed.receive()

            link.stop()
            link.start()
            fake.dialed.receive()
            link.stop()
            runCurrent()
            val closedAtOnce = fake.rfcomm.all { it.ours.closed } && fake.groupsRemoved == fake.groupsCreated
            advanceTimeBy(120_000)

            assertThat(closedAtOnce).isTrue()
            assertThat(link.state.value).isEqualTo(CarPlayState.Idle)
            assertThat(fake.groupsCreated).isEqualTo(2)
        }

    @Test
    fun `the first identification rejection is corrected and a second one fails the attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (link, _) = started()
            val phone = PhoneIap2(fake.dialed.receive())
            phone.handshake()
            val rejection = iap2Message(0x1D03) { u16List(6, listOf(0x4301)) }

            phone.send(iap2Message(0x1D00))
            phone.expect(0x1D01)
            phone.send(rejection)
            val corrected = phone.expect(0x1D01)
            phone.send(rejection)
            runCurrent()

            assertThat(corrected.parameter(6)!!.u16List().contains(0x4301)).isFalse()
            assertThat(
                (link.state.value as CarPlayState.Recovering).error,
            ).isEqualTo(CarPlayError.IdentificationRejected)
        }

    @Test
    fun `the iPhone refusing authentication fails the attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (link, _) = started()
            val phone = PhoneIap2(fake.dialed.receive())
            phone.handshake()

            phone.send(iap2Message(0x1D00))
            phone.expect(0x1D01)
            phone.send(iap2Message(0x1D02))
            phone.send(iap2Message(0xAA04))
            runCurrent()

            assertThat((link.state.value as CarPlayState.Recovering).error).isEqualTo(CarPlayError.AuthenticationFailed)
        }

    @Test
    fun `losing Bluetooth after the AirPlay connection is verified keeps CarPlay connected`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (link, _) = started()
            connect()

            fake.rfcomm
                .single()
                .phone
                .close()
            runCurrent()

            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
        }

    @Test
    fun `missing MFi files are a problem for the rider`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.credentialsError = CarPlayError.IdentityUnavailable("identity.pk8 missing")
            val (link, _) = started()

            runCurrent()

            val error = (link.state.value as CarPlayState.Recovering).error
            assertThat(error.needsUser).isTrue()
            assertThat(sink.events("carplay.recovering").size).isEqualTo(1)
        }

    @Test
    fun `run twice at once is refused, and cancelling run releases everything`() =
        runTest(timeout = TEST_TIMEOUT) {
            val link = runtime()
            val job = backgroundScope.launch { link.run() }
            link.start()
            runCurrent()

            assertFailure { link.run() }.isInstanceOf<IllegalStateException>()
            job.cancel()
            runCurrent()

            assertThat(link.state.value).isEqualTo(CarPlayState.Stopped)
            assertThat(link.keepAlive.value).isFalse()
            assertThat(fake.groupsRemoved).isEqualTo(fake.groupsCreated)
            assertThat(sink.events("carplay.run.end").size).isEqualTo(1)
        }

    @Test
    fun `the Bluetooth link keeps acknowledging the iPhone's packets after CarPlay is connected`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (link, _) = started()
            // DiPlay's wireless parameters: every data packet must be acknowledged within 500 ms.
            val phone = PhoneIap2(fake.dialed.receive(), PhoneIap2.ACKING_PARAMETERS)
            bootstrap(phone)
            fake.pairings.save(phoneId, phoneKeys.publicKey)
            check(PhoneAirPlay(fake.listeners.single().connect(), random).pairVerify(phoneId, phoneKeys))
            runCurrent()
            check(link.state.value is CarPlayState.Connected)

            val sentAt = currentTime
            val sequence = phone.send(iap2Message(0x4E0D))
            phone.awaitAcknowledgement(sequence)

            assertThat(currentTime - sentAt).isLessThanOrEqualTo(500L)
            assertThat(link.state.value).isInstanceOf<CarPlayState.Connected>()
        }

    @Test
    fun `a port whose own timeout expires fails the attempt and is retried, never a silent stop`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.groupTimesOutInside = true
            val (link, _) = started()
            advanceTimeBy(2) // the port's own 1 ms timeout

            val error = (link.state.value as CarPlayState.Recovering).error
            fake.groupTimesOutInside = false
            advanceTimeBy(2_500)

            assertThat(error).isInstanceOf<CarPlayError.Unexpected>()
            assertThat(fake.groupsCreated).isEqualTo(1)
            assertThat(link.keepAlive.value).isTrue()
        }

    @Test
    fun `a cleanup step that times out does not leave the radio or the listener open`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.unpublishTimesOutInside = true
            fake.dialError = CarPlayError.DialFailed("page timeout")
            val (link, _) = started()

            advanceTimeBy(fake.dialMillis + 2) // the dial failure, then unpublish's own 1 ms timeout

            assertThat(fake.listeners.single().closed).isTrue()
            assertThat(fake.groupsRemoved).isEqualTo(1)
            // The attempt's own error, not the cleanup's timeout.
            assertThat(
                (link.state.value as CarPlayState.Recovering).error,
            ).isEqualTo(CarPlayError.DialFailed("page timeout"))
        }

    @Test
    fun `a dial that finished while the group took longer than the dial limit is used`() =
        runTest(timeout = TEST_TIMEOUT) {
            fake.createGroupMillis = 16_000
            val (link, seen) = started()
            fake.dialed.receive()

            advanceTimeBy(16_001)

            assertThat(seen.filterIsInstance<CarPlayState.Recovering>()).isEmpty()
            assertThat(link.state.value).isEqualTo(CarPlayState.Connecting(ConnectStep.IDENTIFYING))
        }

    @Test
    fun `a request handler that fails answers 500 and keeps the connection`() =
        runTest(timeout = TEST_TIMEOUT) {
            started()
            bootstrap(PhoneIap2(fake.dialed.receive()))
            fake.pairings.save(phoneId, phoneKeys.publicKey)
            fake.pairingsBroken = true
            val airPlay = PhoneAirPlay(fake.listeners.single().connect(), random)

            val verified = airPlay.pairVerify(phoneId, phoneKeys)
            val info = airPlay.request("GET", "/info")

            assertThat(verified).isFalse()
            assertThat(sink.events("carplay.airplay.handler_failed").size).isEqualTo(1)
            assertThat(info.status).isEqualTo(200)
        }

    @Test
    fun `plaintext sent behind pair-verify M3 is refused, not served as authenticated`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (link, _) = started()
            bootstrap(PhoneIap2(fake.dialed.receive()))
            fake.pairings.save(phoneId, phoneKeys.publicKey)
            val airPlay = PhoneAirPlay(fake.listeners.single().connect(), random)
            val smuggled = "POST /auth-setup RTSP/1.0\r\nCSeq: 99\r\nContent-Length: 0\r\n\r\n".encodeToByteArray()

            airPlay.pairVerify(phoneId, phoneKeys, trailing = smuggled)
            runCurrent()

            assertThat(link.state.value).isEqualTo(CarPlayState.Connecting(ConnectStep.PAIRING))
            assertThat(sink.events("carplay.airplay.dropped").size).isEqualTo(1)
            assertThat(sink.events("carplay.airplay.verified")).isEmpty()
        }

    private companion object {
        const val SEED = 7
        val TEST_TIMEOUT = 20.seconds
    }
}
