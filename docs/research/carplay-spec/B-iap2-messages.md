# Spec B: iAP2 control-session messages for wireless CarPlay (taken from DiPlay source)

Source root: `/Volumes/My files/pocof1/carlink-src/vendor/diplay`. Every claim below comes from source code or tests in that tree.
I did not use anything under `docs/research` or `notes/`.

Short path prefixes used in citations:

| Prefix | Full path |
|---|---|
| `wire/` | `shared/src/main/java/com/shilapi/xcertplay/iap2/wire/` |
| `body/` | `shared/src/main/java/com/shilapi/xcertplay/iap2/body/` |
| `msg/` | `shared/src/main/java/com/shilapi/xcertplay/iap2/message/Iap2Messages.kt` |
| `cat/` | `shared/src/main/java/com/shilapi/xcertplay/iap2/catalog/Iap2Endpoint.kt` |
| `sess/` | `shared/src/main/java/com/shilapi/xcertplay/iap2/session/Iap2Session.kt` |
| `ident/` | `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2IdentificationClient.kt` |
| `wctl/` | `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2WirelessControlClient.kt` |
| `mfiC/` | `shared/src/main/java/com/shilapi/xcertplay/mfi/Iap2MfiAuthenticationClient.kt` |
| `mfiL/` | `shared/src/main/java/com/shilapi/xcertplay/mfi/LocalMfiAuthenticationClient.kt` |
| `mfiI/` | `shared/src/main/java/com/shilapi/xcertplay/mfi/MfiAuthenticationClient.kt` |
| `ctl/` | `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt` |
| `host/` | `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` |
| `test/` | `shared/src/test/java/com/shilapi/xcertplay/` |

---

## 0. Where the control session travels

- CSM bytes go on iAP2 **link session id 10** (`CONTROL_SESSION_ID = 10`, `transport/Iap2LinkEngine.kt:660`). The EA session is 11 and file transfer is 12 (`:661-662`).
- The link advertises session descriptors `(10, type 0, version controlSessionVersion)`, `(11, type 2, version 1)` and `(12, type 1, version 2)` (`Iap2LinkEngine.kt:609-611`). `controlSessionVersion = 2` for both the wired and wireless configs (`transport/Iap2LinkChannel.kt:361-369`).
- Bluetooth bootstrap: `Iap2Session.openWireless` uses `WIRELESS_LINK_CONFIG` with `initiateNegotiation = false` ("LIVI sends the iAP2 marker but lets the phone initiate synchronization, and keeps acknowledgements enabled for Bluetooth", `Iap2LinkChannel.kt:379-388`).
- Wi-Fi runtime: the iAP2 link inside the AirPlay type-130 tunnel uses `openTunnel`. That is `WIRED_LINK_CONFIG`, the accessory initiates, and zero-acknowledgement mode is on (`Iap2LinkChannel.kt:390-399`, `ctl/CarPlayController.kt:1895`).
- A complete CSM frame is split into link payloads no larger than the peer's advertised control payload limit (`transport/Iap2CsmChannel.kt:70-72`, `wire/Iap2CsmFramer.kt:137-153`). On receive, the framer reassembles frames across link packets.
- The link layer itself (0xFF5A packets, SYN, ACK, checksums) is **out of scope** for this spec.

---

## 1. Framing

### 1.1 CSM frame (message)

From `wire/Iap2CsmFramer.kt:6` ("`0x4040 | u16 length | u16 messageId | body`, big-endian and without a checksum"), `:98-121`:

```
offset 0  u16 BE  0x4040            start of message (START = 0x4040, :98; bytes 0x40 0x40, :106-107)
offset 2  u16 BE  length            = 6 + body length, i.e. the WHOLE frame including this header (:117 writeU16(frame, 2, frame.size))
offset 4  u16 BE  messageId         (:118)
offset 6  ...     body              a sequence of parameters (§1.2), may be empty
```

- `HEADER_BYTES = 6`. The maximum frame is `0xFFFF` bytes, so the body can be at most 65529 bytes (`:99-102`).
- There is **no checksum** at the CSM level (`:6`).
- An empty body is valid. For example, StartIdentification `0x1D00` arrives as `40 40 00 06 1D 00` (the test harness uses an empty `0x1d00` body, `test/transport/Iap2ControlClientTestHarness.kt:20`).
- How the receiver resyncs (`:35-52`): it discards one byte at a time until it sees `0x40 0x40`. If the length is below 6, it discards one byte and searches again. It waits until `length` bytes are buffered.

### 1.2 Parameter

From `wire/Iap2Wire.kt:6` and `wire/Iap2CsmFramer.kt:123-134`:

```
offset 0  u16 BE  paramLength       = 4 + payload length (counts its own 4-byte header; :130 writeU16(parameter, 0, parameter.size))
offset 2  u16 BE  paramId           16-bit id (:131)
offset 4  ...     payload
```

- `Iap2Parameter.HEADER_BYTES = 4` (`Iap2Wire.kt:34`).
- The parser rejects `length < 4`, a length beyond the buffer, and a truncated header (`Iap2Wire.kt:94-115`).
- Parameters keep their wire order. **Repeated ids are allowed and preserved** (`Iap2Wire.kt:38-43`).

### 1.3 Value encodings (`wire/Iap2WireCodec.kt`)

All integers are big-endian (`:5-6`).

| Type | Encoding | Line |
|---|---|---|
| none / "void" (presence flag) | zero-length payload, so the parameter is exactly 4 bytes: `00 04 ii ii` | `:13` |
| u8 / i8 | 1 byte | `:15-23` |
| bool | u8 `0x00` or `0x01`. When reading, the generic reader treats any non-zero as true (`body/Iap2BodyReader.kt:61`). | `:25` |
| u16 / i16 | 2 bytes BE | `:27-35` |
| u32 / i32 | 4 bytes BE | `:37-49` |
| u64 | 8 bytes BE (non-negative only in this API) | `:51-63` |
| blob / bytes | raw bytes, copied | `:65` |
| string | **UTF-8 + one terminating NUL (0x00)**. An embedded NUL is rejected. The reader requires a trailing NUL. | `:67-70`, `:124-129` |
| string list (`strings`) | concatenation of NUL-terminated strings **inside ONE parameter** | `:72-76` |
| u16 list (`u16List`) | concatenated BE u16s, no count prefix | `:78-87` |
| group | payload is itself a parameter list (§1.2), built recursively. Child ids are numbered independently from 0 inside each group. | `body/Iap2BodyBuilder.kt:71-81` |

