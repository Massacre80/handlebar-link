# Spec A: iAP2 link layer for wireless CarPlay over Bluetooth RFCOMM (taken from DiPlay source)

Source root: `/Volumes/My files/pocof1/carlink-src/vendor/diplay`. Only source code and tests were read; nothing under `docs/research` or `notes/`.
Path abbreviations used below:

- `ENG` = `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2LinkEngine.kt`
- `CH` = `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2LinkChannel.kt`
- `CSM` = `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2CsmChannel.kt`
- `BT` = `shared/src/main/java/com/shilapi/xcertplay/transport/BluetoothRfcommDuplexStream.kt`
- `CPC` = `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`
- `SYNT` = `shared/src/test/java/com/shilapi/xcertplay/transport/Iap2LinkEngineSynchronizationTest.kt`
- `FTT` = `shared/src/test/java/com/shilapi/xcertplay/transport/Iap2LinkEngineFileTransferTest.kt`

Roles: the Android head unit (DiPlay) is the **accessory**, and the iPhone is the **device**. "Peer" below always means the iPhone.

---

## 1. RFCOMM transport

| Item | Value | Where |
|---|---|---|
| Role | DiPlay is the **RFCOMM client**. It dials the iPhone. No `BluetoothServerSocket` or `listenUsing*` exists anywhere in DiPlay (checked with grep). | `CPC:1465`, `CPC:1473` |
| Socket call | `device.createRfcommSocketToServiceRecord(UUID.fromString(IAP2_IPHONE_UUID))`. This is the **secure** (authenticated and encrypted) variant. The insecure variant is never used. Android does an SDP lookup of the UUID on the iPhone. | `CPC:1465` |
| UUID | `00000000-deca-fade-deca-deafdecacafe` | `CPC:3726` |
| Connect | `dialSocket.connect()` on a background thread (`BackgroundDial`, a CarLink change). The dial starts early, while the hotspot, Bonjour and listener are coming up. | `CPC:1471-1474` |
| Connect timeout | `RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L`, measured from the start of the dial. On timeout the socket is closed and an `IOException` is thrown. | `CPC:3742`, `CPC:1685-1686`, `CPC:3192-3218` |
| Target device | The user-chosen address if one is set (it must be bonded). Otherwise: the bonded devices whose name contains "iPhone"; among them, the one currently connected; else the only bonded iPhone; else the only bonded device. Otherwise it fails. There is no discovery and no `cancelDiscovery()`. | `CPC:3044-3086` |
| Pre-connect steps | (a) If the adapter is `STATE_TURNING_ON`, wait up to 3 s (`CPC:1420-1423`, `CPC:1453-1455`). (b) `BydBluetoothSuspend.resumeAndWait` (`CPC:1452`). (c) Fail if the radio is off (`CPC:1456`). (d) Diagnostics only: log the bond state and whether the UUID is in the cached `device.uuids` (`CPC:3222-3250`). No SDP fetch (`fetchUuidsWithSdp`) is forced. | as cited |
| After connect | `BluetoothRfcommDuplexStream(socket)` is built (`CPC:1712-1713`). Then `Iap2Session.openWireless(stream, …)` → `Iap2CsmChannel.openWireless` → `Iap2LinkChannel.openWireless` (`CPC:1727`, `CSM:232-236`, `CH:383-388`). | |
| "Unreal link" check (head-unit quirk) | If the connect returned suspiciously fast and no byte has arrived, DiPlay later dials a **random** UUID on the same device. If that also "succeeds" quickly, the Bluetooth stack is declared fake. Diagnostics only. | `CPC:1721-1726`, `CPC:3104-3146` |
| Stream I/O | One daemon reader thread does blocking `read()` in chunks of up to `READ_CHUNK_BYTES = 8_192`. It stops reading while `MAX_PENDING_BYTES = 65_536` are buffered. Each `send()` = `output.write(data)` followed by `flush()`. If `flush()` fails with EACCES/EPERM, flushing is turned off for that socket (Ford SYNC+ quirk). `read() == -1` is EOF, which the link sees as an empty array, and `feedEof()` kills the link. | `BT:74-107`, `BT:169-231`, `BT:333-334`, `CH:161-166` |
| Link pump | The worker calls `recv(8192, min(100 ms, next protocol deadline))` (minimum 1 ms). Every engine `takeOutput()` is written with one `send()`. | `CH:145-199`, `CH:350-352` |

CarLink-only alternative (not upstream DiPlay): `ModuleIap2Route` can carry the same raw iAP2 byte stream through a stereo's Bluetooth module (a ZLink daemon on `127.0.0.1:3152`, or the FYT `/dev/rf_serial`). In that case Android's RFCOMM is not used at all. The link-layer bytes are identical (`ZbtIap2Link.kt:1-45`, `CPC:1414-1446`).

