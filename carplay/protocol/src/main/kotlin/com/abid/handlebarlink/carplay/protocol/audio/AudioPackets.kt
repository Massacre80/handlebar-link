package com.abid.handlebarlink.carplay.protocol.audio

import com.abid.handlebarlink.carplay.protocol.crypto.ChaCha20Poly1305
import com.abid.handlebarlink.carplay.protocol.pairing.counterNonce
import com.abid.handlebarlink.carplay.protocol.stream.MediaStreamException
import java.security.GeneralSecurityException

/**
 * One decrypted CarPlay audio packet. [rtp] is what DiPlay hands its renderer: the clear 12-byte RTP header
 * followed by the decrypted payload (AudioStream.kt:154; BufferedAudioStream.kt:369).
 */
public class AudioPacket(
    /** The RTP header and the decrypted payload. */
    public val rtp: ByteArray,
) {
    /** RTP sequence number, u16 big-endian at byte 2. */
    public val sequence: Int get() = ((rtp[2].toInt() and 0xFF) shl 8) or (rtp[3].toInt() and 0xFF)

    /** RTP timestamp (the sample time DiPlay tracks), u32 big-endian at byte 4, as 0..2^32 − 1. */
    public val timestamp: Long get() = readU32Be(rtp, 4)

    /** RTP payload type, the low 7 bits of byte 1. */
    public val payloadType: Int get() = rtp[1].toInt() and 0x7F

    /** A copy of the decrypted payload (everything after the RTP header). */
    public fun payload(): ByteArray = rtp.copyOfRange(AudioWire.RTP_HEADER_BYTES, rtp.size)
}

/**
 * The sealed RTP layout every CarPlay audio stream shares (AudioStream.kt:27-33, 131-139;
 * MicrophonePacketizer.kt:81-126; BufferedAudioStream.kt:354-370; spec C §8):
 * `RTP header (12, clear) | ciphertext | Poly1305 tag (16) | nonce tail (8)`, nonce = 4 zero bytes + the
 * tail, associated data = RTP bytes 4..11 (timestamp and SSRC).
 */
public object AudioWire {
    /** RTP header length (AudioStream.kt:212). */
    public const val RTP_HEADER_BYTES: Int = 12

    /** Nonce tail length (AudioStream.kt:214). */
    public const val NONCE_TAIL_BYTES: Int = 8

    /** Header, tag and nonce tail: the smallest packet (AudioStream.kt:114, 215). */
    public const val MIN_PACKET_BYTES: Int = RTP_HEADER_BYTES + ChaCha20Poly1305.TAG_BYTES + NONCE_TAIL_BYTES

    /** DiPlay's receive buffer for one audio datagram (AudioStream.kt:210). */
    public const val DATAGRAM_BYTES: Int = 4_096

    /** The UDP receive buffer DiPlay asks for on the data socket (AudioStream.kt:62-67, 211). */
    public const val RECEIVE_BUFFER_BYTES: Int = 512 * 1024

    private const val AAD_START = 4

    /** Opens one sealed packet with [cipher]; null when it is too short or does not authenticate. */
    internal fun open(
        cipher: ChaCha20Poly1305,
        packet: ByteArray,
        offset: Int,
        length: Int,
    ): AudioPacket? {
        if (length < MIN_PACKET_BYTES) return null
        val sealedEnd = offset + length - NONCE_TAIL_BYTES
        val nonce =
            ByteArray(NONCE_BYTES).also {
                packet.copyInto(
                    it,
                    NONCE_BYTES - NONCE_TAIL_BYTES,
                    sealedEnd,
                    sealedEnd + NONCE_TAIL_BYTES,
                )
            }
        val aad = packet.copyOfRange(offset + AAD_START, offset + RTP_HEADER_BYTES)
        val payload =
            try {
                cipher.open(nonce, aad, packet, offset + RTP_HEADER_BYTES, sealedEnd - offset - RTP_HEADER_BYTES)
            } catch (_: GeneralSecurityException) {
                null
            }
        return payload?.let {
            val rtp = ByteArray(RTP_HEADER_BYTES + it.size)
            packet.copyInto(rtp, 0, offset, offset + RTP_HEADER_BYTES)
            it.copyInto(rtp, RTP_HEADER_BYTES)
            AudioPacket(rtp)
        }
    }