- An empty string is encoded as the single byte `00`. Test `openNetworkEncodesAnEmptyCredentialInBothBootstrapMessages` checks this (`test/transport/Iap2WirelessControlClientTest.kt:164-174`).
- Optional parameters are **omitted entirely** when absent. They are never zero-filled (`Iap2WireCodec.kt:8-10`, `Iap2BodyBuilder.kt:83-111`).

---

## 2. Message ids used on the wireless path

The ids come from `cat/Iap2Endpoint.kt` (catalog) and are duplicated as constants in the clients.

| Id | Name | Dir | Catalog line | Client constant |
|---|---|---|---|---|
| 0x1D00 | StartIdentification | iPhone→acc | `cat:70-75` | `ident:232` |
| 0x1D01 | IdentificationInformation | acc→iPhone | `cat:76-153` | `ident:233` |
| 0x1D02 | IdentificationAccepted | iPhone→acc | `cat:154-159` | `ident:234` |
| 0x1D03 | IdentificationRejected | iPhone→acc (catalog says BIDIRECTIONAL) | `cat:160-165` | `ident:235` |
| 0xAA00 | RequestAuthenticationCertificate | iPhone→acc | `cat:179-184` | `mfiC:104` |
| 0xAA01 | AuthenticationCertificate | acc→iPhone | `cat:185-191` | `mfiC:105` |
| 0xAA02 | RequestAuthenticationChallengeResponse | iPhone→acc | `cat:192-198` | `mfiC:106` |
| 0xAA03 | AuthenticationResponse | acc→iPhone | `cat:199-205` | `mfiC:107` |
| 0xAA04 | AuthenticationFailed | iPhone→acc | `cat:206-211` | `mfiC:108` |
| 0xAA05 | AuthenticationSucceeded | iPhone→acc | `cat:212-217` | `mfiC:109` |
| 0x5000 | StartNowPlayingUpdates | acc→iPhone | `cat:447-452` | |
| 0x5200 | StartRouteGuidanceUpdates | acc→iPhone | `cat:435-440` | |
| 0xAE00 | StartPowerUpdates | acc→iPhone | `cat:415` | |
| 0x4157 | StartCommunicationsUpdates | acc→iPhone | `cat:459-464` | |
| 0x4154 | StartCallStateUpdates | acc→iPhone | `cat:465-470` | |
| 0x5702 | RequestAccessoryWiFiConfigurationInformation | iPhone→acc | `cat:369-374` | `wctl:238` |
| 0x5703 | AccessoryWiFiConfigurationInformation | acc→iPhone | `cat:375-387` | `wctl:239` |
| 0x4300 | CarPlayAvailability | iPhone→acc | `cat:262-293` | `wctl:240` |
| 0x4301 | CarPlayStartSession | acc→iPhone | `cat:294-337` | `wctl:241` |
| 0x4E0D | WirelessCarPlayUpdate | iPhone→acc | `cat:516-522` | `wctl:242` |
| 0x4E0E | DeviceTransportIdentifierNotification | iPhone→acc | `cat:523-532` | `wctl:243` |
| 0x5700 / 0x5701 | RequestWiFiInformation / WiFiInformation | registered (`cat:351-368`) | | **never sent or handled on the wireless path** |
| 0xA100 / 0xA101 / 0xA102 | Start / Update / Stop VehicleStatus | runtime tunnel only | `cat:628-630` | `transport/Iap2VehicleStatus.kt:47-49` |
| 0xFFFA / 0xFFFB / 0xFFFC | Start / LocationInformation / Stop | runtime tunnel only | `cat:395-413` | `transport/Iap2LocationClient.kt:222-224` |

---

## 3. Full wireless sequence

The same `Iap2WirelessControlClient.run` sequence runs **twice** per wireless connection (`ctl:1744-1802`, `ctl:1888-1957`):

1. **Bluetooth RFCOMM bootstrap** (`Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP`, `ident:110-116`). The identification has location and vehicle status forced off (`ident:123-136`). The control-loop timeout is `controlLoopTimeoutMillis()` (`ctl:1789`).
2. **Wi-Fi runtime tunnel** (AirPlay type-130 stream, `RUNTIME_TUNNEL`). This run is identified again with the configured location and vehicle flags, authenticated again, and given `NO_TIMEOUT_MILLIS` (`ctl:1915-1929`).

Both runs use the same `Iap2WirelessCarPlayEndpoint` (`ctl:1756-1769`, `wirelessAirPlayEndpoint`).

Order inside `run` (`wctl:23-235`):

```
iPhone                                   Accessory (Android)
  |  [link ready on session 10]              |
  |-- 0x1D00 StartIdentification --------->  |
  |  <------- 0x1D01 IdentificationInformation|
  |-- 0x1D02 IdentificationAccepted ------->  |   (or 0x1D03 → at most one corrected 0x1D01 resend, §3.2)
  |-- 0xAA00 RequestAuthCertificate ------->  |
  |  <------- 0xAA01 AuthenticationCertificate|
  |-- 0xAA02 RequestChallengeResponse ----->  |
  |  <------- 0xAA03 AuthenticationResponse   |
  |-- 0xAA05 AuthenticationSucceeded ------>  |   (0xAA04 → failure)
  |  <------- 0x5000 StartNowPlayingUpdates   |  ┐
  |  <------- 0x5200 StartRouteGuidanceUpdates|  │ five subscriptions, unsolicited,
  |  <------- 0xAE00 StartPowerUpdates        |  │ sent back to back (wctl:58-60)
  |  <------- 0x4157 StartCommunicationsUpd.  |  │
  |  <------- 0x4154 StartCallStateUpdates    |  ┘
  |   — event loop (any order) —              |
  |-- 0x5702 RequestAccWiFiConfig --------->  |
  |  <------- 0x5703 AccessoryWiFiConfig      |
  |-- 0x4300 CarPlayAvailability ---------->  |
  |  <------- 0x4301 CarPlayStartSession      |
  |-- 0x4E0D WirelessCarPlayUpdate -------->  |   (logged only)
  |-- 0x4E0E DeviceTransportIdentifier ---->  |
  |  <------- 0x5703 (re-sent, unsolicited)   |
  |-- anything else → forwarded to onIncoming (route guidance, now playing, etc.)
```