---

## 2. Bytes sent before the link starts (the iAP2 marker)

```kotlin
val IAP2_MARKER = byteArrayOf(0xff.toByte(), 0x55, 0x02, 0x00, 0xee.toByte(), 0x10)   // ENG:664
```

Wire bytes: **`FF 55 02 00 EE 10`**

Wireless flow (`openWireless` → `engine.start(wiredInitiator = false)`, `CH:148`, `CH:387`):

1. `start()` sets the state to `DETECTING`, writes the marker, and arms a resend timer of `MARKER_RESEND_MILLIS = 1_000` (`ENG:173-179`, `ENG:680`).
2. While the state is `DETECTING`, the marker is **re-sent every 1000 ms with no limit** (`ENG:214-221`).
3. **Expected back:** the iPhone's own 6-byte marker `FF 55 02 00 EE 10`.
   - In `DETECTING`, the engine waits until ≥ 6 bytes are buffered. If the first 6 bytes are not exactly the marker, it **dies** with `"iAP2 marker was not received"`. There is **no resync or scan** in this state (`ENG:270-279`).
   - When the marker matches, its 6 bytes are discarded and `enterNegotiating()` runs at once. Any bytes after the marker in the same chunk are parsed as link frames.
4. `enterNegotiating()` stops the marker timer, sends DiPlay's **SYN** immediately, and arms a SYN resend every `SYNCHRONIZATION_RESEND_MILLIS = 500` ms (`ENG:318-325`, `ENG:681`).

Wired and tunnel flow, for contrast (`wiredInitiator = true`): the marker and the SYN are written back to back without waiting (`ENG:176-178`). Both land in one `send()`.

**Ambiguity:** the KDoc on `openWireless` says DiPlay "lets the phone initiate synchronization" (`CH:379-382`). The code does **not** wait for a phone SYN. DiPlay sends its own SYN as soon as the phone's marker arrives. "Phone initiates" in the code only means that DiPlay does not send a SYN before the phone has answered the marker. DiPlay also accepts a SYN from the phone (see §5).

---

## 3. Link packet layout

### Header (9 bytes, always present)

| Off | Size | Field | Encoding / value | Where |
|---|---|---|---|---|
| 0 | 1 | Start MSB | `0xFF` (`LINK_START_HIGH`) | `ENG:538`, `ENG:673` |
| 1 | 1 | Start LSB | `0x5A` (`LINK_START_LOW`) | `ENG:539`, `ENG:674` |
| 2 | 2 | Packet length, big-endian u16 | The **whole frame**: 9 header bytes + payload + 1 payload-checksum byte. A frame with no payload has length = 9 (`0x0009`) and **no** payload checksum. | `ENG:536`, `ENG:540-541` |
| 4 | 1 | Control byte | bit flags, see below | `ENG:542` |
| 5 | 1 | Sequence number (PSN) | u8 | `ENG:543` |
| 6 | 1 | Acknowledgement number | Always `lastReceivedInOrder` (u8) on **every** outgoing frame | `ENG:544` |
| 7 | 1 | Session ID | u8. It is `0` for SYN, ACK and EAK frames. | `ENG:545`, `ENG:518-531` |
| 8 | 1 | Header checksum | over bytes 0..7 | `ENG:546` |

### Payload (optional)

`payload[N]` followed by **one payload-checksum byte**. It is present only when the frame has a payload, that is, when length > 9 (`ENG:549-552`).

### Control byte bits

| Bit | Mask | Name | Where |
|---|---|---|---|
| 7 | `0x80` | SYN | `ENG:675` |
| 6 | `0x40` | ACK | `ENG:676` |
| 5 | `0x20` | EAK | `ENG:677` |
| 4 | `0x10` | RST | `ENG:678` |
| 3 | `0x08` | SLP (sleep) | **not defined anywhere in DiPlay** (see §5.8) |

Control values DiPlay **sends**:

- SYN = `0x80` (`ENG:518`). DiPlay never sends SYN+ACK.
- pure ACK = `0x40` (`ENG:522`)
- data = `0x40`. Data always carries the ACK bit (`ENG:530`).
- EAK = `0x20`, without the ACK bit (`ENG:526`)
- RST: never sent.

### Checksums (exact code)

```kotlin
// ENG:701-702 — used for the header (count = 8) and for the payload (count = payload.size)
private fun checksum(bytes: ByteArray, count: Int): Int =
    (-bytes.take(count).sumOf { u8(it) }) and 0xff

// ENG:699 — validation: the sum of all bytes, including the checksum byte, must be 0 mod 256
private fun checksumValid(bytes: ByteArray): Boolean = bytes.fold(0) { sum, byte -> (sum + u8(byte)) and 0xff } == 0
```

