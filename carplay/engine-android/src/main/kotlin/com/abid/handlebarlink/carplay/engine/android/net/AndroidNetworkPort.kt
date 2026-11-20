package com.abid.handlebarlink.carplay.engine.android.net

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.android.AndroidPlatform
import com.abid.handlebarlink.carplay.engine.android.BlockingIo
import com.abid.handlebarlink.carplay.engine.android.ThreadedByteStream
import com.abid.handlebarlink.carplay.engine.spi.NetworkPort
import com.abid.handlebarlink.carplay.engine.spi.SocketUse
import com.abid.handlebarlink.carplay.engine.spi.TcpConnection
import com.abid.handlebarlink.carplay.engine.spi.TcpListener
import com.abid.handlebarlink.carplay.engine.spi.UdpSocket
import com.abid.handlebarlink.carplay.protocol.audio.AudioWire
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * The AirPlay listener: one port on every group address ([PortBinder]), an accept thread per address, and each
 * accepted connection tuned for liveness ([TcpTuning]) and served by a [ThreadedByteStream].
 */
internal class AndroidNetworkPort(
    private val platform: AndroidPlatform,
) : NetworkPort {
    override suspend fun listen(
        addresses: List<String>,
        preferredPort: Int,
        use: SocketUse,
    ): TcpListener {
        requireLocalNetwork()
        return openOnIo(platform.io, TcpListener::close) {
            val servers =
                try {
                    // Numeric literals only (never a DNS lookup); a scoped "fe80::1%p2p-wlan0-0" keeps its interface.
                    val inet = addresses.map { InetAddress.getByName(it) }
                    PortBinder.bindAll(
                        addresses = inet,
                        preferredPort = preferredPort,
                        bind = { address, port ->
                            boundOrClosed(ServerSocket()) { it.bind(InetSocketAddress(address, port)) }
                        },
                        portOf = { it.localPort },
                        close = { it.close() },
                    )
                } catch (e: IOException) {
                    throw CarPlayException(CarPlayError.NetworkFailed("AirPlay listener on $addresses: $e"), e)
                }
            val port = servers.first().localPort
            platform.log.info(
                "carplay.android.listener.bound",
                "port" to port,
                "preferred" to preferredPort,
                "addresses" to addresses,
                "use" to use.name,
            )
            AndroidTcpListener(servers, port, platform.log, readPriority = readPriority(use))
        }
    }

    override suspend fun bindUdp(use: SocketUse): UdpSocket {
        requireLocalNetwork()
        return openOnIo(platform.io, UdpSocket::close) {
            val socket =
                try {
                    // DiPlay's `DatagramSocket(null)`, reuseAddress, bound to [::]:0 (NtpClock.kt:41-43).
                    boundOrClosed(DatagramSocket(null)) {
                        it.reuseAddress = true
                        it.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
                    }
                } catch (e: IOException) {
                    throw CarPlayException(CarPlayError.NetworkFailed("UDP socket: $e"), e)
                }
            if (use == SocketUse.AUDIO) raiseReceiveBuffer(socket)
            platform.log.debug(
                "carplay.android.udp.bound",
                "port" to socket.localPort,
                "use" to use.name,
                "rcvbuf" to socket.receiveBufferSize,
            )
            AndroidUdpSocket(
                socket,
                receivePriority =
                    if (use ==
                        SocketUse.AUDIO
                    ) {
                        Process.THREAD_PRIORITY_AUDIO
                    } else {
                        null
                    },
            )
        }
    }

    /**
     * Android 17 blocks local traffic for apps targeting 37 without ACCESS_LOCAL_NETWORK (local-network-permission:
     * "Accepting an incoming TCP connection: yes").
     */
    private fun requireLocalNetwork() {
        if (Build.VERSION.SDK_INT >= ANDROID_17 &&
            platform.context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw CarPlayException(CarPlayError.PermissionMissing(Manifest.permission.ACCESS_LOCAL_NETWORK))
        }
    }

    /**
     * An audio data socket keeps Wi-Fi bursts in the kernel while the receiver pauses: DiPlay asks for 512 KiB when the
     * socket has less, best effort; the platform may grant less, which the bound log shows (AudioStream.kt:62-68).
     */
    private fun raiseReceiveBuffer(socket: DatagramSocket) {
        try {
            if (socket.receiveBufferSize < AudioWire.RECEIVE_BUFFER_BYTES) {
                socket.receiveBufferSize = AudioWire.RECEIVE_BUFFER_BYTES
            }
        } catch (e: IOException) {
            platform.log.warn("carplay.android.udp.rcvbuf_failed", "error" to e.toString())
        }
    }

    private companion object {
        const val ANDROID_17 = 37

        /** DiPlay raises the screen reader to display priority (ScreenStream.kt:75-76). */
        fun readPriority(use: SocketUse): Int? = if (use == SocketUse.SCREEN) Process.THREAD_PRIORITY_DISPLAY else null
    }
}

