# DiPlay AirPlay/CarPlay protocol internals

Source: `/Volumes/My files/pocof1/carlink-src`, vendored DiPlay engine.
All paths below are relative to the repo root unless noted otherwise.
Abbreviations:
- `airplay/` = `vendor/diplay/shared/src/main/java/com/shilapi/xcertplay/airplay/`
- `media/` = `vendor/diplay/shared/src/main/java/com/shilapi/xcertplay/media/`
- `orch/` = `vendor/diplay/shared/src/main/java/com/shilapi/xcertplay/orchestration/`
- `common/` = `vendor/diplay/common/src/main/java/com/shilapi/xcertplay/`
- `net/` = `vendor/diplay/shared/src/main/java/com/shilapi/xcertplay/network/`
- `mfi/` = `vendor/diplay/shared/src/main/java/com/shilapi/xcertplay/mfi/`

How claims are marked:
- No mark: read in the code, with a file:line citation.
- **[I]**: my inference, not checked in code or on a device.

---

## 1. Bootstrap: MFi authentication files

### 1.1 `DiPlayBootstrap.ensure()` (`common/DiPlayBootstrap.kt`)

Called once per process, synchronized + volatile `ready` flag (lines 14-43).
Only acts when `mfiTarget == MfiTarget.LOCAL`.

Steps:
1. Checks if `noBackupFilesDir/offline-mfi/` exists.
2. Creates a staging directory `noBackupFilesDir/offline-mfi-staging/`.
3. Copies two asset files from `assets/offline-mfi/`:
   - `identity.pk8` — PKCS#8 EC private key (P-256)
   - `certificate.p7b` — X.509 accessory certificate
4. Sets strict Unix permissions (owner-only read/execute).
5. Validates by calling `LocalMfiAuthenticationClient.load(staging)` — does a challenge-sign-verify
   round-trip to prove key matches certificate.
6. Atomically renames staging → final `offline-mfi/` directory.

### 1.2 `LocalMfiAuthenticationClient` (`mfi/LocalMfiAuthenticationClient.kt`)

MFi v3 provider. `protocolMajor() = 3` (line 24).

- `readCertificate()` returns the raw `certificate.p7b` bytes (line 25-30).
- `signChallenge(challenge)` signs a 32-byte digest with `NONEwithECDSA` (no second hash), converts
  DER to raw 64-byte P-256 signature (line 32-40).
- `load(directory)` validates: exactly one X.509 certificate, P-256 curve, key-cert match via
  random-challenge sign-verify (lines 48-76).

### 1.3 Identity and pairing

- `AirPlayIdentity` (`airplay/AirPlayIdentity.kt:3-19`): Ed25519 keypair + UUID pairing ID.
  `generate()` creates new random identity.
- `PairingStore` (`airplay/PairingStore.kt:4-16`): In-memory `HashMap<String, ByteArray>` mapping
  identifier → Ed25519 public key, with `onSave` callback for persistence.
- `AirPlayPersistence` (`common/AirPlayPersistence.kt`): SharedPreferences-based. Stores identity
  (`identity_private`, `identity_public`, `pairing_id`), pairings, lockdown records, display/audio
  settings.
- `DiPlayBootstrap.deviceId()`: SHA-256 of Ed25519 public key, first 6 bytes as a locally-administered
  MAC address.

---

## 2. Session start sequence

### 2.1 Entry point: CarLink side

`CarPlayAdapter.start(plan)` → `DiPlayEngineHooks.openHost(usbAttach)` → starts `CarPlayHostActivity`.

`CarPlayAdapter` (`carlink-core/.../connection/CarPlayAdapter.kt`, 424 lines):
- `start(plan)` (line 86-125): sets DiPlay prefs, opens the host activity.
- `install()` (line 198+): configures accessory name, Wi-Fi Direct stable identity, key remapping,
  rotation policy, BYD integrations.
- `CarPlayLinkWatch` monitors Wi-Fi Direct link health.

### 2.2 Host activity

