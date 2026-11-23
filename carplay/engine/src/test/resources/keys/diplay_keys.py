# A transcription of DiPlay's key and Siri commands, for test vectors independent of our Kotlin
# (run: python3 -I diplay_keys.py). DiPlay source: CarLink vendor/diplay on upstream/main.
#   AirPlayHid.mediaReport / telephonyReport  (shared/.../airplay/AirPlayHid.kt:70-72): one byte, the array index
#   AirPlayHid media descriptor   (AirPlayHid.kt:117-123): index 1 play, 2 pause, 3 play/pause, 4 next, 5 previous
#   AirPlayHid telephony descriptor (AirPlayHid.kt:125-133): index 1 hook switch, 3 drop
#   AirPlaySession.sendMedia / sendTelephony (AirPlaySession.kt:380-388): the index, then 0 (release)
#   AirPlaySession.sendHidReport (AirPlaySession.kt:444-451): {type, uuid, hidReport}, insertion order
#   AirPlaySession.invokeSiri (AirPlaySession.kt:390-393): requestSiri with siriAction 2, then 3
#   CarPlayController TELEPHONY_HOOK_SWITCH = 1, TELEPHONY_DROP = 3 (orchestration/CarPlayController.kt:3723-3724)
import plistlib

KNOB_UID = "2a2a2a2b"       # AirPlayHid.kt:24, 0x2a2a2a2b.toString(16)
MEDIA_UID = "2a2a2a2c"      # AirPlayHid.kt:25, 0x2a2a2a2c.toString(16)
TELEPHONY_UID = "2a2a2a2d"  # AirPlayHid.kt:26


def hid(uid, index):
    return plistlib.dumps({"type": "hidSendReport", "uuid": uid, "hidReport": bytes([index])},
                          fmt=plistlib.FMT_BINARY, sort_keys=False)


# AirPlayHid.knobReport (AirPlayHid.kt:62-70): 4 bytes [buttons, x, y, wheel]; buttons bit0 select, bit1 home, bit2 back.
def knob(buttons):
    return plistlib.dumps({"type": "hidSendReport", "uuid": KNOB_UID, "hidReport": bytes([buttons, 0, 0, 0])},
                          fmt=plistlib.FMT_BINARY, sort_keys=False)


def siri(action):
    return plistlib.dumps({"type": "requestSiri", "params": {"siriAction": action}},
                          fmt=plistlib.FMT_BINARY, sort_keys=False)


for name, index in [("play", 1), ("pause", 2), ("play_pause", 3), ("next", 4), ("previous", 5), ("release", 0)]:
    print("media", name, hid(MEDIA_UID, index).hex())
for name, index in [("hook_switch", 1), ("drop", 3), ("release", 0)]:
    print("telephony", name, hid(TELEPHONY_UID, index).hex())
for name, buttons in [("home", 0x02), ("back", 0x04), ("release", 0x00)]:
    print("knob", name, knob(buttons).hex())
for action in (2, 3):
    print("siri", action, siri(action).hex())
