package com.abid.handlebarlink.bike.protocol

/**
 * An immutable sequence of bytes exchanged with the meter (or served to it by the phone).
 *
 * Every frame this module builds is a [SabFrame], so callers cannot change a frame after it was
 * built and matched against a reply.
 */
public class SabFrame(
    bytes: ByteArray,
) {
    private val bytes: ByteArray = bytes.copyOf()

    /** Number of bytes in the frame. */
    public val size: Int get() = bytes.size

    /** The byte at [index]. */
    public operator fun get(index: Int): Byte = bytes[index]

    /** A copy of the bytes, for handing to the Bluetooth stack. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    /** Whether [other] holds exactly the same bytes. */
    public fun contentEquals(other: ByteArray): Boolean = bytes.contentEquals(other)

    override fun equals(other: Any?): Boolean = other is SabFrame && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = bytes.toSabHex()
}

/** Upper-case hex bytes separated by spaces, as written in docs/protocol/SAB_PROTOCOL.md (`01 0A 00`). */
public fun ByteArray.toSabHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

internal fun frameOf(vararg values: Int): SabFrame = SabFrame(ByteArray(values.size) { values[it].toByte() })
