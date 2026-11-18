# DiPlay Engine: AirPlay/CarPlay Connection Sequence

Cold start → first frame dissection of the DiPlay engine inside CarLink v1.0.16.
Source: `/Volumes/My files/pocof1/carlink-src` (branch `bike`, tag v1.0.16).

---

## 1. Bootstrap: `DiPlayBootstrap.ensure()`

**File:** `vendor/diplay/common/.../DiPlayBootstrap.kt`

The bootstrap runs once per app launch, called from `CarPlayPreflight` before any session starts.

### What it does (lines 14–43)

1. **Guard**: Only runs for `MfiTarget.LOCAL` (software-only MFi auth — our target path).
2. **Copy auth files**: Copies `identity.pk8` (Ed25519 private key) and `certificate.p7b` (MFi
   certificate chain) from `assets/offline-mfi/` to `noBackupFilesDir/local-mfi/`.
   - Uses atomic staging: writes to a temp dir, then renames.
   - Sets strict file permissions on the directory.
3. **Load identity**: Calls `LocalMfiAuthenticationClient.load(directory)` which parses the PKCS#8
   key and X.509 certificate, pre-computes signature state.
4. **Device ID generation** (line 45): `SHA-256(AirPlayIdentity.publicKey)` → first 6 bytes →
   colon-separated hex MAC with locally-administered bit set. This becomes the AirPlay `deviceID`.
5. **Optional**: Resets debug logs if `resetDebugLogsOnBootstrap` is set.

### What Handlebar Link needs

- **The two auth files** (`identity.pk8`, `certificate.p7b`) bundled in assets, never committed to
  git (rule from `~/.carlink-private/`).
- An `AirPlayIdentity` holding the Ed25519 keypair + a stable pairing ID.
- A `PairingStore` (simple key-value file) for persisting paired iPhone LTPK entries.
- The `LocalMfiAuthenticationClient` class (implements MFi challenge-response signing for Apple's
  auth-setup handshake during AirPlay pairing).

### What we can skip

- `MfiTarget.USB_CH341`, `MfiTarget.I2C`, `MfiTarget.REMOTE` — hardware coprocessor and remote
  server paths are for car head units with physical MFi chips. We use LOCAL (software) only.

---

## 2. Session Start Sequence

### 2a. The call chain

```
User taps Connect
  → SessionManager.start(request)                     [SessionManager.kt:639]
    → begin()                                          [SessionManager.kt:676]
      → enginePreflight()                              [Connection.kt:272]
      → warmUp() (optional BT page)
      → applySettings(plan)                            [Connection.kt:216]
      → CarPlayAdapter.start(plan, onEvent)            [CarPlayAdapter.kt:86]
        → hooks.openHost(usbAttach)                    [CarPlayAdapter.kt:317]
          → CarPlayHostActivity (FLAG_ACTIVITY_NEW_TASK)
            → CarPlayController.start()                [CarPlayController.kt:567]
```

### 2b. SessionManager orchestration (`SessionManager.kt`)

**start()** (line 639):
- Increments `sessionId`, stops any current session, clears recovery state.
- Gets ranked connection paths from `WirelessPolicy` (Same Wi-Fi, Car Hotspot, Wi-Fi Direct).
- Calls `begin()`.

**begin()** (line 676):
- Runs preflight checks (auth, permissions, Bluetooth, Wi-Fi).
- Optionally `warmUp()` — pages the iPhone over Bluetooth to wake its radio.
- Creates `SessionPlan(engine=CARPLAY, transport, path, phoneAddress, phoneName, attempt)`.
- Sets state to Active.
- Waits for existing engines to stop → `worker.execute { applySettings(plan) }` →
  `main.post { adapter.start(plan) }`.

**applySettings()** (Connection.kt:216):
- For CARPLAY: sets up `ModuleIap2Route`, calls `CarPlaySettingsPolicy.apply()` with display
  resolution, physical size, FPS, picture settings, fine location permission, etc.

### 2c. CarPlayAdapter.start() (`CarPlayAdapter.kt:86`)

1. Validates engine is CARPLAY.
2. Checks `hooks.hasSession()` — rejects if already connected.
3. Creates `CarPlaySessionTracker`.
4. Registers listeners: `CarPlayStatusBus` (status), `HostState` (host activity state),
   `WirelessStartupTimeline` (milestone tracking).
