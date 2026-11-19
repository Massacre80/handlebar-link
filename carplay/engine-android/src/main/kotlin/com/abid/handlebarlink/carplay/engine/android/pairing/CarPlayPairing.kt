package com.abid.handlebarlink.carplay.engine.android.pairing

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import com.abid.handlebarlink.carplay.engine.android.AndroidPlatform
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pairing the rider's iPhone inside the app, the way CarLink's PairingSession does it (carlink-core/.../connection/
 * PairingSession.kt on upstream/main, whose KDoc notes shipping receivers do the same): this phone becomes visible
 * for [DISCOVERABLE_SECONDS] ([discoverableIntent], a system dialog), the rider pairs *from the iPhone's* Bluetooth
 * settings, [session] follows the bond (BONDING, then BONDED) and tells an iPhone by its iAP2 record or name
 * ([IphoneEvidence]). The app then [choose]s it for CarPlay (CarPlayIphone). [forget] drops it from the app; Android
 * has no public call to unpair (see [IphoneChoices]).
 *
 * Bluetooth work runs on [io] (Binder calls, preferences); every flow may be collected from the main thread.
 * Created by [createCarPlayPairing]; one per process (Hilt `@Singleton`).
 */
public class CarPlayPairing internal constructor(
    private val radio: PairingRadio,
    private val store: IphoneChoiceStore,
    private val io: CoroutineDispatcher,
    logger: Logger,
) {
    private val log = logger.child("pairing")
    private val choices = MutableStateFlow<IphoneChoices?>(null)
    private val lock = Mutex()

    /**
     * The pairing screen's state while collected: Bluetooth's broadcasts are watched only then. Ends after
     * [IphonePairingState.NotAllowed] (collect again once the permission is granted).
     */
    public fun session(): Flow<IphonePairingState> =
        channelFlow {
            val timers = Channel<PairingEvent>(Channel.UNLIMITED)
            var tracked = PairingTracker.start(radio.allowed(), radio.isOn(), radio.localName())
            send(tracked.shown)
            if (tracked.shown == IphonePairingState.NotAllowed) return@channelFlow
            merge(radio.events(), timers.receiveAsFlow()).collect { event ->
                val step = PairingTracker.next(tracked, event)
                if (step.state.shown != tracked.shown) log.info("pairing.state", "state" to step.state.shown)
                tracked = step.state
                send(tracked.shown)
                step.effects.forEach { effect ->
                    when (effect) {
                        is PairingEffect.ReadServices -> {
                            radio.fetchServices(effect.address)
                        }

                        is PairingEffect.StartCheckTimer -> {
                            launch {
                                delay(PairingTracker.CHECK_MILLIS)
                                timers.send(PairingEvent.CheckTimedOut(effect.address))
                            }
                        }
                    }
                }
            }
        }.distinctUntilChanged().flowOn(io)

    /**
     * The iPhones paired with this phone and not forgotten, by name, read again whenever a bond or Bluetooth changes
     * while collected; null when Bluetooth cannot be read (no permission).
     */
    public fun iphones(): Flow<List<PairedIphone>?> =
        combine(
            radio
                .events()
                .filter { it is PairingEvent.BondChanged || it is PairingEvent.Radio }
                .map {}
                .onStart { emit(Unit) },
            loadedChoices(),
        ) { _, choices -> readIphones(choices) }
            .distinctUntilChanged()
            .flowOn(io)

    /** The address of the iPhone the rider chose for CarPlay, or null (CarPlay then picks the only paired iPhone). */
    public fun chosen(): Flow<String?> = loadedChoices().map { it.chosen }.distinctUntilChanged()

    /** Chooses [address] for CarPlay (after pairing, or from the iPhone sheet); it is no longer forgotten. */
    public suspend fun choose(address: String) {
        edit { it.choose(address) }
        log.info("pairing.chosen", "address" to address)
    }

    /** Forgets [address] in the app: no longer listed nor chosen; it stays paired with Android. */
    public suspend fun forget(address: String) {
        edit { it.forget(address) }
        log.info("pairing.forgotten", "address" to address)
    }

    /**
     * Sets this phone's Bluetooth name to [name] (BluetoothAdapter.setName: public, BLUETOOTH_CONNECT, only while
     * Bluetooth is on). False when the name is not valid ([BluetoothName.isValid]) or Android refused.
     */
    public suspend fun rename(name: String): Boolean {
        val clean = name.trim()
        if (!BluetoothName.isValid(clean)) return false
        val done = withContext(io) { radio.rename(clean) }
        log.info("pairing.rename", "done" to done)
        return done
    }

    private fun readIphones(choices: IphoneChoices): List<PairedIphone>? {
        if (!radio.allowed()) return null
        return radio
            .bonded()
            .filter { IphoneEvidence.isIphone(it.name, it.uuids) && !choices.forgotten(it.address) }
            .map { PairedIphone(it.address, IphoneEvidence.display(it.name, it.address)) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.address }))
    }

    /** The choices, read from the store once, then as changed. */
    private fun loadedChoices(): Flow<IphoneChoices> =
        flow {
            lock.withLock { load() }
            emitAll(choices.filterNotNull())
        }

    private suspend fun edit(change: (IphoneChoices) -> IphoneChoices) {
        lock.withLock {
            val next = change(load())
            withContext(io) { store.write(next) }
            choices.value = next
        }
    }

    /** Call with [lock] held. */
    private suspend fun load(): IphoneChoices =
        choices.value ?: withContext(io) { store.read() }.also { choices.value = it }

    /** How pairing is started: the visibility request and the permissions it needs. */
    public companion object {
        /** How long this phone stays visible: the most Android grants for one request. */
        public const val DISCOVERABLE_SECONDS: Int = 300

        /**
         * The permissions [discoverableIntent] and the session need (both Nearby devices): BLUETOOTH_ADVERTISE for
         * ACTION_REQUEST_DISCOVERABLE on Android 12+, BLUETOOTH_CONNECT to read names and bonds.
         */
        public val PERMISSIONS: List<String> =
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)

        /**
         * The system dialog that makes this phone visible for [DISCOVERABLE_SECONDS] (and turns Bluetooth on if
         * needed). Its result code is the granted seconds, or RESULT_CANCELED.
         */
        public fun discoverableIntent(): Intent =
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
    }
}

/** Rules for a Bluetooth name (BluetoothAdapter.setName: "a maximum of 248 bytes using UTF-8 encoding"). */
internal object BluetoothName {
    /** At most this many UTF-8 bytes. */
    const val MAX_BYTES = 248

    /** Not blank and short enough. */
    fun isValid(name: String): Boolean = name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size <= MAX_BYTES
}

/**
 * The pairing for the app: Android's Bluetooth through [AndroidPairingRadio], the choices in the CarPlay module's
 * preferences, blocking work on [io]. Creating it only constructs objects (no I/O, no Binder call; docs/LESSONS.md 60).
 */
public fun createCarPlayPairing(
    context: Context,
    time: TimeProvider,
    logger: Logger,
    io: CoroutineDispatcher,
): CarPlayPairing {
    val platform = AndroidPlatform(context, time, logger)
    return CarPlayPairing(AndroidPairingRadio(platform), PreferenceChoiceStore.of(context), io, logger)
}
