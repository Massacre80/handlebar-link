# Honda SAB / BTU meter BLE protocol (from RoadSync 23.10.20, `com.honda.ms.dm.sab`)

Reverse-engineered from a jadx decompile for interoperability with the owner's own CB350. Everything below comes from the decompiled source unless it is marked **[INFERRED]** or **[UNKNOWN]**.

**Path conventions**
- `S/` = `scratchpad/rs_src/sources/com/drivemode/sab/`
- `B/` = `S/ble/`
- `RES` = `scratchpad/rs_res/resources/res/values/strings.xml`. This is a resource-only decode made with `jadx --no-src`.
- Line numbers are the original jadx line numbers.
- Obfuscated RxAndroidBle method names were checked against `com/polidea/rxandroidble2/RxBleConnection.java:76-92` and `internal/connection/RxBleConnectionImpl.java:240-290`:
  - `a()` = getMtu
  - `c(char, mode)` = setupNotification, which enables **notifications** (CCCD 0x0001)
  - `d(int)` = requestMtu
  - `e(char, bytes)` = writeCharacteristic, using the characteristic's default write type and returning the written bytes
  - `f()` = discoverServices (20 s timeout)
  - `g(uuid)` = readCharacteristic
  - `h(uuid, bytes)` = writeCharacteristic by UUID
  - `i()` = readRssi
  - `RxBleClient.a(ctx)` creates a **new** client instance (`RxBleClient.java:19`). `RxBleDevice.a(autoConnect)` = establishConnection.
- RxJava operators checked in `io/reactivex/Observable.java`: `V0` = onErrorResumeNext (654), `j1` = retryWhen (857), `H1` = takeUntil (499), `K1` = timeout (529).

---

## 0. TL;DR

- The SAB link is **pure BLE GATT**. Nothing in the app uses classic Bluetooth profiles (HFP/A2DP/SPP) towards the meter. Audio goes from the phone to the rider's headset, and the meter is not involved.
- Switch presses arrive as **1-byte notifications** on the *Function Notification Source* characteristic (`6c060578-…`). The byte is `FunctionId` (1..10), and the meter decides short vs. long press itself. Before that happens, the app has to register "assignments" (function IDs) on the *Assignment Control* characteristic and finish with `[0x03]`.
- **Security is not on the critical path in RoadSync.** The whole SAB init (assignments, notifications) runs *before* the key exchange. The key exchange runs afterwards over a **second GATT client**, and its errors are swallowed (`onErrorResumeNext`). Assignments, button notifications and display commands are **plaintext**. The RSA/AES exchange is fully specified below and can be implemented, but it is probably optional. Whether the meter's firmware enforces it is **[UNKNOWN]**; test without it first.
- Bonding: RoadSync calls `BluetoothDevice.createBond()` during first pairing, before any GATT use.
- The CB350 is model family "NMA". Its display capability (expected) is **INDICATOR only**:
  - RoadSync sends only "active indicator" icon bitmasks (phone/nav/music/message/mute/no-network) plus `REQUEST_SAB_MODE`.
  - It never sends turn arrows or text to an NMA bike.
  - The meter's clock is set by the **phone acting as a GATT server with the standard Current Time Service (0x1805)**.

---

## 1. Scanning, identification, bonding, MTU

### 1.1 Advertising and identification
| Item | Value | Source |
|---|---|---|
| Scan filter (onboarding presence check) | Service UUID **`4d87b1ea-528b-4798-b002-e2e1442a2e86`** (Vehicle Information Service) | `S/onboarding/pair/OnboardingPairingResetViewModel.java:152-155` → `B/controller/SABController$scanDevices$1.java:108-116` |
| Scan mode | `ScanSettings.setScanMode(2)` = SCAN_MODE_LOW_LATENCY | `SABController$scanDevices$1.java:106` |
| Device name | Regex **`HONDA BTU [A-Za-z0-9]*`** (CompanionDeviceManager `BluetoothLeDeviceFilter.setNamePattern`, `setSingleDevice(false)`) | `OnboardingPairingResetViewModel.java:564` |
| Name prefix used to find old bonds to remove | `name.startsWith("HONDA BTU")` → `removeBond` via reflection | `OnboardingPairingResetViewModel.java:491-529`, `B/utils/BTUtils.java:53-68` |
| Test devices | `"HONDA BTU ARD"` (Arduino simulator: no CTS server started), `"HONDA BTU SIM"` (simulator: firmware string forced to "15.15") | `B/utils/BTUtils.java:119-151`, `SABController.java:1417-1418, 2340-2341` |
| Manufacturer data | **Not used** anywhere | — |