So `checksum = (0x100 - (Σ bytes mod 0x100)) mod 0x100`, the two's complement of the 8-bit sum.
The header checksum is `checksum(header, 8)` (`ENG:546`). The payload checksum is `checksum(payload, payload.size)` over the payload bytes only (`ENG:551`).
On receive, the header is validated with `checksumValid(all 9 bytes)` (`ENG:688`). The payload is validated with `checksumValid(payload + checksum byte)` (`ENG:287`).

### Builder (exact code, `ENG:533-554`)

```kotlin
private fun writePacket(payload: ByteArray?, sequence: Int, control: Int, sessionId: Int) {
    if (state == State.DEAD) return
    accumulatedAcknowledgements = 0
    val length = payload?.size?.plus(HEADER_BYTES + CHECKSUM_BYTES) ?: HEADER_BYTES
    val header = ByteArray(HEADER_BYTES)
    header[0] = LINK_START_HIGH
    header[1] = LINK_START_LOW
    header[2] = (length ushr 8).toByte()
    header[3] = length.toByte()
    header[4] = control.toByte()
    header[5] = sequence.toByte()
    header[6] = lastReceivedInOrder.toByte()
    header[7] = sessionId.toByte()
    header[8] = checksum(header, HEADER_BYTES - CHECKSUM_BYTES).toByte()
    val frame = ByteArrayOutputStream(length)
    frame.write(header)
    if (payload != null) {
        frame.write(payload)
        frame.write(checksum(payload, payload.size))
    }
    appendOutput(frame.toByteArray())
}
```

### Size constants

| Constant | Value | Where |
|---|---|---|
| `HEADER_BYTES` | 9 | `ENG:666` |
| `CHECKSUM_BYTES` | 1 | `ENG:667` |
| `MAX_WIRE_FRAME_BYTES` | `0xFFFF` (65535) | `ENG:668` |
| `MAX_PAYLOAD_BYTES` | 65535 − 9 − 1 = 65525 | `ENG:669` |
| Peer-limited payload | `payload.size <= peer.maxLength - 10` (`ENG:598-599`). The channel exposes `peerMaxControlPayloadBytes = peer.maxLength - 10` (`CH:208`). CSM frames are split into chunks of that size (`CSM:70-72`, `iap2/wire/Iap2CsmFramer.kt:144-153`). | |
| Usable peer | `peer.maxLength > 10` is required, otherwise the link dies when the handshake completes (`ENG:596`, `ENG:382-384`) | |

---

## 4. Link synchronization (SYN) payload

### Encoder (exact code, `ENG:67-82`)

```kotlin
fun encode(): ByteArray {
    val out = ByteArrayOutputStream(10 + sessions.size * SESSION_DESCRIPTOR_BYTES)
    out.write(SYNCHRONIZATION_VERSION)
    out.write(maxOutgoing)
    writeU16(out, maxLength)
    writeU16(out, retransmissionTimeoutMillis)
    writeU16(out, acknowledgementTimeoutMillis)
    out.write(maxRetransmissions)
    out.write(maxAcknowledgements)
    sessions.forEach {
        out.write(it.id)
        out.write(it.kind)
        out.write(it.version)
    }
    return out.toByteArray()
}
```

`writeU16` is big-endian: high byte first, then low byte (`ENG:713-716`).

| Off | Size | Field | DiPlay wireless value | Where |
|---|---|---|---|---|
| 0 | 1 | Link version | `0x01` (`SYNCHRONIZATION_VERSION`) | `ENG:672` |
| 1 | 1 | Max outstanding packets | `0x04` | `CH:366-369` (`maxOutgoing = 4`) |
| 2 | 2 | Max packet length (whole frame) | `0xFFFF` | `ENG:603` |
| 4 | 2 | Retransmission timeout (ms) | `0x0FA0` = 4000 | `ENG:604`, `ENG:682` |
| 6 | 2 | Cumulative-ACK timeout (ms) | `0x01F4` = 500 | `ENG:605`, `ENG:724` (config default) |
| 8 | 1 | Max retransmissions | `0x04` | `ENG:606`, `ENG:683` |
| 9 | 1 | Max cumulative ACKs | `0x03` | `ENG:607`, `ENG:684` |
| 10.. | 3·n | Session list: `id, type, version` | see below | `ENG:608-612` |

### Local synchronization builder (exact code, `ENG:601-613`)