5. Selects the phone address in DiPlay prefs.
6. Enables wireless mode.
7. Acquires Wi-Fi lock.
8. Starts Wi-Fi Direct link watch (if applicable path).
9. Opens `CarPlayHostActivity` via Intent with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_REORDER_TO_FRONT`.

### 2d. CarPlayHostActivity (`CarPlayHostActivity.kt`)

Creates:
- `CarPlayRuntimeConfig` with MFi target, iAP2 identification (accessory name, model number,
  serial number, firmware/hardware versions).
- `AndroidMediaSink` — the video/audio decoder backend (default 1280×720).
- `CarPlayController` — the core orchestrator.
- `TextureView` for video output (SurfaceView as fallback for smooth/direct video modes).

Calls `CarPlayController.start()`.

### 2e. CarPlayController phases (`CarPlayController.kt`)

The controller moves through phases: **IDLE → MFI → WIRELESS → IPHONE → REENUMERATION → DATAPATHS → CONTROL**.

Three thread pools:
- `executor` — single thread for USB/I2C/Bluetooth work
- `touchExecutor` — single thread for HID touch input
- `tunnelExecutor` — single thread for wireless iAP2 tunnel

#### Phase: MFI (`startMfi()`, line 1000)

For LOCAL target (line 1031):
1. Runs on `executor`.
2. Calls `LocalMfiAuthenticationClient.load(directory)` — loads the offline MFi identity.
3. Stores as `MfiSession(client, null)`.
4. Reports `CarPlayStatus.MfiReady`.
5. Calls `startPhone()`.

#### Phase: startPhone() (line 1320)

1. Pre-warms the Android location provider (if location reporting enabled).
2. If wireless transport → marks `MFI_READY` on timeline → `startWireless()`.
3. If wired → `startIphone()`.

#### Phase: WIRELESS (`startWireless()` / `runWireless()`, lines 1342–1886)

This is the big one. Steps in order:

1. **Generation tracking**: Each wireless attempt gets a monotonic generation number. All async
   callbacks check `isStaleWirelessRun(generation)` before proceeding.

2. **Close previous wireless stack** (line 1382).

3. **Create FirstTcpWatchdog** (line 1392): Arms a timeout for the first AirPlay TCP connection
   from the iPhone. If no TCP arrives after `StartSession`, it fires
   `WirelessStartupFailure.FIRST_TCP_TIMEOUT`.

4. **Bluetooth RFCOMM dial** (lines 1420–1475):
   - Gets the `BluetoothAdapter`, checks it's enabled.
   - Selects the paired iPhone device.
   - Creates `BluetoothSocket` via `createRfcommSocketToServiceRecord(IAP2_IPHONE_UUID)`.
   - Starts the RFCOMM connect in a **background thread** (`BackgroundDial`).
   - UUID: Apple's iAP2 RFCOMM service UUID.
   - **This runs in parallel with the hotspot setup** — the dial takes ~6.6s when the iPhone's BT
     was idle, and nothing else depends on it yet.

5. **Start wireless hotspot** (line 1477): `startWirelessHotspot(generation)` — creates the Wi-Fi
   Direct P2P group (or uses car hotspot / same Wi-Fi depending on path).

6. **Resolve host address** (lines 1503–1533):
   - `HotspotAddressRecovery` polls for usable addresses.
   - `HotspotAddressPolicy.select()` picks IPv6 link-local (preferred for Wi-Fi Direct) or IPv4.
   - Validates IPv6 scope ID is set.

7. **Attach AirPlay listener** (lines 1598–1627):
   - Binds to `CarPlayVpnService.attachWireless()`.
   - This creates the AirPlay TCP server socket bound to the hotspot address.
   - Returns the bound port (default 7000, falls back if busy).

8. **Start Bonjour/mDNS** (lines 1638–1675):
   - Creates `CarPlayBonjour` — advertises `_airplay._tcp` and `_carplay-ctrl._tcp` services.
   - Uses JmDNS library bound to the hotspot interface.
   - TXT records include: `deviceid`, `features` (hex), `flags=0x4`, `model`, `srcvers`,
     `protovers=1.1`, `pi` (pairing ID), `pk` (public key hex).
   - Monitors for iPhone's own `_carplay-ctrl._tcp` advertisements (peer mDNS).

9. **Await Bluetooth dial completion** (lines 1680–1707):
   - Blocks on the `BackgroundDial` future.
   - Timeout: `RFCOMM_CONNECT_TIMEOUT_MILLIS`.
   - On success, wraps socket in `BluetoothRfcommDuplexStream`.

10. **Open iAP2 session over RFCOMM** (lines 1727–1739):
    - `Iap2Session.openWireless(stream)` — negotiates iAP2 session layer (CSM = Control Session
      Manager).

11. **Build wireless identification** (lines 1744–1767):
    - `Iap2WirelessIdentification(hostBluetoothMac, hotspot.ssid)`.
    - `Iap2WirelessCarPlayEndpoint`: SSID, passphrase, channel, security type, IP addresses,
      AirPlay port, device identifier, public key, source version, AP BSSID.

12. **Run iAP2 wireless control** (lines 1783–1802):
    - `Iap2WirelessControlClient.run(identification, endpoint, ...)`.
    - This performs the iAP2 identification exchange.
    - Sends the **CarPlay StartSession** message to the iPhone, which tells it to join the Wi-Fi
      network and connect to the AirPlay server.
    - iPhone joins the hotspot → discovers the AirPlay service → opens TCP to AirPlay port.

13. **iPhone connects to AirPlay** → `AirPlaySession` accepts the TCP connection → runs RTSP
    control loop (see section 2f).

14. **Wireless tunnel handoff** (lines 1888–1941):
    - After AirPlay session is established, the iPhone opens a **type-130 tunnel data stream**
      through the AirPlay connection itself.
    - `startWirelessTunnelControl()` opens a second iAP2 session over this tunnel.
    - This tunnel replaces the Bluetooth RFCOMM link for ongoing iAP2 control.
    - The tunnel carries location data, vehicle status, and runtime iAP2 messages.

### 2f. AirPlay RTSP handshake (`AirPlaySession.kt`)

Once the iPhone TCP-connects to the AirPlay listener:

**runControl()** (line 453): Daemon thread that reads RTSP messages in a loop.

The iPhone sends these requests in order, the server responds:

| Step | Request | Handler | What happens |
|------|---------|---------|-------------|
| 1 | `GET /info` | line 599 | Returns the AirPlay info plist (see §3). iPhone reads display config. |
| 2 | `POST /pair-setup` (×3) | line 586 | SRP-6a exchange with PIN "3939". Exchanges Ed25519 LTPKs. See §2g. |
| 3 | `POST /pair-verify` (×2) | line 591 | Ephemeral X25519 DH. Derives ChaCha20 control-channel keys. See §2h. |
| 4 | `POST /auth-setup` | line 594 | MFi SAP auth: `MfiSapAuthSetup.handle()` — the MFi certificate challenge. |
| 5 | `SETUP` (initial) | line 669 | Device info, timing port, event port, keep-alive port, enabled features. |
| 6 | `SETUP` (streams) | line 715 | Stream negotiation: screen (110/111), audio (96/100/101/102/103), data (130). Each stream gets a data port. |
| 7 | `RECORD` | line 564 | Signals session active → `listener.onSessionActive()`. |
| 8 | Video frames flow on screen data port. |
| 9 | First decoded frame → `videoFrameRendered()` → `listener.onVideoFrameRendered()` → **FIRST_FRAME**. |

After step 5, the **control channel becomes encrypted**: `ControlCipher(readKey, writeKey)` using
ChaCha20-Poly1305 (line 534).

After step 5, the **event channel** opens (line 863): a separate TCP connection from the iPhone,
also encrypted with ChaCha20-Poly1305, used for HID reports (touch, knob, media buttons),
night mode commands, Siri, and /command messages.

### 2g. Pair-Setup (`PairSetup.kt`)

3-round exchange using **SRP-6a** with fixed credentials:
- Username: `"Pair-Setup"`
- PIN: `"3939"`

| Round | Client→Server | Server→Client |
|-------|---------------|---------------|
| M1→M2 | state=1 | salt + SRP public key B |
| M3→M4 | SRP public key A + proof | server proof (or error) |
| M5→M6 | encrypted(controller LTPK + signature) | encrypted(accessory LTPK + signature) |

After M6, the controller's Ed25519 LTPK is saved in the `PairingStore`. The session key from SRP
derives encryption keys via HKDF-SHA-512.

**Crypto used**: SRP-6a, HKDF-SHA-512, ChaCha20-Poly1305, Ed25519 sign/verify.

### 2h. Pair-Verify (`PairVerify.kt`)

2-round exchange for subsequent connections (already paired):

| Round | Client→Server | Server→Client |
|-------|---------------|---------------|
| M1→M2 | ephemeral X25519 pub | ephemeral X25519 pub + encrypted(accessory ID + Ed25519 sig) |
| M3→M4 | encrypted(controller ID + Ed25519 sig) | state=4 (success) |

Derives:
- Shared secret via X25519 DH
- Control read/write keys via HKDF-SHA-512 (`"Control-Salt"` / `"Control-Read-Encryption-Key"` / `"Control-Write-Encryption-Key"`)
- Event read/write keys via HKDF-SHA-512 (`"Events-Salt"` / `"Events-Read-Encryption-Key"` / `"Events-Write-Encryption-Key"`)

**After pair-verify, all RTSP traffic is encrypted with ChaCha20-Poly1305.**

---

## 3. AirPlay /info Plist — Display Config

**File:** `vendor/diplay/shared/.../airplay/AirPlayInfoPlist.kt`

### The full /info response structure (line 31–85)

```
sourceVersion       "220.68" (or similar)
features            0x615653aee2 (CarPlay features bitmask)
statusFlags         4
model               "xcertplay"
manufacturer        "xcertplay"
deviceID            <colon-separated MAC from identity hash>
bluetoothIDs        [<BT MAC>]
name                <accessory name>
rightHandDrive      false
keepAliveLowPower   false
modes               {resources: [screen, audio], appStates: [...]}
audioLatencies      [9 entries, type 100/101/102 × audioType]
audioFormats        [9+ entries with PCM/Opus/AAC-LC bitmasks]
extendedFeatures    ["vocoderInfo", "enhancedRequestCarUI"]
displays            [<display entry>]
hidDevices          [touch, knob, media, telephony]
hevcInfo            {} (if HEVC enabled)
mainBufferedInfo    {} (if buffered audio enabled)
```

### Display entry for fixed landscape phone mount (line 201–239)

For the Poco F1 (1080×2246, but we want landscape CarPlay at a chosen resolution):

```kotlin
displayEntry(config.main, STREAM_TYPE_MAIN_SCREEN=110, MAIN_UUID)
```

Produces:
```
uuid              "b7e6c5a0-1111-4000-8000-000000000001"
type              110 (main screen)
maxFPS            60 (sanitized, range 30-60, step 5)
widthPixels       <negotiated, e.g. 1920>
heightPixels      <negotiated, e.g. 720>
widthPhysical     <mm, default 200, range 100-400>
heightPhysical    <derived from width × pixel ratio if null>
features          0x0A (HIGH_FIDELITY_TOUCH=0x08 | KNOBS=0x02)
primaryInputDevice 1
uiAppearanceMode   0 (automatic)
uiAppearanceSetting 0
mapAppearanceMode   0
mapAppearanceSetting 0
viewAreas          [<single full-display area>]
initialViewArea    0
```

### For a plain canvas (no split screen, no dock relocation)

A single `viewArea` entry covering the full display:
```
widthPixels       = display.widthPixels
heightPixels      = display.heightPixels
originXPixels     = 0
originYPixels     = 0
safeArea:
  widthPixels     = display.widthPixels (minus any safe insets)
  heightPixels    = display.heightPixels (minus any safe insets)
  originXPixels   = safeArea.left (0 for no insets)
  originYPixels   = safeArea.top (0 for no insets)
  drawUIOutsideSafeArea = true
