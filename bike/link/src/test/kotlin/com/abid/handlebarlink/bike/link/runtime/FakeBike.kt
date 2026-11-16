package com.abid.handlebarlink.bike.link.runtime

import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkException
import com.abid.handlebarlink.bike.link.spi.ClockServer
import com.abid.handlebarlink.bike.link.spi.ConnectionChange
import com.abid.handlebarlink.bike.link.spi.FakeGattLink
import com.abid.handlebarlink.bike.link.spi.GattConnection
import com.abid.handlebarlink.bike.link.spi.GattLink
import com.abid.handlebarlink.bike.link.spi.GattRadio
import com.abid.handlebarlink.bike.link.spi.LinkPorts
import com.abid.handlebarlink.bike.link.spi.SystemChange
import com.abid.handlebarlink.bike.link.spi.SystemFacts
import com.abid.handlebarlink.bike.link.spi.SystemSignals
import com.abid.handlebarlink.bike.link.spi.WakeLockPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * A whole CB350 behind the runtime's ports: ignition, radio, the meter, the clock server, the system
 * signals and the wake lock. Enforces Android's rule of one open connection, and logs the order of
 * platform calls in [calls].
 */
internal class FakeBike(
    private val scope: CoroutineScope,
) {
    var ignitionOn = false
        private set

    /** Radio time from connectGatt to connected when the meter is advertising (bike log: ~1.2 s). */
    var connectMillis = 1_200L

    /** Statuses the next direct attempts fail with, 500 ms after they start (e.g. 133, 62). */
    val directFailures = ArrayDeque<Int>()

    /** Applied to each new connection's meter: index 0 is the first setup on this bike. */
    var meterScript: (meter: FakeGattLink, setupIndex: Int) -> Unit = { meter, index ->
        if (index > 0) meter.assignmentStatus = { 0x0D }
    }

    val calls = mutableListOf<String>()
    val open = mutableMapOf<Int, Connection>()
    private var setups = 0

    val signals = Signals()
    val server = Server()
    val wakeLock = WakeLock()
    val radio =
        object : GattRadio {
            override fun open(
                gen: Int,
                autoConnect: Boolean,
            ): GattConnection {
                if (!signals.facts.permissionGranted) throw LinkException(LinkError.PermissionMissing)
                check(open.isEmpty()) { "connectGatt while generation ${open.keys} is still open" }
                calls += "open $gen ${if (autoConnect) "auto" else "direct"}"
                return Connection(gen, autoConnect).also { open[gen] = it }
            }
        }
    val ports = LinkPorts(radio, server, signals, wakeLock)

    /** The ignition: on lets waiting connections connect; off drops live links with status 8. */
    fun ignition(on: Boolean) {
        ignitionOn = on
        open.values.toList().forEach {
            if (on) {
                it.connectSoon()
            } else if (it.connected) {
                it.drop(STATUS_IGNITION_OFF)
            }
        }
    }

    /** The rider presses the switch (the live connection's meter sends [code]). */
    fun press(code: Int) {
        open.values
            .single { it.connected }
            .meter
            .press(code)
    }

    inner class Connection(
        val gen: Int,
        val autoConnect: Boolean,
        val meter: FakeGattLink = FakeGattLink(),
    ) : GattConnection,
        GattLink by meter {
        private val channel = Channel<ConnectionChange>(Channel.UNLIMITED)
        override val changes: Flow<ConnectionChange> = channel.receiveAsFlow()
        var connected = false
            private set
        var closed = false
            private set
        private var connecting = false

        init {
            val failure = if (autoConnect) null else directFailures.removeFirstOrNull()
            if (failure != null) {
                scope.launch {
                    delay(DIRECT_FAILURE_MILLIS)
                    drop(failure)
                }
            } else if (ignitionOn) {
                connectSoon()
            }
        }

        fun connectSoon() {
            if (connecting || connected || closed) return
            connecting = true
            scope.launch {
                delay(connectMillis)
                connecting = false
                if (!closed && ignitionOn) {
                    meterScript(meter, setups++)
                    connected = true
                    channel.trySend(ConnectionChange.Connected)
                }
            }
        }

        fun drop(status: Int) {
            if (closed) return
            connected = false
            channel.trySend(ConnectionChange.Disconnected(status))
        }

        fun serviceChanged() {
            channel.trySend(ConnectionChange.ServiceChanged)
        }

        override fun close() {
            if (closed) return
            closed = true
            connected = false
            open.remove(gen)
            calls += "close $gen"
        }
    }

    inner class Server : ClockServer {
        var opens = true
        var isOpen = false
            private set

        override fun open(): Boolean {
            calls += "server open"
            isOpen = opens
            return opens
        }

        override fun close() {
            if (isOpen) calls += "server close"
            isOpen = false
        }
    }

    class Signals : SystemSignals {
        var facts = SystemFacts(bluetoothOn = true, bonded = true, permissionGranted = true)
            private set
        private val flow = MutableSharedFlow<SystemChange>(extraBufferCapacity = 16)
        override val changes: Flow<SystemChange> = flow

        override fun current(): SystemFacts = facts

        fun report(change: SystemChange) {
            facts =
                when (change) {
                    is SystemChange.Bluetooth -> facts.copy(bluetoothOn = change.on)
                    is SystemChange.Bond -> facts.copy(bonded = change.bonded)
                    is SystemChange.Permission -> facts.copy(permissionGranted = change.granted)
                    SystemChange.KeyMissing -> facts
                }
            check(flow.tryEmit(change))
        }
    }

    class WakeLock : WakeLockPort {
        var held = false
            private set

        override fun acquire(timeoutMillis: Long) {
            held = true
        }

        override fun release() {
            held = false
        }
    }

    companion object {
        const val STATUS_IGNITION_OFF = 8
        const val DIRECT_FAILURE_MILLIS = 500L
    }
}
