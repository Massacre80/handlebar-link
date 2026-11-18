package com.abid.handlebarlink.carplay.protocol.plist

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import com.abid.handlebarlink.carplay.protocol.hex
import com.abid.handlebarlink.carplay.protocol.toHex
import org.junit.Test
import java.lang.management.ManagementFactory
import java.math.BigInteger

/**
 * Golden bytes from Python 3.15's plistlib (`dumps(value, fmt=FMT_BINARY, sort_keys=False)`), an independent
 * implementation of Apple's format. "small" is also byte-identical to spec D §3's example from DiPlay's encoder.
 */
class BinaryPlistTest {
    @Test
    fun `a one-entry dictionary matches plistlib and DiPlay`() {
        assertThat(BinaryPlistWriter.write(Plist.dict("a" to 1)).toHex()).isEqualTo(SMALL)
    }

    @Test
    fun `mixed values match plistlib byte for byte`() {
        assertThat(BinaryPlistWriter.write(mixed()).toHex()).isEqualTo(MIXED)
    }

    @Test
    fun `an array of twenty uses the long count form`() {
        assertThat(BinaryPlistWriter.write(Plist.of((0 until 20).toList())).toHex()).isEqualTo(ARRAY20)
    }

    @Test
    fun `three hundred keys use two-byte references and offsets, as plistlib`() {
        val dict = Plist.Dict((0 until 300).associate { "key%03d".format(it) to Plist.of(it) })
        val golden = resource("/plist/three-hundred-keys.bplist")

        assertThat(BinaryPlistWriter.write(dict).toHex()).isEqualTo(golden.toHex())
        assertThat(BinaryPlistReader.read(golden)).isEqualTo(dict)
    }

    @Test
    fun `plistlib's bytes read back to the same values`() {
        assertThat(BinaryPlistReader.read(hex(MIXED))).isEqualTo(mixed())
        assertThat(BinaryPlistReader.read(hex(SMALL))).isEqualTo(Plist.dict("a" to 1))
    }

    @Test
    fun `typed accessors read dictionary entries`() {
        val dict = BinaryPlistReader.read(hex(MIXED)) as Plist.Dict

        assertThat(dict.long("n5e9")).isEqualTo(5_000_000_000L)
        assertThat(dict.long("big")).isEqualTo(-1L) // 2^64-1 keeps its bits
        assertThat(dict.text("uni")).isEqualTo("héllo")
        assertThat(dict.data("data")!!.toHex()).isEqualTo("0001")
        assertThat(dict.array("displays")!!.size).isEqualTo(1)
        assertThat((dict.array("displays")!![0] as Plist.Dict).long("width")).isEqualTo(1920L)
        assertThat(dict.dictionary("pi")).isEqualTo(null)
    }

    @Test
    fun `malformed input is refused`() {
        val good = hex(SMALL)
        assertFailure { BinaryPlistReader.read(good.copyOf(30)) }.isInstanceOf<PlistException>()
        assertFailure { BinaryPlistReader.read(good.changed(0, 'x'.code)) }.isInstanceOf<PlistException>()
        // Offset table pointing past the objects.
        assertFailure { BinaryPlistReader.read(good.changed(good.size - 1, 0x7F)) }.isInstanceOf<PlistException>()
        // Dictionary value reference to an object that does not exist.
        assertFailure { BinaryPlistReader.read(good.copyOf().also { it[10] = 9 }) }.isInstanceOf<PlistException>()
        // Dictionary that refers to itself.
        assertFailure { BinaryPlistReader.read(good.copyOf().also { it[10] = 0 }) }.isInstanceOf<PlistException>()
        // A key that is not a string.
        assertFailure { BinaryPlistReader.read(good.copyOf().also { it[9] = 2 }) }.isInstanceOf<PlistException>()
    }

    @Test
    fun `shared references cannot multiply the work (a billion-laughs plist)`() {
        // 30 arrays, each holding the next one twice: 2^30 reads if followed naively, 0.2 KiB of input.
        val levels = 30
        val objects =
            (0 until levels).map {
                val next = (it + 1).toByte()
                if (it < levels - 1) byteArrayOf(0xA2.toByte(), next, next) else byteArrayOf(0x08)
            }
        val body = objects.fold(ByteArray(0)) { acc, o -> acc + o }
        var offset = 8
        val table = objects.map { o -> offset.also { offset += o.size }.toByte() }.toByteArray()
        val trailer =
            ByteArray(6) + byteArrayOf(1, 1) + longBytes(levels.toLong()) + longBytes(0) + longBytes(8L + body.size)
        val bomb = "bplist00".encodeToByteArray() + body + table + trailer

        assertFailure { BinaryPlistReader.read(bomb) }.isInstanceOf<PlistException>()
    }

    @Test
    fun `a large blob behind many references is refused instead of copied each time`() {
        // DiPlay BplistCodecHostileInputTest.aLargeBlobThatManyReferencesPointAtIsRejected: 200 KB, 20 000 references.
        val blob = byteArrayOf(0x4F, 0x12) + intBytes(BLOB_BYTES, 4) + ByteArray(BLOB_BYTES)
        val refs = ByteArray(2 * 20_000) { if (it % 2 == 1) 1 else 0 }
        val array = byteArrayOf(0xAF.toByte(), 0x11) + intBytes(20_000, 2) + refs

        val plist = plist(listOf(array, blob), refSize = 2)

        assertFailure { BinaryPlistReader.read(plist) }.isInstanceOf<PlistException>()
    }

