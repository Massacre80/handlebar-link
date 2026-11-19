package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayViewArea
import com.abid.handlebarlink.carplay.engine.spi.BonjourService
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneOffer
import com.abid.handlebarlink.carplay.protocol.audio.AudioCapabilities
import com.abid.handlebarlink.carplay.protocol.plist.Plist

/**
 * What the receiver advertises: the Bonjour `_airplay._tcp` record (DiPlay CarPlayBonjour.kt:83-96) and
 * the `GET /info` plist (AirPlayInfoPlist.kt:31-103, 201-287; spec D §4, §6).
 *
 * `/info` carries what DiPlay's builder does with audio on, 48 kHz music, no cluster, no HEVC and no buffered audio
 * (AirPlayInfoPlist.kt:31-85): identity, features, modes, audio latencies and formats (DiPlay's defaults,
 * `AudioCapabilities`, with input formats on the recording streams when [microphone] allows, Opus among them when the
 * platform can encode it, AirPlayInfoPlist.kt:155-162), the display and the HID devices. DiPlay notes the iPhone aborts
 * a session whose `/info` lacks audio formats and HID devices (AirPlayInfoPlist.kt:3-8). OEM icons are left out, as
 * DiPlay's builder does without icons (AirPlayInfoPlist.kt:64).
 */