- **No PowerSourceUpdate (0xAE03) is sent on the wireless path.** The wired client sends it before the subscriptions (`transport/Iap2WiredControlClient.kt:54-55`). The wireless client does not (`wctl:58-60`), and wireless identification removes 0xAE03 from the sent list (`ident:333`).
- The accessory never sends 0x5703 before it receives 0x5702 or 0x4E0E (`wctl:127-214`).

### 3.1 StartIdentification (0x1D00), iPhone→acc

- The body is ignored. Any 0x1D00, including a repeat, causes a fresh 0x1D01 built with the current omission set (`ident:218`).
- Test `aRestartedIdentificationKeepsTheCorrection` checks this (`test/transport/Iap2IdentificationRetryTest.kt:85-91`).

### 3.2 IdentificationInformation (0x1D01), acc→iPhone

Builder: `ident:302-403`. Parameters are emitted in this order (wire order is exactly the order below). Catalog names come from `cat:81-152`.

| Param id | Catalog name | Type | Value DiPlay sends (wireless) | Source |
|---|---|---|---|---|
| 0 | name | string | `config.name` = `CarPlayHostIntegration.accessoryName`, default `"DiPlay"` (CarLink hook) | `ident:322`, `host:203`, `common/.../CarPlayHostIntegration.kt:183` |
| 1 | modelIdentifier | string | `normalizedModel()`: user setting, default `"DiPlay"` | `ident:323`, `host:204`, `host:5393-5394`, `AirPlayPersistence.kt:147` |
| 2 | manufacturer | string | `normalizedManufacturer()`, default `"DiPlay"` | `ident:324`, `host:205`, `AirPlayPersistence.kt:146` |
| 3 | serialNumber | string | `"DIPLAY-"` + deviceId without colons. deviceId = first 6 bytes of SHA-256(Ed25519 AirPlay public key), with byte0 = `(b0 & 0xFC) \| 0x02`, written as uppercase hex. Result: `"DIPLAY-XXXXXXXXXXXX"`. | `ident:325`, `host:206`, `common/.../DiPlayBootstrap.kt:45-49` |
| 4 | firmwareVersion | string | `"0.1.0"` | `ident:326`, `host:207` |
| 5 | hardwareVersion | string | `"1.0"` | `ident:327`, `host:208` |
| 6 | MessagesSentByAccessory | u16 list (one param) | see list A | `ident:328-336` |
| 7 | MessagesReceivedFromDevice | u16 list (one param) | see list B | `ident:337-344` |
| 8 | powerProvidingCapability | u8 | **0** for wireless (2 for wired) | `ident:345` |
| 9 | maximumCurrentDrawnFromDevice | u16 | **20** (always, wired and wireless) | `ident:346` |
| 10 | externalAccessoryProtocols | group | child 0 = u8 **1**; child 1 = string **`"com.shilapi.xcertplay"`**; child 2 = u8 **0**. The catalog has no child schema, so the source gives no names for these children. The source comment calls it "This app's own EA protocol identifier; it deliberately does not claim a CarPlay EA flag". | `ident:347-351`, `ident:49-50` |
| 12 | currentLanguage | string | `"en"` | `ident:352`, `ident:48` |
| 13 | supportedLanguages | string list | `"en\0"`: ONE parameter, one string | `ident:353` |
| 17 | BluetoothTransport | group | see below | `ident:363-370` |
| 24 | WirelessCarPlayTransport | group | see below | `ident:371-378` |
| 20 | VehicleInformation | group | runtime tunnel only, when vehicle status is enabled and a battery reading exists | `ident:380`, `transport/Iap2VehicleStatus.kt:56-63` |
| 21 | VehicleStatus | group | same condition as 20 | `Iap2VehicleStatus.kt:64-73` |
| 22 | LocationInformation | group | runtime tunnel only, when location is enabled | `ident:381-389` |
| 30 | RouteGuidanceDisplayComponent | group | always sent | `ident:390-401` |

- **Not sent:** param 16 USBHostTransport (wired only, `ident:354-361`). The wired test asserts it is absent on wireless (`test/transport/Iap2WirelessControlClientTest.kt:89`).
- **Not sent:** params 11, 14, 15, 18, 19, 23 and 25+ other than 30. In particular, **no ProductPlanUID or other plan-UID parameter exists anywhere in the source.** A case-insensitive grep for `productplan`, `ppuid` and `plan uid` over all `.kt` files returns nothing.

**Group 17, BluetoothTransport** (`ident:363-370`; names from `cat:107-119`):

| Child | Name (catalog) | Type | Value |
|---|---|---|---|
| 0 | componentID | u16 | **0** |
| 1 | name | string | **`"blue"`** |
| 2 | isSupported | none | (present) |
| 3 | bluetoothMAC | blob, **6 raw bytes** | parsed from `"AA:BB:CC:DD:EE:FF"` (`ident:26-28`) |
| 4 | transportName | string | **`"blue"`** |
| 5 | isAvailable | none | (present) |

Where the MAC comes from:

- CarLink module path: the module's MAC with colons (`ctl:1586-1588`).
- Otherwise: `BluetoothAdapter.address`, then `Settings.Secure "bluetooth_address"`, skipping `02:00:00:00:00:00`, then `airPlayConfig.btMac` (`ctl:3352-3369`, `ctl:3750`).
- The test checks `aa bb cc dd ee ff` (`test/transport/Iap2WirelessControlClientTest.kt:94-97`).