    private const val NONCE_BYTES = ChaCha20Poly1305.NONCE_BYTES
}

/**
 * Decrypts the datagrams of a realtime audio stream (types 100, 101, 102, UDP), one RTP packet per datagram,
 * as DiPlay's AudioStream.kt:91-178, with the stream's
 * [com.abid.handlebarlink.carplay.protocol.stream.StreamKeys.output] key.
 *
 * As DiPlay, a datagram that is too short or does not authenticate is dropped and the stream goes on
 * (AudioStream.kt:114-129, 138-153); its RTP version byte is not checked. The control (RTCP) port's datagrams
 * are read and discarded (AudioStream.kt:181-190). Not thread-safe: one instance per receiving thread.
 */
public class RealtimeAudioDecryptor(
    key: ByteArray,
    cipherName: String,
) {
    private val cipher = ChaCha20Poly1305(key, cipherName)

    /** Opens one datagram of [length] bytes at [offset]; null when it is dropped. */
    public fun open(
        datagram: ByteArray,
        offset: Int = 0,
        length: Int = datagram.size,
    ): AudioPacket? {
        require(offset >= 0 && length >= 0 && offset + length <= datagram.size) { "Slice out of range" }
        return AudioWire.open(cipher, datagram, offset, length)
    }
}

/**
 * Reads the main buffered audio stream (type 103, TCP), as DiPlay's BufferedAudioStream.kt:76-135, 346-370.
 * Each frame is a u16 big-endian length that counts its own two bytes, then one sealed RTP packet under the
 * SETUP plist's 32-byte `shk` (CarPlayMediaEngine.kt:283-287).
 *
 * - A length below [MIN_FRAME_BYTES] ends the stream: [MediaStreamException] (BufferedAudioStream.kt:89-94).
 * - A frame whose first byte is not 0x80 (RTP v2 without padding, extension or CSRCs) or that does not
 *   authenticate is dropped (BufferedAudioStream.kt:97-101, 358-367).
 * - A frame with an empty payload is dropped (BufferedAudioStream.kt:111).
 *
 * Playback timing, the 8 MiB queue and its back-pressure stay with the caller. Not thread-safe.
 */
public class BufferedAudioReader(
    key: ByteArray,
    cipherName: String,
) {
    private val cipher = ChaCha20Poly1305(key, cipherName)
    private val prefix = ByteArray(LENGTH_BYTES)
    private var prefixFilled = 0
    private var body = ByteArray(0)
    private var bodySize = -1
    private var bodyFilled = 0
    private var failed = false

    /**
     * Adds [length] bytes of [bytes] from [offset]; returns the packets of the frames they completed.
     *
     * @throws MediaStreamException on an invalid frame length; every later call throws [IllegalStateException].
     */
    public fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
    ): List<AudioPacket> {
        check(!failed) { "The buffered audio stream already failed" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "Slice out of range" }
        val packets = ArrayList<AudioPacket>()
        var at = offset
        val end = offset + length
        while (at < end) {
            if (bodySize < 0) {
                prefix[prefixFilled++] = bytes[at++]
                if (prefixFilled == LENGTH_BYTES) startBody()
            } else {
                val n = minOf(bodySize - bodyFilled, end - at)
                bytes.copyInto(body, bodyFilled, at, at + n)
                bodyFilled += n
                at += n
                if (bodyFilled == bodySize) {
                    frame()?.let(packets::add)
                    prefixFilled = 0
                    bodySize = -1
                    bodyFilled = 0
                }
            }
        }
        return packets
    }

    private fun startBody() {
        val frameLength = ((prefix[0].toInt() and 0xFF) shl 8) or (prefix[1].toInt() and 0xFF)
        if (frameLength < MIN_FRAME_BYTES) {
            failed = true
            throw MediaStreamException("Buffered audio frame length $frameLength is below $MIN_FRAME_BYTES")
        }
        bodySize = frameLength - LENGTH_BYTES
        if (body.size < bodySize) body = ByteArray(bodySize)
    }

    private fun frame(): AudioPacket? {
        val packet = if ((body[0].toInt() and 0xFF) == RTP_V2_PLAIN) AudioWire.open(cipher, body, 0, bodySize) else null
        return packet?.takeIf { it.rtp.size > AudioWire.RTP_HEADER_BYTES }
    }

    /** Framing constants (BufferedAudioStream.kt:330-350). */
    public companion object {
        /** The length prefix (BufferedAudioStream.kt:346). */
        public const val LENGTH_BYTES: Int = 2

        /** Smallest valid frame length, prefix included (BufferedAudioStream.kt:90). */
        public const val MIN_FRAME_BYTES: Int = LENGTH_BYTES + AudioWire.MIN_PACKET_BYTES

        /** The buffer size advertised in the SETUP reply and enforced (BufferedAudioStream.kt:333-334). */
        public const val AUDIO_BUFFER_BYTES: Int = 8 * 1024 * 1024

        private const val RTP_V2_PLAIN = 0x80
    }
}

