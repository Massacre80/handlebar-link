"""Independent vectors for the CarPlay media streams (README.md next to this file).

Written from DiPlay's source (file:line in the comments), not from our Kotlin. Uses Python's
`cryptography` (OpenSSL) for ChaCha20-Poly1305 and HKDF-SHA512. Run:  python3 -I generate_vectors.py
"""
import math
import struct

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


def hkdf(ikm, salt, info):
    return HKDF(algorithm=hashes.SHA512(), length=32, salt=salt, info=info).derive(ikm)


def nonce64(counter):  # AirPlayCrypto.kt:219-228
    return b"\x00" * 4 + struct.pack("<Q", counter)


print("== Stream keys (CarPlayMediaEngine.kt:665-678, 705-706, 715-722) ==")
shared = bytes(range(32))
for number in (12345, 0xF000000000000001):
    salt = b"DataStream-Salt" + str(number).encode()  # unsigned decimal, no separator
    print(salt.decode(), "output", hkdf(shared, salt, b"DataStream-Output-Encryption-Key").hex())
    print(salt.decode(), "input ", hkdf(shared, salt, b"DataStream-Input-Encryption-Key").hex())

print("== Screen stream (ScreenStream.kt:83-125, 160-170) ==")
screen_key = bytes(range(32))  # ScreenStreamPeerTest.kt:24
aead = ChaCha20Poly1305(screen_key)


def screen_header(opcode, body_size, ntp):
    header = bytearray(i & 0xFF for i in range(128))  # filler: the whole header is the AAD
    header[0:4] = struct.pack("<I", body_size)
    header[4] = opcode
    header[8:16] = struct.pack("<Q", ntp)
    return bytes(header)


def sealed_frame(counter, plain, ntp):
    header = screen_header(0, len(plain) + 16, ntp)
    return header + aead.encrypt(nonce64(counter), plain, header)


picture_a = bytes.fromhex("00000002" "6511" "00000003" "419a02")
avcc = bytes.fromhex("016400 1f ffe1 0004 6764001f 01 0004 68ee3c80".replace(" ", ""))
picture_b = bytes.fromhex("00000001" "41")
t_a = (3 << 32) | 0x40000000  # 3.25 s (ScreenCodecTest.kt:69-72)
t_b = t_a + 0x04444444  # one 1/60 s step (ScreenCodecTest.kt:74-76)
frame_a = sealed_frame(0, picture_a, t_a)
config = screen_header(1, len(avcc), 0) + avcc
frame_b = sealed_frame(1, picture_b, t_b)  # the config frame does not advance the counter
print("frameA", frame_a.hex())
print("config", config.hex())
print("frameB", frame_b.hex())
print("frameB sealed with counter 2 (wrong)", sealed_frame(2, picture_b, t_b).hex())

print("== Realtime audio RTP (AudioStream.kt:114-154) ==")
audio_key = bytes(i + 1 for i in range(32))
header = bytes.fromhex("8060" "0007" "fffffc00" "11223344")
payload = bytes.fromhex("216c4f5510")
tail = bytes(range(7, 15))
packet = header + ChaCha20Poly1305(audio_key).encrypt(b"\x00" * 4 + tail, payload, header[4:12]) + tail
print("audio", packet.hex())
print("buffered (u16 BE length incl. itself, BufferedAudioStream.kt:89-97)", (struct.pack(">H", len(packet) + 2) + packet).hex())

print("== Microphone uplink (MicrophonePacketizer.kt:95-126; inputs of MicrophonePacketizerTest.kt:11-15) ==")
mic = ChaCha20Poly1305(audio_key)
for seq, ts, n, body in ((0, 0, 0, bytes.fromhex("28010203")), (1, 480, 1, bytes.fromhex("f8003142"))):
    h = bytes([0x80, 100]) + struct.pack(">H", seq) + struct.pack(">I", ts) + b"\x00" * 4
    print("mic", seq, (h + mic.encrypt(nonce64(n), body, h[4:12]) + struct.pack("<Q", n)).hex())

print("== Timing clock (NtpClock.kt:30, 55-58, 147-180, 205-224) ==")
MASK = (1 << 64) - 1


def ntp64_now(millis):  # NtpClock.kt:213-218, IEEE doubles as in Java
    seconds = millis / 1000.0 + 2208988800
    whole = math.floor(seconds)
    fraction = math.floor((seconds - whole) * float(1 << 32))
    return (whole << 32) | fraction


def ntp_from_nanos(ns):  # NtpClock.kt:220-224
    s, r = divmod(ns, 1_000_000_000)
    return ((s << 32) | ((r << 32) // 1_000_000_000)) & MASK


wall_millis = 1_700_000_000_250
mono0 = 5_000_000_000
offset0 = ((ntp64_now(wall_millis) & MASK) * 1_000_000_000 >> 32) - mono0  # NtpClock.kt:205-211
print("offset0 ns", offset0)
print("now(mono0)", hex(ntp_from_nanos(mono0 + offset0)))
print("now(mono0 + 1000000007)", hex(ntp_from_nanos(mono0 + 1_000_000_007 + offset0)))
step = 3600 * 1_000_000_000  # an offset of exactly 3600 s: t2 = t3 = t1 + 3600 << 32, t4 = t1
print("now after 3600 s step", hex(ntp_from_nanos(mono0 + offset0 + step)))
d = 274877907  # t2 = t3 = t1 + d, t4 = t1: offset d / 2^32 s = 64.0000000 ms < 128 ms, slewed by 1/8
slew = round((d + d) * 0.5 / float(1 << 32) / 8.0 * 1e9)
print("slew ns", slew, "now after slew", hex(ntp_from_nanos(mono0 + offset0 + step + slew)))
