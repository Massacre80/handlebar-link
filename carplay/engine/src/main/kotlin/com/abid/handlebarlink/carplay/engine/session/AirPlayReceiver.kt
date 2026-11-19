package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.spi.BonjourService
import com.abid.handlebarlink.carplay.protocol.plist.Plist

/**
 * What the receiver advertises: the Bonjour `_airplay._tcp` record (DiPlay CarPlayBonjour.kt:83-96) and
 * the `GET /info` plist (AirPlayInfoPlist.kt:31-103, 201-287; spec D §4, §6).
 *
 * `/info` carries what DiPlay's builder does with audio on, 48 kHz music, no microphone, no cluster, no HEVC and no
 * buffered audio (AirPlayInfoPlist.kt:31-85): identity, features, modes, audio latencies and formats, the display and
 * the HID devices. DiPlay notes the iPhone aborts a session whose `/info` lacks audio formats and HID devices
 * (AirPlayInfoPlist.kt:3-8). OEM icons are left out, as DiPlay's builder does without icons (AirPlayInfoPlist.kt:64).
 */
internal class AirPlayReceiver(
    private val config: CarPlayConfig,
    private val deviceId: String,
    private val bluetoothId: String,
    private val pairingId: String,
    private val publicKeyHex: String,
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
            "audioLatencies" to audioLatencies(),
            "audioFormats" to audioFormats(),
            "extendedFeatures" to listOf("vocoderInfo", "enhancedRequestCarUI"),
            "displays" to listOf(mainDisplay()),
            "hidDevices" to HidDevices.all(config.name, config.widthPixels, config.heightPixels, MAIN_DISPLAY_UUID),
        )

    /** Nine entries, every latency 0 (AirPlayInfoPlist.kt:105-120). */
    private fun audioLatencies(): List<Plist> =
        listOf(
            MAIN_AUDIO to null,
            MAIN_AUDIO to "default",
            MAIN_AUDIO to "media",
            MAIN_AUDIO to "telephony",
            MAIN_AUDIO to "speechRecognition",
            MAIN_AUDIO to "alert",
            ALT_AUDIO to null,
            ALT_AUDIO to "default",
            MAIN_HIGH_AUDIO to "default",
        ).map { (type, audioType) ->
            val entry =
                linkedMapOf<String, Any>("type" to type, "inputLatencyMicros" to 0L, "outputLatencyMicros" to 0L)
            audioType?.let { entry["audioType"] = it }
            Plist.of(entry)
        }

    /**
     * What each audio stream may carry (AirPlayInfoPlist.kt:122-177) at DiPlay's default 48 kHz music rate
     * (AirPlayConfig.kt:75) and without a microphone, so no entry has `audioInputFormats`. Speech is plain PCM
     * (`speechWithout8And16Khz` off) and calls are not limited to their own rate (`callAtCallRate` off).
     */
    private fun audioFormats(): List<Plist> {
        fun format(
            type: Int,
            audioType: String,
            output: Int,
        ) = Plist.dict("type" to type, "audioType" to audioType, "audioOutputFormats" to output)
        return listOf(
            format(MAIN_AUDIO, "compatibility", PCM),
            format(ALT_AUDIO, "compatibility", PCM),
            format(MAIN_AUDIO, "default", PCM or OPUS),
            format(MAIN_AUDIO, "alert", PCM or OPUS),
            format(MAIN_AUDIO, "media", PCM),
            format(MAIN_AUDIO, "telephony", PCM_MONO or OPUS),
            format(MAIN_AUDIO, "speechRecognition", PCM_MONO or OPUS),
            format(ALT_AUDIO, "default", PCM or OPUS),
            format(MAIN_HIGH_AUDIO, "media", AAC_LC),
        )
    }

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

    private fun mainDisplay(): Plist {
        val width = config.widthPixels
        val height = config.heightPixels
        val fullArea =
            Plist.dict(
                "widthPixels" to width,
                "heightPixels" to height,
                "originXPixels" to 0,
                "originYPixels" to 0,
            )
        val area =
            Plist.Dict(
                fullArea.entries +
                    ("safeArea" to Plist.Dict(fullArea.entries + ("drawUIOutsideSafeArea" to Plist.Bool(true)))),
            )
        return Plist.dict(
            "uuid" to MAIN_DISPLAY_UUID,
            "type" to MAIN_SCREEN,
            "maxFPS" to config.maxFps / FPS_STEP * FPS_STEP,
            "widthPixels" to width,
            "heightPixels" to height,
            "widthPhysical" to config.widthMillimetres,
            "heightPhysical" to
                Math
                    .round(
                        config.widthMillimetres * height.toDouble() / width,
                    ).toInt()
                    .coerceAtLeast(1),
            "features" to (FEATURE_HIGH_FIDELITY_TOUCH or FEATURE_KNOBS),
            "primaryInputDevice" to 1,
            "uiAppearanceMode" to 0,
            "uiAppearanceSetting" to 0,
            "mapAppearanceMode" to 0,
            "mapAppearanceSetting" to 0,
            "viewAreas" to listOf(area),
            "initialViewArea" to 0,
        )
    }

    companion object {
        /** AirPlay source version DiPlay reports (CarPlayHostActivity.kt:5195), sent in /info, Bonjour and iAP2. */
        const val SOURCE_VERSION = "950.7.1"

        /** CarPlay feature bits with audio (AirPlayInfoPlist.kt:17). */
        const val FEATURES = 0x615653AEE2L

        /** Our main display's UUID; any fixed UUID works, the iPhone echoes it in SETUP. */
        const val MAIN_DISPLAY_UUID = "6f0c2a1e-3b5d-4c8a-9e71-2d4b6a8c0f13"

        private const val MAIN_SCREEN = 110
        private const val MAIN_AUDIO = 100
        private const val ALT_AUDIO = 101
        private const val MAIN_HIGH_AUDIO = 102

        /** PCM 8 to 32 kHz mono and stereo, plus 48 kHz (AirPlayInfoPlist.kt:146-147: 0x3fc | 0xc000). */
        private const val PCM = 0x3fc or 0xc000

        /** PCM mono 8, 16, 24, 32 kHz plus 48 kHz (AirPlayInfoPlist.kt:150: 0x154 | 0x4000). */
        private const val PCM_MONO = 0x154 or 0x4000

        /** Opus (AirPlayInfoPlist.kt:151). */
        private const val OPUS = 0x70000000

        /** AAC-LC 48 kHz (AirPlayInfoPlist.kt:152). */
        private const val AAC_LC = 0x800000
        private const val FEATURE_KNOBS = 0x02
        private const val FEATURE_HIGH_FIDELITY_TOUCH = 0x08
        private const val RESOURCE_SCREEN = 1
        private const val RESOURCE_AUDIO = 2
        private const val PRIORITY = 100
        private const val FPS_STEP = 5
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