**Group 24, WirelessCarPlayTransport** (`ident:371-378`; names from `cat:123-135`):

| Child | Name (catalog) | Type | Value |
|---|---|---|---|
| 0 | componentID | u16 | **1** |
| 1 | "SSID" | string | **the hotspot SSID** (`hotspotInfo.ssid`, `ctl:1744`) |
| 2 | isSupported | none | present |
| 3 | transportIdentifier | u16 | **1** |
| 4 | isAvailable | none | present |
| 5 | isEnabled | none | present |

The catalog calls child 1 "SSID". Whether the iPhone reads it as an SSID or as a component name is not stated in source; see §7.

**Group 30, RouteGuidanceDisplayComponent** (`ident:390-401`):

| Child | Name | Type | Value |
|---|---|---|---|
| 0 | Identifier | u16 | **42** |
| 1 | Name | string | **`"RouteGuidance"`** |
| 2 | MaxCurrentRoadNameLength | u16 | 64 |
| 4 | MaxAfterManeuverRoadNameLength | u16 | 64 |
| 6 | MaxGuidanceManeuverStorageCapacity | u16 | 8 |
| 7 | MaxLaneGuidanceDescriptionLength | u16 | 64 |
| 8 | MaxLaneGuidanceStorageCapacity | u16 | 8 |

**Group 22, LocationInformation** (runtime tunnel only, `ident:382-388`):

- child 0 u16 0
- child 1 string `config.name`
- child 17 none
- child 18 none
- child 20 none, only when vehicle speed is enabled

**Groups 20 and 21, EV** (runtime tunnel only, `transport/Iap2VehicleStatus.kt:56-73`):

- Group 20: id u16 3; name; engine type u8 2 (electric); display name (6); maps display name (8); one u8 (11) per charging connector.
- Group 21: id u16 4; name; voids 3, 6, 11, 15, 21, 30.

**List A, param 6 (sent by accessory), wireless bootstrap**

Base list from `ident:412-427`, with 0xAE03 removed and 0x5703 appended (`ident:333-334`). Wire order:

```
AA01 AA03 5000 5002 5200 5203 AE00 AE02 4157 4159 4154 4156 4301 5703
```

- Runtime tunnel with location: 0xFFFB is inserted after 0x4301 (before the 0xAE03 filter), so it comes before 0x5703 (`ident:304-305`).
- With vehicle status: 0xA101 is appended to the base list (`ident:315`).
- **Order note:** in the code, `+ LOCATION_INFORMATION` and `+ VEHICLE_STATUS_UPDATE` are appended to the base array, and then the wireless branch appends 0x5703 at the end.

**List B, param 7 (received from device), wireless bootstrap**

Base list from `ident:428-443` plus `WIRELESS_PHONE_MESSAGES` (`ident:453`):

```
AA00 AA02 AA04 AA05 EA00 EA01 5001 5201 5202 5204 AE01 4158 4155 4300 4E0D 4E0E 5702
```

- Runtime with location: `FFFA FFFC` are inserted after 4300 (`ident:310`).
- Runtime with vehicle status: `A100 A102` are added after those (`ident:316-319`).
- In both cases they come before `4E0D 4E0E 5702`, which are appended last (`ident:342`).

**Retry rule (IdentificationRejected 0x1D03)**, `ident:220-226`, `:292-299`. One automatic retry happens only if all of these hold:

- the rejection's parameter ids are a subset of {6, 7};
- their payloads are even-length lists of BE u16 message ids (at most 32 retained);
- none of the ids is an authentication id (0xAA00 to 0xAA05).

The accessory then resends 0x1D01 with those ids removed from **both** lists. A second rejection throws. For how rejection parameters 6 and 7 are parsed, see `ident:251-285`.

### 3.3 IdentificationAccepted (0x1D02), iPhone→acc

- The body is ignored. Identification returns (`ident:219`).
- **Any other message id during identification throws `UnexpectedMessage`** (`ident:227`).

### 3.4 Authentication (`mfiC:19-70`)

1. **Before** any frame is received, the certificate is read from the authenticator (`mfiC:28`) and the 0xAA01 frame is prebuilt (`mfiC:29-32`).
2. `0xAA00` (body ignored) → send `0xAA01` (`mfiC:45-49`).
3. `0xAA02` → read **param 0** (blob) as the challenge. The length must be 1..128 (`mfiC:51-54`, `:110`). Sign it and send `0xAA03` (`mfiC:56-58`).
4. `0xAA05` → done (`mfiC:60-63`). `0xAA04` → throws `"iPhone sent AuthenticationFailed"` (`mfiC:64`).
5. **Any other id → throws** (`mfiC:65-67`). AuthenticationCertificateSerial (0xAA06) and device authentication (0xAA1x) are never used.

Frame layouts:

- **0xAA01 AuthenticationCertificate** (MFI type): one param, id **0**, blob = certificate bytes exactly as returned by `readCertificate` (`msg:209-212`, `cat:190`).
  - BAA variant (remote service only): the body comes back from the server already encoded and is parsed only to validate it (`msg:218-221`). An alternative helper builds param 0 = leaf, param 1 = u8 1, param 2 = intermediate (`msg:228-233`). Neither applies to local MFi.
- **0xAA02 RequestAuthenticationChallengeResponse**: param **0** = challenge blob (`msg:240-245`, `cat:197`).
- **0xAA03 AuthenticationResponse**: one param, id **0**, blob = signature (`msg:235-238`, `cat:204`).

### 3.5 Subscriptions after authentication (acc→iPhone)

The wireless loop sends `Iap2WiredControlClient.subscriptions()`, which equals `Iap2ControlMessages.subscriptions()` (`wctl:58-60`, `transport/Iap2WiredControlClient.kt:175`, `msg:288-313`). Order:

| Message | Body | Source |
|---|---|---|
| 0x5000 StartNowPlayingUpdates | group 0 { none 1, 4, 6, 12, 26 }, group 1 { none 0, 1, 7 } | `msg:278-286` |
| 0x5200 StartRouteGuidanceUpdates | u16 param 0 = 42 (the route guidance component id), none 1, none 2 | `msg:290-294` |
| 0xAE00 StartPowerUpdates | none 4, none 5, none 6 | `msg:295-299` |
| 0x4157 StartCommunicationsUpdates | none 0, none 4, none 5 | `msg:300-304` |
| 0x4154 StartCallStateUpdates | none 0, 1, 2, 3, 4, 11 | `msg:305-312` |

None of these waits for a reply.

### 3.6 RequestAccessoryWiFiConfigurationInformation (0x5702) and the 0x5703 reply

- 0x5702 body: ignored.
- Reply (`wctl:127-162`, builder `wctl:262-269`, `msg:155-167`), **0x5703 AccessoryWiFiConfigurationInformation**, params in this order:

| Param | Name (`cat:380-386`) | Type | Value |
|---|---|---|---|
| 0 | BSSID | blob, 6 bytes, **optional** | `hotspotInfo.accessPointBssid`, sent only when non-null (`ctl:1766`, `wctl:317`, `wctl:320`). The source says this is "Wi-Fi AP hint for 0x5703; independent of the receiver's AirPlay identity" (`network/WirelessHotspotManager.kt:28`). |
| 1 | SSID | string | hotspot SSID |
| 2 | passphrase | string | hotspot passphrase. `""` encodes as `00` for open networks. |
| 3 | securityType | u8 | enum `Iap2WirelessSecurity` (`wctl:295-301`): NONE=0, WEP=1, WPA_WPA2=2, WPA3_TRANSITION=3, WPA3_ONLY=4 |
| 4 | channel | u8 | `hotspotInfo.channel` (0..255) |

Send caps:

- At most **5** replies before 0x4E0E is seen (`wctl:247`).
- At most **2** after 0x4E0E, shared with the automatic resend on 0x4E0E (`wctl:248`).
- Requests beyond a cap are ignored with a log line (`wctl:140-143`).

### 3.7 CarPlayAvailability (0x4300), iPhone→acc → CarPlayStartSession (0x4301)

Decoded for diagnostics only (`wctl:164-166`, decoder `msg:102-131`, schema `cat:267-292`):

- group 0 "wired" { u8 0 wiredAvailable (0/1), string 1 usbIdentifier }
- group 1 "wireless" { u8 0 wirelessAvailable (0/1), string 1 bluetoothIdentifier }
- group 2 "themeAssets" { u8 0 }

**Any 0x4300 triggers a 0x4301**, whatever the availability flags say. The cap is **3** StartSession sends (`wctl:167-175`, `:249`).

**0x4301 CarPlayStartSession, wireless form** (`wctl:272-285`, builder `msg:55-100`, schema `cat:299-336`). Wire order:

| Param | Name | Type | Value DiPlay sends |
|---|---|---|---|
| 0 | wired group | group | **omitted** on wireless (sent only when there are wired IPv6 addresses or a reserved value, `msg:72-77`) |
| 1 | wireless group | group | children below |
| 1.0 | SSID | string | hotspot SSID |
| 1.1 | passphrase | string | hotspot passphrase (`""` gives `00`) |
| 1.2 | channel | u8 | hotspot channel |
| 1.3 | wirelessIP | string, **repeated once per address** | the address list. The published host address comes first, then the other interface addresses, deduplicated (`ctl:1753-1755`). The text is `InetAddress.hostAddress` with any `%scope` suffix removed (`ctl:3404-3410`). IPv4 dotted quad or IPv6 text. On Wi-Fi Direct the list holds one address (the P2P backend passes no other `hostAddresses`, WifiP2pGroupManager.kt:676-688). |
| 1.4 | securityType | u8 | same enum as 0x5703 |
| 2 | airPlayPort | **u32** | the bound AirPlay listener port (`ctl:1626`, `ctl:1762`) |
| 3 | deviceIdentifier | string | `hotspotInfo.bssid`, unless it is null or `02:00:00:00:00:00`, in which case `airPlayConfig.deviceId` is used (`ctl:1539-1541`). For Wi-Fi P2P this is the P2P interface MAC as **lowercase** `aa:bb:..` (`network/WifiP2pGroupManager.kt:893-897`). The fallback deviceId is **uppercase** `XX:XX:..` (`DiPlayBootstrap.kt:45-49`). |
| 4 | publicKey | string | `identity.publicKeyHex`: the 32-byte **Ed25519** AirPlay public key as **lowercase ASCII hex** (64 characters) + NUL. It is **not** raw bytes. (`ctl:1764`, `airplay/AirPlayIdentity.kt:5-11`, `airplay/Bytes.kt:15-23`) |
| 5 | sourceVersion | string | `"950.7.1"` (`host:5195`, `ctl:1765`) |
| 6 | SDKVersion | string | **not sent** (null) |
| 7 | clusterAsset group | group | **not sent** |
| 8 | mutualAuth | u8 | **not sent** |

The 0x4301 frame carries **no BSSID**. Test `apHintIsEncodedOnlyInWifiConfigurationAndDoesNotChangeReceiverStartIdentity` checks this (`test/transport/Iap2WirelessControlClientTest.kt:142-151`).

### 3.8 Transport notifications (iPhone→acc)

- **0x4E0D WirelessCarPlayUpdate**: param 0 u8, which must be 0 or 1 or decoding throws (`msg:184-193`, `cat:521`). The accessory only logs it and sets a flag (`wctl:178-182`). No reply.
- **0x4E0E DeviceTransportIdentifierNotification**: param 0 string bluetoothMAC (optional), param 1 string usbTransportIdentifier (optional) (`msg:195-204`, `cat:528-531`). The accessory marks "transport seen" and **re-sends 0x5703 unsolicited**, within the post-transport cap of 2 (`wctl:184-214`).

### 3.9 Other messages in the loop