/**
 * Opens a resource with blocking [open] on [io] so that it is never lost: the open is not cancelled halfway, and when
 * the caller was cancelled meanwhile (withContext then drops the result and throws), the resource is closed with
 * [close] before the cancellation goes on.
 */
internal suspend fun <T : Any> openOnIo(
    io: CoroutineContext,
    close: (T) -> Unit,
    open: () -> T,
): T {
    var opened: T? = null
    try {
        return withContext(io + NonCancellable) { open().also { opened = it } }
    } catch (e: CancellationException) {
        opened?.let(close)
        throw e
    }
}

/** Runs [bind] on a fresh [socket]; a failed bind closes the socket, whose descriptor would otherwise stay open. */
internal inline fun <S : Closeable> boundOrClosed(
    socket: S,
    bind: (S) -> Unit,
): S {
    try {
        bind(socket)
    } catch (e: IOException) {
        closeQuietly(socket)
        throw e
    }
    return socket
}

/**
 * Accept threads feed one queue; [accept] takes from it. Closing the servers aborts the blocked accepts. A failed
 * accept on one address is retried there (accept(2): pending network errors such as ECONNABORTED or EPROTO are passed
 * back and should be retried); only an address that keeps failing, or whose server closed, stops, and the listener
 * fails only once every address has stopped. A socket taken by an accept that was cancelled is closed.
 */
