package com.abid.handlebarlink.carplay.engine.android.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothSocketException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.android.AndroidPlatform
import com.abid.handlebarlink.carplay.engine.android.BlockingIo
import com.abid.handlebarlink.carplay.engine.android.ReceiverIds
import com.abid.handlebarlink.carplay.engine.android.Settings
import com.abid.handlebarlink.carplay.engine.android.ThreadedByteStream
import com.abid.handlebarlink.carplay.engine.android.connectOnOwnThread
import com.abid.handlebarlink.carplay.engine.android.credentials.IdentityStore
import com.abid.handlebarlink.carplay.engine.android.fail
import com.abid.handlebarlink.carplay.engine.android.pairing.IphoneChoices
import com.abid.handlebarlink.carplay.engine.spi.BluetoothPort
import com.abid.handlebarlink.carplay.engine.spi.ByteStream
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which iPhone CarPlay uses ([IphoneChoice]), shared by the Bluetooth port (it dials it) and the Wi-Fi Direct port
 * (its stable network is per iPhone). Live links come from ACL broadcasts, watched from the first use on.
 */
internal class IphoneSelector(
    private val platform: AndroidPlatform,
    private val settings: Settings,
) {
    private val linked = ConcurrentHashMap.newKeySet<String>()
    private val watching = AtomicBoolean(false)

    /** Addresses with a live Bluetooth link seen since the watch started. */
    fun linked(): Set<String> = linked.toSet()

    /** The rider's choice from the app (CarPlayIphone.choose), or null. */
    fun chosenAddress(): String? = settings.string(CHOSEN_KEY)

    /**
     * The iPhone to use. Blocking (Binder): call from a background thread.
     *
     * @throws CarPlayException PermissionMissing without BLUETOOTH_CONNECT, BluetoothUnavailable when no iPhone fits.
     */
    fun select(adapter: BluetoothAdapter): BondedDevice {
        watchLinks()
        if (platform.context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw CarPlayException(CarPlayError.PermissionMissing(Manifest.permission.BLUETOOTH_CONNECT))
        }
        val bonded =
            try {
                adapter.bondedDevices.orEmpty().map { BondedDevice(it.address, it.name) }
            } catch (e: SecurityException) {
                throw CarPlayException(CarPlayError.PermissionMissing(Manifest.permission.BLUETOOTH_CONNECT), e)
            }
        // iPhones the rider forgot in the app stay paired with Android (no public unpair): never pick one by itself.
        val forgot = IphoneChoices.parseForgot(settings.string(IphoneChoices.FORGOT_KEY))
        val choice = IphoneChoice.choose(chosenAddress(), bonded, linked(), forgot)
        platform.log.info(
            "carplay.android.iphone.selected",
            "name" to choice.name,
            "address" to choice.address,
            "chosen" to (chosenAddress() != null),
            "bonded_iphones" to bonded.count { it.name?.contains("iPhone", ignoreCase = true) == true },
            "linked" to linked().any { it.equals(choice.address, ignoreCase = true) },
        )
        return choice
    }

    /**
     * ACL links from the Bluetooth stack's broadcasts. They are protected broadcasts sent by the Bluetooth process,
     * not the system, so the receiver must be exported (as :bike:link-android's AndroidSystemSignals); no app can
     * forge a protected broadcast. Registered once, for the life of the process (the ports are a singleton).
     */
    private fun watchLinks() {
        if (!watching.compareAndSet(false, true)) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val device = intent.device() ?: return
                    when (intent.action) {
                        BluetoothDevice.ACTION_ACL_CONNECTED -> linked.add(device.address)
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> linked.remove(device.address)
                        else -> return
                    }
                    platform.log.debug(
                        "carplay.android.bluetooth.acl",
                        "action" to intent.action,
                        "address" to device.address,
                    )
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        ContextCompat.registerReceiver(
            platform.context,
            receiver,
            filter,
            null,
            platform.handler,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    companion object {
        const val CHOSEN_KEY = "iphone.address"
    }
}

/**
 * Bluetooth: the RFCOMM dial to the iPhone's iAP2 service and the Bluetooth-on signal. The connect runs on a thread
 * of its own and the stream's reads and writes on two more ([ThreadedByteStream]); cancelling either closes the
 * socket.
 */
internal class AndroidBluetoothPort(
    private val platform: AndroidPlatform,
    private val selector: IphoneSelector,
    private val identities: IdentityStore,
) : BluetoothPort {
    @Volatile private var loggedAddress: String? = null

    override suspend fun dialIap2(): ByteStream {
        val (socket, address) = withContext(platform.io) { openSocket() }
        val linked = selector.linked().any { it.equals(address, ignoreCase = true) }
        val start = platform.time.elapsedMillis()
        platform.log.info("carplay.android.rfcomm.dialing", "address" to address, "acl_up" to linked)
        val connected =
            connectOnOwnThread(
                threadName = "carplay-rfcomm-dial",
                socket = socket,
                connect = { it.connect() },
                close = { it.close() },
                failure = { e -> dialFailure(e, address, platform.time.elapsedMillis() - start) },
            )
        platform.log.info(
            "carplay.android.rfcomm.connected",
            "after_ms" to platform.time.elapsedMillis() - start,
            "acl_up" to linked,
        )
        return ThreadedByteStream(RfcommIo(connected), "carplay-rfcomm") { CarPlayError.LinkFailed(it) }
    }

    /** Checks, picks the iPhone and creates the (unconnected) secure socket. */
    private fun openSocket(): Pair<BluetoothSocket, String> {
        val context = platform.context
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            fail(CarPlayError.PermissionMissing(Manifest.permission.BLUETOOTH_CONNECT))
        }
        val adapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: fail(CarPlayError.BluetoothUnavailable("this phone has no Bluetooth"))
        if (!adapter.isEnabled) fail(CarPlayError.BluetoothUnavailable("Bluetooth is off"))
        val iphone = selector.select(adapter)
        // "Always call cancelDiscovery() before connect()" (Android "Connect Bluetooth devices"); it needs
        // BLUETOOTH_SCAN, which the app holds for its own setup scan. Without it no discovery of ours can be running.
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            adapter.cancelDiscovery()
        }
        return try {
            adapter.getRemoteDevice(iphone.address).createRfcommSocketToServiceRecord(IAP2_UUID) to iphone.address
        } catch (e: SecurityException) {
            fail(CarPlayError.PermissionMissing(Manifest.permission.BLUETOOTH_CONNECT), e)
        } catch (e: java.io.IOException) {
            fail(CarPlayError.DialFailed("RFCOMM socket: $e"), e)
        }
    }

    private fun dialFailure(
        e: Exception,
        address: String,
        afterMillis: Long,
    ): CarPlayException {
        if (e is SecurityException) {
            return CarPlayException(
                CarPlayError.PermissionMissing(Manifest.permission.BLUETOOTH_CONNECT),
                e,
            )
        }
        // BluetoothSocketException (API 34) carries the stack's reason code: device check P11.
        val code =
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ) {
                (e as? BluetoothSocketException)?.errorCode
            } else {
                null
            }
        platform.log.warn(
            "carplay.android.rfcomm.failed",
            "address" to address,
            "after_ms" to afterMillis,
            "code" to code,
            "error" to e.toString(),
        )
        return CarPlayException(CarPlayError.DialFailed(if (code != null) "$e (code $code)" else e.toString()), e)
    }

    /**
     * This phone's Bluetooth address for identification: the id derived from the AirPlay identity, which is what
     * DiPlay and CarLink send on this Poco ([ReceiverIds]). The real address is out of reach: `getAddress()` returns
     * 02:00:00:00:00:00 without LOCAL_MAC_ADDRESS (a signature|privileged permission), and the `bluetooth_address`
     * secure setting is guarded by the same permission (AOSP SettingsProvider.java main :2130-2142). The engine calls
     * this after loading the credentials, so the identity is already in memory.
     */
    override fun localAddress(): ByteArray {
        val identity = identities.peek() ?: identities.load()
        val address = ReceiverIds.fromIdentity(identity.keys.publicKey)
        val text = ReceiverIds.format(address)
        if (loggedAddress != text) {
            loggedAddress = text
            platform.log.info(
                "carplay.android.bluetooth.local_address",
                "source" to ReceiverIds.Source.IDENTITY,
                "address" to text,
            )
        }
        return address
    }

    override val radioOn: Flow<Unit> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                            BluetoothAdapter.STATE_ON
                        ) {
                            platform.log.info("carplay.android.bluetooth.on")
                            trySend(Unit)
                        }
                    }
                }
            // Sent by the Bluetooth stack, not the system: exported, like the ACL watch (a protected broadcast).
            ContextCompat.registerReceiver(
                platform.context,
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                null,
                platform.handler,
                ContextCompat.RECEIVER_EXPORTED,
            )
            awaitClose { platform.context.unregisterReceiver(receiver) }
        }

    /** The connected socket's streams. */
    private class RfcommIo(
        private val socket: BluetoothSocket,
    ) : BlockingIo {
        private val input = socket.inputStream
        private val output = socket.outputStream

        override fun read(buffer: ByteArray): Int = input.read(buffer)

        override fun write(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }

        override fun close() {
            socket.close()
        }
    }

    companion object {
        /** The iPhone's iAP2 RFCOMM service (DiPlay CarPlayController.kt:1465; spec A §1). */
        val IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    }
}

private fun Intent.device(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        // Android 12: the typed overload exists from API 33.
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }
