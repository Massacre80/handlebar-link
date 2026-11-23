# Changelog

## 2.1.3 — 2026-11-23

- **Music plays on your intercom again.** CarPlay's music now goes out over the intercom's music (A2DP) channel and lets the phone route it there, instead of trying to force it onto the intercom's *call* channel — which shares its Bluetooth address but is only open during a call, so music fell silent. Songs now play on the V6.

## 2.1.2 — 2026-11-23

- **BikePlay's Home control goes to your bike dashboard.** The Home button in the on-screen controls now returns to Handlebar Link's home screen (CarPlay keeps running in the background), instead of opening the iPhone's CarPlay app grid. Back still steps back inside CarPlay.

## 2.1.1 — 2026-11-23

- **Re-pairing your iPhone works again.** If your iPhone was already paired with the bike (for example after reinstalling or flashing a ROM), the Add-iPhone screen used to sit on "Waiting" forever and BikePlay wouldn't connect. Now an already-paired iPhone is recognised straight away — no need to un-pair and pair again.
- **Choose where music plays, even to a disconnected intercom.** The Music picker now lists your intercom (not only devices that happen to be connected right now), and choosing it actually routes CarPlay's music there — our own audio is sent to the device, the same way calls already are.
- **On-screen controls in BikePlay.** A small floating cluster over the CarPlay screen — collapses to a dot, expands to Back · Home · Settings — so you can step back or jump to the app grid without reaching past the dash.

## 2.1.0 — 2026-11-21

- **Portrait display.** BikePlay now runs in portrait. iOS refuses a rotating-square display that starts in its portrait area, so portrait is declared as a plain portrait display at the right physical size — the zoom matches landscape, and the "CarPlay does not support this display resolution" screen is gone.
- **Re-pair after reinstall.** Reinstalling the app no longer sends you back through setup when the meter is still paired. The app re-adopts the meter from its surviving companion-device association, which also restores ignition auto-start.
- **Smoother music.** A song that dips mid-stream now resumes on a warm buffer (~100 ms, down from ~300 ms), and skipping to the next song keeps that warm buffer instead of cold-starting every track.
- **Richer ride logging.** Location forwarding logs periodic GPS stats (provider, accuracy, speed), making the next ride's logs easier to read.

## 2.0.0 — 2026-11-20

- First public release: Honda CB350 dashboard with its own wireless CarPlay receiver, intercom audio, calls, and GPS forwarding to the iPhone.
