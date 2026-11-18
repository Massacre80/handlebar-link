# Spec D: DiPlay wireless CarPlay, AirPlay session, bplist, Bonjour, Wi-Fi Direct

Source: `/Volumes/My files/pocof1/carlink-src/vendor/diplay` (DiPlay as vendored in CarLink, including the
`// CarLink` hooks). Only source code and unit tests were read. No `docs/research` or `notes/` files were used.

Path abbreviations (all under `vendor/diplay/`):

| Short | Path |
|---|---|
| `AS` | `shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt` |
| `RM` | `shared/.../airplay/RtspMessage.kt` |
| `CC` | `shared/.../airplay/ControlCipher.kt` |
| `ACR` | `shared/.../airplay/AirPlayCrypto.kt` |
| `PV` / `PS` | `shared/.../airplay/PairVerify.kt` / `PairSetup.kt` |
| `MSAP` | `shared/.../airplay/MfiSapAuthSetup.kt` |
| `BP` | `shared/.../airplay/BplistCodec.kt` |
| `INFO` | `shared/.../airplay/AirPlayInfoPlist.kt` |
| `CFG` | `shared/.../airplay/AirPlayConfig.kt` |
| `HID` | `shared/.../airplay/AirPlayHid.kt` |
| `ME` | `shared/.../airplay/CarPlayMediaEngine.kt` |
| `NTP` | `shared/.../airplay/NtpClock.kt` |
| `BJ` | `shared/.../network/CarPlayBonjour.kt` |
| `P2P` | `shared/.../network/WifiP2pGroupManager.kt` |
| `VPN` | `shared/.../network/CarPlayVpnService.kt` |
| `CTL` | `shared/.../orchestration/CarPlayController.kt` |
| `WCC` | `shared/.../transport/Iap2WirelessControlClient.kt` |
| `HOST` | `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` |

`shared/...` = `shared/src/main/java/com/shilapi/xcertplay`.

---

## 1. End-to-end wireless sequence (Bluetooth bootstrap + Wi-Fi Direct)

### 1.1 Entry and threads

- `CarPlayController.start()` (CTL:567) → `startMfi()` → when MFi is ready, `startPhone()` (CTL:1320) → `startWireless()` (CTL:1342).
- `startWireless` bumps `wirelessGeneration`, resets the handoff flags, reports `StartingHotspot`, and runs `runWireless(generation)` on the single worker `executor` (CTL:1357).
- Every later step checks `isStaleWirelessRun(generation)` (CTL:3027): `closed || phase != WIRELESS || generation != wirelessGeneration || wirelessFailureReported`. A stale run returns silently.

### 1.2 `runWireless` order (CTL:1377-1886)

Steps 1-14 run on the worker thread, in this order. **[CarLink]** marks a step that the CarLink hooks changed.

1. `closeWirelessStack(generation)` cleans up any earlier run (CTL:1382; body CTL:3252-3289).
2. A `FirstTcpWatchdog` is created (CTL:1392-1411). Its timer does **not** start yet: it starts at StartSession (step 13). Default `timeoutMillis = FIRST_TCP_MILLIS = 30_000` (`network/WirelessStartupPolicy.kt:10`, FirstTcpWatchdog.kt:12).
3. MFi client is required: `mfiSession?.client ?: throw IOException("MFi coprocessor client is unavailable")` (CTL:1412-1413).
4. Bluetooth radio:
   - If the adapter is `STATE_TURNING_ON`, the run waits up to **3 000 ms** (CTL:1421-1423 and 1453-1455; `awaitBluetoothEnabled` default CTL:226).
   - `BydBluetoothSuspend.resumeAndWait` runs (CTL:1452).
   - The run fails if the radio is off (CTL:1456-1457).
5. iPhone selection: `selectWirelessBluetoothDevice` (CTL:3044-3087).
   - If the user configured an address, that bonded device is used.
   - Otherwise, among bonded devices whose name contains "iPhone": exactly one connected iPhone wins. Connection is checked by reflection `isConnected`, then by the HEADSET, A2DP, A2DP_SINK(11) and HEADSET_CLIENT(16) profiles, each with a 3 s proxy timeout (CTL:3345).
   - Otherwise exactly one bonded iPhone, otherwise exactly one bonded device. Anything else throws.
6. **[CarLink] RFCOMM dial starts first, in the background.**
   - `device.createRfcommSocketToServiceRecord(UUID "00000000-deca-fade-deca-deafdecacafe")` (CTL:1465; UUID CTL:3726).
   - `BackgroundDial("wireless-rfcomm-connect") { socket.connect() }` (CTL:1471-1474; `orchestration/BackgroundDial.kt:17-60`) runs `connect()` on its own daemon thread immediately.
   - Upstream dialed later. The comment at CTL:1430-1432 says the access point, listener and Bonjour now start while the dial runs.
7. Wi-Fi Direct group: `startWirelessHotspot(generation)` (CTL:1477, 2924-3025).
   - For `WIFI_P2P` mode it builds `WifiP2pGroupManager(...)` (CTL:2967-2969) and calls `manager.start(WIFI_P2P_START_TIMEOUT_MILLIS = 20_000)` (CTL:3001-3002, 3731).
   - This runs **concurrently with the RFCOMM dial**. See §7.
8. `pauseWifiScans` (CTL:1481, 3033-3042). It is on by default (pref `wifi_direct_scan_pause`, default `true`, CTL:3037).
9. Address selection (CTL:1488-1533). See §7.4. Result: `hostAddress`, normally the group owner's IPv4.
   - IPv6 is used only if no IPv4 exists, or if the remembered family is IPv6.
   - An IPv6 address must be link-local with a scope id (CTL:1526-1533).
   - The first-TCP deadline is set: `watchdog.timeoutMillis = firstTcpTimeoutMillis(...)` (CTL:1538), which is 30 000 ms, or 10 000 ms for an IPv6 guess. The IPv6 guess is off by default (`WirelessAddressFamilyPlan.kt:37`).
10. Receiver identity for this session:
    - `deviceIdentifier = hotspotInfo.bssid` (the P2P interface MAC) unless it equals `02:00:00:00:00:00`. Otherwise it is the saved `airPlayConfig.deviceId` (CTL:1539-1541).
    - `hostBluetoothMac = accessoryBluetoothMac(adapter)` (CTL:1586-1588, 3352-3369).
    - `wirelessAirPlayConfig = airPlayConfig.copy(deviceId = deviceIdentifier, btMac = hostBluetoothMac)` (CTL:1593-1596).
11. AirPlay TCP listener: `service.attachWireless(bindAddress = hostAddress, …, additionalBindAddresses = hotspotInfo.hostAddresses.filter { it != hostAddress })` (CTL:1599-1625; VPN:137-166).
    - For Wi-Fi Direct, `hostAddresses` holds only `hostAddress` (P2P:676-689 does not set it, so the default is `listOfNotNull(hostAddress)`, `WirelessHotspotManager.kt:27`). The result is **one listener on `hostAddress`**.
    - Port: preferred `config.port = 7000` (CFG:74), then 7001..7010, then an ephemeral port (`network/AirPlayPortSelector.kt:17,57-64`).
    - Accept threads start now (VPN:236-247).
    - `listenerPort = service.boundPort()` (CTL:1626).
12. Bonjour: `CarPlayBonjour(..., config = wirelessAirPlayConfig.copy(port = listenerPort), advertisedHost = hostAddress, useInterfaceMdns = true, additionalAddresses = …)` then `start()` (CTL:1638-1671).
    - `additionalAddresses` = `hotspotInfo.hostAddresses` plus any scoped IPv6 link-local that appears within `BONJOUR_LINK_LOCAL_WAIT_MILLIS = 600` ms (CTL:1661-1664, 3715), minus `hostAddress`. **[CarLink]**
    - Publishing and browsing then run asynchronously on JmDNS threads and the `carplay-bonjour` worker. See §1.3 and §6.
