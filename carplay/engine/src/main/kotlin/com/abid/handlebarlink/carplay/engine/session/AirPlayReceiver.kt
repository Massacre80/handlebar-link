package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.spi.BonjourService
import com.abid.handlebarlink.carplay.protocol.plist.Plist

/**
 * What the receiver advertises: the Bonjour `_airplay._tcp` record (DiPlay CarPlayBonjour.kt:83-96) and
 * the `GET /info` plist (AirPlayInfoPlist.kt:31-103, 201-287; spec D §4, §6).
 *
 * `/info` here carries identity, features, modes and the display. DiPlay notes the iPhone aborts a session
 * whose `/info` lacks audio formats and HID devices (AirPlayInfoPlist.kt:4-7); those come with the media
 * milestone (docs/PLAN.md, CarPlay C2), so for now a session ends after pairing and authentication.
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
            "extendedFeatures" to listOf("vocoderInfo", "enhancedRequestCarUI"),
            "displays" to listOf(mainDisplay()),
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