```

### HID devices (line 58–63)

Four HID devices are **required** (iPhone aborts without them):
1. **Touch** — multi-touch digitizer, coordinates in display pixels
2. **Knob** — rotary encoder (up/down/left/right/select)
3. **Media** — play/pause/next/prev buttons
4. **Telephony** — answer/hangup buttons

### What to set for the CB350 Poco F1 mount

- **widthPixels/heightPixels**: Match the Poco F1's usable landscape area (e.g., 2246×1080 for
  full screen, or 1920×1080/1280×720 for standard CarPlay resolutions).
- **widthPhysicalMm**: ~130mm (Poco F1's screen width in landscape). This affects the iPhone's
  rendering: font sizes, button hit targets.
- **maxFPS**: 60 (the Poco's display rate).
- **Single viewArea**: Full display, no safe insets, no dock edge override.
- **features**: `0x0A` (touch + knobs) is correct for a touchscreen.
- **rightHandDrive**: false (India, Honda CB350).
- **hevc**: false initially (simpler; H.264 is universal).
- **microphone**: false initially (no Siri mic needed for v1).

---

## 4. Video Decode and Display

### 4a. Stream reception (`ScreenStream.kt`)

**Wire format** (line 15–21):
- 128-byte header + body per message.
- Header byte 4 = opcode: `0` = video frame, `1` = video config.

**Video config** (OP_VIDEO_CONFIG = 1):
- Body contains an `avcC` (H.264) or `hvcC` (H.265) atom.
- `ScreenCodec.detectConfig()` (line 205) scans for the fourcc marker.
- Calls `listener.onCodec(codec)` + `listener.onConfig(codecData)`.

**Video frame** (OP_VIDEO_FRAME = 0):
1. Body is **ChaCha20-Poly1305 encrypted** (line 107):
   - Key: the `DataStream` output key from the SETUP response.
   - Nonce: 8-byte little-endian counter (monotonic per frame).
   - AAD: the 128-byte header.
2. `ScreenCodec.decryptFrame(key, counter, header, body)` → plaintext payload.
3. Payload contains length-prefixed NALUs.
4. `ScreenCodec.lengthPrefixedToAnnexB(payload)` (line 178) — replaces 4-byte length prefixes
   with `00 00 00 01` start codes in-place.
5. Calls `listener.onFrame(annexBBytes, senderNanos, arrivalNanos)`.

**Threading**: The screen stream thread runs at `THREAD_PRIORITY_DISPLAY` (line 76).

### 4b. MediaCodec decode (`AndroidMediaSink.kt`)

**Constructor** (line 181): Takes a `Surface`, video width/height (default 1280×720).

**Codec selection**: Uses `MediaCodecList(REGULAR_CODECS)` to find a suitable H.264 or H.265
hardware decoder. Prefers hardware; falls back to software for HEVC if
`preferSoftwareHevcDecoder` is set.

**Decode flow**:
1. `onCodec()` → remembers the codec type.
2. `onConfig()` → creates `MediaFormat` with CSD-0 (SPS/PPS for H.264 or VPS/SPS/PPS for H.265).
3. `MediaCodec.createDecoderByType()` → configure with the Surface → start.
4. `onFrame()` → dequeue input buffer → copy NALU data → queue input buffer.
5. Output buffers are released to the Surface (rendered directly).
6. First rendered frame → `AirPlaySession.videoFrameRendered()`.

**Pacing** (smooth video mode):
- When `videoPacingDelayMillis > 0`, frames are released at the sender's timestamp + delay.
- Only works with SurfaceView (honours release timestamps); TextureView renders immediately.

### 4c. Display surface (`CarPlayHostActivity.kt`)

**Primary**: `TextureView` (line 2076) — created in the activity, provides a `SurfaceTexture`.
- Advantages: supports hardware-accelerated transforms (rotation, scaling), picture hold (bitmap
  capture), brightness/contrast adjustments via `ColorMatrix`.
- Limitation: adds one frame of latency; does not honour release timestamps.

**Fallback**: `SurfaceView` (line 6684) — used when:
- "Smooth video" setting is on (needs release timestamps).
- "Direct video output" setting is on.
- The hardware decoder can't output to a TextureView.
- No hardware acceleration on the window.

**Layout**: `CarPlayVideoLayout.fit()` (line 9 of `CarPlayVideoLayout.kt`) computes letterbox
dimensions preserving aspect ratio:
```kotlin
val scale = minOf(viewWidth / canvasWidth, viewHeight / canvasHeight)
val width = canvasWidth * scale
val height = canvasHeight * scale
// centered: left = (viewWidth - width) / 2, top = (viewHeight - height) / 2
```

**Picture hold**: When the surface goes away (e.g., activity paused), the last frame is captured as
a Bitmap and shown as a static image until a new surface arrives.

**Surface handoff**: `MediaCodec.setOutputSurface()` (line 1583) allows swapping between
TextureView and SurfaceView without restarting the decoder (preserves reference frames).

### 4d. Threading model summary

| Thread | Priority | Role |
|--------|----------|------|
| `airplay-control` | Normal | RTSP message loop, pair-setup/verify, SETUP/TEARDOWN |
| `airplay-event-accept` | Normal | Accepts event channel TCP, then reads encrypted events |
| `airplay-screen` | DISPLAY | Receives + decrypts video frames |
| MediaCodec output | DISPLAY | Releases decoded frames to Surface |
| `airplay-keepalive` | Normal | UDP keep-alive receiver |
| NTP timing | Normal | Time sync with iPhone |
| `xcertplay-session` (executor) | Normal | MFi, Bluetooth RFCOMM, iAP2 |
| touch executor | Normal | HID touch report sending |
| tunnel executor | Normal | Wireless iAP2 tunnel control |

---

## 5. Minimum Viable Subset

### MUST HAVE — Core protocol (replicate or directly reuse)

| Module | Key Classes | Why |
|--------|------------|-----|
| **MFi Auth** | `DiPlayBootstrap`, `LocalMfiAuthenticationClient`, `MfiSapAuthSetup` | Apple requires MFi challenge-response during /auth-setup. Without it, no CarPlay. |
| **AirPlay Identity** | `AirPlayIdentity`, `PairingStore` | Ed25519 keypair + pairing persistence. Pair-setup and pair-verify need these. |
| **Pair-Setup** | `PairSetup`, `Srp6a`, `Srp6aSession` | SRP-6a with PIN "3939". First pairing with an iPhone. |
| **Pair-Verify** | `PairVerify` | X25519 + Ed25519 for subsequent connections. Derives encryption keys. |
| **AirPlay Crypto** | `AirPlayCrypto` (ChaCha20-Poly1305, HKDF-SHA-512, Ed25519, X25519) | All encryption. This is the crypto foundation. |
| **RTSP** | `RtspMessage`, `BplistCodec` | RTSP framing (parse requests, build responses) + binary plist encode/decode. |
| **Control Cipher** | `ControlCipher` | ChaCha20-Poly1305 stream cipher for the RTSP control and event channels. |
| **AirPlay Info** | `AirPlayInfoPlist`, `AirPlayConfig`, `AirPlayDisplayConfig` | The /info response. iPhone won't proceed without a valid one. |
| **Screen Stream** | `ScreenStream`, `ScreenCodec` | TCP video receiver: decrypt frames, convert to Annex B NALUs. |
| **NTP Timing** | `NtpClock` | Time sync — iPhone requires the timing port in SETUP response. |
| **HID** | `AirPlayHid`, `AirPlayTouchSlots` | Touch digitizer + knob HID reports. Required for CarPlay interaction. |
| **Media Sink** | `AndroidMediaSink` (video portion) | MediaCodec decode → Surface. The rendering backend. |
| **Video Layout** | `CarPlayVideoLayout` | Letterbox fitting. |
| **AirPlay Session** | `AirPlaySession` | The control connection orchestrator: RTSP loop, SETUP handling, event channel. |

### MUST HAVE — Wireless transport

| Module | Key Classes | Why |
|--------|------------|-----|
| **Bluetooth RFCOMM** | `BluetoothRfcommDuplexStream` | Dial the iPhone and carry initial iAP2 link. |
| **iAP2 Session** | `Iap2Session`, `Iap2WirelessControlClient` | iAP2 protocol over RFCOMM → identification → StartSession. |
| **iAP2 MFi** | `Iap2MfiAuthenticationClient` | MFi auth over iAP2 (the iPhone challenges the accessory). |
| **Wi-Fi P2P / Hotspot** | Hotspot creation classes | Create the Wi-Fi Direct group the iPhone joins. |
| **Bonjour** | `CarPlayBonjour`, JmDNS | Advertise `_airplay._tcp` + `_carplay-ctrl._tcp` for iPhone discovery. |

### NICE TO HAVE — Can defer or simplify

| Module | Reason to defer |
|--------|----------------|
| **Audio streams** | Navigation audio, media, Siri — not critical for first frame / visual CarPlay. |
| **Microphone / Opus** | Siri voice input — can add later. |
| **Cluster (alt screen)** | Dashboard/instrument cluster — only relevant for multi-display. |
| **Video in car** | Video playback permission — not needed for navigation CarPlay. |
| **VPN Service** | Only needed for wired CarPlay USB networking. Skip for wireless-only. |
| **NCM USB Bridge** | Wired CarPlay USB networking. Skip for wireless. |
| **Location provider** | Sends GPS to iPhone — CarPlay works without it (uses iPhone GPS). |
| **Vehicle status** | Speed, gear, etc. — optional CarPlay feature. |
| **Stall watchdog / recovery** | SessionManager's retry/recovery logic — simplify for v1. |
| **BYD integrations** | Vehicle-specific hooks. Not relevant for motorcycle. |
| **Wi-Fi scan pause** | Optimization to prevent Android Wi-Fi scans from disrupting the hotspot. |
| **Picture hold / bitmap** | Nice UX polish, not required for first frame. |
| **Smooth video / pacing** | SurfaceView release-timestamp mode — TextureView is fine for v1. |
| **Buffered audio** | AAC-LC music streaming — defer. |

### CAN SKIP entirely

| Module | Why |
|--------|-----|
| CH341 USB host, I2C transport, MFi hardware coprocessor | Hardware MFi paths — we use LOCAL. |
| Remote MFi server | Cloud MFi auth — we use LOCAL. |
| Android Auto adapter (`DiAutoAdapter`, `vendor/diauto`) | Entirely separate engine. |
| ModuleIap2Route | For stereos with external Bluetooth modules. |
| CarPlayWaitingView | CarLink's custom "waiting" UI overlay. |
| BydBluetoothSuspend | BYD-specific Bluetooth workaround. |
| InstantBluetoothLink | Heuristic for detecting fake BT connections. |

---

## Summary: Cold Start → First Frame Timeline

```
t=0     DiPlayBootstrap.ensure()           Copy auth files, load MFi identity
t=1     CarPlayPreflight                   Check: auth OK, BT on, Wi-Fi on, iPhone bonded
t=2     SessionManager.begin()             Create plan, apply settings
t=3     CarPlayAdapter.start()             Open CarPlayHostActivity
t=4     CarPlayController.start()          Phase MFI → load local MFi client
t=5     startPhone() → startWireless()     Phase WIRELESS begins
t=6     ├─ RFCOMM dial (background)        Connect to iPhone over Bluetooth (~6.6s)
        ├─ startWirelessHotspot()          Create Wi-Fi Direct P2P group
        ├─ Resolve host address            Get IPv6/IPv4 for the hotspot
