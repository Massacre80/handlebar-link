# CallStateUpdate (0x4155) messages built with struct, independently of our Kotlin encoder, for the parser's tests
# (run: python3 -I call_state_update.py). Framing: docs/research/carplay-spec/B-iap2-messages.md §1 (DiPlay
# iap2/wire/Iap2CsmFramer.kt): 40 40 | u16 length of the whole message | u16 id | parameters, each
# u16 length (header included) | u16 id | payload; strings are UTF-8 plus one NUL.
# Parameters as DiPlay reads them (shared/.../hud/BydCarPlayCall.kt:22-24, 34-51, 78-81): 0 remote id (string),
# 1 display name (string), 2 status (u8), 4 call UUID (string). Parameter 3 is what DiPlay's own test adds
# (CarPlayCallStateTest.kt:17-24, `u8(3, 1)`), so the parser must skip it.
import struct


def param(pid, payload):
    return struct.pack(">HH", 4 + len(payload), pid) + payload


def string(pid, text):
    return param(pid, text.encode("utf-8") + b"\x00")


def u8(pid, value):
    return param(pid, bytes([value]))


def message(mid, *params):
    body = b"".join(params)
    return struct.pack(">HHH", 0x4040, 6 + len(body), mid) + body


CASES = {
    # DiPlay CarPlayCallStateTest.kt:28, update("a", status = 2, name = "Mum")
    "ringing_a": message(0x4155, string(1, "Mum"), u8(2, 2), u8(3, 1), string(4, "a")),
    # DiPlay CarPlayCallStateTest.kt:44, update("b", status = 1, remote = "+971500000000")
    "sending_b": message(0x4155, string(0, "+971500000000"), u8(2, 1), u8(3, 1), string(4, "b")),
    # No UUID: the call is known by its remote id (BydCarPlayCall.kt:40)
    "active_remote_only": message(0x4155, string(0, " +441234 "), u8(2, 4)),
    # A blank UUID falls back to the remote id
    "held_blank_uuid": message(0x4155, string(0, "+441234"), u8(2, 5), string(4, "  ")),
    # No status: DiPlay ignores the update (BydCarPlayCall.kt:37)
    "no_status": message(0x4155, string(4, "a")),
    # A status of two bytes: optionalU8 refuses it, so the update is ignored
    "wide_status": message(0x4155, param(2, b"\x00\x02"), string(4, "a")),
    # A remote id without its NUL counts as empty; the UUID still names the call
    "bad_remote": message(0x4155, param(0, b"+44"), u8(2, 2), string(4, "c")),
    # A UUID without its NUL is ignored; the remote id names the call
    "bad_uuid": message(0x4155, string(0, "+44"), u8(2, 2), param(4, b"c")),
    # A NowPlayingUpdate carrying a u8 2: not a call (CarPlayCallStateTest.kt:66)
    "now_playing": message(0x5001, u8(2, 2)),
    # The two statuses that end a call (BydCarPlayCall.kt:41-42)
    "disconnected_a": message(0x4155, u8(2, 0), string(4, "a")),
    "disconnecting_b": message(0x4155, u8(2, 6), string(4, "b")),
    # The engine's call sequence: "a" answered, then held
    "active_a": message(0x4155, u8(2, 4), string(4, "a")),
    "held_a": message(0x4155, u8(2, 5), string(4, "a")),
    # A status DiPlay does not know
    "unknown_a": message(0x4155, u8(2, 9), string(4, "a")),
}

for name, data in CASES.items():
    print(name, data.hex())
