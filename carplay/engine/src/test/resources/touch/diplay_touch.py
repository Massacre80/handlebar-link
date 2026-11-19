# A transcription of DiPlay's touch path, for test vectors independent of our Kotlin:
#   AirPlayTouchSlots.assign  (vendor/diplay/shared/.../airplay/AirPlayTouchSlots.kt:14-34)
#   AirPlaySession.sendTouch scaling (AirPlaySession.kt:351-355): x * widthPixels, y * heightPixels
#   AirPlayHid.touchReport   (AirPlayHid.kt:44-55), Math.round = floor(v + 0.5)
#   AirPlaySession.sendHidReport (AirPlaySession.kt:444-451): {type, uuid, hidReport}, insertion order
import math, plistlib, sys

SLOTS = 2
class Slots:
    def __init__(self):
        self.pointers = [None]*SLOTS
        self.held = [None]*SLOTS
    def assign(self, contacts):
        report = [None]*SLOTS
        for (cid, x, y, down) in contacts:
            if cid in self.pointers:
                slot = self.pointers.index(cid)
            elif None in self.pointers and down:
                slot = self.pointers.index(None)
            else:
                continue
            self.pointers[slot] = cid
            report[slot] = (slot, x, y, down)
        for slot in range(SLOTS):
            if report[slot] is None and self.held[slot] is not None:
                h = self.held[slot]; report[slot] = (slot, h[1], h[2], False)
            sent = report[slot]
            if sent is not None and sent[3]:
                self.held[slot] = sent
            else:
                self.held[slot] = None; self.pointers[slot] = None
        return [r for r in report if r is not None]

def jround(v): return int(math.floor(v + 0.5))

def touch_report(contacts):
    out = bytearray(6*SLOTS)
    for slot in range(SLOTS):
        o = slot*6; out[o] = slot
        c = next((c for c in contacts if c[0] == slot), None)
        if c is None: continue
        out[o+1] = 1 if c[3] else 0
        x = jround(max(c[1], 0.0)); y = jround(max(c[2], 0.0))
        out[o+2] = x & 0xff; out[o+3] = (x >> 8) & 0xff
        out[o+4] = y & 0xff; out[o+5] = (y >> 8) & 0xff
    return bytes(out)

def command(report):
    return plistlib.dumps({"type": "hidSendReport", "uuid": "2a2a2a2a", "hidReport": report},
                          fmt=plistlib.FMT_BINARY, sort_keys=False)

def run(name, w, h, events):
    s = Slots()
    print(name, f"{w}x{h}")
    for ev in events:
        scaled = [(c[0], c[1]*w, c[2]*h, c[3]) for c in s.assign(ev)]
        r = touch_report(scaled)
        print("  report", r.hex(), "\n  command", command(r).hex())

run("tap", 1080, 2250, [[(7, 0.5, 0.25, True)], [(7, 0.5, 0.25, False)]])
run("two", 1080, 2250, [
    [(3, 0.1, 0.2, True)],
    [(3, 0.1, 0.2, True), (5, 0.9, 0.8, True)],
    [(3, 0.1, 0.2, True), (5, 0.9, 0.8, True), (9, 0.5, 0.5, True)],
    [(3, 0.12, 0.2, False), (5, 0.9, 0.8, True), (9, 0.5, 0.5, True)],
    [(5, 0.9, 0.8, True), (9, 0.5, 0.5, True)],
    [],
])
