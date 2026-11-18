# CarPlay engine research — compiled from 4 parallel dissections

Source: CarLink 1.0.16 (`/Volumes/My files/pocof1/carlink-src`, branch `bike`, tag v1.0.16) and
Handlebar Link v2 (`/Volumes/My files/pocof1/handlebar-link`).

Abbreviations: CPHA = `vendor/diplay/common/.../CarPlayHostActivity.kt`,
CPC = `vendor/diplay/shared/.../orchestration/CarPlayController.kt`,
CPHI = `vendor/diplay/common/.../CarPlayHostIntegration.kt`.

---

## 0. The 12-step critical path (cold start to first frame, wireless)

```
 1. Generate/load Ed25519 identity (once, persisted)
 2. Load MFi auth (LOCAL: identity.pk8 + certificate.p7b from ~/.carlink-private/)
 3. Create Wi-Fi Direct P2P group (stable SSID + passphrase)
 4. Publish _airplay._tcp via JmDNS on the P2P interface
 5. Discover _carplay-ctrl._tcp (iPhone's CarPlay control)
 6. Connect Bluetooth RFCOMM to bonded iPhone (UUID 00000000-deca-fade-deca-deafdecacafe)
 7. iAP2 wireless control: identification -> MFi auth -> Wi-Fi creds (0x5703) -> StartSession (0x4301)
 8. Accept AirPlay TCP from iPhone on port 7000
 9. RTSP handshake: pair-setup (SRP-6a) -> pair-verify (Ed25519) -> auth-setup (MFi SAP) -> GET /info
10. SETUP streams: NTP timing + event channel, then per-stream (video TCP, audio UDP)
11. RECORD -> video frames flow: decrypt ChaCha20-Poly1305, Annex B, MediaCodec -> Surface
12. Touch HID reports sent back via encrypted event channel
```

---

## 1. Bootstrap and identity

### Entry point

CarLink: `CarLinkCore.install(app, config)` from `Application.onCreate` in the main process.
Our equivalent: the app's `Application.onCreate` or the ride service, main process only.

### Ed25519 identity (generated once, persisted)

- `AirPlayIdentity`: Ed25519 key pair + UUID pairing ID
- Generated via `AirPlayCrypto.ed25519Generate()` using BouncyCastle
- Stored in SharedPrefs (`xcertplay_airplay`): `identity_private`, `identity_public`, `pairing_id`
- The public key (hex) and pairing ID go into Bonjour TXT records (`pk`, `pi`) and iAP2 StartSession

### MFi authentication (the Poco has no hardware coprocessor)