```kotlin
private fun localSynchronization() = SynchronizationPayload(
    maxOutgoing = config.maxOutgoing,
    maxLength = MAX_WIRE_FRAME_BYTES,
    retransmissionTimeoutMillis = if (config.zeroAcknowledgements) 0 else DEFAULT_RETRANSMISSION_TIMEOUT_MILLIS,
    acknowledgementTimeoutMillis = if (config.zeroAcknowledgements) 0 else config.acknowledgementTimeoutMillis,
    maxRetransmissions = if (config.zeroAcknowledgements) 0 else DEFAULT_MAX_RETRANSMISSIONS,
    maxAcknowledgements = if (config.zeroAcknowledgements) 0 else DEFAULT_MAX_ACKNOWLEDGEMENTS,
    sessions = listOf(
        SessionDescriptor(CONTROL_SESSION_ID, 0, config.controlSessionVersion),
        SessionDescriptor(EA_SESSION_ID, 2, 1),
        SessionDescriptor(FILE_TRANSFER_SESSION_ID, 1, 2),
    ),
)
```

### Configurations

Wireless uses `WIRELESS_LINK_CONFIG = Iap2LinkConfig(maxOutgoing = 4, controlSessionVersion = 2)` (`CH:366-369`). Its `zeroAcknowledgements` stays at the default `false` and `acknowledgementTimeoutMillis` at the default 500 (`ENG:721-731`).

Wired and AirPlay-tunnel use the same values plus `zeroAcknowledgements = true` (`CH:361-365`). That gives retransmission timeout = 0, ACK timeout = 0, max retransmissions = 0 and max ACKs = 0.

### Session list (identical for wired and wireless; only the control version differs by config)

| Session ID | Type | Version | Meaning | Where |
|---|---|---|---|---|
| `0x0A` (10) | `0x00` | `0x02` for wireless and wired (the `Iap2LinkConfig()` default would be 1) | Control (CSM messages) | `ENG:609`, `ENG:660` |
| `0x0B` (11) | `0x02` | `0x01` | External Accessory | `ENG:610`, `ENG:661` |
| `0x0C` (12) | `0x01` | `0x02` | File Transfer | `ENG:611`, `ENG:662` |

So the type codes are: 0 = control, 1 = file transfer, 2 = external accessory.

**Wireless SYN payload (19 bytes):** `01 04 FF FF 0F A0 01 F4 04 03 0A 00 02 0B 02 01 0C 01 02`

### Decoding the peer's SYN (`ENG:85-104`)

- The peer's SYN is ignored (`decode` returns `null`) when it is shorter than 10 bytes or its version byte ≠ 1.
- Session descriptors are read in groups of 3 while 3 bytes remain. A trailing 1 or 2 bytes are silently ignored.
- The peer's session list is **stored but never checked**. Data is routed only by the session byte in each frame header.

---

## 5. Handshake, sequencing, ACK and retransmission rules

All runtime limits come from the **peer's** SYN (`peerSynchronization`). That includes maxOutgoing, maxLength, retransmission timeout, ACK timeout, max retransmissions and max ACKs, and they govern both directions. DiPlay's own advertised values matter only to the iPhone. Before a peer SYN arrives, `peerSynchronization` is initialised to the local values (`ENG:125`).

### 5.1 States

`IDLE → DETECTING → NEGOTIATING → NORMAL`, with `DEAD` terminal (`ENG:22-28`).

### 5.2 Initial values

| Variable | Initial value | Where |
|---|---|---|
| `sentSequence` | `INITIAL_SEQUENCE = 99` (`0x63`). The SYN uses seq 99, and the first data packet is 100. | `ENG:131`, `ENG:679` |
| `lastReceivedInOrder` | 0, so the SYN header's ack byte = `0x00` | `ENG:133` |
| `lastSentAcknowledged` | `null`. `sequenceDistance(x, null) == 0`. | `ENG:132`, `ENG:704-705` |

`sequenceDistance(a, b) = (a - b) & 0xFF` and `nextSequence(s) = (s + 1) & 0xFF` (`ENG:704-707`). Sequence numbers wrap 255 → 0. 0 is not skipped.

### 5.3 Handshake (wireless, as coded)

```
HU → iPhone : FF 55 02 00 EE 10                       (repeated every 1 s until the marker comes back)
iPhone → HU : FF 55 02 00 EE 10                       (must be the first 6 bytes received)
HU → iPhone : SYN      ctrl=0x80 seq=99 ack=0 sid=0 + DiPlay SYN payload   (repeated every 500 ms while NEGOTIATING)
iPhone → HU : SYN+ACK  ctrl=0xC0 seq=S  ack=99 sid=0 + peer SYN payload
HU → iPhone : ACK      ctrl=0x40 seq=99 ack=S  sid=0  (9-byte header only)
              → NORMAL, writable=true; the control session can be used
```

