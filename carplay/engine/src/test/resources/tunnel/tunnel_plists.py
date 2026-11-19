"""Independent bytes for the iAP2 tunnel hand-over tests (TunnelHandoffTest).

Every plist is written by Python's plistlib (FMT_BINARY, keys in insertion order), never by our encoder. The keys
and values are DiPlay's (file:line next to each) and the real iPhone's, as CarLink logged them on the Poco
(2026-11-19 15:16:22.850: SETUP stream type=130 payload={...}; 22.869: command type=disableBluetooth keys=[deviceID]).
Run:  python3 -I tunnel_plists.py
"""
import plistlib


def show(name, value):
    print(name, plistlib.dumps(value, fmt=plistlib.FMT_BINARY, sort_keys=False).hex())


UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"  # CarPlayMediaEngine.kt:707

# The real iPhone's type-130 SETUP (CarLink log), in the order logged.
show("TUNNEL_SETUP", {"streams": [{
    "controlType": 2,
    "channelID": "FA:56:DF:5B:E5:9A-RCS-5",
    "seed": 6277450898808826287,
    "clientUUID": "7DB7CA70-0A85-4D8C-AC8C-F332909C7893",
    "type": 130,
    "wantsDedicatedSocket": True,
    "sendMessageAsIs": True,
    "clientTypeUUID": UUID,
}]})
# DiPlay's reply (CarPlayMediaEngine.kt:437-442): type 130, streamID 1, dataPort; the port is the fake's fourth.
show("TUNNEL_REPLY_50003", {"streams": [{"type": 130, "streamID": 1, "dataPort": 50003}]})

# Lower-case uuid (DiPlay upper-cases it, :380), a streamConnectionID to echo (:438-441).
show("TUNNEL_SETUP_WITH_ID", {"streams": [{"type": 130, "clientTypeUUID": UUID.lower(), "seed": 12345,
                                            "streamConnectionID": 77}]})
show("TUNNEL_REPLY_WITH_ID_50003", {"streams": [{"type": 130, "streamID": 1, "dataPort": 50003,
                                                  "streamConnectionID": 77}]})

# Declined: another data stream (the video-in-car settings channel, VideoInCar), and no seed (:384).
show("OTHER_DATA_STREAM", {"streams": [{"type": 130, "clientTypeUUID": "BB493F61-A6B8-4769-8D74-80C23A9F71C4",
                                         "seed": 1}]})
show("NO_SEED", {"streams": [{"type": 130, "clientTypeUUID": UUID}]})
show("EMPTY_STREAMS_REPLY", {"streams": []})

# The iPhone's hand-over command (CarLink log keys=[deviceID]) and DiPlay's other spelling (CarPlayController.kt:2920-2922).
show("DISABLE_BLUETOOTH", {"type": "disableBluetooth", "params": {"deviceID": "C0:17:54:D1:2E:E8"}})
show("DISABLE_BLUETOOTH_DASHED", {"type": "Disable-Bluetooth", "params": {}})

# Our first message on the tunnel (AirPlaySession.kt:395-400): iAPSendMessage with the marker and DiPlay's tunnel
# SYN back to back (spec A 2 and 7.2 B: FF 55 02 00 EE 10, then FF 5A 00 1D 80 63 00 00 A7 | 01 04 FF FF 00 ... | D4).
marker = bytes.fromhex("ff550200ee10")
tunnel_syn = bytes.fromhex("ff5a001d80630000a7" "0104ffff0000000000000a00020b02010c0102" "d4")
show("IAP_SEND_START", {"type": "iAPSendMessage", "params": {"data": marker + tunnel_syn}})
