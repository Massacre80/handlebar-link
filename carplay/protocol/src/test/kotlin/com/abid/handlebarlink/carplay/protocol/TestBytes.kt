package com.abid.handlebarlink.carplay.protocol

/** Bytes from hex as printed in an RFC or a capture; spaces, colons and line breaks are ignored. */
internal fun hex(text: String): ByteArray =
    text
        .filter { it.isLetterOrDigit() }
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

/** Lower-case hex of [bytes], no separators. */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