13. Wait for the RFCOMM dial: `awaitBluetoothDial(dial, socket, address, RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000)` (CTL:1685-1686, 3192-3219, 3742).
    - The 15 s budget counts from when the dial **started** (BackgroundDial.kt:53-58), not from this wait.
    - On timeout the socket is closed and the run throws `IOException("Timed out after 15000ms connecting RFCOMM to …")`.
14. iAP2 over RFCOMM:
    - Wrap the socket in `BluetoothRfcommDuplexStream` (CTL:1709-1720), then call `Iap2Session.openWireless(stream, …)` (CTL:1727-1732).
    - Instant-link check: if the connect took < 50 ms and no byte has arrived (`transport/BluetoothLinkCheck.kt:17-18,32`), a watcher waits `SILENCE_MILLIS = 12_000` (BluetoothLinkCheck.kt:38) and probes a random UUID (CTL:3104-3146).
    - `Iap2WirelessControlClient.run(...)` (CTL:1783-1802; WCC:23-235) does, in order (WCC:50-63): Identification, then MFi authentication, then the 5 subscriptions, then `onReady`. After that it loops on incoming frames:
      - `0x5702 RequestAccessoryWiFiConfiguration` → send `0x5703 AccessoryWiFiConfiguration`. At most 5 sends before the transport notification and 2 after (WCC:127-162, 247-248).
      - `0x4300 CarPlayAvailability` → `beforeStartSession` → send `0x4301 CarPlayStartSession` (WCC:164-176), at most 3 (WCC:249). `onStartSessionSent` calls `watchdog.startSessionSent()`, which **arms the 30 s first-TCP timer** from that moment (CTL:1792-1795; FirstTcpWatchdog.kt:24-31).
      - `0x4e0e DeviceTransportIdentifierNotification` → send `0x5703` again (WCC:184-214).
      - Every other frame is forwarded to `onRouteFrame` (CTL:942).
    - The bootstrap loop deadline is `CONTROL_LOOP_TIMEOUT_MILLIS = 5 min`, or 24 h when location reporting is on (CTL:3425-3431, 3736-3737). A live session with a rendered frame can extend it (CTL:2103-2107; WCC:379-399).

### 1.3 What runs in parallel after step 12

- **Bonjour publish.** `_airplay._tcp` is published through one JmDNS instance per advertised address (BJ:284-304).
- **Bonjour browse and the connect probe.** A `_carplay-ctrl._tcp.local.` listener on each JmDNS (BJ:299) handles `serviceAdded` by calling `requestServiceInfo(type, name, true)` (BJ:190-195).
  - On `serviceResolved`, it takes the iPhone's address of the **same family as that JmDNS instance** (BJ:206-208) and dedupes by `name|isIPv4` (BJ:218).
  - It then enqueues the endpoint (BJ:223). The worker emits `Resolved` and runs `probe()` (BJ:414-421).
- **Probe.** Up to `MAX_PROBE_ATTEMPTS = 7` attempts, `PROBE_RETRY_DELAY_MILLIS = 1_500` ms apart (BJ:549-568, 673-674). Each attempt:
  - binds the source address to the advertised address of the same family (BJ:586, 631-632);
  - `connect` with a 3 000 ms timeout and `soTimeout` 3 000 ms (BJ:587-590, 675-676);
  - writes the request in §1.4;
  - reads one status line, capped at 8 KiB (BJ:604, 618-629, 677).
  - The controller only **logs** the result (CTL:1647-1657). No step waits for it or acts on it.
  - The CarLink comment at CTL:1658-1660 calls this probe the "invite": without IPv6 mDNS "no invite ever went out".
- **TCP accept.** The iPhone joins the group and connects to `hostAddress:listenerPort`. `acceptLoop` (VPN:250-320) then:
  - sets `tcpNoDelay = true`, `keepAlive = true` and `SO_LINGER(true, 0)` (VPN:259-261);
  - calls `onTcpAccepted` (VPN:274-278), which cancels the first-TCP watchdog (CTL:1970-1977; FirstTcpWatchdog.kt:33-41);
  - logs `"airplay TCP accepted family=IPv4|IPv6"` (VPN:279-281);
  - builds an `AirPlaySession` and calls `start()` (VPN:282-313).
  - Several sessions can exist at once. A new accept does **not** close older sessions (VPN:311, 322-324).
  - A peer that is one of our own addresses is "internal" (`AirPlayTcpAccepted.kt:21-23`). Its `onSessionActive` and `onSessionEnded` are suppressed (VPN:289-308).

### 1.4 `GET /ctrl-int/1/connect` probe, exact bytes (BJ:97-129)

```
GET /ctrl-int/1/connect HTTP/1.1\r\n
Host: <host>:<port>\r\n                 (IPv6: "[<addr without %scope>]:<port>")
User-Agent: AirPlay/<sourceVersion>\r\n  (sourceVersion = "950.7.1", HOST:5195)
AirPlay-Receiver-Device-ID: <deviceId with ':' removed>\r\n
Connection: close\r\n
\r\n
```

- `<host>:<port>` is the **iPhone's** resolved `_carplay-ctrl` address and port, not ours (BJ:591-598).
- `deviceId` is `config.deviceId` with the colons removed (BJ:113). On Wi-Fi Direct this is the P2P interface MAC in **lowercase** hex (P2P:893-897, CTL:1539). The test vector uses `020000000002` (CarPlayBonjourTest.kt:74).
- Only the status line is read. Its status is parsed with `^HTTP/\d(?:\.\d)? (\d{3})` for diagnostics only (BJ:649-650).

### 1.5 After the TCP accept: handoff from Bluetooth to the Wi-Fi tunnel

1. The AirPlay session runs (§2).
2. The iPhone SETUPs a type-130 data stream with `clientTypeUUID = E9459FD0-BCAD-4C45-820F-1E72447EF2F2` (ME:707). `media.setIapTunnelHandler(::startWirelessTunnelControl)` was installed at CTL:1775. The tunnel's iAP2 control starts after the SETUP response is written (ME:474-487 → CTL:1888-1957). It runs `Iap2WirelessControlClient.run` again with `NO_TIMEOUT_MILLIS`, and `onReady` sets `wirelessTunnelReady` (CTL:2064-2073).
3. The iPhone sends `POST /command` `{type: "disableBluetooth"}` (also matched as `"disable-bluetooth"`, case-insensitive, CTL:2920-2922) on the **active** session. That sets `wirelessHandoffRequested` and arms `WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000` (CTL:506-525, 2164-2187, 3741).
4. When both the request and tunnel readiness are true, a thread closes the Bluetooth bootstrap (CSM, RFCOMM stream, socket) and reports `WirelessActive` (CTL:2076-2101, 2221-2237).
5. If the handoff times out without tunnel readiness:
   - If a session is active **and** a frame rendered, the run reports `WirelessActiveFallback` and keeps Bluetooth as the iAP2 channel (CTL:2196-2204).
   - Otherwise it calls `closeWirelessStack` and `fail("Wireless CarPlay handoff timed out waiting for tunnel iAP2")` (CTL:2205-2208).

### 1.6 Failure, teardown and reconnect triggers

| Trigger | Effect (cite) |
|---|---|
| No external TCP within 30 s (10 s for an IPv6 guess) after StartSession was sent | `flipWirelessAddressFamily` (saves the other family in pref `diplay_wireless/hotspot_address_family` if an alternate exists, CTL:3371-3391), then `fail(FIRST_TCP_TIMEOUT)` and `closeWirelessStack` on a daemon thread (CTL:1399-1408) |
| RFCOMM dial timeout or error | throws; caught at CTL:1840 → `fail` + `closeWirelessStack` (CTL:1876-1883) |
| RFCOMM EOF (`IOException("iAP2 link ended")`) **after** session active, frame rendered, or session-SETUP (deviceID) | Wi-Fi kept. For SETUP-only, `armSetupKeepAlive`: if no session within `SETUP_SESSION_WAIT_MILLIS = 5_000` it fails (CTL:1847-1872, 2113-2140; WirelessStartupPolicy.kt:17; predicate CTL:171-179) |
| Bootstrap loop returns `CHANNEL_CLOSED` before handoff and with no session or tunnel | `IOException("Wireless CarPlay control channel closed before tunnel iAP2 ready")` (CTL:1807-1834) |
| Bootstrap loop `TIMED_OUT` before wireless active | status `ControlEnded` (CTL:1835-1838) |
| Tunnel iAP2 closes | `Failed("Wireless iAP2 tunnel closed")` (CTL:1934-1935) |
| Accept loop `IOException` | VPN releases the listener and calls `listener.onTransportError` (VPN:315-368) → host reconnects |
| AirPlay session ends (any cause, §2.9) | `onSessionEnded` (CTL:2010-2021 → 448-468). The controller does **not** restart. The host activity logs "AirPlay session ended; reconnecting from scratch" and calls `reconnectAfterLoss` (HOST:5511-5535) |