`CarPlayHostActivity` (`common/CarPlayHostActivity.kt`):
- Full-screen CarPlay host using **TextureView** for decoded video.
- Creates `CarPlayController` with runtime config, AirPlay config, identity, pairings, media handler.
- Calls `controller.start()`.

### 2.3 Controller start

`CarPlayController.start()` (`orch/CarPlayController.kt:567-593`):
1. If video-in-car enabled, creates `VideoInCarGate`.
2. For wired: registers USB permission and attach receivers.
3. Calls `startMfi()`.

### 2.4 MFi discovery (`startMfi()`, line 1000)

Depending on `MfiTarget`:
- **LOCAL**: Loads `LocalMfiAuthenticationClient` from `noBackupFilesDir/offline-mfi/`. Fast path.
- **USB_CH341**: Discovers CH341 USB I2C bridge, opens it, scans I2C bus at 0x10 and 0x11 for the
  MFi coprocessor.
- **I2C**: Opens Linux `/dev/i2c-*` directly.
- **REMOTE**: Connects to a remote MFi server.

Once MFi is ready → `startPhone()` (line 1320).

### 2.5 Wireless path (`runWireless()`, line 1377)

Runs on a single worker executor:

1. **RFCOMM Bluetooth dial** (line 1437-1476): Opens a Bluetooth RFCOMM socket to the iPhone's iAP2
   UUID (`IAP2_IPHONE_UUID`). Runs as `BackgroundDial` in parallel with hotspot setup. Takes ~6.6s
   when iPhone Bluetooth was idle.

2. **Hotspot setup** (`startWirelessHotspot()`, line 1477): Creates Wi-Fi Direct group (P2P),
   local-only hotspot, or manual hotspot depending on configuration. For Wi-Fi Direct, uses
   `WifiP2pGroupManager` to create a persistent group with stable SSID/passphrase.

3. **Address selection** (lines 1488-1538): Waits for the hotspot interface to get an address.
   Prefers IPv6 link-local for Wi-Fi Direct (`WirelessAddressFamilyPlan`). Falls back to IPv4.

4. **Bonjour/mDNS** (`net/CarPlayBonjour.kt`, line 1554+): Publishes `_airplay._tcp` service using
   JmDNS on the hotspot interface. The TXT record carries feature flags, device ID, etc.
   `CarPlayBonjourProtocol.featuresTxt(features)` formats the features as hex low/high pair
   (`CarPlayBonjour.kt:77-80`).

5. **iAP2 link** (after RFCOMM connects): Opens the iAP2 control session over Bluetooth. Sends iAP2
   identification (accessory name, protocols). The iPhone's `CarPlayStartSession` message tells it to
   connect to the advertised AirPlay service.

6. **AirPlay TCP accept** (`FirstTcpWatchdog` monitors): The VPN service (`CarPlayVpnService`)
   listens on the AirPlay port. The iPhone connects over Wi-Fi to this port. 30s watchdog.

---

## 3. AirPlay /info plist

### 3.1 Builder: `AirPlayInfoPlist.build(config)` (`airplay/AirPlayInfoPlist.kt`, 287 lines)

Returns a `Map<String, Any?>` serialized as binary plist.

### 3.2 Top-level fields (lines 37-84)

| Field | Value | Notes |
|-------|-------|-------|
| `sourceVersion` | from config | e.g. "770.8.1" |
| `features` | `0x615653aee2` (`CARPLAY_FEATURES`) | Bitmask; audio bits masked off if `disableAudioOutput` |
| `statusFlags` | `4L` | Fixed |
| `model` | "xcertplay" | Configurable |
| `manufacturer` | "xcertplay" | Configurable |
| `deviceID` | SHA-256(Ed25519 pubkey) as MAC | |
| `bluetoothIDs` | `[btMac]` | Bluetooth MAC |
| `name` | device name | |
| `rightHandDrive` | boolean | |
| `keepAliveLowPower` | false | |
| `keepAliveSendStatsAsBody` | false | |
| `modes` | resources + appStates | Resource modes (§3.5) |
| `audioLatencies` | 9 entries | Types 100,101,102 with audioTypes |
| `audioFormats` | 9+ entries | PCM, Opus, AAC-LC combinations |
| `extendedFeatures` | `["vocoderInfo", "enhancedRequestCarUI"]` | |
| `displays` | 1-2 display entries | Main + optional cluster |
| `hidDevices` | 4 HID devices | Touch, knob, media, telephony |
| `oemIconVisible` | true (if icons) | |
| `oemIcons` | icon data array | |
| `hevcInfo` | `{}` (if hevc) | Enables H.265 video |
| `mainBufferedInfo` | `{}` (if buffered audio) | TCP music buffering |
| `videoPlaybackInfo` | video-in-car info | Only if videoInCar |