    @Test
    fun `an array that holds itself a million times allocates nothing per level`() {
        // DiPlay anArrayThatContainsItselfDoesNotAllocateItsFullCountAtEveryLevel: reading every reference up
        // front would keep 4 MB per level alive for the 32 levels before the depth limit.
        val count = 1_000_000
        val array = byteArrayOf(0xAF.toByte(), 0x12) + intBytes(count, 4) + ByteArray(count)
        val plist = plist(listOf(array), refSize = 1)
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

        val before = threads.currentThreadAllocatedBytes
        assertFailure { BinaryPlistReader.read(plist) }.isInstanceOf<PlistException>()
        val allocated = threads.currentThreadAllocatedBytes - before

        assertThat(allocated).isLessThan(8L * 1024 * 1024)
    }

    @Test
    fun `a reference beyond the Int range is refused, not truncated`() {
        // Eight-byte reference 0x1_0000_0001 would truncate to 1, a valid object.
        val array = byteArrayOf(0xA1.toByte()) + longBytes(0x1_0000_0001L)

        val plist = plist(listOf(array, byteArrayOf(0x09)), refSize = 8)

        assertFailure { BinaryPlistReader.read(plist) }.isInstanceOf<PlistException>()
    }

    @Test
    fun `an offset table position near the Long range is refused`() {
        val good = hex(SMALL)
        val bad = good.copyOf().also { longBytes(Long.MAX_VALUE - 1).copyInto(it, it.size - 8) }

        assertFailure { BinaryPlistReader.read(bad) }.isInstanceOf<PlistException>()
    }

    /** A bplist00 of [objects] in order, object 0 on top, four-byte offsets. */
    private fun plist(
        objects: List<ByteArray>,
        refSize: Int,
    ): ByteArray {
        val body = objects.fold(ByteArray(0)) { acc, o -> acc + o }
        var offset = 8
        val table = objects.fold(ByteArray(0)) { acc, o -> acc + intBytes(offset.also { offset += o.size }, 4) }
        val trailer =
            ByteArray(6) + byteArrayOf(4, refSize.toByte()) + longBytes(objects.size.toLong()) + longBytes(0) +
                longBytes(8L + body.size)
        return "bplist00".encodeToByteArray() + body + table + trailer
    }

    private fun intBytes(
        value: Int,
        size: Int,
    ) = ByteArray(size) { (value ushr (8 * (size - 1 - it))).toByte() }

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }

    private fun ByteArray.changed(
        index: Int,
        value: Int,
    ) = copyOf().also { it[index] = value.toByte() }

    private fun longBytes(value: Long) = ByteArray(8) { (value ushr (56 - 8 * it)).toByte() }

    @Test
    fun `values outside the plist integer range are refused`() {
        assertFailure { Plist.of(BigInteger.ONE.shiftLeft(64)) }.isInstanceOf<IllegalArgumentException>()
        assertFailure { Plist.of(Any()) }.isInstanceOf<IllegalArgumentException>()
    }

    private fun mixed(): Plist =
        Plist.dict(
            "displays" to
                listOf(
                    mapOf(
                        "width" to 1920,
                        "height" to 1080,
                        "uuid" to "e5f7a68d-7b0f-4305-984b-974f677a150b",
                        "features" to 14,
                    ),
                ),
            "neg" to -1,
            "big" to BigInteger.ONE.shiftLeft(64) - BigInteger.ONE,
            "top" to BigInteger.ONE.shiftLeft(63),
            "pi" to 3.5,
            "data" to byteArrayOf(0, 1),
            "uni" to "héllo",
            "t" to true,
            "f" to false,
            "long" to "x".repeat(20),
            "dup1" to "same",
            "dup2" to "same",
            "n300" to 300,
            "n70000" to 70000,
            "n5e9" to 5_000_000_000L,
        )

    private companion object {
        const val BLOB_BYTES = 200_000
        const val SMALL =
            "62706c6973743030d1010251611001080b0d000000000000010100000000000000030000000000000000000000000000000f"
        const val ARRAY20 =
            "62706c6973743030af10140102030405060708090a0b0c0d0e0f10111213141000100110021003100410051006100710081009" +
                "100a100b100c100d100e100f1010101110121013081f21232527292b2d2f31333537393b3d3f414345000000000000010100" +
                "0000000000001500000000000000000000000000000047"
        const val MIXED =
            "62706c6973743030df100f0102030405060708090a0b0c0d0e0f101a1b1c1d1e1f202122232324252658646973706c61" +
                "7973536e65675362696753746f70527069546461746153756e6951745166546c6f6e6754647570315464757032546e33" +
                "3030566e3730303030546e356539a111d412131415161718195577696474685668656967687454757569645866656174" +
                "757265731107801104385f102465356637613638642d376230662d343330352d393834622d3937346636373761313530" +
                "62100e13ffffffffffffffff140000000000000000ffffffffffffffff14000000000000000080000000000000002340" +
                "0c00000000000042000165006800e9006c006c006f09085f101478787878787878787878787878787878787878785473" +
                "616d6511012c120001117013000000012a05f2000008002900320036003a003e00410046004a004c004e00530058005d" +
                "00620069006e00700079007f0086008b00940097009a00c100c300cc00dd00ee00f700fa010501060107011e01230126" +
                "012b0000000000000201000000000000002700000000000000000000000000000134"
    }
}
