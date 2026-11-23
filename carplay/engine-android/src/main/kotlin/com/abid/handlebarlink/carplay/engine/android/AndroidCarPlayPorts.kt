package com.abid.handlebarlink.carplay.engine.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import com.abid.handlebarlink.carplay.engine.android.audio.AndroidAudioPort
import com.abid.handlebarlink.carplay.engine.android.audio.AndroidAudioSystem
import com.abid.handlebarlink.carplay.engine.android.audio.AndroidMicrophonePort
import com.abid.handlebarlink.carplay.engine.android.bluetooth.AndroidBluetoothPort
import com.abid.handlebarlink.carplay.engine.android.bluetooth.IphoneSelector
import com.abid.handlebarlink.carplay.engine.android.bonjour.AndroidBonjourPort
import com.abid.handlebarlink.carplay.engine.android.credentials.AndroidCredentialsPort
import com.abid.handlebarlink.carplay.engine.android.credentials.AssetMfiSource
import com.abid.handlebarlink.carplay.engine.android.credentials.FilePairingStore
import com.abid.handlebarlink.carplay.engine.android.credentials.IdentityStore
import com.abid.handlebarlink.carplay.engine.android.location.AndroidLocationPort
import com.abid.handlebarlink.carplay.engine.android.net.AndroidNetworkPort
import com.abid.handlebarlink.carplay.engine.android.wifi.AndroidWifiDirectPort
import com.abid.handlebarlink.carplay.engine.android.wifi.StableNetworks
import com.abid.handlebarlink.carplay.engine.spi.CarPlayPorts
import com.abid.handlebarlink.carplay.protocol.crypto.ChaCha20Poly1305
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import java.io.File
import java.security.SecureRandom

/**
 * The CarPlay engine's platform on Android, for `createCarPlayLink`: RFCOMM to the iPhone, a Wi-Fi Direct group,
 * the AirPlay sockets, mDNS, the stored identities, and CarPlay's sound and microphone (`audio/`). Creating it only
 * constructs objects: no I/O, no Binder call,
 * no system service (safe on the main thread, docs/LESSONS.md 60); all real work happens lazily inside the
 * suspending port calls, on the ports' own threads. Create one
 * per process (Hilt `@Singleton`). The app binds it as `CarPlayPortsSource { createAndroidCarPlayPorts(context, time,
 * logger, screen) }`; the app's own permission flow asks for what each port checks (the manifest lists them, every port
 * checks its own before use and fails with PermissionMissing). The iPhone's picture goes to
 * [screen] ([createCarPlayScreen]), which the app also shows. Calls and Siri use the rider's Bluetooth headset from
 * [headsets] when it is connected (docs/PLAN.md "C5 audio", "Routes").
 */
public fun createAndroidCarPlayPorts(
    context: Context,
    time: TimeProvider,
    logger: Logger,
    screen: CarPlayScreen,
    headsets: CarPlayHeadsetSource = CarPlayHeadsetSource.ANY,
    media: CarPlayHeadsetSource = CarPlayHeadsetSource.ANY,
): CarPlayPorts {
    val platform = AndroidPlatform(context, time, logger)
    val settings = PreferenceSettings { platform.preferences }
    val random = SecureRandom()
    val identities = IdentityStore({ File(platform.noBackupDirectory, IDENTITY_FILE) }, random, logger)
    val selector = IphoneSelector(platform, settings)
    val audio = AndroidAudioSystem(platform, headsets, media)
    return CarPlayPorts(
        bluetooth = AndroidBluetoothPort(platform, selector, identities),
        wifiDirect = AndroidWifiDirectPort(platform, selector, StableNetworks(settings, random), identities),
        network = AndroidNetworkPort(platform),
        bonjour = AndroidBonjourPort(platform),
        credentials =
            AndroidCredentialsPort(
                mfi = AssetMfiSource { platform.context.assets },
                identities = identities,
                pairings = {
                    FilePairingStore(
                        File(platform.noBackupDirectory, PAIRINGS_FILE),
                        MAX_PAIRINGS,
                        platform.ioExecutor::execute,
                        logger,
                    )
                },
                io = platform.io,
                log = logger,
            ),
        screen = screen.port,
        audio = AndroidAudioPort(audio),
        microphone = AndroidMicrophonePort(audio),
        location = AndroidLocationPort(platform, logger),
        cipherName = ChaCha20Poly1305.Transformation.ANDROID,
    )
}

private const val IDENTITY_FILE = "airplay-identity"
private const val PAIRINGS_FILE = "airplay-pairings"

/** iPhones remembered after pair-setup; one more evicts the oldest (a rider has one or two). */
private const val MAX_PAIRINGS = 16

/** The iPhone CarPlay dials, when the rider picks one; otherwise the only paired (or connected) iPhone is used. */
public object CarPlayIphone {
    /** Remembers [address] ("AA:BB:…") as the iPhone to dial; null goes back to the automatic choice. */
    public fun choose(
        context: Context,
        address: String?,
    ) {
        context.getSharedPreferences(AndroidPlatform.PREFERENCES, Context.MODE_PRIVATE).edit {
            if (address == null) remove(IphoneSelector.CHOSEN_KEY) else putString(IphoneSelector.CHOSEN_KEY, address)
        }
    }

    /** The chosen iPhone's address, or null. */
    public fun chosen(context: Context): String? =
        context
            .getSharedPreferences(
                AndroidPlatform.PREFERENCES,
                Context.MODE_PRIVATE,
            ).getString(IphoneSelector.CHOSEN_KEY, null)
}