### 3.3 Display entry (`displayEntry()`, lines 201-239)

| Field | Value | Notes |
|-------|-------|-------|
| `uuid` | `"b7e6c5a0-1111-4000-8000-000000000001"` | `MAIN_UUID` |
| `type` | 110 | `STREAM_TYPE_MAIN_SCREEN` |
| `maxFPS` | 60 | Sanitized to 30-60, step 5 (`AirPlayDisplaySettings.kt:15-18`) |
| `widthPixels` | e.g. 2246 or scaled | Display resolution × scale |
| `heightPixels` | e.g. 1080 or scaled | |
| `widthPhysical` | 200mm default | Range 100-400mm; controls iOS UI sizing |
| `heightPhysical` | auto from aspect | |
| `features` | `0x0A` | `HIGH_FIDELITY_TOUCH` (0x08) \| `KNOBS` (0x02) |
| `primaryInputDevice` | 1 | Touch |
| `uiAppearanceMode` | 0 | Auto |
| `uiAppearanceSetting` | 0 | |
| `mapAppearanceMode` | 0 | |
| `mapAppearanceSetting` | 0 | |
| `viewAreas` | 1 area (full display) | |
| `initialViewArea` | 0 | |

Cluster (alt) display: type 111 (`STREAM_TYPE_ALT_SCREEN`), uuid `ALT_UUID`.

### 3.4 HID devices (`airplay/AirPlayHid.kt`, lines 22-143)

4 devices, each with a USB HID descriptor:

1. **Touch** (UID `0x2a2a2a2a`): Multi-touch, 2 contacts. 6 bytes per finger: slot (u8), tip-switch
   (u8), X (u16le), Y (u16le). Coordinates range 0..widthPixels / 0..heightPixels.

2. **Knob** (UID `0x2a2a2a2b`): 4-byte report. Buttons: select, home, back. Plus X axis, Y axis,
   scroll wheel.

3. **Media** (UID `0x2a2a2a2c`): 1-byte index. Play/pause, next track, previous track, volume up/down,
   etc.

4. **Telephony** (UID `0x2a2a2a2d`): 1-byte index. Hook switch, drop, digits 0-9, star, pound.

Touch reports are sent via `sendHidReport()` on the encrypted event channel.

### 3.5 Resource modes

- Screen: mode 1 (TRANSFER_TAKE, PRIORITY_NICE_TO_HAVE, CONSTRAINT_ANYTIME)
- Audio: mode 2 (same policy)
- AppStates entries for foreground/background/off.

### 3.6 Audio formats

9+ entries covering:
- PCM at 44100/48000 Hz, stereo (types 100, 101, 102)
- Opus (if enabled)
- AAC-LC (various sample rates)
- Entertainment default: 48000 Hz (`config.entertainmentSampleRate`)

### 3.7 Display config classes

- `AirPlayConfig` (`airplay/AirPlayConfig.kt`, 115 lines): `deviceName`, `deviceId`, `btMac`,
  `sourceVersion`, `main` (AirPlayDisplayConfig), `cluster`, `port=7000`,
  `entertainmentSampleRate=48000`, `hevc`, `disableAudioOutput`, `microphone`, `microphoneOpus`,
  `manufacturer="xcertplay"`, `model="xcertplay"`.

- `AirPlayDisplayConfig`: `widthPixels`, `heightPixels`, `widthPhysicalMm`, `heightPhysicalMm`,
  `fps=60`, `primaryInputDevice=1`, `viewArea`, `safeArea`, `features`, `viewAreas`, `cornerMasks`.

