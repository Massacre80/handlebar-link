# A transcription of DiPlay's main-display negotiation, for test vectors independent of our Kotlin
# (run: python3 -I diplay_display.py [out-dir]). DiPlay source: CarLink vendor/diplay on upstream/main (1d37e9ac),
# DiPlay's CarPlay turning-screen square as CarLink turns it on for a phone (carlink-core CarPlayAdapter.kt:269-272).
#   HOST = common/.../CarPlayHostActivity.kt
#   HOST:7281-7282  evenDisplaySize: each side down to even, at least 2
#   HOST:5289-5297  resolvePhysicalSize -> AirPlayDisplaySettings.resolvePhysicalSizeMm (AirPlayDisplaySettings.kt:44-81),
#                   maximum = the largest window seen (HOST:6196-6203), reference = loadWidthPhysicalMm, default
#                   CarPlaySize.DEFAULT = MEDIUM = 300 mm (AirPlayPersistence.kt:833-839, CarPlaySize.kt:8-14),
#                   basis WIDTH (AirPlayDisplaySettings.kt:26)
#   HOST:5032-5036  display scale 100 % and UI scale DEFAULT leave the canvas as it is (CarPlayDisplayScale.kt:33-41,
#                   CarPlayUiScale.kt:19-21)
#   HOST:5124-5145  the square: CarPlayRotation.squareSide(long side, SMOOTHER, hevc=false); squareMm = the long
#                   side's physical length; safe area dropped
#   CarPlayRotation.kt:20-23, 39-78  squareSide: limit = min(1920, long), candidates [limit & ~1] + SIDES below it,
#                   the first the decoder takes ([takes] here: our decoder needs isSizeSupported, so DiPlay's configure
#                   probe for squares beyond the listed sizes is not used); none -> the plain canvas
#   CarPlayRotation.kt:88-99  turningAreas: (side, even(side*h/w)) for the landscape window, (even(side*w/h), side) for
#                   the portrait one. Our two windows are known (the display's cutout rule: landscape leaves the
#                   cutout strip out, portrait is the whole screen), so they replace turnedWindows' estimate
#                   (CarPlayRotation.kt:105-117), which assumes system bars that keep their screen edges.
#   CarPlayViewAreas.kt:178-233  build: one area per screen, [landscape, portrait], automatic dock (no edge), no split
#                   screen, no side panel, no CarLink size variants; initial = the full screen of the start orientation;
#                   a plain canvas with one screen the canvas's size declares no areas (null)
#   AirPlayInfoPlist.kt:201-286  displayEntry and areaDict: the display dict in insertion order;
#                   safeAreaDrawOutside default true (AirPlayPersistence.kt:1456-1458)
#   AirPlayDisplaySettings.kt:30-31, 89-92  sanitizeFps: 30..60 snapped to the nearest 5
#   AirPlayDisplaySettings.kt:41-42  reported millimetres clamped 1..2000
#   HOST:6152-6166  contentRect: an area smaller than the canvas fills the window, scaled per axis; else
#                   CarPlayVideoLayout.fit (CarPlayVideoLayout.kt:9-17)
#   CarPlayVideoSurface.kt:44-54  CarPlaySurfaceBounds.from: the SurfaceView's integer bounds
#   CarPlayTouchMapper.kt:14-33  x = (eventX - content.left) / content.width, clamped 0..1 (y likewise)
import math, plistlib, sys, os

UUID = "6f0c2a1e-3b5d-4c8a-9e71-2d4b6a8c0f13"   # ours (AirPlayReceiver.MAIN_DISPLAY_UUID); any fixed UUID works
SIDES = [2560, 2304, 2048, 1920, 1600, 1280]       # CarPlayRotation.kt:20
SMOOTHER = 1920                                    # CarPlayRotation.kt:23
REFERENCE_MM = 300                                 # CarPlaySize.DEFAULT (MEDIUM)


def jround(v):  # Java Math.round(double)
    return int(math.floor(v + 0.5))