- 0xA100 and 0xA102 go to the vehicle-status reporter. 0xFFFA and 0xFFFC go to the location reporter (`wctl:216-223`). On the bootstrap link these are not declared, so the phone should not send them.
- Every other frame is forwarded to `onIncoming` (`onRouteFrame`) (`wctl:225-229`).

---

## 4. MFi details

### 4.1 Local (offline) authenticator: `mfiL`

**Files.** `identity.pk8` and `certificate.p7b` are read from directory `offline-mfi` (`mfiL:45`, `:50`, `:56`), each at most 16 KiB (`:46`, `:78-88`).

- On first run the app copies them from APK assets `offline-mfi/<name>` into app-private storage, then loads them (`common/.../DiPlayBootstrap.kt:24-39`).
- CarLink keeps the real files outside the repo, in `~/.carlink-private/offline-mfi/`.

**identity.pk8.** PKCS#8 DER bytes passed to `KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(...))` (`mfiL:50-55`). The buffer is zeroed after use.

**certificate.p7b, as sent on the wire.**

- The **file bytes are sent verbatim** in 0xAA01 param 0. `readCertificate` returns `certificate.copyOf()` (`mfiL:25-30`).
- No DER extraction happens. The file is parsed only for validation: `CertificateFactory("X.509").generateCertificates(...)` must yield exactly one certificate (`:57-59`), with a P-256 EC public key (curve order check, `:60-65`).
- `generateCertificates` accepts either a PKCS#7 bundle or a bare DER X.509 certificate, so the code works with both.
- **Which one the iPhone receives depends on the file.** I checked the structure of the deployed CarLink file (`~/.carlink-private/offline-mfi/certificate.p7b`, 607 bytes) with `openssl asn1parse`. It starts `SEQUENCE { OID pkcs7-signedData, [0] { SEQUENCE { INTEGER 1, SET {}, SEQUENCE { OID pkcs7-data ...`. That is a **DER PKCS#7 SignedData (certs-only) bundle**, so the raw PKCS#7 is what goes on the wire.
- The unit test writes a bare DER X.509 certificate under the `.p7b` name (`test/mfi/LocalMfiAuthenticationClientTest.kt:48-52`), so the tests do not pin the PKCS#7 form.

**What is signed.**

- `signChallenge` **requires exactly 32 bytes** (`mfiL:33`). It signs them with `Signature.getInstance("NONEwithECDSA")` (`:35-37`), which means **no hashing**. The comment says: "The iAP2/AirPlay caller supplies a digest already. Do not hash it a second time." (`:34`)
- So the iAP2 0xAA02 challenge (param 0) is used **as is**, treated as the 32-byte digest. There is no pre-hash.
- The test signs SHA-256 digests and verifies with NONEwithECDSA (`test/mfi/LocalMfiAuthenticationClientTest.kt:61-77`).

**Signature output.**

- The DER ECDSA signature is converted to **raw r‖s, 64 bytes**: each value is left-padded to 32 bytes, and a leading 0x00 is stripped from 33-byte integers (`mfiL:38`, `:90-103`).
- The test asserts `signature.size == 64` (`test/mfi/LocalMfiAuthenticationClientTest.kt:64`, `:108`).
- That 64-byte value is param 0 of 0xAA03.

**Self-check at load.** It signs a random 32-byte value and verifies it against the certificate's public key, failing if they do not match (`mfiL:66-74`).

**Auth protocol version.**

- `protocolMajor()` returns a hard-coded **3** (`mfiL:23`).
- The iAP2 exchange does **not** use `protocolMajor` at all. It is used only by AirPlay MFi-SAP, which pre-hashes SHA-1 for major 2 and SHA-256 otherwise before calling `signChallenge` (`airplay/MfiSapAuthSetup.kt:39-43`).
- No protocol version is sent in any iAP2 message.

### 4.2 Hardware coprocessor (`mfiI`), for reference

The I2C chip path:

- The certificate length is read from register 0x30 and the data from 0x31 onward, in 128-byte windows (`mfiI:60-82`, `:234-236`).
- To sign: write the challenge length (u16 BE) to 0x20, the challenge to 0x21, and `0x01` to 0x10. Poll 0x10 until it reads `0x10` (3 s timeout). Read the response length from 0x11 and the signature from 0x12 (`mfiI:90-120`, `:229-245`).
- The challenge may be 1..128 bytes. This path returns the chip's signature unchanged, with no DER or raw conversion in code.

---

## 5. Replies and timeouts

### 5.1 Request → response table

| Received | Accessory reply | Condition / cap |
|---|---|---|
| 0x1D00 | 0x1D01 | always, including repeats (`ident:218`) |
| 0x1D03 | 0x1D01 without the rejected ids | once, only if correctable (`ident:220-226`) |
| 0x1D02 | — | ends identification |
| 0xAA00 | 0xAA01 | every time (`mfiC:45-49`) |
| 0xAA02 | 0xAA03 | every time (`mfiC:50-59`) |
| 0xAA05 / 0xAA04 | — | success / exception |
| (after 0xAA05) | 0x5000, 0x5200, 0xAE00, 0x4157, 0x4154 | unsolicited, once per run |
| 0x5702 | 0x5703 | up to 5 before 0x4E0E, up to 2 after |
| 0x4300 | 0x4301 | up to 3 per run |
| 0x4E0E | 0x5703 | unsolicited resend, counts toward the post-transport cap of 2 |
| 0x4E0D | — | log only |

### 5.2 Timeouts