/**
 * Seals microphone samples for the iPhone's uplink port of a type-100 stream, as DiPlay's
 * MicrophonePacketizer.kt:95-126, with the stream's
 * [com.abid.handlebarlink.carplay.protocol.stream.StreamKeys.input] key: RTP header `0x80`, payload type,
 * sequence (u16 BE), timestamp (u32 BE), SSRC 0; nonce = 4 zero bytes + LE64 counter, also sent as the tail.
 * Sequence, timestamp and nonce counter start at 0. Not thread-safe.
 */
public class MicrophonePacketSealer(
    key: ByteArray,
    cipherName: String,
    private val payloadType: Int,
) {
    private val cipher = ChaCha20Poly1305(key, cipherName)
    private var sequence = 0
    private var timestamp = 0
    private var nonce = 0L

    /** Seals [body] (encoded audio, or big-endian PCM: [toWirePcm]) and advances the RTP clock by [samples]. */
    public fun seal(
        body: ByteArray,
        samples: Int,
    ): ByteArray {
        val header = ByteArray(AudioWire.RTP_HEADER_BYTES)
        header[0] = 0x80.toByte()
        header[1] = (payloadType and 0x7F).toByte()
        header[2] = (sequence ushr 8).toByte()
        header[3] = sequence.toByte()
        for (i in 0 until 4) header[4 + i] = (timestamp ushr (24 - 8 * i)).toByte()
        val nonceBytes = counterNonce(nonce)
        val sealed = cipher.seal(nonceBytes, header.copyOfRange(4, AudioWire.RTP_HEADER_BYTES), body)
        val packet = header + sealed + nonceBytes.copyOfRange(4, nonceBytes.size)
        sequence = (sequence + 1) and 0xFFFF
        timestamp += samples
        nonce++
        return packet
    }

    /** Byte order of microphone PCM. */
    public companion object {
        /**
         * Android's little-endian 16-bit PCM as the big-endian samples CarPlay's microphone payload carries
         * (MicrophonePacketizer.kt:128-139). A trailing odd byte is kept as is.
         */
        public fun toWirePcm(pcmLittleEndian: ByteArray): ByteArray {
            val output = pcmLittleEndian.copyOf()
            var index = 0
            while (index + 1 < output.size) {
                val first = output[index]
                output[index] = output[index + 1]
                output[index + 1] = first
                index += 2
            }
            return output
        }
    }
}

private fun readU32Be(
    source: ByteArray,
    offset: Int,
): Long =
    ((source[offset].toLong() and 0xFF) shl 24) or
        ((source[offset + 1].toLong() and 0xFF) shl 16) or
        ((source[offset + 2].toLong() and 0xFF) shl 8) or
        (source[offset + 3].toLong() and 0xFF)
