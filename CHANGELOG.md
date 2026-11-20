# Changelog

All notable changes to Handlebar Link are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow semantic
versioning and are tagged in this repository.

## [2.0.0] — 2026-11-21

First public release, built from the `v2` line that replaces the early prototype.

### Added

- Meter link for the Honda CB350/CB350RS: BLE protocol, pairing through Android's
  companion-device consent, ignition auto-start, restart recovery and ride diagnostics.
- **BikePlay**, the app's own CarPlay receiver: wired bootstrap, Wi-Fi Direct session,
  picture and touch, audio, Siri, calls and the iAP2 tunnel hand-off. CarLink is no longer
  used or required; handlebar presses are routed to BikePlay.
- Smart handlebar actions per situation (riding, ringing, on a call), with safe microphone
  mute handling and a rider-facing Handlebar page.
- Garage: service, chain, paper and custom reminders with daily notifications, service log,
  colourways and "Forget this bike".
- Night and Bento themes, portrait and landscape (two-pane) layouts, one app header,
  design-system tokens and screenshot tests for every screen.
- Companion-device presence, foreground-service and update recovery, and a shareable
  diagnostic log.
- Public release packaging: AGPL-3.0 licence, third-party notices, signed release builds
  and a tagged-release workflow.

### Changed

- `main` now points to this line; the former 4-commit prototype is kept at `prototype`
  and tagged `prototype-1.0`.
- Release builds are signed with a key configured outside the repository. Development
  builds were debug-signed and must be uninstalled before installing this release.

### Notes

- Not an Apple-certified product. CarPlay depends on a builder-supplied accessory identity
  and may stop working after an iOS update. See the README's CarPlay notice.

[2.0.0]: https://github.com/Devsed-official/handlebar-link/releases/tag/v2.0.0
