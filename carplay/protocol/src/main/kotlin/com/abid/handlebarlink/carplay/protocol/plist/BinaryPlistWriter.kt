package com.abid.handlebarlink.carplay.protocol.plist

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Apple's binary plist (`bplist00`), written as CoreFoundation and Python's plistlib write it: objects
 * depth-first with a container before its children (a dict's keys, then its values), equal scalars stored
 * once, integers in the smallest width (negative ones as 8-byte signed, values from 2^63 as 16-byte),
 * reals as doubles, ASCII strings as `0x5n`, others UTF-16BE.
 *
 * DiPlay's codec deviates (spec D §3: negatives as reals, 2^63+ as 8 bytes, no uniquing); the iPhone reads
 * Apple's own format, so we write that. Golden vectors in the tests come from Python's plistlib.
 */
public object BinaryPlistWriter {
    private val MAGIC = "bplist00".encodeToByteArray()
    private const val TRAILER_BYTES = 32
    private val SIGNED_64_LIMIT: BigInteger = BigInteger.ONE.shiftLeft(63)

    /** Encodes [root]. */
    public fun write(root: Plist): ByteArray {
        val objects = ArrayList<Plist>()
        val scalarIds = HashMap<Plist, Int>()
        val refs = HashMap<Int, IntArray>()
        flatten(root, objects, scalarIds, refs)
        val refSize = sizeFor(objects.size.toLong())
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        val offsets = LongArray(objects.size)
        objects.forEachIndexed { index, value ->
            offsets[index] = out.size().toLong()
            writeObject(out, value, refs[index], refSize)
        }
        val tableOffset = out.size().toLong()
        val offsetSize = sizeFor(tableOffset)
        offsets.forEach { writeUnsigned(out, it, offsetSize) }
        out.write(ByteArray(6))
        out.write(offsetSize)
        out.write(refSize)
        writeUnsigned(out, objects.size.toLong(), 8)
        writeUnsigned(out, 0, 8)
        writeUnsigned(out, tableOffset, 8)
        check(out.size() == tableOffset.toInt() + offsets.size * offsetSize + TRAILER_BYTES)
        return out.toByteArray()
    }

    /** Assigns object ids; returns the id of [value]. Scalars are shared; containers are always new. */
    private fun flatten(
        value: Plist,
        objects: MutableList<Plist>,
        scalarIds: MutableMap<Plist, Int>,
        refs: MutableMap<Int, IntArray>,
    ): Int {
        val children =
            when (value) {
                is Plist.Array -> value.items
                is Plist.Dict -> value.entries.keys.map { Plist.Text(it) } + value.entries.values
                else -> null
            }
        if (children == null) {
            return scalarIds.getOrPut(value) {
                objects += value
                objects.size - 1
            }
        }
        val id = objects.size
        objects += value
        refs[id] = IntArray(children.size) { flatten(children[it], objects, scalarIds, refs) }
        return id
    }

    private fun writeObject(
        out: ByteArrayOutputStream,
        value: Plist,
        children: IntArray?,
        refSize: Int,
    ) {
        when (value) {
            is Plist.Bool -> {
                out.write(if (value.value) 0x09 else 0x08)
            }

            is Plist.Integer -> {
                writeInteger(out, value.value)
            }

            is Plist.Real -> {
                out.write(0x23)
                writeUnsigned(out, java.lang.Double.doubleToRawLongBits(value.value), 8)
            }

            is Plist.Date -> {
                out.write(0x33)
                writeUnsigned(out, java.lang.Double.doubleToRawLongBits(value.secondsSince2001), 8)
            }

            is Plist.Data -> {
                writeMarker(out, 0x40, value.value.size)
                out.write(value.value)
            }

            is Plist.Text -> {
                writeText(out, value.value)
            }

            is Plist.Array -> {
                writeMarker(out, 0xA0, children!!.size)
                children.forEach { writeUnsigned(out, it.toLong(), refSize) }
            }

            is Plist.Dict -> {
                writeMarker(out, 0xD0, children!!.size / 2)
                children.forEach { writeUnsigned(out, it.toLong(), refSize) }
            }
        }
    }

    private fun writeInteger(
        out: ByteArrayOutputStream,
        value: BigInteger,
    ) {
        when {
            value.signum() < 0 -> {
                out.write(0x13)
                writeUnsigned(out, value.toLong(), 8)
            }

            value >= SIGNED_64_LIMIT -> {
                out.write(0x14)
                writeUnsigned(out, 0, 8)
                writeUnsigned(out, value.toLong(), 8)
            }

            else -> {
                val size = sizeFor(value.toLong(), allowEight = true)
                out.write(0x10 or Integer.numberOfTrailingZeros(size))
                writeUnsigned(out, value.toLong(), size)
            }
        }
    }

    private fun writeText(
        out: ByteArrayOutputStream,
        text: String,
    ) {
        if (text.all { it.code < 0x80 }) {
            writeMarker(out, 0x50, text.length)
            out.write(text.encodeToByteArray())
        } else {
            writeMarker(out, 0x60, text.length)
            out.write(text.toByteArray(Charsets.UTF_16BE))
        }
    }

    /** Type nibble with the count inline below 15, else 0xF and an integer object. */
    private fun writeMarker(
        out: ByteArrayOutputStream,
        type: Int,
        count: Int,
    ) {
        if (count < 0x0F) {
            out.write(type or count)
        } else {
            out.write(type or 0x0F)
            writeInteger(out, BigInteger.valueOf(count.toLong()))
        }
    }

    /** 1, 2, 4 or 8 bytes: the smallest that holds [value] (Python plistlib's _count_to_size). */
    private fun sizeFor(
        value: Long,
        allowEight: Boolean = false,
    ): Int =
        when {
            value < (1L shl 8) -> 1
            value < (1L shl 16) -> 2
            value < (1L shl 32) || !allowEight -> 4
            else -> 8
        }

    private fun writeUnsigned(
        out: ByteArrayOutputStream,
        value: Long,
        size: Int,
    ) {
        for (shift in (size - 1) * 8 downTo 0 step 8) out.write((value ushr shift).toInt() and 0xFF)
    }
}