def even(size):  # HOST:7281-7282
    return (max(size[0] // 2 * 2, 2), max(size[1] // 2 * 2, 2))


def sanitize_mm(v):
    return min(max(v, 1), 2000)


def resolve_physical(cw, ch, mw, mh, ref):  # AirPlayDisplaySettings.kt:44-81, basis WIDTH
    reference_mm = max(ref, 1) * (cw / mw)
    width_mm = reference_mm
    height_mm = width_mm * ch / cw
    return sanitize_mm(jround(width_mm)), sanitize_mm(jround(height_mm))


def snap_fps(v):  # AirPlayDisplaySettings.kt:89-92 with 30, 60, 5
    c = min(max(v, 30), 60)
    return (((c - 30) + 5 // 2) // 5) * 5 + 30


def square_side(long_side, takes):  # CarPlayRotation.kt:39-78, picture SMOOTHER
    limit = min(SMOOTHER, long_side)
    aligned = limit & ~1
    for c in [aligned] + [s for s in SIDES if s < aligned]:
        if c >= 2 and takes(c):
            return c
    return None


def negotiate(screen_long, screen_short, cutout, portrait, fps, takes):
    cut = min(max(cutout, 0), screen_long - screen_short)
    landscape_window = (screen_long - cut, screen_short)
    portrait_window = (screen_short, screen_long)
    size = portrait_window if portrait else landscape_window
    aligned = even(size)
    max_w = max(landscape_window[0], portrait_window[0], size[0])   # the largest windows seen
    max_h = max(landscape_window[1], portrait_window[1], size[1])
    wmm, hmm = resolve_physical(aligned[0], aligned[1], max_w, max_h, REFERENCE_MM)
    long_px = max(aligned)
    side = square_side(long_px, takes)
    if side is None:
        return dict(window=size, width=aligned[0], height=aligned[1], wmm=wmm, hmm=hmm, fps=fps, areas=None, initial=0)
    square_mm = wmm if aligned[0] >= aligned[1] else hmm
    land = (side, int(side * landscape_window[1] // landscape_window[0]) & ~1)
    port = (int(side * portrait_window[0] // portrait_window[1]) & ~1, side)
    areas = [land, port]                       # screens [landscape, portrait], one edge (None)
    initial = 1 if size[1] > size[0] else 0    # startPortrait = size.height > size.width
    return dict(window=size, width=side, height=side, wmm=square_mm, hmm=square_mm, fps=fps, areas=areas,
                initial=initial)


def area_dict(w, h, area, inset=(0, 0, 0, 0)):  # AirPlayInfoPlist.kt:242-286, drawOutside true
    if area is None:
        left = top = 0; right = bottom = 0
    else:
        left, top = 0, 0
        right, bottom = w - area[0], h - area[1]
    view = dict(widthPixels=w - left - right, heightPixels=h - top - bottom, originXPixels=left, originYPixels=top)
    # OURS, not DiPlay (its turning square drops the safe area, HOST:5124-5145): the area's own safe insets
    # (l, t, r, b) shrink its safe area; (0, 0, 0, 0) is DiPlay's safe area, the whole area.
    il, it, ir, ib = inset
    safe = dict(widthPixels=view["widthPixels"] - il - ir, heightPixels=view["heightPixels"] - it - ib,
                originXPixels=left + il, originYPixels=top + it)
    safe["drawUIOutsideSafeArea"] = True
    view["safeArea"] = safe
    return view


def display_entry(n, insets=None):  # AirPlayInfoPlist.kt:201-240
    w, h = n["width"], n["height"]
    e = {
        "uuid": UUID, "type": 110, "maxFPS": snap_fps(n["fps"]),
        "widthPixels": w, "heightPixels": h,
        "widthPhysical": sanitize_mm(n["wmm"]), "heightPhysical": sanitize_mm(n["hmm"]),
        "features": 0x08 | 0x02, "primaryInputDevice": 1,
        "uiAppearanceMode": 0, "uiAppearanceSetting": 0, "mapAppearanceMode": 0, "mapAppearanceSetting": 0,
    }
    areas = n["areas"]
    if insets:
        e["viewAreas"] = [area_dict(w, h, a, i) for a, i in zip(areas, insets)]
    else:
        e["viewAreas"] = [area_dict(w, h, a) for a in areas] if areas else [area_dict(w, h, None)]
    e["initialViewArea"] = 0 if not areas else min(max(n["initial"], 0), len(areas) - 1)
    if areas and len(areas) > 1:
        e["viewAreaTransitionControl"] = True
    return e


def content_rect(cw, ch, area, vw, vh):  # HOST:6152-6166, CarPlayVideoLayout.kt:9-17
    if area is not None and (area[0] != cw or area[1] != ch):
        ox, oy = (area[2], area[3]) if len(area) > 2 else (0, 0)
        sx = vw / area[0]; sy = vh / area[1]
        return (-ox * sx, -oy * sy, cw * sx, ch * sy)
    s = min(vw / cw, vh / ch)
    return ((vw - cw * s) / 2, (vh - ch * s) / 2, cw * s, ch * s)


def surface_bounds(c):  # CarPlayVideoSurface.kt:44-54 (Kotlin roundToInt on a Float: half up)
    left = jround(c[0]); top = jround(c[1])
    return (left, top, max(jround(c[0] + c[2]) - left, 1), max(jround(c[1] + c[3]) - top, 1))


def touch(c, x, y):  # CarPlayTouchMapper.kt:26-27
    return (min(max((x - c[0]) / max(c[2], 1), 0.0), 1.0), min(max((y - c[1]) / max(c[3], 1), 0.0), 1.0))


out = sys.argv[1] if len(sys.argv) > 1 else None
POCO = (2246, 1080)
cases = [
    ("poco-landscape", POCO, 0, False, 60, lambda s: s <= 2176),
    ("poco-portrait", POCO, 0, True, 60, lambda s: s <= 2176),
    ("poco-landscape-cutout89", POCO, 89, False, 60, lambda s: s <= 2176),
    ("poco-portrait-cutout89", POCO, 89, True, 60, lambda s: s <= 2176),
    ("poco-landscape-plain", POCO, 0, False, 60, lambda s: False),
    ("poco-landscape-1600", POCO, 0, False, 60, lambda s: s <= 1600),
]
for name, (L, S), cut, portrait, fps, takes in cases:
    n = negotiate(L, S, cut, portrait, fps, takes)
    e = display_entry(n)
    data = plistlib.dumps(e, fmt=plistlib.FMT_BINARY, sort_keys=False)
    print(name, "window=%dx%d" % n["window"], "canvas=%dx%d" % (n["width"], n["height"]),
          "mm=%dx%d" % (n["wmm"], n["hmm"]), "areas=%s" % n["areas"], "initial=%d" % e["initialViewArea"],
          "maxFPS=%d" % e["maxFPS"], "bytes=%d" % len(data))
    if out:
        with open(os.path.join(out, name + ".bplist"), "wb") as f:
            f.write(data)
    # The picture on the window the display gives it (the start window), and three touches in it.
    area = n["areas"][n["initial"]] if n["areas"] else None
    vw, vh = n["window"]
    c = content_rect(n["width"], n["height"], area, vw, vh)
    print("  content=(%r, %r, %r, %r)" % c, "bounds=%s" % (surface_bounds(c),))
    for (x, y) in [(0.0, 0.0), (vw / 2, vh / 2), (vw - 1.0, vh - 1.0), (vw * 0.25, vh * 0.75)]:
        print("  touch(%r, %r) -> (%r, %r)" % ((x, y) + touch(c, x, y)))

# Two layouts no Poco session uses, for the other branches: a whole canvas letterboxed in a window of another shape
# (fit), and an area away from the corner (Apple's widescreen profile: the right 1280x720 of 1920x720, VIEW_AREAS.md).
for name, cw, ch, area, vw, vh, touches in [
    ("fit", 2246, 1080, None, 2000, 1200, [(1000.0, 600.0), (10.0, 200.0), (1999.0, 1000.0)]),
    ("offset-area", 1920, 720, (1280, 720, 640, 0), 2246, 1080, [(0.0, 0.0), (1123.0, 540.0), (2245.0, 1079.0)]),
    # Each edge rounds on its own: top 154.32 and bottom 1045.68 give 892 rows, where rounding the height gives 891.
    ("fit-rounding", 2246, 1001, None, 2000, 1200, [(1000.0, 600.0)]),
    # An area away from the top on a view whose two axes scale differently (1.7547 across, 1.5 down).
    ("offset-area-y", 1920, 1080, (1280, 720, 320, 180), 2246, 1080, [(0.0, 0.0), (1123.0, 540.0)]),
]:
    c = content_rect(cw, ch, area, vw, vh)
    print(name, "content=(%r, %r, %r, %r)" % c, "bounds=%s" % (surface_bounds(c),))
    for (x, y) in touches:
        print("  touch(%r, %r) -> (%r, %r)" % ((x, y) + touch(c, x, y)))


# OURS, not DiPlay: safe areas measured from the phone (app ScreenEdges.kt, owner 2026-11-20), an independent
# transcription of the rule. A rectangle inset by r(1 - 1/sqrt 2) has its corners on a rounded corner of radius r;
# upright, the bottom also clears the navigation bar's strip (CarPlay docks along it) and the top and bottom clear the
# cutout ends; on its side only the corners (CarPlay docks on a side edge; the window already leaves the cutout strip
# out). Screen pixels scaled to the area per axis, rounded up, under a quarter side.
def safe_insets(edges, portrait, window, area):
    radius, cut_top, cut_bottom, navigation = edges
    corner = math.ceil(radius * (1 - math.sqrt(0.5)))
    top = max(cut_top, corner) if portrait else corner
    bottom = max(cut_bottom, navigation, corner) if portrait else corner  # on its side CarPlay docks on a side edge

    def scaled(px, a, w):
        return 0 if px <= 0 or w <= 0 else min(math.ceil(px * a / w), (a - 1) // 4)

    return (scaled(corner, area[0], window[0]), scaled(top, area[1], window[1]),
            scaled(corner, area[0], window[0]), scaled(bottom, area[1], window[1]))


# The Poco F1 as Android reports it: corners 104 px (top; bottom 0), cutout 86 px at the top, navigation bar 63 px.
POCO_EDGES = (104, 86, 0, 63)
for name, portrait in [("poco-safe-landscape", False), ("poco-safe-portrait", True)]:
    n = negotiate(2246, 1080, 86, portrait, 60, lambda s: s <= 2176)
    land_window, port_window = (2246 - 86, 1080), (1080, 2246)
    insets = [safe_insets(POCO_EDGES, False, land_window, n["areas"][0]),
              safe_insets(POCO_EDGES, True, port_window, n["areas"][1])]
    e = display_entry(n, insets)
    data = plistlib.dumps(e, fmt=plistlib.FMT_BINARY, sort_keys=False)
    print(name, "areas=%s" % n["areas"], "insets=%s" % insets, "initial=%d" % e["initialViewArea"],
          "bytes=%d" % len(data))
    if out:
        with open(os.path.join(out, name + ".bplist"), "wb") as f:
            f.write(data)