- `AirPlayDisplaySettings` (`airplay/AirPlayDisplaySettings.kt`, 93 lines): FPS range 30-60 step 5,
  default 60. Physical width range 100-400mm step 5mm, default 200mm.
  `resolvePhysicalSizeMm()` scales physical dimensions based on pixel ratios.

- `CarPlaySize` (`airplay/CarPlaySize.kt`, 19 lines): Presets LARGE=250mm, MEDIUM=300mm (default),
  SMALL=350mm. Width in mm controls iOS UI element sizing (wider = smaller controls).

- `CarPlayDisplayScale` (`airplay/CarPlayDisplayScale.kt`, 48 lines): Scale range 0.3x-1.0x
  (tenths 3-10), default 1.0x. Custom percent: 30%-160% for oversized stream + downscale.
  Ensures even pixel dimensions for video decoder compatibility.

---

## 4. AirPlay session: RTSP control and streams

### 4.1 `AirPlaySession` (`airplay/AirPlaySession.kt`, 1082 lines)

Main session class. Owns the RTSP connection, pairing, /info serving, stream SETUP/TEARDOWN, NTP
timing, keep-alive, and the encrypted event channel for HID.

### 4.2 Control thread

Thread name: `"airplay-control"` (daemon). Runs `runControl()` — reads RTSP messages from socket,
dispatches to `handle()`.

`handle()` routes (line ~300+):
- `SETUP` — negotiates and starts audio/video/data streams, returns ports
- `SET_PARAMETER` — receives parameter changes from iPhone
- `RECORD` — starts streaming
- `TEARDOWN` — stops a stream or the session
- `pair-setup` — SRP-6a pairing (first connection)
- `pair-verify` — Ed25519 pair verification (reconnection)
- `auth-setup` — MFi SAP authentication
- `/info` — returns the full /info plist (§3)
- `/command` — receives commands from iPhone
- `/feedback` — receives feedback/diagnostics

### 4.3 Event channel

Separate TCP socket, encrypted with HKDF-SHA512 derived keys from the shared secret:
- Salt: `"Events-Salt"`
- Read key: `"Events-Read-Encryption-Key"`
- Write key: `"Events-Write-Encryption-Key"`

Used for HID reports (touch, knob, media, telephony) via `sendHidReport()`.

### 4.4 Stream types

| Type | Constant | Purpose |
|------|----------|---------|
| 110 | `MAIN_SCREEN` | Main CarPlay display |
| 111 | `ALT_SCREEN` | Cluster/instrument display |
| 100 | `MAIN_AUDIO` | Media audio |
| 101 | `ALT_AUDIO` | Navigation/guidance audio |
| 102 | `MAIN_HIGH_AUDIO` | High-quality media audio |
| 103 | `MAIN_BUFFERED_AUDIO` | TCP-buffered music |
| 130 | `DATA` | iAP2 data tunnel |

### 4.5 Feature negotiation

`setupEnabledFeatures()` negotiates:
- HEVC video (H.265)
- iAP2 data channel
- View areas
- Alt screen (cluster)
- Corner masks
- Video playback (video-in-car, iOS 27)
- Main buffered audio

### 4.6 Cluster display

`setClusterUiShown()` / `setClusterUrl()` manage the instrument-cluster display (type 111).

---

## 5. Video decode pipeline

### 5.1 Stream reception: `ScreenStream` (`airplay/ScreenStream.kt`, 248 lines)

TCP server on a random port (returned to AirPlay SETUP). Accepts one connection from the expected
peer.

Thread: `"airplay-screen"`, priority `THREAD_PRIORITY_DISPLAY` (line 76).

Message format: **128-byte header** + body.
- Body size: `readU32Le(header, 0)` (line 84). Max 8MB.
- Opcode: `header[4]` (line 103).

| Opcode | Meaning | Body |
|--------|---------|------|
| 0 | Video frame | ChaCha20-Poly1305 encrypted. 16-byte tag at end. |
| 1 | Video config | Clear. Contains `avcC` or `hvcC` box. |

### 5.2 Frame decryption