The host activity's reconnect policy (`reconnectAfterLoss`, HOST:6247-6340):

- **Startup failures** (`startupFailure` retryable): `WirelessStartupRetryBudget` gives delays of 2 s, 4 s, 8 s, 16 s, 30 s (`2000 << retries`, capped at 30 s), for at most `MAX_STARTUP_RETRIES = 5` (`common/.../WirelessStartupRetryBudget.kt:11-14`; WirelessStartupPolicy.kt:18). After that, retries stop and a Retry button appears. The budget resets after a session that has been stable for 60 s (`STABLE_SESSION_MILLIS`, WirelessStartupPolicy.kt:19; HOST:5501-5507).
- **Reason contains "AirPlay iAP tunnel"**: 15 000 ms (HOST:7251).
- **Bluetooth dial failure** (reason contains "socket might closed", "RFCOMM" or "Bluetooth connect") and fewer than 8 attempts: 1 500 ms. **[CarLink]** (HOST:6241-6245, 7249-7250)
- **Otherwise**: `2000 * 2^min(attempts, 4)`, capped at 30 000 ms (HOST:6298, 7247).
- `reconnectAttempts` is reset to 0 on session active (HOST:5478) and on manual retry (HOST:6367).
- The retry calls `restartCarPlay`, which builds a **new controller**. The old one is closed through `oldController.close()` and `awaitClosed(4 000 ms)` (HOST:6374-6410).

`closeWirelessStack` order (CTL:3252-3289):

1. watchdog terminate;
2. diagnostics;
3. setup keep-alive;
4. clear the iAP tunnel handler;
5. tunnel iAP2;
6. Bluetooth bootstrap (CSM, RFCOMM stream, socket);
7. **[CarLink]** hotspot close (removes the P2P group);
8. Bonjour close (JmDNS closes on daemon threads, BJ:370-375);
9. `service.detachWireless(owner)`, which closes the listener and all sessions (VPN:175-177, 371-379).

### 1.7 When the iPhone disconnects

- **TCP FIN or RST on the control socket.** `input.read` returns -1 ("peer EOF"), or throws ("control I/O failed") (AS:461-466, 543-545). `finally { close() }` (AS:546-549) then:
  1. clears the main and cluster screen tokens;
  2. closes the socket;
  3. calls `media.onSessionClosed` (all streams, buffered audio and microphones, ME:551-567);
  4. runs `teardown()` (NTP, keep-alive UDP, event server and socket, AS:870-885);
  5. calls `listener.onSessionEnded` once (AS:157-169).
- **Silent peer death.** `TcpLiveness.configure` is applied to the control socket (AS:454): `SO_KEEPALIVE`, `TCP_KEEPIDLE` 10 s, `TCP_KEEPINTVL` 3 s, `TCP_KEEPCNT` 3, `TCP_USER_TIMEOUT` 20 000 ms on API 26 and later (`network/TcpLiveness.kt:14-43`). The read then fails and the same path runs.
- **Other paths that close the whole session:**
  - event-socket EOF or decrypt error (AS:973-980);
  - event-command write error (AS:343-346);
  - video stream end (ME:160-169);
  - iAP tunnel end (ME:422-428);
  - tunnel attach rejected after SETUP (ME:482-486).
- Then §1.6: the host reconnects from scratch (new Wi-Fi Direct group, new RFCOMM, new Bonjour, new listener).

---

## 2. RTSP/HTTP control channel (`AirPlaySession`)

### 2.1 Framing (RM:50-90)

- The read loop reads up to `READ_CHUNK_BYTES = 16 KiB` per `read` (AS:458, 1004).
- **Before encryption**, the raw bytes are appended to `accumulated`. **After encryption**, the bytes go to `encBuf`, `ControlCipher.decrypt` yields the plaintext of whole frames, and the plaintext is appended to `accumulated` (AS:467-481).
- `RtspMessage.parseMessages(accumulated)` parses one message at a time:
  - Find `"\r\n\r\n"`. If it is missing and more than `MAX_HEADER_BYTES = 16 KiB` is buffered, it throws `ProtocolException` (RM:32, 54-58), which closes the session.
  - The header block is decoded as **US-ASCII** and split on `"\r\n"`.
  - The request line is split on `' '` with a limit of 3: `method`, `path` (default `""`) and `protocol` (default `"RTSP/1.0"`) (RM:65, 80-82).
  - Each header line is split at its first `:`. The name is trimmed and **lowercased**, and the value is trimmed. A duplicate header overwrites the earlier one (HashMap), and lines without a colon are skipped (RM:67-72).
  - The body length is `content-length` parsed as Int; a bad value counts as 0 and a negative one is coerced to 0. A body over `MAX_BODY_BYTES = 1 MiB` throws (RM:35, 74-75). There is no chunked encoding.
  - The body is raw bytes, which supports binary plist, TLV8 and octets. An incomplete body waits for more data (RM:77).
- Several pipelined requests in one buffer are handled in order (RM:53, 84-88). The test vector is `ControlReceiveBoundsTest.kt:38-46`: two requests plus a partial `"GET /in"` left as `rest`.

### 2.2 Response building (RM:93-108)

- **Status line.** `<request protocol> <status> <text>\r\n`. The protocol echoes the request's (RTSP/1.0 or HTTP/1.1). The status defaults to 200. The text comes from {200 OK, 400 Bad Request, 404 Not Found, 500 Internal Server Error}; **any other status prints "OK"** (RM:38-43, 96).
- **Headers, in this order:**
  1. the handler's headers (only ever `Content-Type`);
  2. `CSeq: <echoed>`, only if the request had `cseq`;
  3. `Content-Length: <n>`, always present, including `0`.
- **No other headers.** No `Server`, `Date`, `Session`, `Public` or `Audio-Jack-Status`.
- Example, empty 200: `RTSP/1.0 200 OK\r\nCSeq: 5\r\nContent-Length: 0\r\n\r\n`.
- Content types (AS:991-993):
  - `application/x-apple-binary-plist`
  - `application/pairing+tlv8`
  - `application/octet-stream`
- A handler exception gives 500 (AS:503-512).
- **All responses for one read batch are written, then flushed once** (AS:533, 540). `notifySetupResponseSent()` is called after **every** flush, not only after SETUP (AS:541, 641-648). This is harmless: the media engine consumes a pending tunnel only once (ME:474-475).

### 2.3 Encryption switch-over (AS:531-538)

- After writing each response, if `cipher == null && pairVerify.controlKeys != null`, the cipher becomes `ControlCipher(readKey, writeKey)`.
- `controlKeys` becomes non-null only when pair-verify M3 verifies (PV:96-99). So:
  - the **pair-verify M4 response is sent in plaintext**;
  - every byte after it is encrypted, both directions.
- Plaintext before the switch-over: `/info` (if sent early), `/pair-setup` (M1-M6) and `/pair-verify` (M1-M4).
- Encrypted after the switch-over: `/auth-setup` ("It runs over the already-encrypted control channel", MSAP:15-16), SETUP, RECORD, GET/SET_PARAMETER, `/feedback`, `/command`, TEARDOWN and the buffered-audio methods.
- Control keys (PV:96-97, 121-123):
  - `readKey = HKDF-SHA512(shared, salt "Control-Salt", info "Control-Write-Encryption-Key", 32)`
  - `writeKey = HKDF-SHA512(shared, "Control-Salt", "Control-Read-Encryption-Key", 32)`
  - `shared` = the X25519 secret from pair-verify.
