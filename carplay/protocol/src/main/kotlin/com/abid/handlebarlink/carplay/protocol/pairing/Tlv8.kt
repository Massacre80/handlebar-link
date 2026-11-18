package com.abid.handlebarlink.carplay.protocol.pairing

import java.io.ByteArrayOutputStream

/** One TLV8 item (HomeKit pairing format: type u8, length u8, value). */
public class Tlv8Item(
    /** Item type. */
    public val type: Int,
    /** Item value; any length (split into 255-byte fragments on the wire). */
    public val value: ByteArray,
)

/**
 * TLV8 as DiPlay's Tlv8Codec.kt:7-75 (spec C §1): values over 255 bytes continue in further items of the
 * same type; two items of one type back to back are separated by `FF 00`. Decoding merges a fragment into
 * the previous one when that was a full 255 bytes of the same type, otherwise the last value wins.
 */
public object Tlv8 {
    /** Pairing TLV types (DiPlay PairSetup.kt:116-124). */
    public const val IDENTIFIER: Int = 0x01

    /** SRP salt. */
    public const val SALT: Int = 0x02

    /** A public key (SRP A/B, Ed25519, X25519). */
    public const val PUBLIC_KEY: Int = 0x03

    /** SRP proof. */
    public const val PROOF: Int = 0x04

    /** ChaCha20-Poly1305 sealed sub-TLV. */
    public const val ENCRYPTED_DATA: Int = 0x05

    /** Pairing state M1..M6. */
    public const val STATE: Int = 0x06

    /** Error code. */
    public const val ERROR: Int = 0x07

    /** Ed25519 signature. */
    public const val SIGNATURE: Int = 0x0A

    private const val SEPARATOR = 0xFF
    private const val MAX_FRAGMENT = 255
    private const val MAX_DECODED_BYTES = 64 * 1024

    /** Encodes [items] in order. */
    public fun encode(items: List<Tlv8Item>): ByteArray {
        val out = ByteArrayOutputStream()
        var previousType = -1
        items.forEach { item ->
            if (item.type == previousType) {
                out.write(SEPARATOR)
                out.write(0)
            }
            var offset = 0
            do {
                val length = minOf(MAX_FRAGMENT, item.value.size - offset)
                out.write(item.type)
                out.write(length)
                out.write(item.value, offset, length)
                offset += length
            } while (offset < item.value.size)
            previousType = item.type
        }
        return out.toByteArray()
    }

    /** Encodes `type to value` pairs in order. */
    public fun encode(vararg items: Pair<Int, ByteArray>): ByteArray =
        encode(items.map { Tlv8Item(it.first, it.second) })

    /** Decodes [bytes] to type → value; stops at a truncated item or at 64 KiB of values. */
    public fun decode(bytes: ByteArray): Map<Int, ByteArray> {
        val values = HashMap<Int, ByteArrayOutputStream>()
        var total = 0
        var at = 0
        var lastType = -1
        var lastLength = 0
        while (bytes.size - at >= 2) {
            val type = bytes[at].toInt() and 0xFF
            val length = bytes[at + 1].toInt() and 0xFF
            if (bytes.size - at - 2 < length || total + length > MAX_DECODED_BYTES) break
            val continues = type == lastType && lastLength == MAX_FRAGMENT
            val value = if (continues) values.getValue(type) else ByteArrayOutputStream().also { values[type] = it }
            value.write(bytes, at + 2, length)
            total += length
            lastType = type
            lastLength = length
            at += 2 + length
        }
        return values.mapValues { it.value.toByteArray() }
    }
}