`ScreenCodec.decryptFrame(key, counter, header, body)` → `AirPlayCrypto.chachaOpen(key, nonce, body, aad)` (line 169-170).

- Key: the DataStream output key from SETUP.
- Nonce: 8-byte little-endian counter (`AirPlayCrypto.nonce64(counter)`), incremented per frame.
- AAD (additional authenticated data): the 128-byte header.
- Tag: 16 bytes at end of body (`ScreenCodec.TAG_SIZE = 16`).

### 5.3 NAL unit conversion

`ScreenCodec.lengthPrefixedToAnnexB(payload)` (line 178-203):

Replaces each 4-byte big-endian NAL length prefix with an Annex B start code `00 00 00 01`.
Validates all lengths before converting. If any NAL is invalid, returns payload unchanged.

### 5.4 Codec detection

`ScreenCodec.detectConfig(payload)` (line 205-213):

Scans for `"hvcC"` or `"avcC"` FourCC in the config payload. Returns `VideoCodec.H265` or
`VideoCodec.H264` plus the codec-specific data after the FourCC. Falls back to heuristic
(`looksLikeAvcC`) for payloads without a clear FourCC.

### 5.5 Sender timestamp

`ScreenCodec.senderNanos(header)` (line 160-164):

Bytes 8..15 of the header, little-endian NTP 32.32 format, converted to nanoseconds. Consecutive
main-screen frames are exactly 1/60 s apart; cluster stream is 1/30 s. Skipped frames leave whole
multiples.

### 5.6 Media handler: `CarPlayMediaEngine` (`airplay/CarPlayMediaEngine.kt`, 200+ lines)

Implements `AirPlayMediaHandler`. On `onScreen()` (line 134):
1. Creates a `ScreenStream` with the decryption key from SETUP.
2. Calls `screen.listen()` which starts the TCP accept thread.
3. Wraps a `currentScreenListener()` that checks `isCurrent()` before each delivery — prevents a
   replaced stream's lingering thread from delivering stale frames.
4. Screen stream delivers frames to `MediaSink` interface.
5. Sets up video recovery handler (keyframe request via `session.sendCommand({"type":"forceKeyFrame"})`).

### 5.7 Decode queue: `VideoDecodeQueue` (`media/VideoDecodeQueue.kt`, 110 lines)

`LinkedBlockingQueue`-based job queue.

Job types (`VideoJob` sealed interface):
- `Config(codec, codecData)` — new codec configuration
- `Frame(nalus, receivedNs, senderNanos, arrivalNanos)` — video frame NALUs
- `SurfaceChanged(surface)` — output surface changed
- `DetachSurface(request)` — surface going away
- `RefreshPicture` — request keyframe
- `Resync` — lost sync, need keyframe

Backlog limits: `maxFrames = 60`, `maxBytes = 8MB` (line 38-39). When exceeded, discards all
queued frames and inserts a `Resync`.

`VideoReferenceChain` (line 26-33): Tracks keyframe dependency. After losing sync (`needsKeyFrame`),
only accepts IDR/random-access frames.

`DecoderInputPump` (line 92-109): Drains output buffers while waiting for input, preventing output
starvation from starving input.

### 5.8 `AndroidMediaSink` (`media/AndroidMediaSink.kt`, 450+ lines)

Concrete `MediaSink` implementation. Key properties:

**Video decoder management**:
- `videoDecoders`: `ConcurrentHashMap<Int, VideoDecoder>` — one per stream type (line 258).
- `surfaces`: `ConcurrentHashMap<Int, Surface>` — rendering targets (line 257).
- `defaultSurface`: the initial Surface passed at construction (line 255).

**Decoder options** (constructor parameters):
- `videoPacingDelayMillis`: smooth video — show frames at iPhone's time + delay. Only SurfaceView
  honours timestamps. 0 = show as soon as decoded (line 207-211).
- `mainVideoFrameRate`: requested as operating rate from decoder (line 213).
- `vendorLowLatencyDecoder`: Qualcomm low-latency mode — output each frame immediately, hand to
  screen from dedicated thread (line 217-218).