- Frame format (CC:10-55):
  - `[u16 LE plaintext length n][ChaCha20-Poly1305(ciphertext n bytes)][16-byte tag]`;
  - AAD = the 2 length bytes;
  - nonce = 4 zero bytes + 8-byte **little-endian** counter (ACR:220-228);
  - separate counters per direction, both starting at 0;
  - plaintext is split into chunks of at most `0x4000` (CC:53).
- **Oddity.** The cipher is chosen at the top of each `read` (AS:468). If the iPhone pipelined encrypted bytes in the same TCP read as the plaintext pair-verify M3, those bytes would be appended as plaintext. In practice the iPhone waits for M4.

### 2.4 Dispatch (AS:552-639): method and URI, then response

Dispatch order: first by **method** (whatever the path), then buffered-audio methods, then by **path suffix** (case-insensitive `path.lowercase().endsWith(...)`, whatever the method, except `/command` and `/feedback`, which require POST).

| Request | Handler | Response |
|---|---|---|
| `SETUP <any>` | `handleSetup` (AS:669-713) | 200, `application/x-apple-binary-plist`, bplist (§5). 400 if the body is not a bplist dict (AS:670-675) |
| `SET_PARAMETER <any>` | Only if `useIphoneCornerMask` and body ≤ 512 KiB + 64 KiB: decodes the bplist and searches for `topLeftCornerMask` (AS:555-563) | 200, empty |
| `RECORD <any>` | `listener.onSessionActive(this)` (AS:564-567) | 200, empty |
| `TEARDOWN <any>` | `handleTeardown` (AS:802-827) | 200, empty. Stream types come from the body; no `streams` means **all streams**. The TCP connection stays open |
| `SETRATE`, `SETRATEANCHORTIME`, `GETANCHOR`, `FLUSHBUFFERED` (only if `mainBufferedAudio && !disableAudioOutput`) | AS:571-582 → ME:354-377 | 200 empty, or 200 plist (anchor). 400 if the body is not a dict (empty allowed for GETANCHOR) |
| `* …/pair-setup` | `PairSetup.handle` (PS) | 200, `application/pairing+tlv8`, TLV8 |
| `* …/pair-verify` | `PairVerify.handle` (PV) | 200, `application/pairing+tlv8`, TLV8 |
| `* …/auth-setup` | `MfiSapAuthSetup.handle` (MSAP:25-67) | 200, `application/octet-stream`, raw. 400 if MFi is missing or the body is malformed (body must be 33 bytes and start with 0x01) |
| `* …/info` | `AirPlayInfoPlist.build(config)` (AS:599-624). An optional bplist request body is only logged | 200, bplist (§4) |
| `POST …/command` | `handleCommand` (AS:773-800) | 200, empty, always |
| `POST …/feedback` | `media.onFeedback` (ME:489-523) | 200 empty when no audio stream is active, else 200 bplist `{streams:[…]}` (§5.4) |
| anything else (`GET_PARAMETER`, `OPTIONS`, `FLUSH`, `GET /…`, …) | — | **200, empty**. No `Public` header for OPTIONS; no body for GET_PARAMETER |

- **pair-setup** (PS): SRP-6a with the RFC 5054 3072-bit group and SHA-512 (`Srp6a.kt:7`). Username `"Pair-Setup"`, PIN `"3939"` (PS:125-126).
  - State 1 → M2 {state 2, pubkey B, salt}.
  - State 3 → M4 {state 4, proof}.
  - State 5 → decrypt with HKDF("Pair-Setup-Encrypt-Salt"/"…-Info") and nonce "PS-Msg05", verify the controller's Ed25519 signature, save the controller LTPK, and reply M6 {state 6, encrypted {id, ltpk, sig}} with nonce "PS-Msg06" (PS:62-106, 128-135).
  - Any error replies {state, error 2} (PS:108-113).
- **pair-verify** (PV):
  - State 1 → M2 {state 2, our ephemeral X25519 pub, encrypted {pairingId, Ed25519 sig over ourEph‖pairingId‖theirEph}} with nonce "PV-Msg02" (PV:42-72).
  - State 3 → decrypt with "PV-Msg03", look up the controller LTPK (error if it is unknown), verify the signature over theirEph‖controllerId‖ourEph, derive the control keys, and reply {state 4} (PV:74-102).
- **auth-setup** (MSAP:20-183):
  - Request: `[0x01][32-byte X25519 pub]`.
  - Response: `[32-byte our pub][u32 BE certLen][cert][u32 BE sigLen][AES-128-CTR(sig)]`. BAA certificates append `[u32 BE len][OPACK {"baIC": intermediate}]`.
  - AES key = `SHA1("AES-KEY"‖shared)[0:16]`, IV = `SHA1("AES-IV"‖shared)[0:16]`.
  - Signed digest: MFi protocol major 2 signs `SHA1(ourPub‖peerPub)`, otherwise `SHA256(ourPub‖peerPub)`. BAA signs the raw `ourPub‖peerPub`.

### 2.5 `/command` handling (AS:773-800)

- The body is decoded to a dict (an empty map on error). `type` and `params` are read from it.
- If the request has an `X-Apple-StreamID` header and `params.data` is bytes, `data` is decoded as a bplist and passed to `onRemoteControlMessage` (video in car). The reply is 200.
- `duckAudio` and `unduckAudio` go to `media.onAudioDuck` (AS:789-792).
- `requestUI` with `url == "videoplayback:"` calls `onVideoPlaybackUiRequested`. Any other `requestUI` calls `onHostUiRequested` (AS:793-797).
- Every command reaches `listener.onCommand`. The controller uses `disableBluetooth` for the handoff (§1.5).

### 2.6 Request order the iPhone sends

**Not recorded in source.** The session does not enforce any order. The code implies these dependencies:

- `/info` can be answered at any time; it needs no pairing.
- `/pair-setup` is needed only if the controller ID is not in `PairingStore`, because pair-verify M3 fails without a stored LTPK (PV:92).
- `/pair-verify` must precede encryption, the event channel and every stream:
  - `acceptEvent` closes the session if `pairVerify.shared == null` (AS:897-903);
  - stream keys need `sharedSecret` (ME:665-677), and `onScreen` and `onAudio` return null (stream rejected) without it.
- `/auth-setup` runs after pair-verify (MSAP:15-16).
- The session SETUP (no `streams`, includes `deviceID` and `timingPort`) precedes the stream SETUPs. WirelessAirPlayHandshake treats the "session SETUP (deviceID)" as a milestone (CTL:1979-1983; `orchestration/WirelessAirPlayHandshake.kt:31-37`).
- `RECORD` marks the session active (AS:564-566). The comment at WirelessStartupPolicy.kt:11-13 says a good connection renders a frame 0.2-0.5 s after the session SETUP.
- `/feedback` (periodic) and `/command` are hidden from the debug overlay, which suggests they are frequent (AS:487-489). Their cadence is not in source.

So the order you will see is roughly: `GET /info` → `POST /pair-setup` ×3 (first time only) → `POST /pair-verify` ×2 → [encrypted] `POST /auth-setup` → `SETUP` (session) → `SETUP` (streams, possibly several times) → `RECORD` → `GET_PARAMETER`, `SET_PARAMETER`, `POST /feedback` and `POST /command` repeatedly → `TEARDOWN`. The **exact iPhone order and the position of /info is an inference, not verified in code.**

### 2.7 Event channel (accessory → iPhone commands)

- **Listener.** Opened by the session SETUP: `ServerSocket(0, backlog 50, "::")` (AS:863-868).
- **Accept.** `acceptFrom(peer)` accepts only the control peer's IP and closes others (AS:887-890; `AirPlayTcpAccepted.kt:30-37`). The socket gets `SO_LINGER 0` and `TCP_NODELAY` (AS:891-894).
- **Keys** (AS:904-916). Note that this is the **opposite naming to the control channel**:
  - `writeKey = HKDF-SHA512(shared, "Events-Salt", "Events-Write-Encryption-Key", 32)`
  - `readKey = HKDF-SHA512(shared, "Events-Salt", "Events-Read-Encryption-Key", 32)`
  - Framing is the same ControlCipher.
