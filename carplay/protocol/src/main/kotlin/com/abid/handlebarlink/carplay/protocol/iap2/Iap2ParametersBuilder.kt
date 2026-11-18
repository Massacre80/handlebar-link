package com.abid.handlebarlink.carplay.protocol.iap2

/** Builds a message's parameters in order (DiPlay iap2/body/Iap2BodyBuilder.kt, Iap2WireCodec.kt). */
public class Iap2ParametersBuilder internal constructor() {
    private val parameters = ArrayList<Iap2Parameter>()

    /** UTF-8 plus a NUL; a NUL inside is refused, as DiPlay does (Iap2WireCodec.kt:67-70). */
    public fun string(
        id: Int,
        value: String,
    ) {
        require('\u0000' !in value) { "iAP2 string $id contains a NUL" }
        parameters += Iap2Parameter(id, value.encodeToByteArray() + 0)
    }

    /** Several NUL-terminated strings in one parameter (supported languages). */
    public fun strings(
        id: Int,
        values: List<String>,
    ) {
        require(values.none { '\u0000' in it }) { "iAP2 string list $id contains a NUL" }
        parameters += Iap2Parameter(id, values.fold(ByteArray(0)) { acc, s -> acc + s.encodeToByteArray() + 0 })
    }

    /** One unsigned byte. */
    public fun u8(
        id: Int,
        value: Int,
    ) {
        require(value in 0..0xFF) { "iAP2 u8 $id out of range: $value" }
        parameters += Iap2Parameter(id, byteArrayOf(value.toByte()))
    }

    /** Big-endian u16. */
    public fun u16(
        id: Int,
        value: Int,
    ) {
        require(value in 0..0xFFFF) { "iAP2 u16 $id out of range: $value" }
        parameters += Iap2Parameter(id, ByteArray(2).also { it.putU16(0, value) })
    }

    /** Big-endian u32. */
    public fun u32(
        id: Int,
        value: Long,
    ) {
        require(value in 0..0xFFFF_FFFFL) { "iAP2 u32 $id out of range: $value" }
        parameters +=
            Iap2Parameter(id, ByteArray(4) { (value ushr (24 - 8 * it)).toByte() })
    }

    /** A presence flag: header only. */
    public fun none(id: Int) {
        parameters += Iap2Parameter(id, ByteArray(0))
    }

    /** Raw bytes. */
    public fun bytes(
        id: Int,
        value: ByteArray,
    ) {
        parameters += Iap2Parameter(id, value.copyOf())
    }

    /** Big-endian u16 values back to back (message id lists). */
    public fun u16List(
        id: Int,
        values: List<Int>,
    ) {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            require(v in 0..0xFFFF) { "iAP2 message id out of range: $v" }
            out.putU16(i * 2, v)
        }
        parameters += Iap2Parameter(id, out)
    }

    /** A group: its own parameter list, ids numbered from 0 inside it. */
    public fun group(
        id: Int,
        content: Iap2ParametersBuilder.() -> Unit,
    ) {
        parameters += Iap2Parameter(id, encodeParameters(Iap2ParametersBuilder().apply(content).parameters))
    }

    internal fun build(): List<Iap2Parameter> = parameters.toList()
}

/** Builds message [id] with the parameters [content] adds. */
public fun iap2Message(
    id: Int,
    content: Iap2ParametersBuilder.() -> Unit = {},
): Iap2Message = Iap2Message(id, Iap2ParametersBuilder().apply(content).build())