- `mtkDecoderTuningEnabled`: MediaTek ACodec low-latency/no-reorder modes (line 219).

**Surface lifecycle**:
- `setSurface(type, surface)` / `clearSurface(type, surface)` (line 317-329).
- `beginSurfaceDetach(surface, parkMain)` (line 339-355): Starts moving decoders off a surface.
  Main-screen decoder can be "parked" (moved to offscreen consumer, keeps state for instant return).
  Returns `SurfaceDetach` with an `await()` method. 2s timeout + 0.5s grace.

**Video recovery**:
- `requestVideoRecovery(type)` runs on `"carplay-video-recovery"` daemon thread (line 294-315).
- Calls the recovery handler (keyframe request) on a single-thread executor.

**Mirror decoders**:
- `setMirrorSurface(type, key, surface)` (line 444+): Decodes the same stream onto additional
  surfaces (e.g. a centre card).

---

## 6. Threading model

| Thread | Priority | Role | File |
|--------|----------|------|------|
| `airplay-control` | default (daemon) | RTSP message read/dispatch | `AirPlaySession.kt` |
| `airplay-screen` | `THREAD_PRIORITY_DISPLAY` | TCP read + ChaCha20 decrypt of video frames | `ScreenStream.kt:53,76` |
| `airplay-event-accept` | default (daemon) | Accept event channel TCP connection | `AirPlaySession.kt` |
| `airplay-keepalive` | default (daemon) | UDP keep-alive | `AirPlaySession.kt` |
| Video decode worker | inside `VideoDecoder` | Polls `VideoDecodeQueue`, feeds NALUs to `MediaCodec` | `AndroidMediaSink.kt` |
| MediaCodec output | set by Android | Releases decoded frames to Surface | (Android framework) |
| `carplay-video-recovery` | default (daemon) | Keyframe request on backlog overflow | `AndroidMediaSink.kt:294` |
| `carplay-mic-start` | default (daemon) | Microphone uplink scheduling | `CarPlayMediaEngine.kt:97` |

The video pipeline is:
```
iPhone → TCP → airplay-screen (decrypt + NAL convert) → MediaSink.onVideoFrame()
  → VideoDecodeQueue.offer() → VideoDecoder worker (MediaCodec.queueInputBuffer)
  → MediaCodec output (releaseOutputBuffer to Surface/TextureView)
```

Frame pacing (optional, `videoPacingDelayMillis > 0`): frames are timestamped with
`senderNanos + delay` and released at that time. Only works with SurfaceView
(`releaseOutputBuffer(index, timestamp)`).

---

## 7. Encryption and crypto

- **Control channel**: After pair-verify, the RTSP control socket is encrypted with the derived
  shared secret (`ControlCipher`).
- **Event channel**: HKDF-SHA512 from shared secret with:
  - Salt: `"Events-Salt"`
  - Read key info: `"Events-Read-Encryption-Key"`
  - Write key info: `"Events-Write-Encryption-Key"`
- **Video frames**: ChaCha20-Poly1305 per frame, with 128-byte header as AAD. Per-frame 64-bit
  counter as nonce.
- **Pairing**: SRP-6a for first pair-setup, Ed25519 for pair-verify.
- **MFi**: P-256 ECDSA signature of a 32-byte digest (NONEwithECDSA).

---

## 8. Minimum viable subset for a clean CarPlay receiver

### 8.1 Essential files (~30-35 files)

**AirPlay protocol** (`airplay/`):
- `AirPlaySession.kt` — RTSP control, event channel, HID input
- `AirPlayConfig.kt` — immutable session configuration
- `AirPlayInfoPlist.kt` — /info response builder
- `AirPlayHid.kt` — touch/knob/media/telephony HID reports
- `AirPlayIdentity.kt` — Ed25519 keypair
- `AirPlayDisplaySettings.kt` — display dimension sanitization
- `AirPlayCrypto.kt` — ChaCha20-Poly1305, HKDF-SHA512, Ed25519
- `PairSetup.kt` / `PairVerify.kt` / `PairingStore.kt` — SRP-6a pairing
- `Srp6a.kt` — SRP implementation
- `Tlv8Codec.kt` — TLV8 encoding for pairing
- `BplistCodec.kt` — binary plist encode/decode
- `RtspMessage.kt` — RTSP message framing
- `ControlCipher.kt` — encrypted control/event channel
- `ScreenStream.kt` — video stream receiver + decrypt
- `NtpClock.kt` — NTP timing exchange
- `AudioStream.kt` — audio stream receiver
- `CarPlayMediaEngine.kt` — media handler binding streams to sink
- `MfiSapAuthSetup.kt` — MFi SAP auth-setup handler