- **Outgoing request** (AS:327-348), encrypted:
  ```
  POST /command RTSP/1.0\r\n
  [X-Apple-StreamID: <id>\r\n]
  Content-Type: application/x-apple-binary-plist\r\n
  Content-Length: <n>\r\n
  CSeq: <eventCseq, starts at 1>\r\n
  \r\n<bplist>
  ```
- **Incoming traffic.** Responses (`RTSP/` or `HTTP/` first token) are ignored. Requests get `200 OK` with echoed CSeq (AS:954-969).
- **Commands sent.** These have `type` and `params` unless noted (AS, INFO, ME):
  - `hidSendReport {uuid:"2a2a2a2a"…, hidReport:data}` — no `params` wrapper (AS:444-451);
  - `setNightMode {nightMode}`;
  - `requestSiri {siriAction 2}` then `{siriAction 3}` (AS:390-393);
  - `iAPSendMessage {data}`;
  - `showUI {uuid, url}`, `stopUI {uuid}`;
  - `forceKeyFrame` — `{uuid}` for the cluster; no params for main (ME:149-153);
  - `changeMapZoomLevel {uuid, zoomDirection 0|1}`;
  - `updateViewArea {uuid, viewAreaIndex, animationDurationMillis 300, adjacentViewAreas}` (INFO:191-199);
  - `setVideoPlaybackAllowed {videoPlaybackAllowed}`.

### 2.8 Timing (NTP-style) and keep-alive

- **Timing.** The `timingPort` reply is a UDP socket bound to `[::]:0` (NTP:39-47). If the iPhone sent `timingPort > 0`, the receiver sends requests to `peer:timingPort` every 1 000 ms (NTP:191).
  - Packet: 32 bytes; `0x80`, PT `210` request or `211` response, u16 BE `7`; originate at 8, receive at 16, transmit at 24 (NTP:86-128, 185-190).
  - It also answers the iPhone's 210 requests.
- **Keep-alive.** `keepAlivePort` is a UDP socket bound to `[::]:0`. It is opened only if the session SETUP has `keepAliveLowPower == true` or `== 1`. Incoming packets are read and discarded (AS:701-703, 840-861).

### 2.9 Session close causes

- peer EOF;
- control decrypt failure (AS:471-477);
- I/O exception, including `ProtocolException` from the header or body caps;
- event channel EOF or failure;
- event write failure;
- video, iAP tunnel or attach failures (§1.7);
- `close()` from the controller or VPN service.

---

## 3. Binary plist codec (BP)

Supported objects:

| Type | Encode (Kotlin → bplist) | Decode (bplist → Kotlin) |
|---|---|---|
| null `0x00` | **not encodable** (throws "unsupported value null", BP:80) | `null` (BP:149) |
| bool `0x08` / `0x09` | `Boolean` (BP:50) | `false` / `true` (BP:150-151) |
| int `0x1n` | `Byte`/`Short`/`Int`/`Long` ≥ 0: **minimal unsigned width**. 1 byte if ≤ 0xFF, 2 if ≤ 0xFFFF, 4 if ≤ 0xFFFFFFFF, else 8 (BP:230-244). `BigInteger` 0..2^64-1: width by bitLength >8/>16/>32 (BP:246-261) | nibble ≤ 3 only (1/2/4/8 bytes), big-endian. 1/2/4-byte values are unsigned, 8-byte as raw signed `Long` → `Long` (BP:154-158). **16-byte ints (`0x14`) are rejected** |
| negative int | **Deviation: encoded as an 8-byte REAL** `0x23 + IEEE754 double` (BP:58-62). Example: `-1` → `23 BF F0 00 00 00 00 00 00` | — |
| real `0x2n` | `Float`/`Double` → always 8-byte double `0x23` (BP:64-67) | 4-byte → `Float`, 8-byte → `Double` (BP:159-166) |
| date `0x33` | not encodable | `java.util.Date` (8-byte double + 978307200 s) (BP:168-170) |
| data `0x4n` | `ByteArray` (BP:69) | `ByteArray` (BP:171-175) |
| string ASCII `0x5n` | `String` with every char ≤ 0x7F (BP:221-225) | US-ASCII (BP:181-185) |
| string Unicode `0x6n` | otherwise UTF-16BE; count = UTF-16 code units (`value.length`) (BP:226) | UTF-16BE (BP:186-191) |
| UID `0x8n` | not encodable | `Long` (BP:177-180) |
| array `0xAn`, set `0xCn` | `List` → array (BP:70-73) | `ArrayList` for both (BP:192-202) |
| dict `0xDn` | `Map`; keys `toString()`; **insertion order**; all key refs, then all value refs (BP:74-79) | `LinkedHashMap`; keys must be strings (BP:203-216) |

Layout details:

- **Count encoding** (BP:263-277): a count below 15 goes in the low nibble. Otherwise the nibble is 0xF, followed by an int marker `0x10|log` and a 1, 2 or 4-byte big-endian count.
- **Object order**: depth-first pre-order. A container is assigned its index **before** its children. For a dict, every key object is created first, then every value object (recursively) (BP:44-83). There is **no uniquing** (Apple dedups repeated strings; this encoder writes them again).
- **Object ref size**: 1 byte if there are ≤ 255 objects, 2 if ≤ 65535, else 4 (BP:86-90).
- **Offset int size**: 1 if the offset table starts at ≤ 0xFF, 2 if ≤ 0xFFFF, else 4 (BP:106-111).
- **Trailer** (32 bytes, BP:114-120):
  - bytes 0-5: 0 (no sort version);
  - byte 6: offsetIntSize;
  - byte 7: objectRefSize;
  - 8-15: numObjects (u64 BE);
  - 16-23: topObject = 0;
  - 24-31: offsetTableOffset (u64 BE).
- **Decoder limits**:
  - minimum 40 bytes (BP:14);
  - offset and ref sizes 1..8 (BP:20);
  - object count ≤ data length (BP:25);
  - depth ≤ 32;
  - reads ≤ min(4·size + 1024, 100 000);
  - payload ≤ min(…, 4 MiB) (BP:36-40, 331-340).
- **Deviations from Apple's `bplist00`**:
  1. Negative integers are written as reals.
  2. Unsigned 64-bit values ≥ 2^63 (BigInteger, e.g. `timestamp` NTP64 in `/feedback`, or `streamConnectionID` echoed through `unsignedPlistInteger`, ME:719-723) are written as **8-byte** ints. Apple reads 8-byte ints as signed, and Apple itself uses 16-byte ints for these values. Round-trip of the iPhone's own 8-byte IDs is bit-exact.
  3. Floats are always written as doubles.
  4. Null cannot be encoded.
  5. 16-byte ints cannot be decoded.

**Derived encode example** (from reading BP; not a test vector): `encode(linkedMapOf("a" to 1))` is 50 bytes:
```
62 70 6C 69 73 74 30 30            "bplist00"
D1 01 02                           obj0 @8 : dict, 1 entry, keyref 1, valref 2
51 61                              obj1 @11: "a"
10 01                              obj2 @13: int 1
08 0B 0D                           offset table @15 (offsetIntSize 1)
00 00 00 00 00 00 01 01            trailer: offsetIntSize=1, refSize=1
00 00 00 00 00 00 00 03            numObjects=3
00 00 00 00 00 00 00 00            topObject=0
00 00 00 00 00 00 00 0F            offsetTableOffset=15
```

---

## 4. `GET /info` plist (INFO:31-85)

The root dict is built in insertion order. Integers are Kotlin `Int`/`Long`, so they are written as unsigned ints of minimal width unless negative (§3).

