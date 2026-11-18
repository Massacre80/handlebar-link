package com.abid.handlebarlink.carplay.protocol.stream

import com.abid.handlebarlink.carplay.protocol.crypto.Hkdf

/**
 * The keys of the media and data streams a stream SETUP opens, as DiPlay derives them
 * (CarPlayMediaEngine.kt:620-622, 665-678, 705-706; spec C §8, spec D §5.3):
 * `HKDF-SHA512(sharedSecret, salt = "DataStream-Salt" + unsigned decimal, info, 32 bytes)`.
 *
 * - [sharedSecret] is pair-verify's raw X25519 secret (`PairVerify` keeps it; spec C §4).
 * - The decimal is the stream's `streamConnectionID` for the screens (110, 111) and audio (100, 101, 102),
 *   and its `seed` for type-130 data streams such as the iAP2 tunnel (CarPlayMediaEngine.kt:384-395).
 * - Pass the plist integer's 64 bits ([com.abid.handlebarlink.carplay.protocol.plist.Plist.Integer.bits]):
 *   they are written as an unsigned decimal, so an 8-byte id read as negative, or one above 2^63,
 *   gives the same salt as DiPlay's `unsignedPlistDecimal` (CarPlayMediaEngine.kt:715-722).
 *
 * Buffered audio (103) has no derived key: its key is the SETUP plist's 32-byte `shk`
 * (CarPlayMediaEngine.kt:283-287).
 */
public object StreamKeys {
    /** Salt prefix; the decimal follows with no separator (CarPlayMediaEngine.kt:674). */
    public const val SALT_PREFIX: String = "DataStream-Salt"

    /** Info of the key for what the iPhone sends us (CarPlayMediaEngine.kt:705). */
    public const val OUTPUT_INFO: String = "DataStream-Output-Encryption-Key"

    /** Info of the key for what we send the iPhone: the microphone uplink (CarPlayMediaEngine.kt:706). */
    public const val INPUT_INFO: String = "DataStream-Input-Encryption-Key"

    /** Key length (CarPlayMediaEngine.kt:676). */
    public const val KEY_BYTES: Int = 32

    /**
     * The key that decrypts what the iPhone sends on a stream: screen frames, audio packets, the iAP2
     * tunnel (CarPlayMediaEngine.kt:620-622, 390-395). [saltNumber] is `streamConnectionID`, or `seed`
     * for a type-130 data stream.
     */
    public fun output(
        sharedSecret: ByteArray,
        saltNumber: Long,
    ): ByteArray = Hkdf.sha512(sharedSecret, salt(saltNumber), OUTPUT_INFO, KEY_BYTES)

    /**
     * The key that encrypts what we send on a stream: the microphone uplink of a type-100 audio stream
     * (CarPlayMediaEngine.kt:635), the video settings channel's write side (:455-462).
     */
    public fun input(
        sharedSecret: ByteArray,
        saltNumber: Long,
    ): ByteArray = Hkdf.sha512(sharedSecret, salt(saltNumber), INPUT_INFO, KEY_BYTES)

    /** `"DataStream-Salt"` and [saltNumber]'s 64 bits as an unsigned decimal (CarPlayMediaEngine.kt:715-722). */
    public fun salt(saltNumber: Long): String = SALT_PREFIX + java.lang.Long.toUnsignedString(saltNumber)
}

/** Stream `type` numbers of a stream SETUP (CarPlayMediaEngine.kt:701-704; spec D §5.2). */
public object StreamType {
    /** Main realtime audio: media, alerts, calls, Siri (CarPlayMediaEngine.kt:703). */
    public const val MAIN_AUDIO: Int = 100

    /** Alternate realtime audio (AirPlayInfoPlist.kt:165, 171). */
    public const val ALT_AUDIO: Int = 101

    /** Main high-quality audio, AAC-LC (AirPlayInfoPlist.kt:172). */
    public const val MAIN_HIGH_AUDIO: Int = 102

    /** Main buffered audio over TCP (BufferedAudioStream.kt:332). */
    public const val MAIN_BUFFERED_AUDIO: Int = 103

    /** Main screen (CarPlayMediaEngine.kt:701). */
    public const val MAIN_SCREEN: Int = 110

    /** Alternate (cluster) screen (CarPlayMediaEngine.kt:702). */
    public const val ALT_SCREEN: Int = 111

    /** Data stream, such as the iAP2 tunnel (CarPlayMediaEngine.kt:704). */
    public const val DATA: Int = 130
}
