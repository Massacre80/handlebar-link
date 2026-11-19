"""Independent vectors for the iAP2 tunnel's data connection (README.md next to this file).

Written from DiPlay's source (IapTunnel.kt, file:line in the comments), not from our Kotlin. Uses Python's
`cryptography` (OpenSSL) for ChaCha20-Poly1305. Run:  python3 -I iap_tunnel_vectors.py
"""
import struct

from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

KEY = bytes(range(1, 33))  # IapTunnelPeerTest.kt:13: ByteArray(32) { (it + 1).toByte() }
aead = ChaCha20Poly1305(KEY)


def nonce64(counter):  # AirPlayCrypto.kt:220-228: 4 zero bytes, then the counter little-endian
    return b"\x00" * 4 + struct.pack("<Q", counter)


def frame(counter, plain):  # IapTunnel.kt:170-187: u16 LE length, sealed with the length bytes as AAD
    aad = struct.pack("<H", len(plain))
    return aad + aead.encrypt(nonce64(counter), plain, aad)


def package(message_type, body, filler):  # IapTunnel.kt:189-205: size u32 BE at 0 (header included), type at 16
    header = bytearray((filler + i) & 0xFF for i in range(32))  # the other header bytes are not read
    header[0:4] = struct.pack(">I", 32 + len(body))
    header[16:20] = message_type
    return bytes(header) + body


# A. DiPlay's own case (IapTunnelPeerTest.kt:59-66), with its zero filler: comm body 01 02 03, counter 0.
plain_a = bytearray(32)
plain_a[3] = 35
plain_a[16:20] = b"comm"
print("A", frame(0, bytes(plain_a) + bytes([1, 2, 3])).hex())

# B. Two frames. Frame 0: a non-comm record, then the first 20 bytes of a comm record carrying the iPhone's
# SYN+ACK (spec A 7.2 C). Frame 1: the rest of it, an empty comm record, and a comm record carrying peer data
# seq 145 (spec A 7.2 E). The iAP2 bytes that come out: C, then E.
syn_ack = bytes.fromhex("ff5a001dc0906300d7" "0104ffff0000000000000a00020b02010c0102" "d4")
data_145 = bytes.fromhex("ff5a00104091630a59" "404000061d00" "5d")
other = package(b"ctrl", b"abcd", 0x40)
comm_c = package(b"comm", syn_ack, 0x80)
empty = package(b"comm", b"", 0xC0)
comm_e = package(b"comm", data_145, 0x10)
b0 = frame(0, other + comm_c[:20])
b1 = frame(1, comm_c[20:] + empty + comm_e)
print("B0", b0.hex())
print("B1", b1.hex())
print("B1 sealed with counter 0 (wrong)", frame(0, comm_c[20:] + empty + comm_e).hex())
