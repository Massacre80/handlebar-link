package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.runtime.RuntimeEnv
import com.abid.handlebarlink.carplay.engine.spi.BonjourService
import com.abid.handlebarlink.carplay.engine.spi.CarPlayCredentials
import com.abid.handlebarlink.carplay.engine.spi.CarPlayPorts
import com.abid.handlebarlink.carplay.engine.spi.TcpConnection
import com.abid.handlebarlink.carplay.engine.spi.TcpListener
import com.abid.handlebarlink.carplay.engine.spi.WifiGroup
import com.abid.handlebarlink.carplay.protocol.iap2.AccessoryIdentity
import com.abid.handlebarlink.carplay.protocol.iap2.StartSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One wireless connection attempt, in DiPlay's order (CarPlayController.kt:1377-1886, spec D §1.2):
 * the RFCOMM dial starts first and runs while the Wi-Fi Direct group, the AirPlay listener and the Bonjour
 * record come up; then iAP2 identification, MFi authentication and StartSession; then the iPhone joins the
 * group and opens the AirPlay control connection, which must pass pair-verify.
 *
 * [run] lasts as long as the verified control connection and always ends with a [CarPlayException] (or
 * cancellation). Everything it opened is closed in reverse order, also when cancelled. Losing Bluetooth
 * after the AirPlay connection is verified is normal (the iPhone moves iAP2 into the Wi-Fi session) and is
 * only logged.
 *
 * Connection improvements from docs/research/carplay-spec/E-connection.md: Bonjour runs beside the bootstrap and
 * never blocks it (#4), the addresses go IPv4 first (#7), StartSession is nudged when no TCP follows (#3), and a
 * [LinkWatch] fails a stalled start in seconds (#10).
 */
internal class WirelessAttempt(
    private val ports: CarPlayPorts,
    private val config: CarPlayConfig,
    private val env: RuntimeEnv,
    private val onStep: (ConnectStep) -> Unit,
    private val onConnected: (String) -> Unit,
    /** Builds each AirPlay session's media seams. */
    private val media: MediaFactory = MediaFactory.PLACEHOLDERS,
) {
    private val cleanup = Cleanup(env.log)
    private val verified = CompletableDeferred<String>()

    /** AirPlay control connections being served. */
    private var open = 0

    private val watch = LinkWatcher(ports.wifiDirect.clients(), env.time, env.log)

    suspend fun run(): Nothing {
        try {
            coroutineScope { connect() }
        } finally {
            withContext(NonCancellable) { cleanup.closeAll() }
        }
    }

    private suspend fun CoroutineScope.connect(): Nothing {
        onStep(ConnectStep.LOADING_IDENTITY)
        val credentials = ports.credentials.load()
        onStep(ConnectStep.PREPARING)
        val dialStart = env.time.elapsedMillis()
        val dial = async { ports.bluetooth.dialIap2().also { stream -> cleanup.add("rfcomm") { stream.close() } } }
        // Registered first: a group created just as the timeout fires is still removed (removeGroup is idempotent).
        cleanup.add("wifi-direct") { ports.wifiDirect.removeGroup() }
        val group = step(GROUP_TIMEOUT, ConnectStep.PREPARING) { ports.wifiDirect.createGroup() }
        val addresses = ipv4First(group.addresses)
        env.log.info("carplay.wifi.group", "network" to group.network, "addresses" to addresses)
        val listener = ports.network.listen(addresses, AIRPLAY_PORT)
        cleanup.add("listener") { listener.close() }
        env.log.info("carplay.airplay.listening", "port" to listener.port)
        val receiver = receiver(credentials, group)
        val platform = SessionPlatform(ports.network, addresses, ports.cipherName, env.time, media)
        val control = { connection: TcpConnection ->
            AirPlayControlConnection(connection, credentials, receiver, env.secureRandom, platform, env.log)
        }
        // Registered before publishing starts: a publish that is still running when the attempt ends is undone.
        cleanup.add("bonjour") { ports.bonjour.unpublish() }
        launch { publish(receiver.bonjour(addresses.first().substringBefore('%'), listener.port)) }
        launch { watch.followClients() }
        launch { watch.run() }

        onStep(ConnectStep.DIALING_PHONE)
        val dialLeft = DIAL_TIMEOUT - (env.time.elapsedMillis() - dialStart)
        // A dial that finished while the group came up is used even when the group took the whole dial time.
        val stream = if (dial.isCompleted) dial.await() else step(dialLeft, ConnectStep.DIALING_PHONE) { dial.await() }
        env.log.info("carplay.bluetooth.dialed", "after_ms" to env.time.elapsedMillis() - dialStart)
        val channel = Iap2Channel(stream, env.time, env.log)
        val startSessionSent = CompletableDeferred<Unit>()
        val tcpArrived = CompletableDeferred<Unit>()
        launch {
            bootstrap(
                channel,
                BootstrapSetup(
                    identity(credentials),
                    ports.bluetooth.localAddress(),
                    credentials.mfi,
                    startSession(group, addresses, listener, credentials),
                ),
                startSessionSent,
                tcpArrived,
            )
        }

        step(START_SESSION_TIMEOUT, ConnectStep.AUTHENTICATING) { startSessionSent.await() }
        onStep(ConnectStep.WAITING_FOR_PHONE)
        val first = step(FIRST_TCP_TIMEOUT, ConnectStep.WAITING_FOR_PHONE) { listener.accept() }
        tcpArrived.complete(Unit)
        watch.tcpAccepted()
        onStep(ConnectStep.PAIRING)
        val ended = CompletableDeferred<CarPlayException>()
        launch { serve(first, control, ended) }
        launch { acceptMore(listener, control, ended) }
        val phone = step(PAIRING_TIMEOUT, ConnectStep.PAIRING) { verified.await() }
        watch.connected()
        ports.wifiDirect.groupWorked()
        onConnected(phone)
        throw ended.await()
    }

    /** Bonjour beside the bootstrap (DiPlay PR #972; AllPlay CHANGELOG.md:331-338): a failure is only logged. */
    private suspend fun publish(service: BonjourService) {
        try {
            ports.bonjour.publish(service)
            env.log.info("carplay.bonjour.published", "port" to service.port)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // Ours: the attempt ends. Otherwise the port's own timeout.
            env.log.warn("carplay.bonjour.failed", "error" to e.toString())
        } catch (e: CarPlayException) {
            env.log.warn("carplay.bonjour.failed", "error" to e.error)
        }
    }

    /** iAP2 over Bluetooth until the iPhone hands over to Wi-Fi; fatal only before AirPlay is verified. */
    private suspend fun bootstrap(
        channel: Iap2Channel,
        setup: BootstrapSetup,
        startSessionSent: CompletableDeferred<Unit>,
        tcpArrived: CompletableDeferred<Unit>,
    ) {
        val milestones =
            object : BootstrapMilestones {
                override fun wifiOffered() = watch.wifiOffered()

                override fun startSessionSent() {
                    watch.startSessionSent()
                    startSessionSent.complete(Unit)
                }
            }
        try {
            coroutineScope {
                launch { channel.run() }
                val control = BootstrapControl(channel, setup, env.time, env.log, milestones)
                onStep(ConnectStep.IDENTIFYING)
                step(IDENTIFY_TIMEOUT, ConnectStep.IDENTIFYING) {
                    channel.connected.await()
                    control.identify()
                }
                onStep(ConnectStep.AUTHENTICATING)
                step(AUTHENTICATE_TIMEOUT, ConnectStep.AUTHENTICATING) { control.authenticate() }
                launch { control.nudge(tcpArrived) }
                control.serve()
            }
        } catch (e: CarPlayException) {
            if (!verified.isCompleted) throw e
            env.log.info("carplay.bootstrap.ended", "reason" to e.error)
        }
    }

    /**
     * Further control connections, at most [MAX_CONNECTIONS] open in total. DiPlay PR #1130 also limits each peer
     * and evicts idle unverified connections; only the iPhone holds the group's passphrase, so the total cap is
     * kept and the rest left to the media milestone.
     */
    private suspend fun CoroutineScope.acceptMore(
        listener: TcpListener,
        control: (TcpConnection) -> AirPlayControlConnection,
        ended: CompletableDeferred<CarPlayException>,
    ) {
        while (true) {
            val connection = listener.accept()
            if (open >= MAX_CONNECTIONS) {
                env.log.warn("carplay.airplay.refused", "peer" to connection.remoteAddress)
                connection.close()
            } else {
                // Undispatched, so serve counts the connection before the next accept.
                launch(start = CoroutineStart.UNDISPATCHED) { serve(connection, control, ended) }
            }
        }
    }

    /** Serves one control connection; a verified one ending ends the attempt. */
    private suspend fun serve(
        connection: TcpConnection,
        connect: (TcpConnection) -> AirPlayControlConnection,
        ended: CompletableDeferred<CarPlayException>,
    ) = coroutineScope {
        open++
        env.log.info("carplay.airplay.accepted", "peer" to connection.remoteAddress)
        val control = connect(connection)
        // An unverified connection gets 30 s, then it is closed (DiPlay PRE_PAIR_VERIFY_TIMEOUT_MILLIS, PR #1130).
        val watchdog =
            launch {
                delay(PRE_VERIFY_TIMEOUT)
                if (control.verifiedPhone == null) connection.close()
            }
        try {
            control.run { phone ->
                watchdog.cancel()
                verified.complete(phone)
            }
        } catch (e: CarPlayException) {
            if (control.verifiedPhone !=
                null
            ) {
                ended.complete(e)
            } else {
                env.log.info("carplay.airplay.dropped", "reason" to e.error)
            }
        } finally {
            open--
            watchdog.cancel()
            connection.close()
        }
    }

    private fun receiver(
        credentials: CarPlayCredentials,
        group: WifiGroup,
    ) = AirPlayReceiver(
        config,
        deviceId = group.interfaceMac,
        bluetoothId = ports.bluetooth.localAddress().joinToString(":") { "%02X".format(it) },
        pairingId = credentials.airPlay.pairingId,
        publicKeyHex = credentials.airPlay.publicKeyHex,
    )

    private fun identity(credentials: CarPlayCredentials) =
        AccessoryIdentity(
            name = config.name,
            model = config.model,
            manufacturer = config.manufacturer,
            serialNumber =
                "HL-" +
                    credentials.airPlay.publicKeyHex
                        .take(SERIAL_HEX)
                        .uppercase(),
            firmwareVersion = "1.0",
            hardwareVersion = "1.0",
            externalAccessoryProtocol = "com.abid.handlebarlink",
        )

    private fun startSession(
        group: WifiGroup,
        addresses: List<String>,
        listener: TcpListener,
        credentials: CarPlayCredentials,
    ) = StartSession(
        network = group.network,
        // Every bound address, IPv4 first, without the IPv6 scope (DiPlay CarPlayController.kt:1753-1755, 3404-3410).
        ipAddresses = addresses.map { it.substringBefore('%') }.distinct(),
        airPlayPort = listener.port,
        deviceId = group.interfaceMac,
        publicKeyHex = credentials.airPlay.publicKeyHex,
        sourceVersion = AirPlayReceiver.SOURCE_VERSION,
    )

    /** Runs [block] within [timeoutMillis]; a timeout fails the attempt as [step] (never a silent cancellation). */
    private suspend fun <T : Any> step(
        timeoutMillis: Long,
        step: ConnectStep,
        block: suspend () -> T,
    ): T =
        withTimeoutOrNull(timeoutMillis.coerceAtLeast(0)) { block() }
            ?: throw CarPlayException(CarPlayError.TimedOut(step))

    companion object {
        /**
         * IPv4 first, then the rest in the platform's order (DiPlay HotspotAddressPolicy.kt:19-30 prefers a usable
         * IPv4; CarLink WirelessAddressFamilyPlan.kt:29-36 measured IPv6 first as slower; DiPlay #242, #1344).
         */
        fun ipv4First(addresses: List<String>): List<String> = addresses.sortedBy { if (IPV4.matches(it)) 0 else 1 }

        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** DiPlay's timings (spec D §1.2, §1.6). */
        const val GROUP_TIMEOUT = 20_000L
        const val DIAL_TIMEOUT = 15_000L
        const val IDENTIFY_TIMEOUT = 30_000L
        const val AUTHENTICATE_TIMEOUT = 30_000L
        const val START_SESSION_TIMEOUT = 60_000L
        const val FIRST_TCP_TIMEOUT = 30_000L
        const val PAIRING_TIMEOUT = 30_000L
        const val PRE_VERIFY_TIMEOUT = 30_000L
        const val AIRPLAY_PORT = 7000
        const val MAX_CONNECTIONS = 8
        private const val SERIAL_HEX = 12
    }
}