| Key | Type | Value / source |
|---|---|---|
| `sourceVersion` | string | `"950.7.1"` (HOST:5195) |
| `features` | int | `0x615653AEE2` (INFO:17). With `disableAudioOutput` ("car Bluetooth audio"): `0x615653AEE2 & ~0x10004540A00 = 0x615203A4E2` (INFO:18-19, 28-29). Written as 8 bytes |
| `statusFlags` | int | `4` |
| `model` | string | `normalizedModel()`, default `"DiPlay"` (AirPlayPersistence.kt:147) |
| `manufacturer` | string | default `"DiPlay"` (AirPlayPersistence.kt:146) |
| `deviceID` | string | wireless: the P2P interface MAC, lowercase `aa:bb:…` (CTL:1539-1541, P2P:893-897). Fallback: `DiPlayBootstrap.deviceId` = SHA-256(Ed25519 pub)[0:6] with the first byte `&0xFC|0x02`, uppercase `XX:XX:…` (`common/.../DiPlayBootstrap.kt:45-49`) |
| `bluetoothIDs` | array[string] | `[btMac]` = adapter address or `Settings.Secure bluetooth_address` (CTL:3352-3369) |
| `name` | string | `CarPlayHostIntegration.accessoryName`. CarLink sets it to `app_name` = `"CarLink"` (`app/.../CarLinkApplication.kt:30`, `carlink_strings.xml:4`); the engine default is `"DiPlay"` |
| `rightHandDrive` | bool | config |
| `keepAliveLowPower` | bool | `false` |
| `keepAliveSendStatsAsBody` | bool | `false` |
| `modes` | dict | `{resources:[{resourceID 1, transferType 1, transferPriority 100, takeConstraint 100, borrowConstraint 100, unborrowConstraint 100}, {resourceID 2, …same}], appStates:[{appStateID 2, state false}, {appStateID 1, speechMode -1}, {appStateID 3, state false}]}` (INFO:87-103). **`speechMode -1` goes on the wire as REAL -1.0** (§3) |
| `audioLatencies` (unless audio disabled) | array[dict] | 9 entries `{type, inputLatencyMicros 0, outputLatencyMicros 0[, audioType]}`: (100), (100, default), (100, media), (100, telephony), (100, speechRecognition), (100, alert), (101), (101, default), (102, default) (INFO:105-120) |
| `audioFormats` (unless audio disabled) | array[dict] | `{type, audioType, audioOutputFormats[, audioInputFormats]}`. See the next table |
| `extendedFeatures` | array[string] | `["vocoderInfo", "enhancedRequestCarUI"]` |
| `displays` | array[dict] | main, plus cluster if configured. See below |
| `hidDevices` | array[dict] | 4 entries (HID:31-41, 74-87): `{hidProductID 1, hidVendorID 2, hidCountryCode 0, uuid, name, displayUUID MAIN_UUID, hidDescriptor data}`. Rows: uuid `"2a2a2a2a"` "xcertplay Touchscreen" (2-finger multitouch, logical max = display w and h); `"2a2a2a2b"` "xcertplay Knob"; `"2a2a2a2c"` "xcertplay Media"; `"2a2a2a2d"` "xcertplay Telephony" |
| `oemIconVisible`, `oemIconLabel`, `oemIcons` | bool, string, array | Always present at runtime, because `icons = listOf(loadAirPlayIcon())` (HOST:5205). Each icon is `{imageData, widthPixels, heightPixels, prerendered true}`. The label defaults to `"BYD"` (AirPlayPersistence.kt:148) |
| `hevcInfo` | dict `{}` | only if HEVC |
| `mainBufferedInfo` | dict `{}` | only if buffered audio is enabled |
| `videoPlaybackInfo` | dict | only if video-in-car is on: `{videoPlaybackAllowed, featuresEx, playbackCapabilities{…}}` (VideoInCar.kt:120-130) |

`audioFormats` (INFO:122-177). The rates offered at runtime are `musicOutput.offeredSampleRates` (HOST:5208), a subset of {44100, 48000}. The bit values are:

- `pcm = 0x3FC | (44.1 ? 0xC00 : 0) | (48 ? 0xC000 : 0)`
- `pcmMono = 0x154 | (44.1 ? 0x400 : 0) | (48 ? 0x4000 : 0)`
- `opus = 0x70000000`
- `aacLc = (44.1 ? 0x400000 : 0) | (48 ? 0x800000 : 0)`
- `speech = pcm` (or `pcm & ~0x3C` with `speechWithout8And16Khz`)

| type | audioType | audioOutputFormats | audioInputFormats (mic only) |
|---|---|---|---|
| 100 | compatibility | pcm | pcmMono |
| 101 | compatibility | pcm | — |
| 100 | default | speech \| opus | pcmMono \| opus (or pcmMono) |
| 100 | alert | speech \| opus | — |
| 100 | media | pcm | — |
| 100 | telephony | pcmMono \| opus (or `0x154` if callAtCallRate) | as default (or `0x154`) |
| 100 | speechRecognition | pcmMono \| opus | as default |
| 101 | default | speech \| opus | — |
| 102 | media | aacLc | — |
| 103 | media (only if mainBuffered) | aacLc | — |

`displays[i]` (INFO:201-240):

- `uuid`: `"b7e6c5a0-1111-4000-8000-000000000001"` (main) or `"b7e6c5a0-2222-4000-8000-000000000002"` (cluster) (INFO:10-11).
- `type`: 110 or 111.
- `maxFPS`: snapped to 30..60 in steps of 5 (37 → 35 in the test).
- `widthPixels`, `heightPixels`.
- `widthPhysical`: default 200 mm, clamped 1..2000.
- `heightPhysical`: derived from the aspect ratio if not set.
- `features`: default `0x08 | 0x02 = 10`.
- `primaryInputDevice`: default 1.
- `uiAppearanceMode`, `uiAppearanceSetting`, `mapAppearanceMode`, `mapAppearanceSetting`: all 0.
- `viewAreas`: array. Each area is `{widthPixels, heightPixels, originXPixels, originYPixels[, viewAreaStatusBarEdge 1|2][, safeArea {widthPixels, heightPixels, originXPixels, originYPixels, drawUIOutsideSafeArea (default true)}]}`.
- `initialViewArea` (int).
- Optional: `cornerMasks` (bool, main only), `viewAreaTransitionControl true` (if more than one area), `initialURL`.

---

## 5. SETUP request and response plists

### 5.1 Session SETUP (body has no `streams`) (AS:686-712)

Keys read from the iPhone:

- `name`, `deviceID` (stored as `deviceBtMac`), `macAddress` (lowercased), `model`, `osVersion`, `sourceVersion`;
- `timingPort` (int);
- `keepAliveLowPower` (bool or 1);
- `features` (array of strings; checked for `"cornerMasks"`, `"videoPlayback"` and `"mainBuffered"`).

All key names are logged sorted (AS:676). Other keys are ignored.

Reply (bplist dict, in this order):

| Key | Value |
|---|---|
| `timingPort` | our UDP port (NTP, bound to `::`) |
| `eventPort` | our TCP port (event server, bound to `::`) |
| `keepAlivePort` | only if requested; UDP, bound to `::` |
| `enabledFeatures` | array[string] (AS:1034-1044), built in order: `"hevc"` if HEVC; `"iAPChannel"` (always); `"viewAreas"` (always); `"altScreen"` if a cluster is configured; `"cornerMasks"` if enabled and proposed; `"videoPlayback"` if enabled and proposed; `"mainBuffered"` if enabled and proposed |

### 5.2 Stream SETUP (body `{streams:[…]}`) (AS:677-683, 715-771)

The reply is `{streams:[…accepted entries in request order…]}`. Rejected or unsupported streams are **omitted**; the request is not failed.