`processFrame` (`ENG:327-358`) handles the flags in this order: RST → SYN → ACK → EAK → data → forced-ACK check.

**SYN bit** (`handleSynchronization`, `ENG:360-378`), while `NEGOTIATING`:
- It stores the peer parameters and sets `peerSynchronizationSequence = S`, `lastReceivedInOrder = S` and `lastAcknowledgedReceived = S`.
- It then **sends a pure ACK at once** (seq = 99, ack = S).
- This happens whether or not the peer's SYN has the ACK bit. A peer **SYN without ACK** gets an ACK, but the state stays `NEGOTIATING`. DiPlay keeps re-sending its own SYN every 500 ms until some frame with the ACK bit arrives.

**ACK bit while `NEGOTIATING`** (`ENG:381-389`):
- If no peer SYN has been received yet, or `peer.maxLength <= 10`, or a queued payload exceeds the peer limit, the link **dies**.
- Otherwise the state becomes `NORMAL`, the SYN timer stops and `Writable(true)` fires.
- **The ack number is not checked against 99.**

**SYN while `NORMAL`** (`ENG:361-370`, issue #1220): DiPlay re-sends a pure ACK only if all of these hold:
- the frame has the ACK bit,
- seq == the stored peer SYN seq,
- the payload is identical to the stored peer SYN,
- no in-order data has arrived yet. `peerSynchronizationSequence` is cleared on the first delivered data packet (`ENG:457`).

Any other SYN in `NORMAL` is ignored. There is never a renegotiation.

**Who initiates:** DiPlay always sends a SYN, after the phone's marker on wireless (`ENG:277`, `ENG:321`). It also accepts a peer-initiated SYN. **Ambiguous by design:** the code handles both cases. See also the oddity at §8.2.

### 5.4 Sending data

`sendSession`, then `sendPacket` (`ENG:255-266`, `ENG:476-501`):

- **Queue condition.** If the state is not `NORMAL`, or `sequenceDistance(sentSequence, lastSentAcknowledged) > peer.maxOutgoing`, the packet is queued. At most `maximumQueuedPackets = 64`, otherwise the link dies (`ENG:727`). Queuing sets `Writable(false)`.
- **Send.**
  - `sentSequence = next`. The frame is ctrl `0x40`, seq = new sequence, ack = `lastReceivedInOrder`, sid = session.
  - This **piggybacks the ACK**: it clears the pending ACK timer and sets `lastAcknowledgedReceived = lastReceivedInOrder`.
  - It sets `deadline = now + peer.retransmissionTimeoutMillis`.
- **Tracking.** If `peer.maxRetransmissions > 0`, the packet is added to `unacknowledged` and the retransmit timer is set to **this packet's** deadline. If it is 0 (zero-ack mode), the packet counts as acknowledged immediately (`lastSentAcknowledged = sentSequence`).
- **Retransmission** (`retransmitDuePacket`, `ENG:503-515`).
  - When the timer fires, take the unacked packet with the earliest deadline. Reset its deadline to `now + peer.RTO`, then `retransmissions += 1`.
  - If `retransmissions == peer.maxRetransmissions`, the link **dies** without sending. With max = 4 that means the original send plus **3** resends, and death on the 4th timeout.
  - Otherwise resend the same seq and session. The ack byte = the current `lastReceivedInOrder`.
  - The timer is then set to the next-earliest deadline among the other packets, or to this packet's new deadline.

### 5.5 Receiving an ACK

`handleAcknowledgement`, `ENG:380-410`:

- `lastSentAcknowledged = ack`. This is a **cumulative** ACK.
- Remove packets from the front of `unacknowledged` until the first packet with `0 < (seq - ack)&0xFF <= peer.maxAcknowledgements + ACK_SLACK(10)`. That packet is unacked, so its deadline re-arms the timer. If none remain, the timer is cleared.
- Then send queued packets while `sequenceDistance(sentSequence, lastSentAcknowledged) < peer.maxOutgoing`, setting `Writable(true)` after each one.

### 5.6 Receiving data

`handleData`, `ENG:427-474`. A frame is data only when `(control & ~0x40) == 0` and it has a payload (`ENG:348`). That means control `0x00` or `0x40` with length > 9.

`d = (seq - lastReceivedInOrder) & 0xFF`

| Case | Action |
|---|---|
| `d == 0` (duplicate) or `d > peer.maxOutgoing + 10` | Send a pure ACK and drop the frame |
| Already buffered out-of-order | Drop it |
| Out-of-order buffer holds ≥ 64 packets | The link dies (`ENG:728`) |
| `d > 1` | Buffer it. If `d >= peer.maxOutgoing`, send an **EAK** whose payload is the list of missing seqs `lastReceivedInOrder+1 … seq-1`, one byte each (`ENG:439-451`, `ENG:525-527`). Return without an ACK. |
| `d == 1` | Deliver this packet and every following contiguous buffered packet in order. Session 10 → `Event.Control`; any other session → `Event.Session`. The channel only acts on session 12; **session 11 (EA) payloads are silently dropped** (`CH:233-246`). |

**ACK policy after delivery** (`ENG:465-473`):
- If `peer.maxAcknowledgements == 0` (zero-ack peer), DiPlay sends no ACK.
- Otherwise, if `(lastReceivedInOrder - lastAcknowledgedReceived)&0xFF >= max(1, peer.maxOutgoing - config.maxOutgoingDelta(0))`, an ACK is sent now.
- Otherwise the ACK timer is set to `now + peer.acknowledgementTimeoutMillis`. It is **re-armed on every packet**. When it fires in `NORMAL`, a pure ACK is sent (`ENG:233-239`).

**Second forced-ACK rule** (`ENG:351-357`): `accumulatedAcknowledgements` counts received frames with the ACK bit. It is reset to 0 whenever DiPlay writes any frame (`ENG:535`). When it reaches `peer.maxAcknowledgements` (> 0), a pure ACK is sent.

**Pure ACK** = 9-byte header, ctrl `0x40`, seq = `sentSequence` (the last sequence used, **not** incremented), ack = `lastReceivedInOrder`, sid 0 (`ENG:521-523`).

### 5.7 Receiving an EAK

`ENG:345-347`, `ENG:412-425`. When the EAK bit is set and there is a payload, and the state is `NORMAL`:
- For each unacked packet whose seq byte appears in the payload: `retransmissions += 1`. If that equals `peer.maxRetransmissions`, the link dies. Otherwise resend the packet.
- After each resend, the pending ACK timer is cleared and the retransmit timer is set to that packet's **old** deadline.

### 5.8 RST and SLP

- **RST** (`0x10`) received → the link dies with `"peer sent iAP2 reset"`, before any other flag is handled (`ENG:328-331`). DiPlay never sends RST. On close or death it simply closes the socket (`CH:256-266`).
- **SLP / sleep (`0x08`)** is **not handled**. There is no constant for it. A frame with only `0x08` (or `0x48`) passes no branch and is **silently ignored**. Because `(ctrl & ~0x40) != 0`, it is never treated as data either. If the ACK bit is also set, the ACK part is still processed.

### 5.9 Timers summary

| Timer | Value | Where |
|---|---|---|
| Marker resend | 1000 ms, unlimited, `DETECTING` only | `ENG:680`, `ENG:214-221` |
| SYN resend | 500 ms, unlimited, `NEGOTIATING` only. Gives up only when a higher layer times out `awaitReady`. | `ENG:681`, `ENG:223-231` |
| Data retransmit | `peer.retransmissionTimeoutMillis`. DiPlay advertises 4000. | `ENG:491`, `ENG:507` |
| Delayed ACK | `peer.acknowledgementTimeoutMillis`. DiPlay advertises 500. | `ENG:472` |
| Pump granularity | ≤ 100 ms receive poll | `CH:351`, `CH:156-161` |

---

## 6. Stream reassembly and resync

`feed` and `parseAvailable`, `ENG:182-201`, `ENG:268-316`, `ReceiveBuffer` at `ENG:615-657`.

1. Received fragments are appended to a 65535-byte ring buffer. If the buffer is full and nothing parses, the link dies (`ENG:186-191`).
2. In `DETECTING`, see §2. The first 6 bytes must be the exact marker, with no scan.
3. When a header with length > 9 has been accepted, the engine waits (`awaitingPayload`) until `length - 9` more bytes (payload plus checksum) are buffered. Then it takes them.
   - If the **payload checksum is bad, the whole frame is silently dropped**. No EAK or NAK is sent, and the bytes are consumed (`ENG:281-295`).
4. Otherwise the engine **resyncs**: while ≥ 2 bytes are buffered and the first two are not `FF 5A`, it discards 1 byte (`ENG:297-301`). It then needs 9 bytes.
5. It then **takes** (consumes) 9 bytes and parses them (`ENG:303-305`, `ENG:687-697`).
   - If the header checksum or start bytes are invalid, or length < 9, the result is `return true` with **all 9 bytes already consumed**. The next scan starts after them, not 1 byte later.
6. A length of exactly 9 means a payload-less frame, processed at once. Otherwise the header is held and the engine waits for the payload.
7. A length > 65535 is impossible because the field is u16, so the `die` at `ENG:306-309` is dead code.

Edge case: length = 10 (payload size 0 plus a checksum byte that must be `00`). It is passed as an empty, **non-null** payload. With ctrl `0x40` it would be delivered as an empty data packet.

---

## 7. Golden vectors

**The test files contain no literal byte arrays of complete encoded link frames.** Both tests build frames with a local `frame()`/`packet()` helper that uses the same algorithm (`SYNT:108-125`, `FTT:45-62`). The only literal link bytes in main code are the marker (`ENG:664`).

The vectors below were computed by **re-implementing `writePacket`/`checksum`/`encode` exactly as quoted above** in a short Python script. They were **not** produced by running the Kotlin tests. Each row names the test input it reproduces.

### 7.1 Literal arrays in source and tests

| Bytes | Meaning | Where |
|---|---|---|
| `FF 55 02 00 EE 10` | iAP2 marker | `ENG:664` |
| `40 40 00 06 1D 00` | CSM payload (session 10) | `SYNT:39` (`dataStillFlowsAfterARepeatedSynAck`) |
| `40 40 00 06 1D 01` | CSM payload sent by DiPlay | `SYNT:46` |
| `40 40 00 06 1D 02` | CSM payload | `SYNT:53` |
| `81 04 00 00 00 00 00 00 00 05 00 02` | file-transfer datagram (session 12) | `FTT:31` |
| `81 01` | file-transfer reply payload | `FTT:38` |

### 7.2 Complete frames (computed)

**A. DiPlay wireless SYN.** This is the first frame after the marker exchange (config `CH:366-369`). Length 0x1D = 29.
```
FF 5A 00 1D 80 63 00 00 A7 | 01 04 FF FF 0F A0 01 F4 04 03 0A 00 02 0B 02 01 0C 01 02 | 29
```

**B. DiPlay wired/tunnel SYN** (zero-ack config, `CH:361-365`, `SYNT:138-142`).
```
FF 5A 00 1D 80 63 00 00 A7 | 01 04 FF FF 00 00 00 00 00 00 0A 00 02 0B 02 01 0C 01 02 | D4
```

**C. Peer SYN+ACK** used in `SYNT` (`peerSynAck()`, `SYNT:100-103`, params `SYNT:145-157`, seq 144 = 0x90, ack 99). The test comment says "a 29-byte SYN+ACK" (`SYNT:144`).
```
FF 5A 00 1D C0 90 63 00 D7 | 01 04 FF FF 00 00 00 00 00 00 0A 00 02 0B 02 01 0C 01 02 | D4
```

**D. DiPlay's ACK of that SYN+ACK.** `acknowledgement(PEER_SYN_SEQUENCE)` at `SYNT:105-106` is asserted at `SYNT:21`, `SYNT:28` and `SYNT:95`.
```
FF 5A 00 09 40 63 90 00 6B
```

**E. Peer data, session 10, seq 145.** Fed at `SYNT:40` in `dataStillFlowsAfterARepeatedSynAck`.
```
FF 5A 00 10 40 91 63 0A 59 | 40 40 00 06 1D 00 | 5D
```

**F. DiPlay data, session 10, seq 100, ack 145.** Asserted at `SYNT:48-51`.
```
FF 5A 00 10 40 64 91 0A 58 | 40 40 00 06 1D 01 | 5C
```

**G. Peer data, seq 146, ack 100.** Fed at `SYNT:54`.
```
FF 5A 00 10 40 92 64 0A 57 | 40 40 00 06 1D 02 | 5B
```

**H. Peer data, seq 145, payload `01`.** Fed at `SYNT:62` (`ignoresARepeatedSynAckOnceDataHasArrived`). After this, a repeat of C produces **no output** (`SYNT:66-68`).
```
FF 5A 00 0B 40 91 63 0A 5E | 01 | FF
```

**I. Ignored SYN+ACKs** (`SYNT:76-79`). Both produce zero output bytes.
- seq 151:
  ```
  FF 5A 00 1D C0 97 63 00 D0 | 01 04 FF FF 00 00 00 00 00 00 0A 00 02 0B 02 01 0C 01 02 | D4
  ```
- maxOutgoing 5:
  ```
  FF 5A 00 1D C0 90 63 00 D7 | 01 05 FF FF 00 00 00 00 00 00 0A 00 02 0B 02 01 0C 01 02 | D3
  ```

**J. File-transfer test, peer SYN+ACK.** Fed at `FTT:26`: maxOutgoing 8, maxLength 4096, RTO 2000, ACK timeout 500, 4 retransmissions, 3 ACKs, sessions (10,0,2) and (12,1,2).
```
FF 5A 00 1A C0 01 63 00 69 | 01 08 10 00 07 D0 01 F4 04 03 0A 00 02 0C 01 02 | F9
```

**K. DiPlay ACK in reply to J** (seq 99, ack 1).
```
FF 5A 00 09 40 63 01 00 FA
```

**L. Peer file-transfer datagram, session 12, seq 2.** Fed at `FTT:32`.
```
FF 5A 00 16 40 02 63 0C E0 | 81 04 00 00 00 00 00 00 00 05 00 02 | 74
```

**M. DiPlay file-transfer reply, session 12, seq 100, ack 2.** The test checks byte 7 = `0C` and bytes 9..10 = `81 01` (`FTT:39-42`).
```
FF 5A 00 0C 40 64 02 0C E9 | 81 01 | 7E
```

**N. Example EAK.** This one is constructed, **not** from a test: DiPlay at sentSequence 99, lastReceivedInOrder 100, missing 101 and 102.
```
FF 5A 00 0C 20 63 64 00 B4 | 65 66 | 35
```

Check of the arithmetic for D: `FF+5A+00+09+40+63+90+00 = 0x295`, `0x295 & 0xFF = 0x95`, `-0x95 & 0xFF = 0x6B` ✓.

---

## 8. Bugs, oddities and ambiguities in DiPlay's implementation

1. **The KDoc and the code disagree about who initiates.** `openWireless` says the phone initiates synchronization (`CH:379-382`). In fact DiPlay sends its SYN as soon as the phone's marker arrives (`ENG:277`, `ENG:318-325`).
2. **A pure ACK before the peer's SYN kills the link.** In `NEGOTIATING`, any frame with the ACK bit but no prior peer SYN takes the `die("peer iAP2 maxLength cannot carry…")` path (`ENG:382-384`). The error message is misleading. A peer that ACKs DiPlay's SYN first and sends its own SYN later would fail.
3. **The ACK number is not validated at the handshake.** Any ACK value completes it (`ENG:381-390`).
4. **No resync in `DETECTING`.** Any 6 bytes other than the exact marker kill the link (`ENG:272-274`). For example, a phone that sent a SYN directly without the marker would fail.
5. **A bad header consumes 9 bytes.** After a header-checksum failure the engine skips 9 bytes, not 1, so a real `FF 5A` inside them is lost (`ENG:303-305`).
6. **Off-by-one in the send window.** `sendPacket` queues only when `distance > peer.maxOutgoing` (`ENG:478`), but draining uses `< peer.maxOutgoing` (`ENG:405`). Direct sends can reach **maxOutgoing + 1** outstanding packets.
7. **Retransmit timer overwrite.** Each new send sets the retransmit timer to the **newest** packet's deadline (`ENG:497`), not the oldest one's. An older packet's timeout can be delayed until the newest packet's deadline.
8. **The ACK window uses the wrong parameter.** The acked-versus-unacked decision uses `peer.maxAcknowledgements + 10` rather than `maxOutgoing` (`ENG:396`). With a peer maxOutgoing above maxAck + 10, unacked packets would be treated as acked.
9. **Retransmission count is one less than advertised.** It dies when `retransmissions == maxRetransmissions`, so it makes `max − 1` resends (`ENG:509-512`, `ENG:417-420`). With `maxRetransmissions = 0`, packets are never tracked, which is fine.
10. **The EAK resend does not refresh the deadline.** The timer is set to the packet's old, possibly past, deadline (`ENG:423`), so a second resend may follow almost at once.
11. **EAK is sent without the ACK bit** (ctrl `0x20`, `ENG:526`), although the header's ack byte is still filled in. Whether the iPhone expects `0x60` is not determinable from this code.
12. **The delayed ACK timer is re-armed on every in-order packet** (`ENG:472`). A steady trickle below the window size postpones the ACK until either the window count or the `maxAcknowledgements` received-ACK count is reached.
13. **`setWritable(true)` is called after each queued packet is drained** (`ENG:408`), even while other packets are still queued.
14. **SLP (0x08) has no handling.** Sleep frames are silently ignored (§5.8).
15. **EA session 11 is advertised but ignored.** Its payloads are dropped by the channel (`CH:234`). The peer's session list is never checked against the local one.
16. **There is no SYN or marker retry limit at the link layer.** Both resend forever until a higher-layer `awaitReady` timeout or a socket close.
17. **Peer parameters govern both directions.** ACK timeout, maxAck, maxOutgoing, retransmission timeout and maxRetransmissions are all read from `peerSynchronization`, including for DiPlay's own ACK behaviour.
18. **Dead code.** `header.length > 65535` cannot happen (`ENG:306-309`).
