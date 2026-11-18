package com.abid.handlebarlink.carplay.engine.android.net

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.android.AndroidPlatform
import com.abid.handlebarlink.carplay.engine.android.BlockingIo
import com.abid.handlebarlink.carplay.engine.android.ThreadedByteStream
import com.abid.handlebarlink.carplay.engine.spi.NetworkPort
import com.abid.handlebarlink.carplay.engine.spi.TcpConnection
import com.abid.handlebarlink.carplay.engine.spi.TcpListener
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

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
    ): TcpListener {
        // Android 17 blocks inbound local connections for apps targeting 37 without ACCESS_LOCAL_NETWORK
        // (local-network-permission: "Accepting an incoming TCP connection: yes").
        if (Build.VERSION.SDK_INT >= ANDROID_17 &&
            platform.context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw CarPlayException(CarPlayError.PermissionMissing(Manifest.permission.ACCESS_LOCAL_NETWORK))
        }
        return withContext(platform.io) {
            val servers =
                try {
                    // Numeric literals only (never a DNS lookup); a scoped "fe80::1%p2p-wlan0-0" keeps its interface.
                    val inet = addresses.map { InetAddress.getByName(it) }
                    PortBinder.bindAll(
                        addresses = inet,
                        preferredPort = preferredPort,
                        bind = { address, port -> ServerSocket().apply { bind(InetSocketAddress(address, port)) } },
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
            )
            AndroidTcpListener(servers, port, platform.log)
        }
    }

    private companion object {
        const val ANDROID_17 = 37
    }
}

/** Accept threads feed one queue; [accept] takes from it. Closing the servers aborts the blocked accepts. */
internal class AndroidTcpListener(
    private val servers: List<ServerSocket>,
    override val port: Int,
    private val log: Logger,
) : TcpListener {
    private val accepted = Channel<Socket>(QUEUE)
    private val closed = AtomicBoolean(false)

    init {
        servers.forEachIndexed { index, server ->
            Thread({ acceptLoop(server) }, "carplay-accept-$index").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!closed.get()) {
            val socket =
                try {
                    server.accept()
                } catch (e: IOException) {
                    if (!closed.get()) {
                        log.warn("carplay.android.listener.accept_failed", "error" to e.toString())
                        accepted.close(CarPlayException(CarPlayError.NetworkFailed("accept: $e"), e))
                    }
                    return
                }
            TcpTuning.configure(socket, log)
            if (accepted.trySend(socket).isFailure) {
                log.warn("carplay.android.listener.dropped", "peer" to socket.inetAddress?.hostAddress)
                closeQuietly(socket)
            }
        }
    }

    override suspend fun accept(): TcpConnection {
        val socket =
            try {
                accepted.receive()
            } catch (e: ClosedReceiveChannelException) {
                throw CarPlayException(CarPlayError.NetworkFailed("the AirPlay listener is closed"), e)
            }
        val peer = socket.inetAddress?.hostAddress.orEmpty()
        return AndroidTcpConnection(socket, peer)
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
    }
}

/** An accepted connection on its own threads. */
private class AndroidTcpConnection(
    socket: Socket,
    override val remoteAddress: String,
) : TcpConnection {
    private val stream = ThreadedByteStream(SocketIo(socket), "carplay-tcp") { CarPlayError.NetworkFailed(it) }

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

private fun closeQuietly(closeable: java.io.Closeable) {
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
    fun configure(
        socket: Socket,
        log: Logger,
    ) {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            ParcelFileDescriptor.fromSocket(socket).use { duplicate ->
                val fd = duplicate.fileDescriptor
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, KEEP_IDLE_SECONDS)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, KEEP_INTERVAL_SECONDS)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, KEEP_COUNT)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_USER_TIMEOUT, USER_TIMEOUT_MILLIS)
            }
        } catch (e: IOException) {
            log.warn("carplay.android.tcp.tuning_failed", "error" to e.toString())
        } catch (e: ErrnoException) {
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