| Stream `type` | Keys read from request | Reply entry |
|---|---|---|
| 110 main screen, 111 alt/cluster screen | `streamConnectionID` (key derivation), `topLeftCornerMask` | `{type, dataPort}` (TCP, bound to `[::]:0`, accepts only the peer; ScreenStream.kt:47-53, 64-66) |
| 100 main audio, 101 alt audio, 102 main high audio | `audioType` (default "default"), `audioFormat` (bits), `streamConnectionID`, `audioLatencyMs`, `dataPort` (iPhone's mic uplink port), `input`, `framesPerPacket`, `vocoderInfo` | `{type, dataPort, controlPort, streamConnectionID}` (UDP data and UDP control, both bound to `::`; ME:271-276; AudioStream.kt:60-80, 193-195) |
| 103 main buffered audio | `shk` (32 bytes, required), `audioFormat` (must be AAC-LC), `ct` (must be 4 if present), `spf` (must be 1024 if present), `streamConnectionID`, `clientID` | `{type 103, dataPort, audioBufferSize 8388608}` (ME:347-351; BufferedAudioStream.kt:41, 334) |
| 130 data, `clientTypeUUID` E9459FD0-BCAD-4C45-820F-1E72447EF2F2 (iAP tunnel) | `seed`, `streamConnectionID` | `{type 130, streamID 1, dataPort[, streamConnectionID]}` (ME:437-442) |
| 130 video-in-car settings channel `BB493F61-A6B8-4769-8D74-80C23A9F71C4` | `seed`, `streamConnectionID` | `{type 130, streamID 2, dataPort[, streamConnectionID]}` (ME:466-471) |
| 130 video-in-car remote control, `controlType 1` | — | `{type 130, streamID ≥3}` (no port, ME:450-454) |

### 5.3 Stream keys

Each stream key is `HKDF-SHA512(pairVerify.shared, salt = "DataStream-Salt" + <unsigned decimal>, info, 32)` (ME:665-677, 390-395):

- the decimal is `streamConnectionID` for screen and audio, and `seed` for data streams;
- info is `"DataStream-Output-Encryption-Key"` for streams the receiver reads, and `"DataStream-Input-Encryption-Key"` for the mic uplink (ME:705-706).

### 5.4 `/feedback` reply (ME:489-523)

The reply is `{streams:[{type, sampleRate (RTP clock), streamConnectionID, timestamp (NTP64 BigInteger), timestampRawNs, sampleTime (u32)}…] + buffered entry}`. The last four fields appear only after the first sample arrives.

---

## 6. Bonjour

- **Published service** `_airplay._tcp.local.` (BJ:300-303, 667):
  - instance name = `config.deviceName` (`"CarLink"` in CarLink);
  - port = `listenerPort` (7000 unless it is busy);
  - weight 0, priority 0;
  - JmDNS host name `"carplay-" + deviceId without ':'` (BJ:283).
- **TXT records** (BJ:83-95), in insertion order:

| Key | Value / format |
|---|---|
| `deviceid` | `config.deviceId` as is, with colons. On Wi-Fi Direct this is the P2P interface MAC in lowercase |
| `features` | `featuresTxt(features)`: `"0x" + lower32 hex` if the upper 32 bits are 0, else `"0x<lo>,0x<hi>"`. Lowercase hex, no padding (BJ:77-81). With audio: `"0x5653aee2,0x61"`. Without audio: `"0x5203a4e2,0x61"` |
| `flags` | `"0x4"` |
| `model` | `config.model` |
| `srcvers` | `config.sourceVersion` (`"950.7.1"`) |
| `protovers` | `"1.1"` |
| `pi` | `identity.pairingId` (random UUID string, lowercase with hyphens; AirPlayIdentity.kt:16) |
| `pk` | Ed25519 public key as lowercase hex (Bytes.kt:15-24) |

- **Interface binding** (wireless always uses `useInterfaceMdns = true`, CTL:1646). There is one `JmDNS.create(address, hostName)` per address in `advertisedAddresses = [hostAddress] + additionalAddresses` (BJ:158-159, 284-298). A JmDNS joins only its own family's multicast group (BJ:282).
  - **[CarLink]** A failure to bind an extra IPv6 link-local address is tolerated (BJ:291-297).
  - System NSD is **not** used on this path. It is only used when `useInterfaceMdns = false` (BJ:308-317).
  - A `WifiManager.MulticastLock("carplay-bonjour")` is held (BJ:174-176, 277).
- **Browsed service** `_carplay-ctrl._tcp.local.` (BJ:299, 668). The TXT `id` value is taken as `bluetoothId` (BJ:221). It is used only in events.
- **No other services** are published or browsed.
- **Oddity (Wi-Fi Direct).** The TCP listener is bound only to `hostAddress`, normally IPv4 (§1.2 step 11). JmDNS, however, also runs on the IPv6 link-local and registers `_airplay._tcp` there. A peer that resolves the AAAA record and connects over IPv6 would find no listener. The iPhone gets the address list through iAP2 StartSession (§7.5), which contains only `hostAddress`. Whether the iPhone ever uses the mDNS AAAA was not verified.

---

## 7. Wi-Fi Direct group (P2P)

### 7.1 Credentials

- **SSID prefix:** `"DIRECT-dp" + hex(SHA-256(packageName + ":" + ANDROID_ID)[0:6]) + "-"`, i.e. 12 lowercase hex characters (`network/P2pOwnership.kt:8-12`). If ANDROID_ID is missing, a stored random UUID replaces it (P2P:75-84).
- **Default (stable identity off):** SSID = prefix + 4 random characters, passphrase = 16 random characters (P2P:962-972). The alphabet is `ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789` (P2P:1121-1122), drawn with `SecureRandom`.
- **[CarLink] Stable identity on** (API 29+): SSID = prefix + the car name's ASCII letters and digits, cut to 32 bytes total (`"CarPlay"` if none). The passphrase is 16 random characters, stored in prefs `carplay_wifi_p2p` (`stable_ssid`, `stable_passphrase`). The group is persistent (`enablePersistentMode(true)`), and a live group with the same pair on a fitting frequency is reused (P2P:183-185, 227-249, 305-314; `network/P2pStableIdentity.kt:60-123`).
- On the API 29+ path: `WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass)` (P2P:297-299).
- On the system-default mode, and on API < 29 (`createGroup(channel, listener)`), the framework picks the credentials. They are read back from `WifiP2pGroup` (P2P:337-341, 645-646, 722-723).

### 7.2 Band and channel

`P2pStartupRecovery.plan` (`network/P2pStartupRecovery.kt:52-96`) builds the request list with `preferredChannel = AUTO`:

1. the remembered successful configuration;
2. the station-aligned frequency (if the car's own Wi-Fi is connected on 2412-2462 or on one of 5180/5200/5220/5240/5745/5765/5785/5805/5825);
3. then either 5180, 5745, 2437, 2412, 2462, or — if the station is on 2.4 GHz or on 5 GHz — **2.4 GHz first**: 2437, 2412, 2462, 5180, 5745;
4. finally `SYSTEM_DEFAULT` (null config).

5 GHz is skipped if the chip reports no 5 GHz band (P2P:191-193, 880). The band-only choices (`AUTO_5_GHZ`, `AUTO_2_4_GHZ`) and manual channels use `setGroupOperatingBand` or `setGroupOperatingFrequency` (P2P:300-304; `WifiP2pChannels.kt:15-39`).

Retry rules (P2pStartupRecovery.kt:116-176):

- `BUSY` is retried once per channel after `beforeRetry`, which includes a 500 ms wait (P2P:276-286).
- Other rejections move on to the next channel.
- `NO_PERMISSION` and `P2P_UNSUPPORTED` end the attempt.
- On Android 13+ with the car hotspot on, a `BUSY` that answers in ≥ 1 s ends with `WIFI_DIRECT_BESIDE_HOTSPOT` (P2pStartupRecovery.kt:23-31).

### 7.3 Pre-existing group

- `requestGroupInfo` is polled every 500 ms (`REQUEST_POLL_NANOS`, P2P:1120).
- An existing group owned by us (recorded `owned_ssid`, or a name matching our prefix) is removed. The run waits up to `REMOVE_GROUP_TIMEOUT_MILLIS = 2 000` ms (P2P:250-267, 1118).
- A foreign group gives `P2pResetRequiredException` (P2P:251-254).

### 7.4 Owner IP

The group must report `isGroupOwner`, a network name, a passphrase, an interface and a frequency (P2P:612-691). Then the run calls `awaitInterfaceAddress(iface)`:

- It polls `NetworkInterface.getByName(iface)` every 100 ms for up to 2 000 ms and returns as soon as an **IPv4** address exists. At the deadline it returns whatever `HotspotAddressPolicy.select` gives: first usable IPv4, else first IPv6 link-local, scoped to the interface (P2P:819-843; HotspotAddressPolicy.kt:19-30).
- Fallback: `requestConnectionInfo().groupOwnerAddress` (P2P:793-809).
- The code does **not** hard-code `192.168.49.1`; the address is whatever Android assigned.

Other values reported by the group:

- `bssid` = the P2P interface hardware MAC, lowercase with colons, else `group.owner.deviceAddress` (P2P:682-683, 893-897).
- `channel` = `wifiFrequencyMhzToChannel(group.frequency)` (`WirelessHotspotManager.kt:73-82`).
- `security`: below API 36 always `WPA_WPA2` (2). On API 36+ it comes from `group.securityType`, with UNKNOWN treated as WPA2 (P2P:905-925).

The controller re-samples the interface for its candidate list (CTL:1503-1515, 3393-3402):

- `HotspotAddressPolicy.select(candidates, preferredFamily)` with no preference chooses IPv4 first.
- If the remembered family is IPv6, the run waits up to `HOTSPOT_READY_MILLIS = 15 000` (or 1 000 ms for a guess) for a scoped link-local (HotspotAddressRecovery.kt:15-44).

### 7.5 What is sent to the iPhone (over iAP2 on RFCOMM, and again on the tunnel)

- **`0x5703 AccessoryWiFiConfiguration`** (`iap2/message/Iap2Messages.kt:155-167`):
  - param 0 BSSID: optional, not set for P2P;
  - 1 SSID (string);
  - 2 passphrase (string);
  - 3 securityType (u8);
  - 4 channel (u8).
- **`0x4301 CarPlayStartSession`** (Iap2Messages.kt:55-95; WCC:272-285):
  - group 1 { 0 ssid, 1 passphrase, 2 channel u8, 3 ipAddress string (repeated), 4 securityType u8 };
  - 2 airPlayPort u32;
  - 3 deviceIdentifier (P2P MAC, lowercase);
  - 4 publicKey (Ed25519 hex);
  - 5 sourceVersion `"950.7.1"`.
  - The IP list is `[hostAddress] + other hostAddresses` as text without `%scope` (CTL:1753-1755, 3404-3410). For Wi-Fi Direct this is one address.
- Bluetooth identification also carries `Iap2WirelessIdentification(hostBluetoothMac, ssid)` (CTL:1744-1752).

---

## 8. Golden vectors from the tests

- **Bonjour TXT** (CarPlayBonjourTest.kt:26-55). Input: deviceId `02:00:00:00:00:02`, sourceVersion `366.0`, model `LIVI`, pairingId `pairing-1`, publicKey `01 23 AB`. Output:
  `{deviceid: "02:00:00:00:00:02", features: "0x5653aee2,0x61", flags: "0x4", model: "LIVI", srcvers: "366.0", protovers: "1.1", pi: "pairing-1", pk: "0123ab"}`.
  `featuresTxt(0xffffffff) == "0xffffffff"` (line 65). The TXT features decode equals the `/info` `features` for audio on and off (lines 57-66).
- **Connect probe** (CarPlayBonjourTest.kt:68-84). Input: `host "fe80::1%wlan0"`, port 7000, srcvers `366.0`, deviceId `02:00:00:00:00:02`. Output:
  `"GET /ctrl-int/1/connect HTTP/1.1\r\nHost: [fe80::1]:7000\r\nUser-Agent: AirPlay/366.0\r\nAirPlay-Receiver-Device-ID: 020000000002\r\nConnection: close\r\n\r\n"`.
- **Probe status line** (CarPlayBonjourProbeLineTest.kt:67-72): `"HTTP/1.1 200 OK\r\n…"` → `"HTTP/1.1 200 OK"`.
- **/info** (AirPlayInfoPlistTest.kt):
  - 1280×720 default: viewArea 1280×720 at 0,0; safeArea 1280×720 with `drawUIOutsideSafeArea true`; `initialViewArea 0`; `primaryInputDevice 1`; no `cornerMasks` (31-56).
  - All four appearance keys are 0 on both displays (12-28).
  - `fps 37` gives `maxFPS 35`. `widthPhysicalMm 125` on 1280×720 gives `heightPhysical 70` (73-99).
  - Safe insets (t10, b15, l20, r25) on 960×540 give safe 915×515 at (20,10) (125-149).
  - With mic, the `audioInputFormats` of default and telephony are `0x70004154`. Without Opus they are `0x4154` (203-253).
  - Audio types `{100, 101, 102}` (275-290).
  - `callAtCallRate`: telephony out and in `0x154`. Default telephony out is `0x70004154` (448-473). This is at the 48 kHz-only default rate.
  - `speechWithout8And16Khz` clears bits `0x3C` on 100/default, 100/alert and 101/default only (425-445).
  - `viewAreaCommand(1, 2)` = `{type "updateViewArea", params {uuid MAIN_UUID, viewAreaIndex 1, animationDurationMillis 300, adjacentViewAreas [0]}}`. `(2, 4)` gives adjacent `[0, 1, 3]` (340-349).
- **bplist decode** (BplistCodecDateTest.kt:11-22): a Python-plistlib hex for `{item:{Content-Location:"https://example.com/v.mp4", Start-Date:<date>}, type:"insertPlayQueueItem"}` decodes to `Date(1790769600000)`.
- **bplist hostile input** (BplistCodecHostileInputTest.kt): every case is rejected — self-referencing dict or array, oversized counts, wide int `0x1F`, wide UID `0x8F`, a non-string key, and a 100k+ read blow-up. A 500 000-byte data object inside a dict still decodes (135-141).
- **RTSP framing** (ControlReceiveBoundsTest.kt:21-81):
  - Content-Length 1.5e9 is rejected.
  - A header without a terminator after 64 KiB is rejected.
  - Two real-sized messages parse, with `rest = "GET /in"`.
  - The connection closes on oversize, and a real request is answered with `"RTSP/1.0 "`.
- **Event command trace** (AirPlayTouchTraceCostTest.kt:33) starts with `"POST /command RTSP/1.0\r\n"`.
- **Not read for vectors:** `AirPlayCryptoChachaTest`, `Tlv8CodecTest` and `VideoInCarTest` were not mined. They may hold ChaCha, TLV8 or HKDF vectors.

---

## 9. Bugs and oddities (summary)

1. Negative bplist ints are encoded as reals (`speechMode -1` in `/info`). Unsigned 64-bit values are encoded as 8-byte signed ints (§3). The iPhone accepts both in this shipping code.
2. Unknown status codes print "OK" (RM:96). Responses never carry `Server`, `Public` or `Session` headers.
3. `OPTIONS`, `GET_PARAMETER` and any unknown route get an empty 200 (AS:637).
4. Control and event key "read/write" labels are mirrored (PV:96-97 vs AS:904-915). This is deliberate or Apple-defined; it is not a bug.
5. `notifySetupResponseSent` fires after every flush (AS:541).
6. The cipher is chosen per TCP read, so a theoretical pipelining edge exists at the pair-verify switch (§2.3).
7. On Wi-Fi Direct, the `deviceid`, `deviceID`, probe header, JmDNS host name and StartSession `deviceIdentifier` all use the **P2P interface MAC in lowercase**. Elsewhere the code uses an uppercase SHA-derived ID. The MAC may change each session; CarLink logs a hash of it to check exactly that (CTL:1570-1572).
8. Wi-Fi Direct: mDNS is published on IPv6 link-local, but the TCP listener is IPv4-only (§6).
9. The controller never reacts to the `/ctrl-int/1/connect` probe result (CTL:1647-1657). The probe is fire-and-forget, with 7 tries spaced 1.5 s apart.
10. The host, not the controller, reconnects after the session ends, by rebuilding the whole stack (§1.6).
11. `TEARDOWN` without `streams` tears down all media but leaves the TCP control connection open (AS:819-826).
