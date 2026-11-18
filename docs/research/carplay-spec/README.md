# CarPlay wire spec (from DiPlay's source)

Byte-level specs for the CarPlay receiver, extracted on 2026-11-18 from DiPlay's working source and tests, every
constant cited by file and line. They replace the earlier summary notes (`06-*.md`) as the source of truth for
protocol bytes: when the code and a spec disagree, re-read the cited DiPlay line.

| File | Covers |
|---|---|
| `A-iap2-link.md` | RFCOMM role, iAP2 marker, link frames, checksums, SYN parameters, ACK/EAK/retransmission |
| `B-iap2-messages.md` | control-session (0x4040) messages: identification, MFi auth, Wi-Fi config, StartSession |
| `C-airplay-pairing.md` | TLV8, SRP-6a pair-setup, pair-verify, control-channel cipher, MFi SAP auth-setup, stream keys |
| `D-airplay-session.md` | wireless start order and timeouts, RTSP routes, binary plist, `/info`, SETUP, Bonjour, Wi-Fi Direct |

## Provenance

- DiPlay: github.com/shihabal3amri/DiPlay (GPL-3.0), v0.2.22, the latest release on 2026-11-18. Its `main` has
  no protocol change after v0.2.22.
- Read through CarLink (github.com/F-e-n-y-x/CarLink, AGPL-3.0), which vendors DiPlay at `vendor/diplay`. Line
  numbers refer to CarLink 1.0.16 (`carlink-src`, branch `bike`).
- CarLink 1.0.17 (2026-11-18) ports four DiPlay hardening PRs that DiPlay has not merged yet. Our implementation
  follows them (read with `git -C carlink-src show upstream/main:vendor/diplay/...`):
  - #1124: before pair-verify completes, only `POST /pair-setup`, `POST /pair-verify` and `GET /info` are answered;
    anything else gets `470 Connection Authorization Required`. The pair-verify secret counts only after M4.
  - #1304: RTSP requests are reassembled in a growing buffer (linear copying), header cap 16 KiB, body cap 1 MiB.
  - #1297: a CSM string with a NUL inside is rejected; a malformed 0x4E0E is logged, never fatal.
  - #1130: per-peer and total limits on AirPlay control connections; 30 s to reach pair-verify.
- Golden vectors marked "computed" in these files were produced by re-implementing DiPlay's encoder; only the
  ones marked as test literals come from DiPlay's own tests.
- Each spec lists DiPlay's bugs and oddities at the end. Where our code deliberately differs, the KDoc says so.

## Checked against the CarLink APK that works on the Poco (2026-11-19)

`CarLink-1.0.16.apk` (sha256 matches its `.sha256` file) is the build that runs CarPlay with the owner's iPhone:
- its `assets/offline-mfi` files are byte-identical (SHA-256) to `~/.carlink-private/offline-mfi`;
- its dex holds DiPlay's classes and the constants these specs use (RFCOMM UUID, source version 950.7.1, the
  pairing/control/event/stream HKDF labels, `application/pairing+tlv8`, `_airplay._tcp`, `_carplay-ctrl._tcp`);
- it predates the hardening PRs (no 470 gate, no ControlFrameBuffer, no connection limits); those come from 1.0.17.

Our `MfiIdentity` loads the owner's real files, sends the 607-byte certificate as-is and signs a 32-byte digest
into 64 raw bytes that verify against the certificate (one-off check, nothing copied into the repo).

Nothing here was checked against the iPhone by our own code yet; that is the on-device step (C2).