| What | Value | Source |
|---|---|---|
| RFCOMM connect | 15 000 ms (phone socket) | `ctl:1686`, `ctl:3742` |
| Bootstrap control-loop deadline | 5 min (`CONTROL_LOOP_TIMEOUT_MILLIS`). 24 h if location reporting is on. It can be extended indefinitely while `keepBluetoothControlAlive` is true, i.e. a rendered frame and an active session exist. | `ctl:3425-3431`, `ctl:3736-3737`, `ctl:2103-2107`, `wctl:379-399` |
| Runtime tunnel loop | none (`NO_TIMEOUT_MILLIS`) | `ctl:1921` |
| Identification | `deadline.requireRemaining()`: the rest of the control deadline, capped at 5 min (`MAX_POLL_MILLIS`). Inside `identify`, the timeout must be ≤ 5 min. It covers link-ready wait, 0x1D00 wait, send, and accept. | `wctl:50`, `wctl:407`, `ident:205-216`, `ident:238` |
| MFi exchange | the rest of the control deadline, capped at 5 min. One deadline covers all of 0xAA00 → 0xAA05. | `wctl:54`, `mfiC:24-27`, `:113` |
| Each subscription and each control send | the remaining deadline, ≤ 5 min | `wctl:59`, `wctl:65-67` |
| Default send timeout, when none is given | 5 000 ms | `sess:117`, `transport/Iap2CsmChannel.kt:222` |
| Unused defaults (overridden on the wireless path) | identify 10 s (`ident:237`); MFi 30 s (`mfiC:112`); wireless control 60 s (`wctl:245`) | |

---

## 6. Golden vectors

### 6.1 Literal vectors from tests

**V1. `Iap2WirelessControlClientTest.accessoryWiFiConfigurationMatchesLiviVector`** (`test/transport/Iap2WirelessControlClientTest.kt:49-57`, hex at `:54`).

Endpoint (`:261-272`): ssid "LIVI", passphrase "secret123", channel 36, WPA3_TRANSITION (3), no BSSID.

```
404000275703000900014c49564900000e00027365637265743132330000050003030005000424
```

Decoded (I verified it with a Python re-encoder):

```
4040 0027 5703                      start, len=39, id=0x5703
0009 0001 4c49564900                param 1 SSID "LIVI\0"
000e 0002 73656372657431323300      param 2 passphrase "secret123\0"
0005 0003 03                        param 3 securityType 3
0005 0004 24                        param 4 channel 36
```

**V2. `Iap2WirelessControlClientTest.carPlayStartSessionMatchesLiviWirelessVector`** (`:59-69`, hex at `:64-66`).

Endpoint: ips ["192.168.2.1"], port 49152, deviceIdentifier "dev-1", publicKey "aabbcc", sourceVersion "1.0".

The test splits the string into three parts that are joined with no separator:

```
40400060430100350001000900004c49564900000e000173656372657431323300000500022400100003
3139322e3136382e322e31000005000403000800020000c000000a00036465762d3100000b000461
61626263630000080005312e3000
```

Decoded:

```
4040 0060 4301                      len=96
0035 0001                           group 1 wireless (53 bytes)
   0009 0000 4c49564900             SSID "LIVI"
   000e 0001 73656372657431323300   passphrase "secret123"
   0005 0002 24                     channel 36
   0010 0003 3139322e3136382e322e3100  wirelessIP "192.168.2.1"
   0005 0004 03                     securityType 3
0008 0002 0000c000                  airPlayPort u32 49152
000a 0003 6465762d3100              deviceIdentifier "dev-1"
000b 0004 61616262636300            publicKey "aabbcc" (ASCII hex string + NUL)
0008 0005 312e3000                  sourceVersion "1.0"
```

**V3. `Iap2RouteGuidanceSubscriptionTest.startRouteGuidanceUpdatesRequestsDeclaredComponent`**: 0x5200 body.

Source: `test/transport/Iap2RouteGuidanceSubscriptionTest.kt:50-58`, bytes at `:56`.

```
body = 00 06 00 00 00 2a | 00 04 00 01 | 00 04 00 02
       (u16 param0 = 42)   (none 1)      (none 2)
```

The full frame would be `40400014 5200` + body (derived).

**V4. `Iap2IdentificationRejectionTest.protocolFixtureRetainsUnsupportedMessagesInsteadOfOnlyParameterIds`**: 0x1D03 body.

Source: `test/transport/Iap2IdentificationRejectionTest.kt:13-28`, bytes at `:19`. The source calls it a "synthetic upstream vector" from carplayd.

```
00 04 00 1e | 00 06 00 06 41 5a | 00 04 00 14
(none 30)     (param 6 = [0x415A])  (none 20)
```

**V5. `Iap2IdentificationRetryTest`**: rejection from older iOS.

Source: `test/transport/Iap2IdentificationRetryTest.kt:25`. Param 6 = `43 01`, param 7 = `43 00`. Encoded body: `0006 0006 4301 0006 0007 4300`.

**V6. Test harness fake MFi** (`test/transport/Iap2ControlClientTestHarness.kt:31-36`):

- certificate `01 02 03` → 0xAA01 = `4040000d aa01 0007 0000 010203` (derived)
- signature `04 05 06` → 0xAA03 = `4040000d aa03 0007 0000 040506` (derived)

**V7. `Iap2ProtocolTest.framerAcceptsFragmentedAndConcatenatedFrames`** (`test/iap2/Iap2ProtocolTest.kt:33-41`):

- `Iap2Frame(0x4300, [00 04 00 00])` → `4040000a4300 00040000`
- `Iap2Frame(0x4301, [00 05 00 00 01])` → `4040000b4301 0005000001`

**Other test assertions with exact bytes:**

- `Iap2WirelessControlClientTest.wirelessIdentificationAdvertisesTransportComponentsWithoutUsbHost` (`:71-111`):
  - param 8 = `00`
  - group 17 child 1 = `"blue\0"`
  - child 3 = `aa bb cc dd ee ff`
  - group 24 child 1 = `"LIVI\0"`
  - 0x5703 and 0x4301 in the sent list; 0xAE03 not in it
  - 0x4300, 0x4E0D, 0x4E0E and 0x5702 in the received list
