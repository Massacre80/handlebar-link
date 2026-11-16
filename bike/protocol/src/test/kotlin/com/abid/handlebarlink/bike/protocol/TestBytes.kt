package com.abid.handlebarlink.bike.protocol

/** Bytes from a hex string as written in SAB_PROTOCOL.md, e.g. "01 0A 00". */
internal fun hex(text: String): ByteArray =
    text
        .split(' ')
        .filter { it.isNotEmpty() }
        .map { it.toInt(16).toByte() }
        .toByteArray()