**Capture, 2026-11-17 (CB350RS meter, Poco F1, Android 17):** in pairing mode the meter is LE-only with a public address, connectable, and advertises the Vehicle Information Service UUID and its name. The advertised name is "HONDA BTU 2040" **followed by two NUL characters** (codes `72 79 78 68 65 32 66 84 85 32 50 48 52 48 0 0`), so an exact or full-pattern match on the name fails (also in CompanionDeviceManager's name filter); cut the name at the first non-printable character first. Earlier scans filtered on the service UUID returned nothing, but the meter may not yet have been advertising then (cause not established). With the cleaned name, setup linked and bonded the meter (LE, "Just Works": bonded 0.5 s after `createBond`) and the link read series code `03F5`.

The scan only flips the UI to "bike found". The actual device is chosen through the CompanionDeviceManager association dialog (`OnboardingPairingResetViewModel.java:555-581`). The scan has a 10 s timeout, after which it fails with EMPTY_RESULT. In our own app, either approach works: scan for the service UUID, or match on the name.

**Putting the meter into pairing mode (CB350 / K0ZA)** (`RES:665-668`):
1. Turn the bike on and press and hold the two buttons on the left side of the meter together until ECO flashes.
2. Press the select switch on the left handlebar to the **right** several times to enter "Bluetooth reset".
3. Press **down** to change "reset" to "Yes", then press **right**.
4. When the Bluetooth mark flashes, the meter is advertising and ready to pair.

### 1.2 Vehicle model detection
Read the Vehicle Information Service (§2). The **Series Code** characteristic `5074e0e8-…` carries a little-endian uint16. The app rebuilds a hex string through `HexUtils.c()`: a 2-byte value `[lo, hi]` becomes `"%02X%02X"(hi, lo)` (`B/utils/HexUtils.java:31-42`, `SABController.java:1585-1589`). The first table match wins, in the order NO_SERIES_CODE, MKTA, K0ZA, K0ZA_2, K0ZF, … (`B/model/SAB.java` Values.c()).

| Series code string | Bytes on air | Enum | Model | Source |
|---|---|---|---|---|
| `03F5` | `F5 03` | K0ZA (also K0ZF, but K0ZA matches first) | **H'ness CB350** ("kiev"; CB350RS = K0ZF also uses 03F5) | `SAB.java:851, 937`; `S/SabVehicle.java:310, 355, 513` |
| `0365` | `65 03` | K0ZA_2 | CB350 variant? (not mapped to a vehicle in `SabVehicle.c`) | `SAB.java:894` |
| `03BC`/`0401`/`03B5`/`04B5` | | K1BA/K1BA_2/K1BF/K1BG | Forza 350 | `SAB.java:980-1109` |
| `04B6` | | K1BH | Forza 250 | `SAB.java:1152` |
| `0448` | | K2KA | CB300F | `SAB.java:1195` |
| `0434` | | K2LA | ADV350 | `SAB.java:1238` |
| `03B2` | | MKJH | CB1000R | `SAB.java:1281` |
| `03AB` | | MKTA | X-ADV | `SAB.java:1324` |
| `03AC` | | MKVA | Forza 750 | `SAB.java:1367` |
| `044E` / `044F` | | MLBA / MLCA | CB750 / XL750 | `SAB.java:1410, 1453` |
| empty | | NO_SERIES_CODE | | `SAB.java:1496` |

Quirk: `SabVehicle.b()` maps K0ZA to `UNKNOWN(seriesCode)` (`S/SabVehicle.java:459-496`). For CB350, RoadSync therefore falls back to the vehicle model the user picked during onboarding (`S/service/SabService.java:644-653`). This has no protocol impact.

The CB350 is family **NMA** = `SabModels("NMA", MOTORCYCLE, [INDICATOR])` (`S/SabModels.java:50-54`).

### 1.3 Classic BT vs. BLE
- No HFP/A2DP/SPP/RFCOMM code targets the meter. A2DP/headset strings in the app are analytics about the rider's headset only.
- The phone does run a **GATT server** (CTS 0x1805) for the meter to read the time (§4.6).

### 1.4 Bonding
`createBond()` is called on the BLE device during first pairing (`SABController.java:291-320`, called from `OnboardingPairingResetViewModel.java:597`). The process:
1. Previous bonds whose name starts with "HONDA BTU" are removed first (`:491-519`, only when not in "add vehicle" flow).
2. A BOND_STATE_CHANGED receiver completes the step on BOND_BONDED (12) and fails it on BOND_NONE (10, with REASON).
3. The onboarding info is read (§4.1).
4. The whole step has a 30 s timeout. On any error the app calls `removeBond` (`:699-709`).

Unknowns:
- Pairing method (Just Works / passkey / LESC) is **[UNKNOWN]**. Nothing in the app handles `ACTION_PAIRING_REQUEST`, so it is presumably Just Works or system-UI confirm.
- Which characteristics need an encrypted link is **[UNKNOWN]**. Bond before using GATT, as RoadSync does.

### 1.5 MTU, connection parameters, RSSI
| Setting | Value | Source |
|---|---|---|
| MTU request | **247**, first step after connect. Failure is tolerated: logged, and the flow continues. 50 ms delay after it. | `SABController.java:1050-1120`; `SABController$startSAB$2.java:114` |
| Connection priority | **Never requested** by the app | grep: no `requestConnectionPriority` outside the RxAndroidBle library |
| RSSI | Polled every **2000 ms** (`interval(0,2000ms)` → readRssi), used only for UI/analytics | `SABController.java:417-480` |
| Connect | `establishConnection(autoConnect=true)` for the riding session; `autoConnect=false` for onboarding and security connections | `S/service/SabService.java:931`; `SABController.java:2430`; `B/security/SecurityController.java:349, 366` |
| First-element timeout | 5000 ms (`ExtensionsKt.b`) | `SABController.java:2347`; `B/utils/ExtensionsKt.java:16-34` |

---

## 2. GATT table

Standard 16-bit UUIDs use base `0000xxxx-0000-1000-8000-00805F9B34FB` (`B/model/GATT.java:567-571`). `GATT.java` is just a generic SIG name table: the Characteristics enum that jadx failed on only holds standard SIG numbers, for example DEVICE_NAME=0x2A00 (10752), CURRENT_TIME=0x2A2B (10795), LOCAL_TIME_INFORMATION=0x2A0F (10767). Recovered from the static initializer at `GATT.java:191-300`.

### 2.1 Services on the meter (GATT client side)
| Service / Characteristic | UUID | Props used by app | Format / purpose | Source |
|---|---|---|---|---|
| **Vehicle Information Service** | `4d87b1ea-528b-4798-b002-e2e1442a2e86` | — | Also the advertised/scan-filter UUID | `SAB.java:1800` |
| ├ Vehicle Type | `5738696d-45b3-475f-81d8-cc685d56ec18` | read | byte[0]: 0=AUTOMOTIVE, 1=MOTORCYCLE | `SAB.java:1725-1728, 1786`; `SABController.java:1558-1559` |
| ├ Display Capability | `0ad39e0b-b78d-4252-9917-1aa31a915da7` | read | byte[0] bitmap: bit0=INDICATOR, bit1=POP_UP_DISPLAY, bit2=MYSTERY_BOX | `SAB.java:679-682, 702-718, 733` |
| ├ Switch Type | `ddd14e97-636d-4338-a798-94cf7ce13667` | read | byte[0]: 0=RESERVED, 1=TYPE_A, 2=TYPE_B (CB350 expected RESERVED) | `SAB.java:1640-1644, 1702` |
| ├ Category Id | `3f033888-0211-472e-9405-6525d4e4ddc7` | read | byte[0]: 0=EMPTY, 1=FI, 9=HEV | `SAB.java:468-472, 530` |
| ├ Series Code | `5074e0e8-006f-41ac-aa0f-de6e4649a830` | read | uint16 LE (see §1.2) | `SAB.java:1617` |
| └ Destination Id | `55f7910b-8cda-458b-9b54-8a7da8cd79d4` | read | byte[0]: 0 DEFAULT, 1 EU(ED), 2 G, 3 F, 4 A, 5 AC, 6 J, 7 U(AUS), 8 BR, 9 MX, 10 KO, 11 CM, 12 SW, 13 TH, 14 CH, 15 IN(Indonesia), **16 ID(India)**, 17 V, 18 ED2, 19 F2, 20 G2, 21 J2, 22 3LS, 23 CA, 24 MA, 25 MM, 26 SI, 27 RU, 28 FO(TW), 29 HK, 30 GS, 31 PH, 32 LO, 33 TU, 34 PE | `SAB.java:553-589, 656` |
| **Device Information** (0x180A) | `0000180a-…` | — | Standard DIS | `SABController.java:1247` |
| ├ Model Number 0x2A24, Serial 0x2A25, FW Rev 0x2A26, HW Rev 0x2A27, SW Rev 0x2A28, Manufacturer 0x2A29 | | read | UTF-8 strings | `SABController.java:1237-1342`; `GATT.java:196-201` |
| **Assignment Service** | `f0746e98-61d6-4570-be7e-7635c65c21cb` | — | Button/colour registration | `SAB.java:65` |
| └ Assignment Control | `4f5fbe6b-474b-4820-833f-95bfbf525d9f` | **write + notify** | Assignment requests (write) / results (notify), §5.2 | `SAB.java:51`; `SABController.java:938, 1628, 988-995, 674` |
| **Data Communication Service** | `8536e103-6771-4d4b-9702-287cb5e1340f` | — | Buttons in, display commands out | `SAB.java:358` |
| ├ Control Point | `d2e23443-f1ad-4293-a70b-a9fd163f7b00` | **write + notify** | Display commands (write) / receipts (notify), §6 | `SAB.java:321`; `SABController.java:1162, 2136, 1394-1402` |
| └ Function Notification Source | `6c060578-d1d9-460a-b86f-eb97f01b2227` | **notify** | **Switch events**: 1 byte FunctionId, §5.3 | `SAB.java:344`; `SABController.java:1158, 1898-1949` |
| **Security Service** | `592c1017-9c7b-4a35-aba6-f268592fb8fc` | — | Key exchange, §3 | `SAB.java:388` |
| ├ Control Point | `4fa28b27-6938-48f8-980e-7c7c9acc6c1e` | write | Security commands | `SAB.java:397` |
| ├ Data Source | `ebb3656f-03a5-44ce-a22c-70cfccf5e247` | **read** (not notify) | Security responses | `SAB.java:414` |
| └ Security Status | `a65b7152-589e-4933-a426-8f729bfad439` | read | `[0x01]` = meter already holds a key | `SAB.java:431`; `SecurityController.java:169-204` |
| **DFU Service** (never touch) | `0ea1bd92-5c48-41ad-9b8f-70da1bd08f35` | — | Firmware update, §8 | `SAB.java:289` |
| ├ Control Point | `13561ecc-f5ed-4109-95c9-2040122b5810` | write/notify | **Do not write** | `SAB.java:229` |
| ├ Packet | `ec2f22ba-a682-42af-b439-68fa72177719` | write | **Do not write** | `SAB.java:275` |
| └ Firmware Version | `32c7720d-440a-47a8-a866-a8b6bea9eeb6` | read | 2 bytes `[major, minor]` → `"%02d.%02d"`. Safe to read; RoadSync reads it on every connect. | `SAB.java:252`; `B/model/dfu/FirmwareVersion.java:49-62`; `SABController.java:1465-1492` |

**GATT probe (our app, 2026-11-17).** RoadSync uses only the services above. To learn everything the CB350's meter offers, the link can run a read-only probe once per run after setup (`BikeLinkConfig.probeGatt`, `GattProbe`): it logs every service, characteristic (with properties) and descriptor, and the value of every readable characteristic and every descriptor (`probe.*` log events). It never writes or subscribes, and reads nothing in the DFU service but its firmware version (`SabSafety.isReadAllowed`). The HID service is listed but not read: Android reserves it for its HID host, and a refused read of its Report Map left `BluetoothGatt` busy, so every later operation failed until the next connection (probe run 1, 2026-11-17; fixed by skipping HID).

**Probe run 1, CB350RS meter, 2026-11-17** (13 services, 42 characteristics; values before the HID read):

| Service | Characteristic | Properties | Value |
|---|---|---|---|
| Generic Attribute `1801` | Service Changed `2A05` | indicate | CCCD `02 00` (indications on, set by Android) |
| Generic Access `1800` | Device Name `2A00` | read, write | "HONDA BTU 2040" + one NUL (15 bytes) |
| | Appearance `2A01` | read | `00 00` (unknown) |
| | Peripheral Preferred Connection Parameters `2A04` | read | `09 00 0C 00 00 00 C8 00`: interval 11.25–15 ms, latency 0, supervision timeout **2 s** |
| Device Information `180A` | Manufacturer `2A29` | read | **"Visteon"** (the meter's maker) |
| | Model Number `2A24` | read | "5801" |
| | Serial Number `2A25` | read | an 8-character serial (not recorded here) |
| | Hardware Revision `2A27` | read | "1.00" |
| | Firmware Revision `2A26` / Software Revision `2A28` | read | "10.01" |
| | System ID `2A23` | read | derived from the meter's address |
| | PnP ID `2A50` | read | `01 A7 00 01 58 00 00`: Bluetooth SIG vendor 0x00A7, product 0x5801, version 0 |
| Battery `180F` | Battery Level `2A19` | read, notify | `64` = **100 %**; Presentation Format `04 00 AD 27 01 01 00` (uint8, percent) |
| HID `1812` | Protocol Mode `2A4E` | read, write-no-response | `01` (report protocol) |
| | Report Map `2A4B`, Report ×2 `2A4D`, HID Information `2A4A`, Boot Keyboard Input `2A22`, Control Point `2A4C` | | not read (Android's) |
| Vehicle Information, Assignment, Data Communication, Security, DFU | as §2.1 | as §2.1 | (reads failed after the HID read; the setup read series `03F5`) |
| **Unknown `cc036c66-e0c9-42ce-b11f-257c546c5ac2`** | `85569fa7-6645-415d-8439-fb32f08e2133` notify; `3bda2a5b-8267-4db0-8064-d0242117d473` write; `3030ac98-582b-4c15-8ef2-e80ef8d303c8` notify | | not in RoadSync |
| **Unknown `146a0176-b46f-48e7-ab74-70a7ce1a5341`** | `2573cb1a-7a02-45cd-957e-ebe6ab066539` write-no-response, notify; `f7b4d188-ad35-424f-a473-000dcdcfd8fb` write, notify; `fe7964e8-a8cc-4fbf-bd1d-14f6e9262bc5` read, write-no-response | | not in RoadSync |
| **Unknown `e95a363a-5a67-437a-88a4-153dbe498bf1`** | `96f6a339-60dc-4983-9789-8da520b7b5f7` write-no-response | | not in RoadSync |

**Listening to the vendor services (owner's approval, 2026-11-17).** None of the three unknown services' UUIDs occurs in RoadSync 23.10.20's code (a byte search of its `classes*.dex` finds the four known SAB service UUIDs and none of these), so they are probably Visteon's. After every setup the probe turns on notifications (CCCD `01 00`, the only write it makes) on exactly `85569fa7`, `3030ac98` (service `cc036c66`), `2573cb1a`, `f7b4d188` (service `146a0176`) and logs every value as `probe.notify`. Nothing is ever written to their command characteristics (`3bda2a5b`, `96f6a339`, or the write side of `2573cb1a`/`f7b4d188`). Result on the bike, 2026-11-17: all four subscriptions succeeded in two sessions (33 s, and 62 s with nine handlebar presses, ignition on); **not one notification came**. They probably answer requests on their command characteristics, whose format is unknown, so they stay unused. **Honda RoadSync Duo 2.1.0** (`jp.co.honda.roadsync.duo`, checked 2026-11-17 by a byte search of its three dex files and native libraries) contains none of these IDs and none of the SAB IDs: it uses a different service family (`5645xxxx-0627-4E27-AC27-C774D8134898`, `0000CC01/CC02-E29B-41D4-A716-446655440000`, User Data `181C`), none of which the CB350's meter has. No known Honda app reads anything from the CB350's meter beyond SAB.

**Probe run 2, 2026-11-17** (HID skipped; every read succeeded):

| Characteristic | Value | Meaning (§2.1) |
|---|---|---|
| Vehicle Type `5738696d` | `01` | MOTORCYCLE |
| Display Capability `0ad39e0b` | `01` | INDICATOR only (no pop-up, no mystery box) |
| Switch Type `ddd14e97` | `00` | RESERVED (as expected) |
| Category Id `3f033888` | `01` | FI (fuel injection) |
| Series Code `5074e0e8` | `F5 03` | `03F5`, CB350 |
| Destination Id `55f7910b` | `00` | DEFAULT (not India `16`) |
| DFU Firmware Version `32c7720d` | `0A 01` | "10.01" (matches Device Information) |
| Security Status `a65b7152` | `00` | the meter holds no key (no key exchange has run) |
| Security Data Source `ebb3656f` | `00` | |
| Unknown `fe7964e8` (service `146a0176`) | `00` | |
| CCCDs of Assignment Control, Function Source, Data Control Point | `01 00` | notifications on (our setup) |
| CCCDs of the unknown services' characteristics | `00 00` | off: nothing subscribes to them |

No characteristic carries odometer, trip, fuel or speed as a readable value. The three unknown services offer only notifications and writes (plus one readable `fe7964e8`, to be read in run 2); whatever they carry would come as notifications, which a read-only probe cannot see.

### 2.2 Service the **phone** hosts (GATT server)
| Item | Value | Source |
|---|---|---|
| Current Time Service | `00001805-…` (primary) | `B/cts/CTSService.java:163-166`; `B/cts/CTSServer.java:179` |
| Current Time 0x2A2B | props 18 (READ \| NOTIFY), perm 1 (READ), with CCCD 0x2902 (perm 17 = READ \| WRITE) | `CTSServer.java:180-185` |
| Local Time Information 0x2A0F | props 2 (READ), perm 1 | `CTSServer.java:188` |

---

## 3. Security

### 3.1 Constants (`B/security/SecurityController.java:62-65`)
| Command | ID |
|---|---|
| SECURE_EXCHANGE | `0x02` |
| VALIDATION | `0x03` |
| KEY_REFRESH | `0x04` (defined but **never used** anywhere in the app) |

### 3.2 Crypto primitives (`B/security/Crypto.java`)
| Use | Algorithm | Source |
|---|---|---|
| Keypair | RSA **1024**, public exponent F4 = 65537 (`RSAKeyGenParameterSpec(1024, F4)`); a new pair every exchange | `:67-72`; `SecurityController.java:247-248` |
| Exchange decrypt | `RSA/ECB/NoPadding` with the private key (raw RSA) | `:47-54` |
| Encrypt | `AES/ECB/PKCS7Padding`, 128-bit key, no IV | `:57-64` |
| Decrypt | `AES/ECB/PKCS7Padding`; on any exception, retried with `AES/ECB/NoPadding` | `:27-44`; `SecurityController.java:149-154` |
| Checksum | **CRC-16/ARC** (reflected poly 0xA001, init 0x0000, no xorout), table-driven; output 2 bytes **little-endian**. Check: `"123456789"` → 0xBB3D; `[0x03]` → 0x0140. | `B/security/Checksum.java:16-34` (table[1]=49345=0xC0C1 verified) |

### 3.3 When it runs in RoadSync
Each time the SAB init flow emits a `VehicleInformation` (i.e. after every successful connect or init), `SabService` runs `keyNegotiation` (`S/service/SabService.java:1182-1196`; `SabService$startBLEConnection$keyNegotiation$1$1.java`):

```
getEncryptionKey(mac)          // BLEManager.f -> SecurityController.x
  onError AlreadySecuredException -> load stored key for mac (CryptoKeyPreference), else throw "No key found!"
.flatMap(key -> validateKey(mac, key))     // BLEManager.v -> SecurityController.L
  onSuccess -> CryptoKeyPreference.n(mac, key)   // persist (again)
.onErrorResumeNext(just(connectionInfo))   // <-- ALL security errors are swallowed (:196)
```

Both `getEncryptionKey` and `validateKey` open their **own** connection: a new `RxBleClient` → `establishConnection(false)`, i.e. a second `BluetoothGatt` client to the same meter while the main SAB connection is alive (`SecurityController.java:114-118, 346-375`). On that connection they call discoverServices, but no MTU request. The ATT MTU is per link on Android, so the 247 from the main connection should apply. Android also performs a prepared (long) write automatically for writes longer than MTU-3.

### 3.4 First-time secure exchange (`getEncryptionKey`, `SecurityController$getEncryptionKey$1.java:42-97`)
1. Discover services. Require service `592c1017-…`, else fail with "Service doesn't exist" (`SecurityController.java:275-320`).
2. **Read Security Status** `a65b7152-…`.
   - If the value equals `[0x01]`: fail with `AlreadySecuredException` ("BTU already contains an encryption key"). The app then uses its stored key (§3.6).
   - Otherwise, continue (`SecurityController.java:169-204`).
3. Generate RSA-1024 (e = 65537).
4. **Write Security Control Point** `4fa28b27-…` with `[0x02] ‖ modulus`.
   - `modulus` = `BigInteger.toByteArray()` of n with a leading `0x00` stripped if present (`SecurityController.java:258-265`). This gives the unsigned big-endian modulus, **128 bytes** for a full 1024-bit key.
   - Total length **129 bytes**. No exponent, length prefix or checksum is sent; the meter must assume e = 65537.
   - Source: `SecurityController$startSecureExchange$2.java:61-66`.
5. After the write response, **read Data Source** `ebb3656f-…` (`:75-77`).
   - If the response length == 1: error ("Invalid secure exchange response"). The byte is presumably an error status; its meaning is **[UNKNOWN]**.
6. Decrypt the response with raw RSA using the private key: `m = c^d mod n` (`SecurityController.java:122-129`). Split the 128-byte plaintext:

   | Offset | Len | Field |
   |---|---|---|
   | 0 | 110 | padding (ignored) |
   | 110 | 16 | **AES-128 key** |
   | 126 | 2 | CRC-16/ARC(aesKey), little-endian |

   The offsets 110 and 126 are `Pipeline.Behavior.SEARCH_CATEGORY_FIELD_NUMBER = 110` and `IS_IN_FOREGROUND_FIELD_NUMBER = 126` (`S/../grpc/Pipeline.java:554, 522`).
7. Check `CRC16ARC(aesKey)` (LE) == crc. On mismatch, fail with "Incorrect CRC detected" (`SecurityController$startSecureExchange$2.java:118-131`).
8. Return the aesKey. It is persisted only after validation succeeds (§3.5).

Implementation note: Android's `RSA/ECB/NoPadding` (Conscrypt) returns a modulus-length (128-byte) output, which the offsets assume. BouncyCastle can strip leading zero bytes. In our own code, do the BigInteger math and **left-pad to 128 bytes** before slicing. Also expect the ciphertext from step 5 to be 128 bytes; that length is **[UNKNOWN]** but implied.

### 3.5 Validation (`validateKey`, `SecurityController$validateKey$1.java:53-104`)
1. Plaintext (3 bytes) = `[0x03] ‖ CRC16ARC([0x03]) LE` = **`03 40 01`** (`:56-58`).
2. `ct = AES-128-ECB-PKCS7(key, plaintext)`, which gives 16 bytes (`:59-62`).
3. **Write Security Control Point**: `ByteBuffer(LE).putShort(1).put(ct)` = **`01 00` ‖ ct** (18 bytes) (`:63`). What the `0x0001` prefix means is **[UNKNOWN]**. It may be a sequence number or a "1 block" counter; RoadSync always sends 1.
4. **Read Data Source** (`:71-73`). If the length == 1: error ("Invalid response").
5. Drop the first 2 bytes. AES-decrypt the rest with the key (PKCS7, falling back to NoPadding). Parse it little-endian (`SecurityController.java:145-165`):
   - byte `status`: must be **1**, otherwise `KeyValidationException("Error in response. Status code: n")`
   - int32 LE **increment ID**. It is only logged (`SabService$startBLEConnection$keyNegotiation$1$1.java:162-165`); nothing uses it.
6. On success, store the key (§3.6).

The meaning of the 2 skipped response bytes and of the trailing CRC inside the decrypted response (if any) is **[UNKNOWN]**; the app does not check them.

### 3.6 Persistence
- `CryptoKeyPreference`: EncryptedSharedPreferences (AES256-SIV keys / AES256-GCM values, master key `_androidx_security_master_key_`), file name = string `pref_sab_keys`.
- Key = **MAC address string**; value = Base64(16-byte AES key).
- Source: `S/common/preference/crypto/CryptoKeyPreference.java:28-48`.
- The bike MAC and model are stored in the Room `KnownVehicle` table (`OnboardingPairingResetViewModel.java:454-476`).

### 3.7 Use of the key after validation
**None.** No other characteristic payload is encrypted, MAC'd or counter-protected. Assignments, function notifications, receipts and display commands are plaintext (§5, §6). The increment ID is not used.

### 3.8 Feasibility for our app
- **Fresh meter**: run §3.4 and then §3.5 with our own RSA key. This is fully specified, but it is **probably optional** given §3.3.
- **Meter already secured by RoadSync** (status `[01]`): we cannot validate without RoadSync's key. Options:
  - (a) skip security entirely, as RoadSync effectively does on failure;
  - (b) reset the meter's Bluetooth (§1.1) and pair fresh;
  - (c) extract the key from RoadSync's EncryptedSharedPreferences (requires root).

  Recommendation: (b) followed by (a). Add security only if the meter turns out to refuse something.

---

## 4. Startup sequence after connect

### 4.1 Onboarding read, run once after bonding (`SABController.N2`, `SABController.java:2426-2476`; `SABController$readOnboardingInformation$1.java:74-103`)
1. Connect with autoConnect=false.
2. discoverServices.
3. Read Vehicle Information: every characteristic of service `4d87b1ea-…` (§2.1) (`SABController.java:1514-1613`).
4. Read Device Information (`:1234-1366`).
5. Read the DFU Firmware Version (`:1465-1492`).
6. Return `OnboardingInformation(vehicleInfo, deviceInfo, fwVersion)`, then disconnect. No assignments are sent at this stage.

### 4.2 Riding session (`SABController.F3` `:2337-2412`; order fixed in `SABController$startSAB$2.java:110-182`)
| # | Step | Detail | Source |
|---|---|---|---|
| 0 | Start the CTS GATT server (unless the device is "HONDA BTU ARD") | §4.6 | `SABController.java:2340-2341, 483-493` |
| 1 | establishConnection(autoConnect=**true**) | 5 s first-connection timeout | `:2345-2347` |
| 2 | Request MTU 247, then wait 50 ms | errors ignored | `:1050-1120`; `$startSAB$2:114` |
| 3 | Start RSSI polling (2 s) | optional | `:417-480` |
| 4 | (no-op step `C2`) | | `:329-334` |
| 5 | discoverServices | | `:799-849` |
| 6 | Read DFU Firmware Version | 2 bytes | `:1465-1492` |
| 7 | Read DIS strings | | `:1234-1366` |
| 8 | Read all Vehicle Information characteristics | | `:1514-1613` |
| 9 | Get Assignment Service / Control characteristic | | `:924-981` |
| 10 | **Enable notify** on Assignment Control `4f5fbe6b` | | `:1617-1695` |
| 11 | Get Data Communication characteristics | | `:1144-1198` |
| 12 | **Enable notify** on Function Notification Source `6c060578` (buttons) | Buttons can arrive from now on | `:1887-2015` |
| 13 | **Enable notify** on Control Point `d2e23443` (receipts) | | `:2125-2196` |
| 14 | **Register assignments** | §5.2 | `:1743-1881` |
| 15 | Check results, write END `[0x03]` to Assignment Control | §5.2 | `:646-717` |
| 16 | Start the display-command queue | §6.1 | `:511-585` |
| 17 | Emit `SAB_INITIALIZED` + VehicleInformation | | `$startSAB$2:97-106` |
| 18 | (SabService) Wait up to **5 s** for a meter-mode event and send **REQUEST_SAB_MODE** `[0x0A, reqId]` | §5.4 | `SabService$startBLEConnection$connection$1.java:88-183`; `SabService.java:1264-1268`; `S/common/projections/Meter.java:165-166` |
| 19 | (SabService) Key negotiation over a second GATT client; errors ignored | §3.3 | `SabService.java:1182-1196` |
| 20 | (NMA state manager) Send ACTIVE_INDICATORS as phone/music/nav state changes | §6.3 | `S/service/nma/NmaStateManager.java:447-458` |

### 4.3 Payload summary
- Vehicle/DIS/FW reads: formats are in §2.1.
- Assignments: §5.2.
- REQUEST_SAB_MODE: `0A rr`.
- CTS: §4.6.

### 4.4 Notification setup mode
All notifications use `NotificationSetupMode.DEFAULT`, i.e. write CCCD `01 00` (notifications, not indications) (`SABController.java:1628, 1898, 2136`).

### 4.5 IOP status
`IopStatus` (`B/controller/IopStatus.java`) only reports progress and failures to analytics (EstablishedSabBleConnection, FailedMtu, FailedAssignmentServiceResponse, and so on). Nothing from it goes on the wire.

### 4.6 Current Time Service (phone as GATT server, `B/cts/CTSServer.java`, `B/cts/CTSData.java`)
- **Read 0x2A2B Current Time** (`CTSServer.java:93-94`) returns 10 bytes, little-endian (`CTSData.java:23-27`):

  | Bytes | Field |
  |---|---|
  | 0-1 | `year` uint16 |
  | 2 | month (1-12) |
  | 3 | day |
  | 4 | hour |
  | 5 | minute |
  | 6 | second |
  | 7 | day_of_week (1 = Mon … 7 = Sun) |
  | 8 | fractions256 = `millis * 0.255` |
  | 9 | adjust_reason (read → 0) |

  The time is local (`LocalDateTime.now()`).
- **Read 0x2A0F Local Time Information** (`:95-96`) returns 2 bytes: `[tz_offset_in_15min_units, dst]`. The DST byte is computed as `tz - tz` and is therefore **always 0** (`CTSData.java:30-35`).
- Any other read gets status 257 (GATT_FAILURE) (`CTSServer.java:97-98, 223-228`).
- CCCD write `01 00`: the device is added to the notify list. `00 00`: it is removed. A response is sent if needed (`:123-157`).
- On `TIME_SET` the server notifies Current Time with reason 1 (MANUAL); on `TIMEZONE_CHANGED`, reason 4 (`:52-80, 192-207`). Notify uses `confirm=false`.

This is how the meter's clock is set: the meter is a GATT client of the phone.

---

## 5. Handlebar switch input

### 5.1 Function IDs (`B/model/assignment/Assignments.java:123-156`; value = ordinal + 1)
| Byte | FunctionId | Physical action on CB350/NMA (tutorial strings) | Source of mapping |
|---|---|---|---|
| `0x01` | PREVIOUS | Short press **UP** | **[INFERRED]** from "move up and down… SHORT PRESS the up and down buttons" (`RES:443`) and the names |
| `0x02` | NEXT | Short press **DOWN** | **[INFERRED]**, as above |
| `0x03` | BACK | Short press **LEFT** | `RES:431` |
| `0x04` | SELECT | Short press **RIGHT** | `RES:439` |
| `0x05` | VOLUME_UP | Registered as HID usage (§5.2); not delivered via Function Source | `AssignmentBuilder.java:41` |
| `0x06` | VOLUME_DOWN | as above | `AssignmentBuilder.java:43` |
| `0x07` | VOLUME_MUTE | Long press **DOWN** | `RES:435` |
| `0x08` | MENU | Long press **LEFT** while in RoadSync mode ("return to main menu") | `RES:437` |
| `0x09` | MODE_SAB | Long press **LEFT** while in default meter mode: the meter switches to "RoadSync/SAB mode" | `RES:433, 897` |
| `0x0A` | MODE_HU | Long press **RIGHT**: the meter returns to its own default functions | `RES:433` |

Which byte the meter sends for long-LEFT (MENU vs MODE_SAB) presumably depends on its current mode. That is meter logic and **[INFERRED]**. Long-press UP is not documented.

### 5.2 Assignment registration
Writes go to Assignment Control `4f5fbe6b-…` (write with response). Results come back as notifications on the same characteristic.

**Request ID** (`B/model/assignment/RequestIdGenerator.java:27-30`):
- A process-global uint16 counter, pre-incremented, so the first ID is 1.
- It is placed on the wire **little-endian**: byte1 = lo, byte2 = hi (`AssignmentBuilder.java:24, 35, 41`).

**Assignment frame types** (`Assignments.Command.a` accepts 1/2/3; `Assignments.java:107-108`):
- **Function, notification type** (`AssignmentBuilder.c`, `:33-36`): `01 idLo idHi fid 02`. Event type 0x02 means "notify via Function Notification Source".
- **Function, HID type** (`:41, :43`): `01 idLo idHi fid 01 07 uu 00`.
  - Event type 0x01 = HID; usage page 0x07 (Keyboard/Keypad); usage uint16 LE.
  - VOLUME_UP uses usage `0x0080` (Keyboard Volume Up): `01 .. .. 05 01 07 80 00`.
  - VOLUME_DOWN uses `0x0081`: `01 .. .. 06 01 07 81 00`.
  - **[INFERRED]** The meter then delivers these as HID-over-GATT key reports, which Android handles natively. The app has no code that receives them, and GATT HID characteristics are not used by the app. The `EventType`/`HidUsagePage` classes are empty because their constants were inlined (`Assignments.java:113-118, 171-176`).
- **Colour** (`AssignmentBuilder.b`, `:20-31`): `02 idLo idHi colorId R G B`.
- **End** (`Assignments.java:453, 463`): `03`. This is a single byte with no request ID. Write it after all results have arrived.

**Assignment list** (`AssignmentBuilder.a`, `:38-52`, in order):
1. VOLUME_UP (HID)
2. VOLUME_DOWN (HID)
3. PREVIOUS, NEXT, BACK, SELECT, VOLUME_MUTE, MENU, MODE_SAB, MODE_HU (all notification type)
4. Colours, only if `colorRegistrationNeeded` (below)

`colorRegistrationNeeded` = `VehicleInformation.j()` (`B/model/vehicleinformation/VehicleInformation.java:120-131`):
- **true** for X-ADV/Forza750/CB1000R/CB750/XL750/no-code/unknown-code
- **false** for K0ZA/K0ZA_2/K0ZF/K1B*/K2*. For the **CB350, no colours are registered**: 10 assignments in total.
- false for AUTOMOTIVE

When colours are registered they are NEGATIVE … GREY_DARK, i.e. colorIds 2..18, with the RGB values in §6.4.

Example for a fresh process on a CB350:
```
01 01 00 05 01 07 80 00
01 02 00 06 01 07 81 00
01 03 00 01 02
01 04 00 02 02
01 05 00 03 02
01 06 00 04 02
01 07 00 07 02
01 08 00 08 02
01 09 00 09 02
01 0A 00 0A 02
03
```

**Per-assignment exchange** (`SABController$handleAssignments$1$2.java:166-310`):
1. Write the frame. RxAndroidBle returns the written bytes; they must equal the frame, otherwise WriteCharacteristicException and a retry.
2. Wait for an Assignment Control notification `r` where `r[1]==a[1] && r[2]==a[2]` (request ID matches, `SABController.java:2208-2209`) **and** `r != a`. Timeout **2000 ms**; on timeout, retry. A notification with the same ID that equals the frame is logged as "Corruption"; other IDs as "Unsync". Both are ignored.
3. Parse the **AssignmentResult**: `[cmd, idLo, idHi, status, (errorPos)]`, 4-5 bytes. `cmd` must be in {1, 2, 3} and `status` in {0, 1, 11, 12, 13} (`B/model/assignment/AssignmentResult.java:27-46`; `Assignments.java:444-445`).
   - `01` OK
   - `0B` Invalid Id
   - `0C` Invalid Parameters (+ error position)
   - `0D` Already Registered
   - `00`: accepted by the parser but not handled (`SABController.java:752-771`)

Retry limits:
- Each assignment is retried on WriteCharacteristicException, IllegalArgumentException or Timeout (`$handleAssignments$1$2.java:69-112`), effectively up to 10000 times.
- If any assignment is missing at the end, the remaining ones are retried (`SABController.java:2045-2050`; `$handleAssignments$1$4.java:47-78`).

After all of them (`SABController.java:646-717`):
- If any status is not in {1, 13}, it is an error and the init stream fails.
- Otherwise write `[0x03]` to Assignment Control. RoadSync does not wait for a result; it only emits ASSIGNMENTS_END_NOTIFICATION when the write completes.

### 5.3 Receiving presses (`SABController.java:1887-2015`)
- Characteristic `6c060578-…` sends notifications with **one byte**, `FunctionId = data[0]`. RoadSync parses only byte 0; values outside 1..10 throw (`Assignments.java:152-157`).
- There are no press/release pairs, no repeat codes and no ACK: the meter has already classified short vs. long press.
- MODE_SAB / MODE_HU are also published as the "meter mode" (`:1946-1947`).
- RoadSync ignores MODE_HU while it is "pending" (`:1938-1940`); this is app logic only.
- RoadSync dispatch (`S/service/common/BaseStateManager.java:1411-1442`): PREVIOUS→page.s(), NEXT→e(), BACK→p(), SELECT→u(), MENU→l(), MODE_SAB→i(), MODE_HU→w(), VOLUME_MUTE→mute toggle.

### 5.4 Modes
- **Default mode (HU)**: the meter shows its own functions. Presumably only the long-left MODE_SAB is sent **[INFERRED]**.
- **RoadSync/SAB mode**: the meter forwards D-pad events.
- After init, RoadSync sends **REQUEST_SAB_MODE** `[0x0A, reqId]` on the Data Control Point (`CommandBuilder.java:131-132`) to ask the meter to enter SAB mode. Timing (`SabService$startBLEConnection$connection$1.java:88-183`):
  - It sends the command on every meter-mode event until it sees MODE_SAB.
  - If no mode event arrives within 5 s, it sends the command once anyway.
  - It sends the command again after recovery.
- For a CarPlay remote, either stay in SAB mode, or let the rider long-press LEFT.

### 5.5 Suggested CarPlay mapping (our app, not RoadSync)
| Byte | Suggested action |
|---|---|
| 0x01 | D-pad up / previous track |
| 0x02 | D-pad down / next track |
| 0x03 | D-pad left / back |
| 0x04 | Select / play-pause |
| 0x07 | Siri (long-down) |
| 0x08 | Home/menu |

---

## 6. Output to the meter (Data Communication Control Point `d2e23443-…`)

### 6.1 Transport (`SABController.java:511-585, 1023-1036`; `SABController$commandsOnDemand$2.java:179-348`)
- **Queue**: commands go through a queue. A new command replaces a pending one with the **same first byte** (command type) that has not been sent yet.
- **Write and receipt**: write the frame (write with response; the echo must equal the frame). Then wait up to **2000 ms** for a Control Point notification `r` with `r[1] == cmd[1]` (request ID, `SABController.java:266-267`) and `r != cmd`.
- **Receipt format**: `[cmdId, reqId, status, (errorPos)]`, 3-4 bytes. `cmdId` must be a known DataCommand (`B/model/datacommunication/Receipt.java:26-42`).
  - status `01` OK
  - `0B` Invalid Command
  - `0C` Invalid Parameters (+ position)
  - Anything else is logged but still counts as delivered (`SABController.java:855-871`).
- **Retries**: on timeout, a write mismatch or a malformed receipt (`$commandsOnDemand$2.java:79-125`).
- **Request ID**: a separate process-global **uint8** counter, post-increment, so the first one is 0 (`B/model/datacommunication/commands/RequestIdGenerator.java:27-30`).

### 6.2 Command IDs (`B/model/datacommunication/DataCommands.java:15-42`)
| ID | Command | Needs capability |
|---|---|---|
| `0x01` | MYSTERY_BOX (full screen: MENU body 1 / LIVE_SCREEN body 2) | MYSTERY_BOX (bit2) |
| `0x02` | POP_UP | POP_UP_DISPLAY (bit1) |
| `0x03` | ACTIVE_INDICATORS_CONTROL | INDICATOR (bit0) |
| `0x0A` | REQUEST_SAB_MODE | — |
| `0x0B` | FIRMWARE_UPDATE (a status notice; the app never constructs it) | — (avoid) |

### 6.3 ACTIVE_INDICATORS_CONTROL, the only display output RoadSync uses on a CB350
`03 rr HH LL` (`B/model/datacommunication/commands/projections/ActiveIndicators.java:23-55`):
- `LL` = OR of PHONE `0x01`, NAVIGATION `0x02`, MUSIC `0x04`, MESSAGE `0x08`, MUTE `0x10`, NO_NETWORK `0x20`.
- `HH` = `0xFF` if (signed) LL < 0, else `0x00`. It is effectively always `00`.
- Example, music + navigation active: `03 05 00 06`. To clear: `03 rr 00 00`.

The NMA state manager drives MUSIC, MUTE, NO_NETWORK (inverted network state), PHONE, NAVIGATION and MESSAGE (`S/service/nma/NmaStateManager.java:447-458, 511-660`; `S/common/projections/Meter.java:154-162`).

**Turn arrows, distance and song title are not sent to NMA bikes (CB350) by RoadSync.** Those need MYSTERY_BOX or POP_UP capability, which RoadSync assigns only to the X-ADV family (`S/SabModels.java:53`). Read the CB350's Display Capability byte first. If bit1 or bit2 is set, the formats below can be tried; otherwise expect `0B`/`0C` receipts.

### 6.4 Colours and icons (for MYSTERY_BOX / POP_UP)
- **ColorId**: the byte value is the ordinal (`Assignments.java:63-94`): 0 PURE_WHITE, 1 PURE_BLACK, 2 NEGATIVE, 3 POSITIVE, 4 GREEN_LIGHT, 5 GREEN, 6 GREEN_DARK, 7 PURPLE_LIGHT, 8 PURPLE, 9 PURPLE_DARK, 10 BLUE_LIGHT, 11 BLUE, 12 BLUE_DARK, 13 ORANGE_LIGHT, 14 ORANGE, 15 ORANGE_DARK, 16 GREY_LIGHT, 17 GREY, 18 GREY_DARK.
- **Registered RGB values** (`Assignments.java:26-45`):

  | Colour | RGB |
  |---|---|
  | NEGATIVE | D6 47 47 |
  | POSITIVE | 38 A8 43 |
  | GREEN_LIGHT | 30 9A 87 |
  | GREEN | 24 73 65 |
  | GREEN_DARK | 0B 27 22 |
  | PURPLE_LIGHT | 78 46 A3 |
  | PURPLE | 57 32 7A |
  | PURPLE_DARK | 27 16 37 |
  | BLUE_LIGHT | 45 7A B1 |
  | BLUE | 34 5C 86 |
  | BLUE_DARK | 12 1F 2C |
  | ORANGE_LIGHT | D8 84 2B |
  | ORANGE | AA 69 27 |
  | ORANGE_DARK | 4C 28 0A |
  | GREY_LIGHT | 71 71 71 |
  | GREY | 4B 4B 4B |
  | GREY_DARK | 27 27 27 |

  (`ColorCodes.java` is unused.)
- **IconId** (`Assignments.java:181-413`), selected values:
  - **Turn icons**: DA_TURN_STRAIGHT 39, ARRIVE 40, DEPART 41, MERGE 42, RIGHT 44, SHARP_RIGHT 45, SLIGHT_RIGHT 46, UTURN 47, FORK_RIGHT 48, ROUNDABOUT 49, RAMP_RIGHT 50, ROUNDABOUT_EXIT 51, ARRIVE_RIGHT 52, LEFT 53, SHARP_LEFT 54, SLIGHT_LEFT 55, UTURN_LEFT 56, FORK_LEFT 57, ROUNDABOUT_LEFT 58, RAMP_LEFT 59, ROUNDABOUT_EXIT_LEFT 60, ARRIVE_LEFT 61, ROUNDABOUT_1..8 = 62..69, ROUNDABOUT_n_LEFT = 70..77, FERRY 78.
  - **Lane icons**: 18..38.
  - **Others**: ACTIVE_NAVIGATION 92, ACTIVE_MUSIC 94, MUSIC_PLAYER 111.
  - The menu/live/popup arrow and button icons reuse low values (1..15) per screen type.
  - IC_NONE = 0.

### 6.5 MYSTERY_BOX / LIVE_SCREEN layout (`projections/LiveScreen.java:119-135`; size logic `CommandBuilder.java:31-55, 81-94`)
```
01 rr headerBg  TEXT(header)  02 bodyBg
   leftIcon  [leftTint leftContentIcon leftContentTint leftBg]     # optional group present iff leftIcon != 0
   rightIcon [rightTint rightContentIcon rightContentTint rightBg]
   contentBg contentIcon [contentIconTint]                         # tint present iff contentIcon != 0
   TEXT(line1) TEXT(line2)
TEXT(s) = len(1B)  then, if len>0: utf8 bytes (≤30 bytes, truncated on codepoint boundary) + textColorId
```
The "present iff" rule is enforced in `CommandBuilder.b`: an icon/colour group must be entirely null or entirely set. Text and its colour must be both present or both absent (`CommandBuilder.c`). The frame length is checked (`dataLength+2`).

### 6.6 MYSTERY_BOX / MENU layout (`projections/MenuScreen.java:108-128`)
```
01 rr headerBg TEXT(header) 01 bodyBg
   upIcon [upTint] downIcon [downTint] leftIcon [leftTint] rightIcon [rightTint] contentIcon [contentTint]
   TEXT(line1) TEXT(line2)
```
Example (RoadSync "off" screen, `Meter.java:104-110`): header bg GREY_LIGHT, no header, body GREY_LIGHT, no icons, line1 "RoadSync"/white, line2 "OFF"/white.

### 6.7 POP_UP layout (`projections/PopUpScreen.java:93-112`; `CommandBuilder.java:112-129`)
```
02 rr bgColor iconId [iconTint]  TEXT(text ≤90B)
   [ leftIcon leftTint leftContentIcon leftContentTint leftBg  rightIcon [rightTint rightContentIcon rightContentTint rightBg] ]
```
The left/right group is present only if leftIcon != 0. A right action requires a left one.

The shutdown/clear sequence on disconnect (`SABController.java:2626-2671`; `S/service/SabService.java:1297-1306`):
- INDICATOR → `03 rr 00 00`
- POP_UP → `02 rr 00 00 00`
- MYSTERY_BOX → the "RoadSync OFF" menu screen

Each is sent blocking, waiting up to 2 s for its receipt.

---

## 7. Keepalive, timeouts, reconnect
- **No periodic keepalive or heartbeat** is required by the protocol. The only periodic traffic is the RSSI read every 2 s (app-side, optional) and CTS notifications on time change.
- **Timeouts**:
  - connection first emission: 5 s
  - assignment result: 2 s per item
  - receipt: 2 s per command
  - meter-mode wait: 5 s
  - onboarding total: 30 s
  - discoverServices: 20 s
- **Reconnect** (sticky mode, `SABController$startSAB$3.java:50-107`): the init stream is resubscribed (`retryWhen`) on:
  - TimeoutException
  - BleAlreadyConnectedException
  - BleDisconnectedException (only when sticky=true; RoadSync's service uses sticky=true, autoConnect=true at `SabService.java:931`)
  - BleGattException, at most 3 times

  Any other error ends the session.
- **Every reconnect re-runs the full sequence** from §4.2: MTU, discovery, reads, notifications, assignments (status 13 "Already Registered" is accepted), END, REQUEST_SAB_MODE, key negotiation.
- RoadSync tears the session down on an LE disconnect when not in sticky retry (`SabService.java:983-1028, 731-739`).

## 8. DFU — exists, never trigger
- **Opcodes**, written to DFU Control Point `13561ecc-…` (`SAB.java:97-106`): START_DFU 0x01, STOP_DFU 0x02, VALIDATE_FIRMWARE 0x03, VALIDATE_HEADER 0x04, RECEIVE_IMAGE_DATA 0x05, REPORT_RECEIVED_IMAGE_SIZE 0x06, ACTIVATE_IMAGE 0x07, RESPONSE_CODE 0x10.
- **Responses** (`:164-171`): 1 SUCCESS, 2 INVALID_STATE, 3 DATA_SIZE_EXCEEDS_LIMIT, 4 VALIDATION_ERROR, 5 OPERATION_FAILED, 6 VERSION_VALIDATION_ERROR.
- The image goes to Packet `ec2f22ba-…`. DFU runs on its own connection (MTU 247) and refuses to start if the SAB stream is connected (`B/BLEManager.java:279-294`; `B/dfu/DFUController.java:240-1620`).
- Also avoid Data command `0x0B` FIRMWARE_UPDATE (status values 1 NEW_FIRMWARE_AVAILABLE, 2 UPDATE_IN_PROGRESS, 3 DOWNLOAD_IN_PROGRESS; `firmwareupdate/FirmwareUpdate.java:19-22`). The app never constructs it.
- **Rules for our app**:
  - Never write any characteristic of service `0ea1bd92-…`.
  - Never enable its notifications.
  - Never send data command 0x0B.
  - Reading Firmware Version `32c7720d` is safe.

---

## 9. Happy-path pseudo-code (Kotlin-ish, plain Android BluetoothGatt)

```kotlin
val VIS   = uuid("4d87b1ea-528b-4798-b002-e2e1442a2e86")
val ASG_S = uuid("f0746e98-61d6-4570-be7e-7635c65c21cb"); val ASG_C = uuid("4f5fbe6b-474b-4820-833f-95bfbf525d9f")
val DC_S  = uuid("8536e103-6771-4d4b-9702-287cb5e1340f"); val DC_CP = uuid("d2e23443-f1ad-4293-a70b-a9fd163f7b00")
val FN_SRC= uuid("6c060578-d1d9-460a-b86f-eb97f01b2227")
val SEC_S = uuid("592c1017-9c7b-4a35-aba6-f268592fb8fc"); val SEC_CP = uuid("4fa28b27-6938-48f8-980e-7c7c9acc6c1e")
val SEC_DS= uuid("ebb3656f-03a5-44ce-a22c-70cfccf5e247"); val SEC_ST = uuid("a65b7152-589e-4933-a426-8f729bfad439")

// ---------- FIRST PAIRING ----------
// meter: Bluetooth reset (§1.1) -> advertising "HONDA BTU xxxx"
dev = scan(filter = ServiceUuid(VIS)) or CompanionDeviceManager(namePattern "HONDA BTU [A-Za-z0-9]*")
dev.createBond(); await BOND_BONDED (timeout 30 s)
save(mac = dev.address)
startCtsGattServer()                  // §4.6 – keep running whenever the app runs
session(dev, firstTime = true)

// ---------- EVERY RIDE ----------
startCtsGattServer()
gatt = dev.connectGatt(ctx, autoConnect = true, cb, TRANSPORT_LE)
session(...)

fun session(gatt, firstTime) {
  gatt.requestMtu(247); delay(50)
  gatt.discoverServices()
  vi = readAll(VIS chars)             // check seriesCode==0x03F5, displayCapability
  enableNotify(ASG_C); enableNotify(FN_SRC); enableNotify(DC_CP)    // CCCD 01 00
  var rid = 0                          // uint16, pre-increment, LE on wire
  val list = buildList {
    add(byteArrayOf(1, lo(++rid), hi(rid), 5, 1, 7, 0x80.toByte(), 0))
    add(byteArrayOf(1, lo(++rid), hi(rid), 6, 1, 7, 0x81.toByte(), 0))
    for (f in listOf(1,2,3,4,7,8,9,10)) add(byteArrayOf(1, lo(++rid), hi(rid), f.toByte(), 2))
    // CB350: no colour assignments
  }
  for (a in list) retry { write(ASG_C, a); r = awaitNotify(ASG_C, 2000) { it[1]==a[1] && it[2]==a[2] && !it.contentEquals(a) }
                          require(r[3] == 1 || r[3] == 13) }
  write(ASG_C, byteArrayOf(3))
  var cmdId = 0                        // uint8 post-increment
  sendCmd(byteArrayOf(0x0A, (cmdId++).toByte()))          // REQUEST_SAB_MODE; expect receipt 0A rr 01
  onNotify(FN_SRC) { b -> when (b[0].toInt()) {
      1 -> up(); 2 -> down(); 3 -> back(); 4 -> select(); 7 -> longDown(); 8 -> menu()
      9 -> meterEnteredSabMode(); 10 -> meterLeftSabMode()  /* optionally re-send 0x0A later */ } }
  // optional bonus: indicators
  sendCmd(byteArrayOf(3, (cmdId++).toByte(), 0, 0x04))     // MUSIC icon on
  // optional security (§3) – only if meter turns out to require it:
  //   second gatt client or same gatt: read SEC_ST; if != [01] -> secureExchange(); validate(key)
  onDisconnect { reconnect with autoConnect=true; on reconnect run session() again }
}

fun sendCmd(c: ByteArray) = retry { write(DC_CP, c); r = awaitNotify(DC_CP, 2000) { it[1]==c[1] && !it.contentEquals(c) }; check(r[2]==1) }

fun secureExchange(): ByteArray {            // §3.4
  kp = RSA1024(e=65537); n = unsignedBE(kp.modulus)          // 128 B
  write(SEC_CP, byteArrayOf(2) + n)                          // 129 B (long write OK)
  c = read(SEC_DS); require(c.size != 1)
  m = leftPad128(c.toBigInt().modPow(kp.d, kp.n))
  key = m.copyOfRange(110,126); require(crc16arcLE(key).contentEquals(m.copyOfRange(126,128)))
  return key
}
fun validate(key: ByteArray): Int {          // §3.5
  ct = aesEcbPkcs7Encrypt(key, byteArrayOf(3, 0x40, 0x01))   // 16 B
  write(SEC_CP, byteArrayOf(1, 0) + ct)
  r = read(SEC_DS); require(r.size != 1)
  p = aesEcbDecrypt(key, r.copyOfRange(2, r.size))
  require(p[0] == 1.toByte()); return leInt(p, 1)            // increment id
}
```

The reconnect path is the same as `session()`. RoadSync does not skip any step on reconnect. It does not need the stored key except for the optional validation.

---

## 10. Open questions / unknowns
1. **Does the meter enforce security?** RoadSync's own flow says no: assignments and buttons happen before the exchange, and its errors are ignored. The firmware could still gate something such as SAB mode. Test plaintext first.
2. **Bonding and encryption requirement** for the custom characteristics: **[UNKNOWN]**. RoadSync always bonds first, and so should we.
3. **Pairing method** (Just Works vs. numeric compare): **[UNKNOWN]**.
4. **Security frames**:
   - the meaning of the `01 00` prefix on the validation write;
   - the 2 skipped bytes in the validation response;
   - the 1-byte error responses;
   - the exact length of the secure-exchange response (assumed 128).
5. **KEY_REFRESH (0x04)** format: never used, so unknown.
6. **Physical mapping**:
   - PREVIOUS=up / NEXT=down is inferred.
   - Whether long-left sends MENU (8) or MODE_SAB (9) depending on the meter's mode is inferred.
   - Behaviour of long-press UP is unknown.
   - Whether the meter sends anything other than MODE_SAB while in default mode is unknown.
7. **CB350 Display Capability value**: RoadSync's model table expects INDICATOR only. Read it on the real bike. If bits 1/2 are set, popups/arrows might work, but RoadSync never tried them on NMA.
8. **VOLUME_UP/DOWN HID assignments**: probably delivered as HID-over-GATT keyboard reports handled by Android (the meter must then expose a HID service). Not verified, and the CB350 likely has no volume buttons anyway.
9. **K0ZA_2 (`0365`)** is defined but unmapped. It is possibly another CB350 variant.
10. **Sharing the link with RoadSync**: running RoadSync and our app at the same time will conflict, because both register assignments and consume events. Uninstall or disable RoadSync.

## Verified on the CB350 (2026-11-16)

Tested with Handlebar Link on a Poco F1 against a CB350 meter (series 03F5, display capability 1):

- **Write type:** the assignment control (`4f5fbe6b`) and data control point (`d2e23443`) are
  **write-without-response + notify** only. A normal write (with response) is refused with
  GATT status 3 (write not permitted). Write with the characteristic's default write type, as
  RoadSync (RxAndroidBle) does. The security exchange is not needed for assignments, button
  notifications or phone mode.
- **Bond:** works with an LE bond made from the phone's own pairing dialog.
- **The meter reads the phone's Current Time Service** right after connecting.
- **Button codes (function source `6c060578`):** up = 1, down = 2, left = 3, right = 4,
  hold down = 7, hold left = 8. Hold right never reaches the phone (the meter keeps it; button
  presses keep working afterwards). Hold up sends nothing. On connect the meter sends 10 then 9
  (phone mode off → on) in answer to REQUEST_SAB_MODE.
- **End to end:** presses forwarded as Android media keys reached the iPhone through CarLink's
  CarPlay session (`DiPlay-MediaKeys: media key … -> CarPlay n sent=true`) whenever CarLink held
  the active media session.