**Media decode** (`media/`):
- `AndroidMediaSink.kt` — MediaCodec video + AudioTrack audio
- `VideoDecodeQueue.kt` — decode queue
- `MediaCodecSupport.kt` — codec detection/configuration
- `MediaCodecStartup.kt` — decoder startup

**Orchestration** (`orch/`):
- `CarPlayController.kt` — wires everything together
- `CarPlayRuntimeConfig.kt` — runtime config
- `MfiRuntime.kt` — MFi coprocessor scanner

**MFi authentication** (`mfi/`):
- `LocalMfiAuthenticationClient.kt` — local P-256 MFi auth
- `MfiAuthenticator.kt` (interface)

**Network** (`net/`):
- `CarPlayBonjour.kt` — mDNS service advertisement
- `CarPlayVpnService.kt` — AirPlay TCP listener

**Transport** (various):
- Wired: `IphoneUsbHost.kt`, `Iap2UsbMuxHost.kt`, `NcmUsbBridge.kt`, `LockdownPairingClient.kt`,
  `Iap2IdentificationClient.kt`, `Iap2LinkEngine.kt`
- Wireless: `BluetoothRfcommDuplexStream.kt`, `Iap2WirelessControlClient.kt`

**Host** (`common/`):
- `CarPlayHostActivity.kt` — Activity with TextureView
- `CarPlayHostIntegration.kt` — integration hooks
- `DiPlayBootstrap.kt` — auth file installation
- `AirPlayPersistence.kt` — SharedPreferences persistence

### 8.2 Optional / skip for Handlebar Link

- **Android Auto** (`vendor/diauto/`, `:aa` process) — entirely separate engine
- **BYD integrations** (`hud/`) — BYD-specific HUD, cluster, wheel keys
- **Geely head unit** (`GeelyHeadUnit.kt`) — Geely-specific
- **CH341 USB I2C** (`Ch341*.kt`) — external MFi hardware, use LOCAL MFi instead
- **Car detection** (`carlink-core/.../detect/`) — phone/head-unit classifier
- **Cluster display** (`CarPlayClusterDisplay.kt`, overlays) — second screen
- **Video in car** (`VideoInCar.kt`) — iOS 27 parked video playback
- **Ambient music** (`AmbientMusic*.kt`) — LED strip visualization
- **Buffered audio** (`BufferedAudioStream.kt`) — TCP music buffering
- **Settings UI** (`common/.../settings/`, `DiPlayActivity.kt`) — DiPlay's own settings
- **Backup/restore** (`backup/`)
- **Glance widget** (`glance/`)
- **iAP2 location** (`Iap2LocationClient.kt`) — GPS forwarding
- **Vehicle speed/NMEA** (`VehicleSpeedNmea.kt`)
- **FYT RF serial** (`FytRfSerialIap2Link.kt`) — FYT head unit specific
- **Icons/OEM branding** (optional cosmetic)

---

## 9. Video-in-car (iOS 27, reference only)

`VideoInCar` (`airplay/VideoInCar.kt`, 254 lines): While the car is parked, the iPhone hands the
head unit a media URL and drives playback; the head unit plays it in its own player.

- `/info` includes `videoPlaybackInfo` with `featuresEx` and `playbackCapabilities`.
- Only `http`/`https` URLs play (no FairPlay/`skd`).
- Remote control via `POST /command` with `X-Apple-StreamID`.
- Settings channel UUID: `BB493F61-A6B8-4769-8D74-80C23A9F71C4`.
- **[I]** Not needed for a motorcycle CarPlay receiver — skip entirely.