- `openNetworkEncodesAnEmptyCredentialInBothBootstrapMessages` (`:164-174`): an empty passphrase is `00`, and securityType NONE is `00`, in both 0x5703 and 0x4301.
- `startSessionEncodesMultipleIpAddresses` (`:176-194`): two repeated child-3 parameters, `"192.168.43.1"` then `"fe80::1234"`.
- `Iap2RouteGuidanceSubscriptionTest.identificationDeclaresRouteGuidanceDisplayComponent` (`:21-33`): group 30 child 0 = `00 2a`, child 6 = `00 08`, child 7 = `00 40`, child 8 = `00 08`.
- `Iap2WiredControlClientTest.powerSourceUpdateOffersTheConfiguredChargingCurrent` (`:36-43`): this is the wired path only.

### 6.2 Derived vectors (NOT DiPlay test vectors)

I computed these with a Python re-encoder that mirrors the builders. The same encoder reproduces V1, V2 and V3 byte for byte.

**D1. Wireless bootstrap 0x1D01** for the `Iap2WirelessControlClientTest.identification()` config:

- name, model and manufacturer "LIVI"; serial "0123456"; firmware "1.0.0"; hardware "1.0"
- BT MAC AA:BB:CC:DD:EE:FF, SSID "LIVI"
- language "en", EA "com.shilapi.xcertplay"

The frame is 339 bytes:

```
404001531d01000900004c49564900000900014c49564900000900024c495649
00000c00033031323334353600000a0004312e302e300000080005312e300000
200006aa01aa035000500252005203ae00ae0241574159415441564301570300
260007aa00aa02aa04aa05ea00ea015001520152025204ae014158415543004e
0d4e0e570200050008000006000900140028000a0005000001001a0001636f6d
2e7368696c6170692e7863657274706c61790000050002000007000c656e0000
07000d656e00002e001100060000000000090001626c75650000040002000a00
03aabbccddeeff00090004626c75650000040005002500180006000000010009
00014c49564900000400020006000300010004000400040005003a001e000600
00002a00120001526f75746547756964616e6365000006000200400006000400
40000600060008000600070040000600080008
```

**D2. Subscription frames** (fixed content):

```
0x5000: 4040002e5000001800000004000100040004000400060004000c0004001a00100001000400000004000100040007
0x5200: 40400014520000060000002a0004000100040002
0xAE00: 40400012ae00000400040004000500040006
0x4157: 404000124157000400000004000400040005
0x4154: 4040001e415400040000000400010004000200040003000400040004000b
```

---

## 7. Ambiguities, oddities and possible bugs in DiPlay

1. **Group 24 child 1 is the SSID.** The catalog names it "SSID" and DiPlay puts the hotspot SSID there (`ident:373`, `cat:129`). Child 3 is a u16 called "transportIdentifier" with value 1. The source does not say whether the iPhone reads child 1 as a component name or as an SSID.
2. **The Bluetooth component name is `"blue"` twice:** child 1 "name" and child 4 "transportName" (`ident:365`, `:368`). Child 4 has no other explanation in source.
3. **Component id 0 is reused.** Group 17 BT uses 0, and on the runtime tunnel group 22 Location also uses 0 (`ident:364`, `:383`). Group 24 uses 1, group 20 uses 3, group 21 uses 4 and group 30 uses 42. Whether duplicate ids across component types are allowed is not covered in source.
4. **Param 13 supportedLanguages is a single parameter holding concatenated NUL-terminated strings** (`Iap2WireCodec.kt:72-76`). With one language ("en") the result is the same as one string parameter. With more than one language, whether this layout is correct is ambiguous. DiPlay only ever sends one.
5. **Param 9 maximumCurrentDrawnFromDevice = 20** is sent even on wireless, where param 8 = 0 (`ident:345-346`).
6. **Group 10 EA children have no schema in the catalog** (`cat:92`). The values 1 / "com.shilapi.xcertplay" / 0 are given without names, and no EA session is ever opened, although 0xEA00 and 0xEA01 are declared as received.
7. **Any 0x4300 triggers 0x4301**, even if the wireless group reports `wirelessAvailable=0` (`wctl:164-176`).
8. **The 0x4301 publicKey is an ASCII hex string, not a blob**, and airPlayPort is **u32** (`msg:87-89`, `cat:321-323`). The LIVI golden vector V2 confirms both.
9. **deviceIdentifier case is inconsistent.** A P2P interface MAC is lowercase; the fallback deviceId is uppercase (`WifiP2pGroupManager.kt:897` vs `DiPlayBootstrap.kt:48`).
10. **MFi is strict.** Any frame other than 0xAA00/02/04/05 during authentication throws (`mfiC:65-67`). Identification likewise throws on anything other than 0x1D00/02/03 (`ident:227`). So an early 0x4E0D, for example, would abort the run.
11. **Challenge length handling differs by layer.** `Iap2MfiAuthenticationClient` accepts 1..128 bytes (`mfiC:52`, `:110`). `LocalMfiAuthenticationClient` throws `IllegalArgumentException` for anything other than 32 (`mfiL:33`), which is not an `Iap2MfiAuthenticationException`.
12. **The certificate format is not pinned by tests.** The test fixture uses a bare X.509 certificate named `.p7b`, while the deployed file is PKCS#7 SignedData. Both are sent verbatim.
13. **The certificate is read before 0xAA00 arrives** (`mfiC:28`). With a slow I2C chip, that time comes out of the auth deadline.
14. **0x5703 is never sent proactively.** It goes out only in reply to 0x5702 or after 0x4E0E. There is no 0x5700 RequestWiFiInformation on this path.
15. **Identification runs twice** (Bluetooth, then the Wi-Fi tunnel) with different message lists. Location and vehicle data are advertised only on the tunnel; the reason is given at `ident:118-122`. 0x4301 can be sent on **both** links if the phone sends 0x4300 on the tunnel too.
16. **Catalog direction labels.** Some labels look odd but are correct for this use: 0xA100 StartVehicleStatusUpdates is iPhone→acc and 0xA101 is acc→iPhone (`cat:628-629`). The catalog marks 0x1D03 as BIDIRECTIONAL (`cat:164`); the client only ever receives it.
17. **The wired group `reserved` (param 0.1, u32) exists in the schema** (`cat:306`). Its meaning is not explained. It is unused on wireless.