internal class AirPlayReceiver(
    private val config: CarPlayConfig,
    private val deviceId: String,
    private val bluetoothId: String,
    private val pairingId: String,
    private val publicKeyHex: String,
    private val microphone: MicrophoneOffer = MicrophoneOffer.NONE,
) {
    fun bonjour(
        address: String,
        port: Int,
    ): BonjourService =
        BonjourService(
            type = "_airplay._tcp",
            name = config.name,
            address = address,
            port = port,
            txt =
                listOf(
                    "deviceid" to deviceId,
                    "features" to featuresText(FEATURES),
                    "flags" to "0x4",
                    "model" to config.model,
                    "srcvers" to SOURCE_VERSION,
                    "protovers" to "1.1",
                    "pi" to pairingId,
                    "pk" to publicKeyHex,
                ),
        )

    fun info(): Plist.Dict =
        Plist.dict(
            "sourceVersion" to SOURCE_VERSION,
            "features" to FEATURES,
            "statusFlags" to STATUS_FLAGS,
            "model" to config.model,
            "manufacturer" to config.manufacturer,
            "deviceID" to deviceId,
            "bluetoothIDs" to listOf(bluetoothId),
            "name" to config.name,
            "rightHandDrive" to false,
            "keepAliveLowPower" to false,
            "keepAliveSendStatsAsBody" to false,
            "modes" to modes(),
            "audioLatencies" to AudioCapabilities.audioLatencies(),
            "audioFormats" to
                AudioCapabilities.audioFormats(
                    microphone = microphone != MicrophoneOffer.NONE,
                    microphoneOpus = microphone == MicrophoneOffer.PCM_AND_OPUS,
                ),
            "extendedFeatures" to listOf("vocoderInfo", "enhancedRequestCarUI"),
            "displays" to listOf(mainDisplay()),
            "hidDevices" to HidDevices.all(config.name, config.widthPixels, config.heightPixels, MAIN_DISPLAY_UUID),
        )

    private fun modes(): Plist =
        Plist.dict(
            "resources" to listOf(resource(RESOURCE_SCREEN), resource(RESOURCE_AUDIO)),
            "appStates" to
                listOf(
                    Plist.dict("appStateID" to APP_STATE_2, "state" to false),
                    Plist.dict("appStateID" to APP_STATE_SPEECH, "speechMode" to SPEECH_MODE_NONE),
                    Plist.dict("appStateID" to APP_STATE_3, "state" to false),
                ),
        )

    private fun resource(id: Int): Plist =
        Plist.dict(
            "resourceID" to id,
            "transferType" to 1,
            "transferPriority" to PRIORITY,
            "takeConstraint" to PRIORITY,
            "borrowConstraint" to PRIORITY,
            "unborrowConstraint" to PRIORITY,
        )

    /**
     * The main display as DiPlay's `displayEntry` writes it (AirPlayInfoPlist.kt:201-240), keys in its order: the
     * canvas, its physical size clamped to 1..2000 mm (the height derived from the width when the config has none,
     * :202-210), the frame rate, then one dictionary per view area ([area]) with the initial one, and
     * `viewAreaTransitionControl` only when there are several (:237). No display safe area, corner masks or dock edge
     * (DiPlay's defaults: AirPlayPersistence.loadMainDisplayDisableSafeArea false, CarPlayDock automatic).
     */
    private fun mainDisplay(): Plist {
        val width = config.widthPixels
        val height = config.heightPixels
        val widthPhysical = config.widthMillimetres.coerceIn(MIN_MILLIMETRES, MAX_MILLIMETRES)
        val heightPhysical =
            (config.heightMillimetres ?: Math.round(widthPhysical * height.toDouble() / width).toInt())
                .coerceIn(MIN_MILLIMETRES, MAX_MILLIMETRES)
        val areas = config.viewAreas.ifEmpty { listOf(config.startViewArea) }
        val entries =
            linkedMapOf<String, Plist>(
                "uuid" to Plist.of(MAIN_DISPLAY_UUID),
                "type" to Plist.of(MAIN_SCREEN),
                "maxFPS" to Plist.of(config.reportedFps),
                "widthPixels" to Plist.of(width),
                "heightPixels" to Plist.of(height),
                "widthPhysical" to Plist.of(widthPhysical),
                "heightPhysical" to Plist.of(heightPhysical),
                "features" to Plist.of(FEATURE_HIGH_FIDELITY_TOUCH or FEATURE_KNOBS),
                "primaryInputDevice" to Plist.of(1),
                "uiAppearanceMode" to Plist.of(0),
                "uiAppearanceSetting" to Plist.of(0),
                "mapAppearanceMode" to Plist.of(0),
                "mapAppearanceSetting" to Plist.of(0),
                "viewAreas" to Plist.of(areas.map(::area)),
                "initialViewArea" to Plist.of(if (config.viewAreas.isEmpty()) 0 else config.initialViewArea),
            )
        if (config.viewAreas.size > 1) entries["viewAreaTransitionControl"] = Plist.Bool(true)
        return Plist.Dict(entries)
    }

    /**
     * One view area as DiPlay's `areaDict` writes it (AirPlayInfoPlist.kt:242-286) for a display without its own safe
     * area: the rectangle, then a safe area of the same rectangle that lets CarPlay draw outside it
     * (`drawUIOutsideSafeArea`, DiPlay's default true, AirPlayPersistence.kt:1456-1458).
     */
    private fun area(area: CarPlayViewArea): Plist {
        val rectangle =
            listOf(
                "widthPixels" to Plist.of(area.width),
                "heightPixels" to Plist.of(area.height),
                "originXPixels" to Plist.of(area.originX),
                "originYPixels" to Plist.of(area.originY),
            )
        val safe = Plist.Dict((rectangle + ("drawUIOutsideSafeArea" to Plist.Bool(true))).toMap())
        return Plist.Dict((rectangle + ("safeArea" to safe)).toMap())
    }

    companion object {
        /** AirPlay source version DiPlay reports (CarPlayHostActivity.kt:5195), sent in /info, Bonjour and iAP2. */
        const val SOURCE_VERSION = "950.7.1"

        /** CarPlay feature bits with audio (AirPlayInfoPlist.kt:17). */
        const val FEATURES = 0x615653AEE2L

        /** Our main display's UUID; any fixed UUID works, the iPhone echoes it in SETUP. */
        const val MAIN_DISPLAY_UUID = "6f0c2a1e-3b5d-4c8a-9e71-2d4b6a8c0f13"

        private const val MAIN_SCREEN = 110

        /** DiPlay's reported-size clamp (AirPlayDisplaySettings.kt:27-28, 41-42). */
        private const val MIN_MILLIMETRES = 1
        private const val MAX_MILLIMETRES = 2000
        private const val FEATURE_KNOBS = 0x02
        private const val FEATURE_HIGH_FIDELITY_TOUCH = 0x08
        private const val RESOURCE_SCREEN = 1
        private const val RESOURCE_AUDIO = 2
        private const val PRIORITY = 100
        private const val STATUS_FLAGS = 4L

        // App state ids as DiPlay sends them (AirPlayInfoPlist.kt:99-101); only id 1, with speechMode, is evident.
        private const val APP_STATE_SPEECH = 1
        private const val APP_STATE_2 = 2
        private const val APP_STATE_3 = 3
        private const val SPEECH_MODE_NONE = -1
        private const val LOW_32_BITS = 0xFFFF_FFFFL
        private const val HIGH_SHIFT = 32
        private const val HEX = 16

        /** "0x<low32>" or "0x<low32>,0x<high32>", lower-case, unpadded (CarPlayBonjour.kt:77-81). */
        fun featuresText(features: Long): String {
            val low = (features and LOW_32_BITS).toString(HEX)
            val high = (features ushr HIGH_SHIFT).toString(HEX)
            return if (features ushr HIGH_SHIFT == 0L) "0x$low" else "0x$low,0x$high"
        }
    }
}