- `LocalMfiAuthenticationClient` loads `identity.pk8` (PKCS#8 EC P-256 private key) and `certificate.p7b`
  from `appContext.noBackupFilesDir/"mfi"` (installed from `assets/offline-mfi/` by `DiPlayBootstrap`)
- Signs with `NONEwithECDSA` (pre-hashed 32-byte digest), DER -> raw 64-byte (r||s) via BouncyCastle ASN1
- Protocol major: 3
- Build: `DIPLAY_AUTH_ASSETS_DIR=~/.carlink-private` copies `offline-mfi/` into assets at build time
- **These files are NEVER committed** (`.gitignore`)

### Display settings written before every session

`CarPlaySettingsPolicy.apply()` writes to DiPlay's SharedPrefs:
- `display_width_physical_mm` (SMALL 350 / MEDIUM 300 / LARGE 220 mm)
- `display_scale_percent` (100 / 75 / 50)
- `display_fps` (30-60, step 5)
- `hevc_enabled`, `direct_video_output`, wireless mode, night mode, etc.

For the Poco F1 on a fixed landscape bike mount:
- Plain canvas (not turning square) — force Screen type = Car stereo or keep auto-rotate off
- `widthPixels`: 2246 (or scaled), `heightPixels`: 1080
- `widthPhysical`: 150-220 mm (bigger controls for glove use)
- `maxFPS`: 60 (Snapdragon 845 handles it)
- `primaryInputDevice`: 1 (touch)
- `features`: 0x0A (HIGH_FIDELITY_TOUCH | KNOBS)

---

## 2. Wireless networking stack

### Wi-Fi Direct group

- `WifiP2pGroupManager` creates the P2P group via `WifiP2pConfig.Builder` (API 29+)
- SSID format: `DIRECT-dp<12 hex SHA-256 of packageName:ANDROID_ID>-<name>` (max 32 bytes)
- Passphrase: 16-char random token, persisted in SharedPrefs (`carplay_wifi_p2p`) for stable identity
- `enablePersistentMode(true)` so the iPhone re-joins its saved network
- Prefers 5 GHz channel with fallback and retry logic
- Security: WPA2-PSK default; API 36+ reads from `WifiP2pGroup.securityType`
- Permissions: `NEARBY_WIFI_DEVICES` (API 33+) or `ACCESS_FINE_LOCATION` (API 29-32)

### Bluetooth RFCOMM

- UUID: `00000000-deca-fade-deca-deafdecacafe` (Apple iAP2)
- `device.createRfcommSocketToServiceRecord(uuid).connect()` in background thread
- Runs in parallel with Wi-Fi Direct group creation
- Timeout: 15 s (`RFCOMM_CONNECT_TIMEOUT_MILLIS`)
- CarLink's `BluetoothWarmup` pages the iPhone early with `fetchUuidsWithSdp()` to cut cold dial
  from ~5 s to ~0.2 s when the BT link is already up

### iAP2 protocol over RFCOMM

After the RFCOMM socket connects:
1. Open iAP2 link: `Iap2LinkChannel.openWireless()` — accessory sends iAP2 marker, phone initiates sync
2. Identification (0x1D00-0x1D02): name, model, manufacturer, serial, BT MAC, SSID
3. MFi auth (0xAA00-0xAA05): iPhone requests cert -> we send cert -> iPhone sends challenge -> we sign -> success/fail
4. Subscriptions: NowPlaying, RouteGuidance, Power, Communications, CallState
5. Wi-Fi credentials (0x5702/0x5703): when iPhone asks, we send SSID, passphrase, channel, security, BSSID
6. Transport notification (0x4E0D/0x4E0E): iPhone confirms, we resend Wi-Fi creds
7. StartSession (0x4300/0x4301): SSID, passphrase, channel, IPs, security, AirPlay port, device ID, public key

### mDNS / JmDNS

- Publish `_airplay._tcp` on the P2P interface (JmDNS interface-bound mode, not system NSD)
- Port 7000 default, fallback 7001-7010
- TXT records: `deviceid`, `features` (hex), `flags` ("0x4"), `model`, `srcvers`, `protovers` ("1.1"),
  `pi` (pairing UUID), `pk` (Ed25519 public key hex)
- Discover `_carplay-ctrl._tcp` — connect probe: `GET /ctrl-int/1/connect` with
  `AirPlay-Receiver-Device-ID` header; max 7 attempts, 1.5 s retry
- Multicast lock: `WifiManager.createMulticastLock("carplay-bonjour")` held while active

### Network path

For the bike: **Wi-Fi Direct only**. CarLink's `WirelessPolicy` supports car hotspot and same-Wi-Fi
too, but those are not needed. Wi-Fi lock: `WifiManager.createWifiLock(WIFI_MODE_FULL_LOW_LATENCY)`.

---

## 3. AirPlay RTSP handshake

After the iPhone connects TCP to our AirPlay port:

### Phase 1: Pairing

- `POST /pair-setup` — SRP-6a exchange (`PairSetup.kt`): establishes shared secret using Ed25519 identity
- `POST /pair-verify` — Ed25519 signature exchange (`PairVerify.kt`): derives session encryption keys
- After pair-verify: control channel encrypted via `ControlCipher` (ChaCha20-Poly1305, HKDF-SHA512 keys)

### Phase 2: MFi SAP authentication

- `POST /auth-setup` — MFi SAP (`MfiSapAuthSetup.kt`): uses the MFi client loaded at bootstrap

### Phase 3: Configuration

- `GET /info` — returns binary plist from `AirPlayInfoPlist.build(config)`:
  - displays: uuid, type (110 = main), maxFPS, pixels, physical mm, features, primaryInputDevice, viewAreas
  - hidDevices: touch (multitouch, 10 contacts), knob, media, telephony — all bound to main display
  - audioFormats: PCM 44100/48000, Opus, AAC-LC, telephony, microphone Opus
  - features: 0x615653aee2 (CARPLAY_FEATURES)

### Phase 4: Stream SETUP

- Initial SETUP: opens NTP timing channel (UDP), event channel (encrypted TCP for HID), negotiates features
- Per-stream SETUP:
  - type 110 (MAIN_SCREEN): `ScreenStream` TCP server, returns port
  - type 100/101/102 (audio): `AudioStream` UDP listener, returns data+control ports
  - type 130 (DATA): iAP2 tunnel
  - type 103 (MAIN_BUFFERED_AUDIO): buffered AAC-LC (optional)

### Phase 5: Active

- `RECORD` — session is live, video frames begin flowing
- Ongoing: `POST /command` (requestUI, duckAudio, requestSiri, forceKeyFrame)
- Ongoing: `POST /feedback` — stream timing for sync
- `TEARDOWN` — ends streams or session

---

## 4. Video pipeline

### Reception and decryption

- `ScreenStream` opens a TCP ServerSocket; iPhone connects after SETUP returns the port
- Each message: 128-byte `AirPlayScreenHeader` + body
- Opcode 0 = video frame (encrypted), Opcode 1 = codec config (cleartext avcC/hvcC)
- Encryption: **ChaCha20-Poly1305**, key derived from DataStream output key via HKDF-SHA512
  (`"DataStream-Output-Encryption-Key"`), nonce = 8-byte LE counter, AAD = the 128-byte header
- After decrypt: length-prefixed NALUs converted to Annex B (00 00 00 01 start codes)
- Thread priority: `THREAD_PRIORITY_DISPLAY`

### Decode

- `MediaCodec.createDecoderByType("video/avc")` for H.264 (or `"video/hevc"` for H.265)
- `MediaFormat` with width, height, `KEY_MAX_INPUT_SIZE`, `KEY_PRIORITY=0`, optional `KEY_LOW_LATENCY`
- Configure with the display Surface, start, feed NALUs to input buffers, drain output to surface
- Worker thread `"carplay-video"` at `THREAD_PRIORITY_DISPLAY`
- Optional Qualcomm low-latency tuning (`vendor.qti-ext-dec-picture-order.enable`)
- **No native video libraries** — 100% Android MediaCodec

### View hierarchy

```
FrameLayout (root, BLACK)
  +-- TextureView (video)
  +-- View (gestureLayer, transparent — touch events)
  +-- FrameLayout (viewport — waiting/status overlays)
```

TextureView is the default; SurfaceView available for direct output (lower latency on slow hardware).
For the Poco F1 (Snapdragon 845): TextureView at full resolution should be fine.

---

## 5. Audio pipeline

### Reception

- `AudioStream`: UDP RTP receiver, one per CarPlay audio stream
- Each datagram: 12-byte RTP header + ChaCha20-Poly1305 ciphertext + 16-byte tag + 8-byte nonce

### Decode

- AAC-LC via `MediaCodec`
- Opus via bundled **Concentus** (pure Java Opus decoder at `shared/.../java/org/concentus/`)
- LPCM passthrough

### Playback

- One `AudioTrack` per stream, `MODE_STREAM`, PCM 16-bit
- Attributes by channel type:
  - MEDIA -> `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC`
  - NAVIGATION -> navigation stream type (default 14)
  - PHONE/TELEPHONY -> `USAGE_VOICE_COMMUNICATION`
  - ASSISTANT -> `CONTENT_TYPE_SPEECH`

### Microphone (Siri)

- Opus encoding via Concentus or platform MediaCodec Opus encoder
- Optional Speex AEC via native `libspeex_echo.so`
- Not needed for base connection; can defer

---

## 6. Touch input

### Path: Android screen -> iPhone

1. Transparent gesture View receives `MotionEvent`
2. `CarPlayTouchMapper.contacts(event, content)` normalizes to 0..1 relative to video area
3. `controller.sendTouch(contacts)` via touchExecutor thread
4. `AirPlaySession.sendTouch()`: assigns to 2 HID slots, scales to pixels, builds HID report
5. Sends via encrypted event channel (`"hidSendReport"`)

### HID report format (12 bytes, 2 contacts x 6 bytes each)

Per contact:
- Byte 0: slot index (0 or 1)
- Byte 1: tip switch (0x01 = down, 0x00 = up)
- Bytes 2-3: X (uint16 LE)
- Bytes 4-5: Y (uint16 LE)

HID descriptor: standard USB multitouch digitizer, Usage Page 0x0D, Usage 0x04 (Touch Screen).

### Other HID devices

- Knob (0x2a2a2a2b): 4 bytes — buttons + X/Y/wheel axes (for handlebar controls)
- Media (0x2a2a2a2c): 1 byte — play/pause/next/prev (indices: PLAY 1, PAUSE 2, PLAY_PAUSE 3, NEXT 4, PREVIOUS 5)
- Telephony (0x2a2a2a2d): 1 byte — Hook Switch / Drop

---

## 7. Foreground service

CarLink's `DiPlaySessionService`:
- FGS types: `connectedDevice | microphone | location`
- Notification: LOW importance, "CarPlay connection running", Disconnect action
- `START_NOT_STICKY` — does not restart after process kill
- `CarPlayBackgroundSession` holds controller/sink snapshot so a new activity adopts a running session

For Handlebar Link: the existing `RideService` already runs as `connectedDevice` FGS. The CarPlay
engine runs alongside the bike link in the same service, in parallel child coroutines. A CarPlay
failure never cancels the bike link (separate child jobs). Add `microphone` type when Siri is needed.

---

## 8. Essential dependencies

| Dependency | Version | Purpose |
|---|---|---|
| `org.bouncycastle:bcprov-jdk18on` | 1.79 | Ed25519, X25519, ChaCha20-Poly1305, SRP-6a, HKDF-SHA512 |
| `org.jmdns:jmdns` | 3.6.3 | mDNS service advertisement/discovery on P2P interface |
| Concentus (bundled source) | — | Pure Java Opus codec for Siri mic and audio decode |

Not needed: ExoPlayer/Media3, ZXing, AndroidX Car App, SpeexDSP native (deferrable).

---

## 9. Android permissions (minimum for wireless CarPlay)

| Permission | Why |
|---|---|
| `INTERNET` | TCP/UDP AirPlay streams |
| `ACCESS_NETWORK_STATE` | Network status checks |
| `CHANGE_WIFI_STATE` | Wi-Fi Direct group creation |
| `ACCESS_WIFI_STATE` | Read Wi-Fi status, 5 GHz band check |
| `CHANGE_WIFI_MULTICAST_STATE` | Multicast lock for JmDNS |
| `NEARBY_WIFI_DEVICES` (API 33+, `neverForLocation`) | Wi-Fi Direct P2P |
| `BLUETOOTH_CONNECT` | RFCOMM dial, bonded device queries |
| `BLUETOOTH` | Adapter state (kept without maxSdkVersion for MIUI compat) |
| `ACCESS_LOCAL_NETWORK` (API 37+) | Prevents Android 17 from dropping local IPv4 |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Already have for the bike link |
| `RECORD_AUDIO` + `FOREGROUND_SERVICE_MICROPHONE` | Siri (deferrable) |

---

## 10. Handlebar Link module layout

### `:carplay:protocol` — pure Kotlin (`hl.jvm.library`)

AirPlay/CarPlay wire protocol codec: /info plist encoding, HID reports (touch, knob, media,
telephony), display configuration types, session frames, audio format types.
No project dependencies.

### `:carplay:engine` — pure Kotlin (`hl.jvm.library`)

The CarPlay session engine. Public API follows the BikeLink pattern:

```kotlin
interface CarPlayLink {
    val state: StateFlow<CarPlayState>      // Stopped, Starting, Negotiating, Streaming, Recovering
    val keepAlive: StateFlow<Boolean>       // Service must stay alive
    val problem: StateFlow<CarPlayProblem?> // User-actionable problems
    fun start()
    fun stop()
    suspend fun run()
}
```

SPI (`spi/`): platform boundary interfaces
```kotlin
class CarPlayPorts(
    val network: NetworkPort,       // TCP/UDP for AirPlay
    val video: VideoSinkPort,       // H.264 -> Surface
    val audio: AudioPort,           // Audio routing
    val discovery: DiscoveryPort,   // mDNS
)
```

Factory: `createCarPlayLink(ports, dispatcher, time, logger, config): CarPlayLink`

Dependencies: `api(:core:common, :carplay:protocol, kotlinx-coroutines-core)`

### `:carplay:engine-test` — pure Kotlin (`hl.jvm.library`)

`FakeCarPlayLink` with controllable state flows for `:app`'s tests.

### No `:carplay:engine-android` initially

The Android adapters (MediaCodec, AudioTrack, Wi-Fi Direct, BT RFCOMM) can live in `:app` initially.
Extract to a separate module if the adapter grows complex.

### ModuleRules additions

```kotlin
":carplay:protocol" to emptySet(),
":carplay:engine" to setOf(":core:common", ":carplay:protocol"),
":carplay:engine-test" to setOf(":carplay:engine"),
```

### DI wiring in `:app`

- `@CarPlayDispatcher`: `Dispatchers.Default.limitedParallelism(1, "carplay")`
- `CarPlayModule`: provides `CarPlayLink` via factory with Android port implementations
- Swap `ControlTarget` binding from `MediaSessionControlTarget` to `CarPlayControlTarget`
  (in-process HID input, no media session needed)
- Delete `MediaAccessListener` (notification listener) when done

### Ride service integration

`RideCoordinator` gains a `carPlay: CarPlayLink` alongside the bike link.
Both run in parallel child coroutines within `ride()`. The service stays alive as long as
either engine's `keepAlive` is true. A CarPlay failure never cancels the bike link.

---

## 11. What to skip

### Not needed for the bike

- Android Auto engine (`:aa` process, `vendor/diauto`)
- BYD/Geely vehicle integrations
- Car hotspot and same-Wi-Fi network paths
- Turning square / rotation logic (fixed landscape mount)
- USB/wired CarPlay (wireless only)
- Lockdown pairing (`LockdownPairingClient`)
- In-car video playback (iOS 27)
- Cluster display (secondary screen)
- Settings UI from CarLink (we have our own)
- CarPlayWaitingView, GpuProbe, DeviceCapabilities auto-detect
- Multi-phone heuristics (we have exactly one iPhone)

### Deferrable

- Speex echo cancellation (Siri quality)
- Buffered audio stream (type 103, music quality)
- Audio focus coordination
- Frame pacing / smooth video (SurfaceView mode)
- Qualcomm/MTK decoder tuning
- Bluetooth warmup optimization
- CarPlayLinkWatch (stuck-start detection)

---

## 12. Key CarLink source files reference

| File | What |
|---|---|
| `vendor/diplay/shared/.../airplay/AirPlaySession.kt` | RTSP message loop, event channel, NTP |
| `vendor/diplay/shared/.../airplay/AirPlayInfoPlist.kt` | Builds /info binary plist |
| `vendor/diplay/shared/.../airplay/AirPlayCrypto.kt` | Ed25519, SRP, HKDF, ChaCha20 |
| `vendor/diplay/shared/.../airplay/AirPlayIdentity.kt` | Ed25519 keypair + pairing ID |
| `vendor/diplay/shared/.../airplay/AirPlayHid.kt` | Touch, knob, media, telephony HID reports |
| `vendor/diplay/shared/.../airplay/ScreenStream.kt` | Video TCP server, decrypt, Annex B |
| `vendor/diplay/shared/.../airplay/AudioStream.kt` | Audio UDP RTP receiver |
| `vendor/diplay/shared/.../airplay/PairSetup.kt` | SRP-6a pairing |
| `vendor/diplay/shared/.../airplay/PairVerify.kt` | Ed25519 session verification |
| `vendor/diplay/shared/.../airplay/ControlCipher.kt` | Encrypted control channel |
| `vendor/diplay/shared/.../airplay/MfiSapAuthSetup.kt` | MFi SAP auth-setup |
| `vendor/diplay/shared/.../airplay/NtpClock.kt` | NTP timing sync |
| `vendor/diplay/shared/.../airplay/AirPlayConfig.kt` | Display config types |
| `vendor/diplay/shared/.../airplay/CarPlayMediaEngine.kt` | Routes streams to MediaSink |
| `vendor/diplay/shared/.../media/AndroidMediaSink.kt` | MediaCodec decode + AudioTrack |
| `vendor/diplay/shared/.../mfi/LocalMfiAuthenticationClient.kt` | Offline MFi P-256 signing |
| `vendor/diplay/shared/.../mfi/Iap2MfiAuthenticationClient.kt` | iAP2 MFi exchange |
| `vendor/diplay/shared/.../network/CarPlayBonjour.kt` | mDNS publish/discover |
| `vendor/diplay/shared/.../network/WifiP2pGroupManager.kt` | Wi-Fi Direct group |
| `vendor/diplay/shared/.../transport/BluetoothRfcommDuplexStream.kt` | RFCOMM socket wrapper |
| `vendor/diplay/shared/.../orchestration/CarPlayController.kt` | Session lifecycle, wireless flow |
| `vendor/diplay/shared/.../orchestration/Iap2WirelessControlClient.kt` | iAP2 control sequence |
| `vendor/diplay/shared/.../orchestration/Iap2IdentificationClient.kt` | iAP2 identification |
| `vendor/diplay/common/.../CarPlayHostActivity.kt` | Full-screen activity, view tree, touch |
| `vendor/diplay/common/.../CarPlayHostIntegration.kt` | Host hooks, session control |
| `vendor/diplay/common/.../DiPlaySessionService.kt` | FGS for background survival |
| `carlink-core/.../connection/CarPlayAdapter.kt` | CarLink's session adapter |
| `carlink-core/.../connection/Connection.kt` | SessionManager |
| `carlink-core/.../connection/CarPlaySettings.kt` | Settings -> DiPlay prefs |
| `carlink-core/.../connection/WirelessPolicy.kt` | Network path selection |
| `carlink-core/.../connection/BluetoothWarmup.kt` | Pre-dial BT paging optimization |
