# Handlebar Link — notices

Handlebar Link (BikePlay) — an Android companion for the Honda CB350RS that links the
bike's meter to a CarPlay receiver running on the phone mounted to the handlebar.

Copyright (C) 2026 Devsed-official (Abid Majid).

This project is distributed under the **GNU Affero General Public License v3.0**
(see [LICENSE](LICENSE)). Some of the code it contains comes from projects with their
own licences; those licences and attributions are preserved below and in
[docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md).

## Code derived from other projects

- **DiPlay** — <https://github.com/shihabal3amri/DiPlay> — Copyright (C) 2024–2026
  shihabal3amri. Licensed under the **GNU General Public License v3.0**. The
  `:carplay:protocol`, `:carplay:engine` and `:carplay:engine-android` modules and the
  byte-level specs in `docs/research/carplay-spec/` are a port of DiPlay's receiver and
  its tests. The verbatim GPL-3.0 text is kept at [carplay/LICENSE](carplay/LICENSE).
- **xcertplay** — <https://github.com/shilapi/xcertplay> — Copyright (C) shilapi,
  GPL-3.0. DiPlay is based on xcertplay; its notices are preserved through DiPlay.
- **CarLink** — <https://github.com/F-e-n-y-x/CarLink> — Copyright (C) 2026 F-e-n-y-x,
  AGPL-3.0. Used as a reference implementation and as the source of four DiPlay
  hardening patches that this port follows. No CarLink code is vendored here.
- **DiAuto** — <https://github.com/shihabal3amri/DiAuto> — AGPL-3.0. Reference only;
  no DiAuto code is vendored here.

## Bundled assets

- **Inter Display** — SIL Open Font License 1.1. See
  `core/designsystem/licenses/Inter-OFL.txt`.
- **Integral CF** — a commercial font licensed by the project owner, bundled with
  this app with the owner's permission. It is **not** covered by this project's
  AGPL-3.0 licence. See `core/designsystem/licenses/README.md`.

## Trademarks and certification

CarPlay is a trademark of Apple Inc.; Android Auto is a trademark of Google LLC.
Honda, CB350 and CB350RS belong to their owner. This is an independent,
**not Apple-certified** project; no affiliation or endorsement by Apple, Google or
Honda is implied. CarPlay authentication uses an experimental accessory identity
supplied by the builder at compile time and is not guaranteed to survive iOS updates.