internal class AndroidTcpListener(
    private val servers: List<ServerSocket>,
    override val port: Int,
    private val log: Logger,
    /** The connections' reader priority ([ThreadedByteStream]); null keeps the default. */
    private val readPriority: Int? = null,
    /** The pause before an address accepts again after a failure. */
    private val retryPause: () -> Unit = { Thread.sleep(ACCEPT_RETRY_MILLIS) },
) : TcpListener {
    private val accepted = Channel<Socket>(QUEUE, onUndeliveredElement = ::closeQuietly)
    private val closed = AtomicBoolean(false)
    private val accepting = AtomicInteger(servers.size)

    init {
        servers.forEachIndexed { index, server ->
            Thread({ acceptLoop(server) }, "carplay-accept-$index").apply {
                isDaemon = true
                start()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // On a bare thread an escaping exception would kill the whole process.
    private fun acceptLoop(server: ServerSocket) {
        var failures = 0
        while (!closed.get()) {
            val socket =
                try {
                    server.accept()
                } catch (e: IOException) {
                    failures++
                    if (!closed.get()) failed(server, e, failures)
                    null
                } catch (e: RuntimeException) {
                    if (!closed.get()) stopped(e)
                    failures = MAX_ACCEPT_FAILURES
                    null
                }
            if (socket != null) {
                failures = 0
                handOver(socket)
            } else if (closed.get() || server.isClosed || failures >= MAX_ACCEPT_FAILURES) {
                break
            }
        }
    }

    /** A failed accept: logged, retried after a pause, or the end of this address when it keeps failing. */
    private fun failed(
        server: ServerSocket,
        error: IOException,
        failures: Int,
    ) {
        log.warn("carplay.android.listener.accept_failed", "error" to error.toString(), "failures" to failures)
        if (server.isClosed || failures >= MAX_ACCEPT_FAILURES) stopped(error) else retryPause()
    }

    private fun handOver(socket: Socket) {
        TcpTuning.configure(socket, log)
        if (accepted.trySend(socket).isFailure) {
            log.warn("carplay.android.listener.dropped", "peer" to socket.inetAddress?.hostAddress)
            closeQuietly(socket)
        }
    }

    /** One address stopped accepting; the last one fails the listener. */
    private fun stopped(cause: Exception) {
        if (accepting.decrementAndGet() > 0) return
        log.warn("carplay.android.listener.failed", "error" to cause.toString())
        accepted.close(CarPlayException(CarPlayError.NetworkFailed("accept: $cause"), cause))
    }

    override suspend fun accept(): TcpConnection {
        val socket =
            try {
                accepted.receive()
            } catch (e: ClosedReceiveChannelException) {
                throw CarPlayException(CarPlayError.NetworkFailed("the AirPlay listener is closed"), e)
            }
        val peer = socket.inetAddress?.hostAddress.orEmpty()
        return AndroidTcpConnection(socket, peer, readPriority)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        servers.forEach { closeQuietly(it) }
        accepted.close()
        while (true) {
            val pending = accepted.tryReceive().getOrNull() ?: break
            closeQuietly(pending)
        }
    }

    private companion object {
        /** More waiting connections than the engine ever serves (8, DiPlay PR #1130) are refused here. */
        const val QUEUE = 16

        /** Failures in a row after which one address stops accepting. */
        const val MAX_ACCEPT_FAILURES = 10

        const val ACCEPT_RETRY_MILLIS = 100L
    }
}

/** An accepted connection on its own threads. */
private class AndroidTcpConnection(
    socket: Socket,
    override val remoteAddress: String,
    readPriority: Int?,
) : TcpConnection {
    private val stream =
        ThreadedByteStream(SocketIo(socket), "carplay-tcp", readPriority) { CarPlayError.NetworkFailed(it) }

    override suspend fun read(buffer: ByteArray): Int = stream.read(buffer)

    override suspend fun write(bytes: ByteArray) = stream.write(bytes)

    override fun close() = stream.close()
}

private class SocketIo(
    private val socket: Socket,
) : BlockingIo {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() = socket.close()
}

internal fun closeQuietly(closeable: Closeable) {
    try {
        closeable.close()
    } catch (_: IOException) {
        // Already closed.
    }
}

/**
 * Notice a vanished iPhone without treating an idle CarPlay screen as one (DiPlay TcpLiveness.kt:12-43):
 * TCP_NODELAY, keep-alive after 10 s idle, every 3 s, 3 probes, and TCP_USER_TIMEOUT 20 s for unacknowledged data.
 * KEEPIDLE/KEEPINTVL/KEEPCNT are the Linux UAPI numbers (<linux/tcp.h> 4/5/6), which `OsConstants` does not expose;
 * the descriptor is a duplicate from `ParcelFileDescriptor.fromSocket` (API 29+), closed after tuning. A failure is
 * logged, never fatal.
 */
internal object TcpTuning {
    @Suppress("TooGenericExceptionCaught") // Runs on a bare accept thread: an escaping exception kills the process.
    fun configure(
        socket: Socket,
        log: Logger,
    ) {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            // fromSocket wraps a failed dup in UncheckedIOException and is null for a socket without a descriptor.
            val duplicate: ParcelFileDescriptor? = ParcelFileDescriptor.fromSocket(socket)
            if (duplicate == null) {
                log.warn("carplay.android.tcp.tuning_failed", "error" to "no descriptor")
                return
            }
            duplicate.use {
                val fd = it.fileDescriptor
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, KEEP_IDLE_SECONDS)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, KEEP_INTERVAL_SECONDS)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, KEEP_COUNT)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_USER_TIMEOUT, USER_TIMEOUT_MILLIS)
            }
        } catch (e: IOException) {
            log.warn("carplay.android.tcp.tuning_failed", "error" to e.toString())
        } catch (e: ErrnoException) {
            log.warn("carplay.android.tcp.tuning_failed", "error" to e.toString())
        } catch (e: RuntimeException) {
            log.warn("carplay.android.tcp.tuning_failed", "error" to e.toString())
        }
    }

    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6
    private const val KEEP_IDLE_SECONDS = 10
    private const val KEEP_INTERVAL_SECONDS = 3
    private const val KEEP_COUNT = 3
    private const val USER_TIMEOUT_MILLIS = 20_000
}