t=7     ├─ Attach AirPlay listener         Bind TCP server to hotspot address:7000
        ├─ Start Bonjour                   Advertise _airplay._tcp, _carplay-ctrl._tcp
t=8     └─ Await RFCOMM connected          Bluetooth link established
t=9     Open iAP2 over RFCOMM             CSM channel
t=10    iAP2 identification + StartSession Tell iPhone: join SSID, connect to AirPlay
t=11    iPhone joins Wi-Fi                 Associates with the P2P group
t=12    iPhone TCP → AirPlay listener      AirPlaySession accepts
t=13    GET /info                          iPhone reads display config
t=14    pair-setup (3 rounds)              SRP-6a, exchange LTPKs
t=15    pair-verify (2 rounds)             X25519 DH, derive encryption keys
t=16    /auth-setup                        MFi SAP challenge
t=17    SETUP (initial)                    Timing, event, features
t=18    SETUP (streams)                    Screen stream → ScreenStream.listen() → data port
t=19    RECORD                             Session active
t=20    Video frames arrive                Encrypted H.264 on screen data port
t=21    Decrypt → Annex B → MediaCodec     Hardware decode → Surface
t=22    FIRST FRAME rendered               🏁
```

Typical wall-clock: **8–15 seconds** from tap to first frame (dominated by Bluetooth dial + iPhone
Wi-Fi join time).
