# Changelog

## 2.1.0 — 2026-11-21

- **Portrait display.** BikePlay now runs in portrait. iOS refuses a rotating-square display that starts in its portrait area, so portrait is declared as a plain portrait display at the right physical size — the zoom matches landscape, and the "CarPlay does not support this display resolution" screen is gone.
- **Re-pair after reinstall.** Reinstalling the app no longer sends you back through setup when the meter is still paired. The app re-adopts the meter from its surviving companion-device association, which also restores ignition auto-start.
- **Smoother music.** A song that dips mid-stream now resumes on a warm buffer (~100 ms, down from ~300 ms), and skipping to the next song keeps that warm buffer instead of cold-starting every track.
- **Richer ride logging.** Location forwarding logs periodic GPS stats (provider, accuracy, speed), making the next ride's logs easier to read.

## 2.0.0 — 2026-11-20

- First public release: Honda CB350 dashboard with its own wireless CarPlay receiver, intercom audio, calls, and GPS forwarding to the iPhone.
