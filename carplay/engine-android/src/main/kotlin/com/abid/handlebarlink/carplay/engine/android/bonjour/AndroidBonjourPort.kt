package com.abid.handlebarlink.carplay.engine.android.bonjour

import android.Manifest
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.android.AndroidPlatform
import com.abid.handlebarlink.carplay.engine.spi.BonjourPort
import com.abid.handlebarlink.carplay.engine.spi.BonjourService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * mDNS through `NsdManager`, no library. A registration with no `setNetwork` asks the system's advertiser for sockets
 * on every interface, and that includes the Wi-Fi Direct group owner's interface (AOSP Connectivity main,
 * `MdnsSocketProvider.requestSocket(null)` :683-709 adds the local-only, tethered and `mWifiP2pTetherInterface`
 * interfaces), so [BonjourService.address] needs no binding. TXT keys longer than 9 characters only log a warning
 * (`NsdServiceInfo.setAttribute` :379-381), and the TXT order is not kept (the attributes are a map); the iPhone
 * reads keys, not positions. Not on the critical path: the engine runs [publish] beside the bootstrap
 * (E-connection.md #4).
 */
internal class AndroidBonjourPort(
    private val platform: AndroidPlatform,
) : BonjourPort {
    private val nsd by lazy { platform.context.getSystemService(NsdManager::class.java) }
    private val registered = mutableListOf<NsdManager.RegistrationListener>()

    override suspend fun publish(service: BonjourService) {
        // NsdManager traffic is local-network traffic (local-network-permission, Android 17 for target 37).
        if (Build.VERSION.SDK_INT >= ANDROID_17 &&
            platform.context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw CarPlayException(CarPlayError.PermissionMissing(Manifest.permission.ACCESS_LOCAL_NETWORK))
        }
        // Getting NsdManager connects to the system's NsdService (a Binder call): off the caller's thread.
        val manager =
            withContext(platform.io) { nsd } ?: throw CarPlayException(CarPlayError.NetworkFailed("no NsdManager"))
        val info =
            try {
                NsdServiceInfo().apply {
                    serviceName = service.name
                    serviceType = service.type
                    port = service.port
                    service.txt.forEach { (key, value) -> setAttribute(key, value) }
                }
            } catch (e: IllegalArgumentException) {
                throw CarPlayException(CarPlayError.NetworkFailed("mDNS record of ${service.type}: $e"), e)
            }
        suspendCancellableCoroutine { continuation ->
            val listener =
                object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                        platform.log.info(
                            "carplay.android.mdns.registered",
                            "type" to service.type,
                            "name" to registeredInfo.serviceName,
                            "port" to service.port,
                        )
                        continuation.resume(Unit)
                    }

                    override fun onRegistrationFailed(
                        failedInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        forget(this)
                        platform.log.warn("carplay.android.mdns.failed", "type" to service.type, "code" to errorCode)
                        continuation.resumeWithException(
                            CarPlayException(
                                CarPlayError.NetworkFailed("mDNS registration of ${service.type} failed: $errorCode"),
                            ),
                        )
                    }

                    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                        platform.log.info("carplay.android.mdns.unregistered", "type" to service.type)
                    }

                    override fun onUnregistrationFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        platform.log.warn(
                            "carplay.android.mdns.unregister_failed",
                            "type" to service.type,
                            "code" to errorCode,
                        )
                    }
                }
            synchronized(registered) { registered += listener }
            continuation.invokeOnCancellation { unregister(listener) }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, platform.executor, listener)
                } else {
                    // Android 12: the Executor overload exists from API 33.
                    manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                }
            } catch (e: IllegalArgumentException) {
                forget(listener)
                continuation.resumeWithException(
                    CarPlayException(CarPlayError.NetworkFailed("mDNS registration: $e"), e),
                )
            }
        }
    }

    override suspend fun unpublish() {
        val listeners = synchronized(registered) { registered.toList() }
        listeners.forEach(::unregister)
    }

    private fun forget(listener: NsdManager.RegistrationListener): Boolean =
        synchronized(registered) {
            registered.remove(listener)
        }

    /** Idempotent: a listener is unregistered at most once, and a refusal is logged, never thrown. */
    private fun unregister(listener: NsdManager.RegistrationListener) {
        if (!forget(listener)) return
        try {
            nsd?.unregisterService(listener)
        } catch (e: IllegalArgumentException) {
            platform.log.warn("carplay.android.mdns.unregister_refused", "error" to e.toString())
        }
    }

    private companion object {
        const val ANDROID_17 = 37
    }
}
