package com.abid.handlebarlink.carplay.protocol.plist

import java.math.BigInteger

/**
 * A property-list value: what AirPlay's `/info`, SETUP, `/command` and `/feedback` bodies carry as binary
 * plists. Integers span signed 64-bit and unsigned 64-bit (stream connection ids use the top bit).
 */
public sealed interface Plist {
    /** A boolean. */
    public data class Bool(
        /** The value. */
        val value: Boolean,
    ) : Plist

    /** An integer in [MIN, MAX]. */
    public data class Integer(
        /** The value. */
        val value: BigInteger,
    ) : Plist {
        init {
            require(value in MIN..MAX) { "Plist integer out of range: $value" }
        }

        /** The value as a Long; values above Long.MAX_VALUE wrap (their bits are kept). */
        val bits: Long get() = value.toLong()

        /** Range limits. */
        public companion object {
            /** Smallest: −2^63. */
            public val MIN: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)

            /** Largest: 2^64 − 1. */
            public val MAX: BigInteger = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE
        }
    }

    /** A double. */
    public data class Real(
        /** The value. */
        val value: Double,
    ) : Plist

    /** A string. */
    public data class Text(
        /** The value. */
        val value: String,
    ) : Plist

    /** Raw bytes. */
    public class Data(
        /** The bytes. */
        public val value: ByteArray,
    ) : Plist {
        override fun equals(other: Any?): Boolean = other is Data && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "Data(${value.size} bytes)"
    }

    /** A date, as seconds since 2001-01-01 (Apple's reference date). */
    public data class Date(
        /** Seconds since 2001-01-01T00:00:00Z. */
        val secondsSince2001: Double,
    ) : Plist

    /** An ordered list. */
    public data class Array(
        /** The items. */
        val items: List<Plist>,
    ) : Plist

    /** A dictionary with string keys, in insertion order. */
    public data class Dict(
        /** The entries. */
        val entries: Map<String, Plist>,
    ) : Plist {
        /** The value of [key], or null. */
        public operator fun get(key: String): Plist? = entries[key]

        /** [key] as a Long, or null when absent or not an integer. */
        public fun long(key: String): Long? = (entries[key] as? Integer)?.bits

        /** [key] as a string, or null. */
        public fun text(key: String): String? = (entries[key] as? Text)?.value

        /** [key] as a dictionary, or null. */
        public fun dictionary(key: String): Dict? = entries[key] as? Dict

        /** [key] as an array, or null. */
        public fun array(key: String): List<Plist>? = (entries[key] as? Array)?.items

        /** [key] as bytes, or null. */
        public fun data(key: String): ByteArray? = (entries[key] as? Data)?.value
    }

    /** Conversions from Kotlin values. */
    public companion object {
        /**
         * Converts Kotlin values: Boolean, Int, Long, BigInteger, Double, Float, String, ByteArray, List and
         * Map (string keys, order kept), and Plist values as they are.
         */
        public fun of(value: Any): Plist =
            when (value) {
                is Plist -> {
                    value
                }

                is Boolean -> {
                    Bool(value)
                }

                is Int -> {
                    Integer(BigInteger.valueOf(value.toLong()))
                }

                is Long -> {
                    Integer(BigInteger.valueOf(value))
                }

                is BigInteger -> {
                    Integer(value)
                }

                is Double -> {
                    Real(value)
                }

                is Float -> {
                    Real(value.toDouble())
                }

                is String -> {
                    Text(value)
                }

                is ByteArray -> {
                    Data(value.copyOf())
                }

                is List<*> -> {
                    Array(value.map { of(requireNotNull(it) { "Plist arrays cannot hold null" }) })
                }

                is Map<*, *> -> {
                    Dict(
                        value.entries.associate { (k, v) ->
                            k.toString() to
                                of(requireNotNull(v) { "Plist value of $k is null" })
                        },
                    )
                }

                else -> {
                    throw IllegalArgumentException("No plist form for ${value::class.simpleName}")
                }
            }

        /** A dictionary from pairs, in order. */
        public fun dict(vararg entries: Pair<String, Any>): Dict = Dict(entries.associate { it.first to of(it.second) })
    }
}

/** A malformed binary plist. */
public class PlistException(
    message: String,
) : Exception(message)
