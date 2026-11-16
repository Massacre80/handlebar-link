# 02 — A reliable BLE link to the CB350 meter, and starting the app when the ignition turns on

Status: research and recommendation (no app code). Date: 2026-11-16.
Scope: Handlebar Link (Kotlin, minSdk 33, targetSdk 36, compileSdk 37) on a Poco F1 running
Android 17 / API 37 (Project Infinity X, LineageOS-based, not rooted). The app is a GATT client of
the bonded "HONDA BTU …" meter. It also hosts the Current Time Service (CTS) GATT server that the
meter reads.

Inputs:
- `handlebar-link-prototype/SAB_PROTOCOL.md`, especially §1.4–1.5, §4.2, §7 and "Verified on the
  CB350".
- The prototype sources `GattClient.kt`, `BikeSession.kt`, `BikeService.kt`, `CtsServer.kt`,
  `MainActivity.kt` and `BootReceiver.kt`.
- `notes/bike-test.log`.
- Code read from shallow clones (commit dates in §8):
  - Nordic Android-BLE-Library 2.11
  - Nordic Kotlin-BLE-Library 2.0.0-beta11
  - nRF Toolbox
  - Gadgetbridge
  - AOSP CompanionDeviceManager (CDM) and ActivityManager sources
  - the API 37 SDK (`api-versions.xml` and the stubs)
- Android documentation and issue trackers.

Every source is in §8. The citation keys in [brackets] point there.

---

## 0. TL;DR (the recommendation)

1. **Write our own small GATT client** of about 500–700 lines, plus a pure-Kotlin connection state
   machine. Do not adopt a library. Do copy specific, named behaviours from Nordic's
   Android-BLE-Library and Kotlin-BLE-Library (§1).
   - We use 6 GATT operations against one fixed device.
   - We also need a GATT server (CTS). Kotlin-BLE-Library v2 has no server yet, and it is beta.
     Android-BLE-Library is Java and is being replaced.
   - The hard parts are policy questions, not GATT plumbing: when to retry, when to give up, what
     wakes us, and how bonds break. Those must be ours, and testable without a phone.
2. **Connection policy.**
   - When the bike is known to be present, connect **directly** (`autoConnect=false`): up to 3
     attempts with a 10 s app timeout each.
   - Then fall back to a background **`autoConnect=true`** connection with no timeout. Re-arm it
     immediately after every link loss.
   - Create a new `BluetoothGatt` for every attempt (`close()` first). Never call `gatt.connect()`
     again.
   - On API 37, use the new `connectGatt(BluetoothGattConnectionSettings, Executor, callback)`.
   - Classify disconnect statuses (8/19/22/62/133/147/auth). Use bounded exponential backoff for
     setup failures only.
   - Re-run the full SAB setup on every connection. It is idempotent: the meter answers "already
     registered" (13).
3. **Auto-start on ignition, primary path: CDM.**
   - Associate the meter once with `CompanionDeviceManager.associate()`.
   - Observe its presence with `startObservingDevicePresence(ObservingDevicePresenceRequest)` on
     API 36+, or `startObservingDevicePresence(String)` on 33–35.
   - Receive `CompanionDeviceService.onDevicePresenceEvent(EVENT_BLE_APPEARED | EVENT_BT_CONNECTED)`
     on 36+, or `onDeviceAppeared(AssociationInfo)` on 33–35.
   - From that callback, start the `connectedDevice` foreground service (FGS). Android allows this
     from the background because the app is CDM-associated and holds
     `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`. We verified this in
     `ActiveServices.java` [AOSP-AS].
   - The system scans for the associated address itself (`SCAN_MODE_LOW_POWER`, a hardware filter
     on the MAC address) [AOSP-BLE]. The observation is persisted and works after a reboot, with no
     process of ours running.
4. **Secondary paths:**
   - an "armed" `autoConnect` inside the running FGS (fast reconnection after drop-outs);
   - `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` receivers, which re-assert observation and start
     the FGS in "always armed" mode. `connectedDevice` is on the Android 15 boot allow-list
     [AOSP-AMC].
   - an optional `PendingIntent` scan (`startScan(filters, settings, PendingIntent)`), only as a
     fallback switch if on-bike tests show CDM presence is unreliable on this ROM.
5. **Bonding:** use the CDM dialog to pick the meter. Then call `createBond(TRANSPORT_LE)` (public
   since API 37) and wait for `BOND_BONDED` before any GATT work. Forget the bike with
   `CompanionDeviceManager.removeBond(associationId)` (API 36). From Android 17 that is the only
   non-privileged way to delete a bond [KBLE-ENV].
6. **Testing:** keep a pure reducer `(State, Event) → (State, Effects)` and run it with virtual
   time against a fake GATT port and a scripted fake meter. Use
   `adb shell cmd companiondevice simulate-device-appeared <id>` [AOSP-SHELL] and the on-device
   checklist in §6.

---

## 1. Library or our own thin client?

### 1.1 What the libraries actually do (read from source)

| Concern | Nordic Android-BLE-Library 2.11 (Java, `BleManagerHandler.java`) | Nordic Kotlin-BLE-Library 2.0.0-beta11 | Gadgetbridge (`BtLEQueue.java`) |
|---|---|---|---|
| Serialising operations | One task queue plus an init queue. One operation in flight (`operationInProgress`). Per-request timeouts through `TimeoutableRequest` (`handler.postDelayed`). | One `OperationMutex` (a coroutines `Mutex`) per peripheral (`client-core/.../internal/OperationMutex.kt`). Every operation is a suspend call. | A queue thread per device. Each action waits on a `CountDownLatch`. **No client-side operation timeout**: a lost callback blocks until disconnect. Write-without-response still waits for `onCharacteristicWrite` ("or timing issues"). |
| autoConnect vs direct | `useAutoConnect(true)` connects **directly first**, then falls back to autoConnect (`initialConnection`, :740-751, :2310-2321). On API 34+ it calls `close()` and a new `connectGatt(autoConnect=true)` instead of `gatt.connect()`: "This method forces autoConnect = true (except on Android 14)" (:705-721). | `ConnectionOptions.AutoConnect` (no timeout, no retry). A **non-bonded** device first gets a 10 s direct attempt. A bonded device uses autoConnect only. `Direct(timeout = 10 s, retry = 2, retryDelay = 300 ms)` (`CentralManager.kt` :195-273). | Always `autoConnect=false`: "connectGatt with true doesn't really work ;( too often connection problems" (`BtLEQueue.java:380`). Waiting is done by alarms with exponential backoff (2 s → 64 s) or by its own FGS scan. |
| 133 / 147 | If the error arrives earlier than `CONNECTION_TIMEOUT_THRESHOLD` = 20 s, it counts as a real error and is retried per `retry(n, delay)`. Later than that it counts as a **timeout** and is not retried: "the app should scan before connecting" (:96-101, :2276-2303). | An error 133/147 that arrives after ≥ 25 s becomes a `TimeoutCancellationException`. Earlier, it is retried `retry` times (`Peripheral.kt` :612-637). It notes that 147 `GATT_CONNECTION_TIMEOUT` exists only from API 35; before that a timeout was 133. | 0x81, 0x85 (133), 0x08, auth/encryption errors and 0x93 force `disconnect()` + `close()`. The next attempt creates a fresh object. |
| 8 / 19 / 22 | Mapped to `ConnectionObserver` reasons: link loss, terminate peer user, terminate local host (`GattError.java` :36-42). | Mapped to `ConnectionState.Disconnected.Reason`. | 8 (when it is the connection status) is treated as unhealthy. "Some devices report AUTHORIZATION instead of TIMEOUT during connection setup" (`BtLEQueue.java:453-458`). |
| Discovery timing | Waits before discovery: 1600 ms if bonded, 300 ms if not (`BleManager.getServiceDiscoveryDelay`). The reason is that a Service Changed indication on a bonded link arrives after `STATE_CONNECTED`, and early discovery returns the stale cache. Discovery is postponed while `BOND_BONDING`. | `onServiceChanged` (API 31) triggers rediscovery. | Delays of 300 ms, or 1000 ms bonded on API 35+: "changed significantly at least in Android 8 and 15". Discovery runs on the main thread ("appears to fix Samsung connection problems"). Cached services are reused if present. |
| Service cache refresh | `refreshDeviceCache()` via the hidden `BluetoothGatt.refresh()` reflection (:1628-1647). | — | Not used. |
| Bonding | `createBond`/`ensureBond` requests. A `BOND_STATE_CHANGED` receiver handles `BONDING → NONE` (failure; it still discovers services) and `BONDED → NONE` (bond removed: no auto-reconnect, `close()`) (:336-440). Status 5/8/137 on an operation counts as an auth error; "should never happen" if already bonded (:2560-2575). | `BondingFailedException`. It also covers the Android 16 `ACTION_ENCRYPTION_CHANGE`. **Android 17 removes bond removal for normal apps; only BLUETOOTH_PRIVILEGED or CDM can do it** (`AndroidEnvironment.kt` :238-242, citing AOSP commit f4c5257). | `createBond()` first, connect after `BOND_BONDED` with a 1 s delay. Unpairing uses `CDM.removeBond(associationId)`. |
| BT off/on | On adapter OFF it fails every pending request, resets `operationInProgress`, and calls close (:270-300, which also cites issues #25 and #41 about a stuck queue after a toggle). | Exposes the environment's `bluetoothState` `StateFlow`. | Adapter receiver: ON → `connect()`, OFF → `disconnect()`. |
| autoConnect and private addresses | — | "Android 13 returns an error (status 133 or 135)" for autoConnect to an **unbonded** RPA. Android 12 and older accept it silently and never connect. "Confirmed on … Android 14 and 17" (`AndroidEnvironment.kt` :216-222). | — |
| MTU | Explicit request. | Explicit `requestMtu` before reporting "connected". The Android 17 automatic MTU (`setAutomaticMtuEnabled`) is deliberately not used. | Explicit. |
| GATT server | Yes (`BleServerManager`, `useServer`). | **No** ("Setting up GATT server" is not done). | Yes, with a 30 s server action timeout. |
| Mocking/testing | Little. | **Strong**: `MockAndroidEnvironment` and `PeripheralSpec` with `simulateDisconnection`, `simulateReset`, `simulatePeripheralBondLoss`, `simulateBondRemoval`, `simulateServiceChange`, `simulateProximityChange`, `simulateMacChange`, `simulateCaching`. | — |
| Status | Mature, but the README points to v2 as its replacement. | "Beta … API should not change". | Production, many devices. |
| Who uses it | Many apps. | nRF Toolbox (`ConnectionOptions.AutoConnect`). It relies on library reconnection and has no CDM or boot receiver. | — |

### 1.2 Decision: our own thin client, with Nordic's lessons copied on purpose

Why not Android-BLE-Library:
- It is a 4,000-line Java handler with its own callback model and threading.
- Its connection policy (`useAutoConnect`, `retry`) is close to what we want. But it hides the
  exact moment of close/reconnect, and we must coordinate that with CDM presence, the CTS server
  and Bluetooth toggles.
- Testing our policy would mean testing through it, or mocking it.
- Its maintainers point users to v2.

Why not Kotlin-BLE-Library v2:
- It is beta.
- It has no GATT server role, and we need the CTS server on the same link.
- Its connection objects are its own abstraction, so the policy layer would again be split between
  the library and us.
- Its mock framework is the most attractive part. We can take its *ideas* (the list of
  `simulate*` events) without the dependency.

Why our own client:
- The surface is tiny:
  - connect;
  - MTU;
  - discover;
  - 2 reads;
  - 3 CCCD writes;
  - write-without-response;
  - notifications;
  - a server with 2 read characteristics plus one notify.
- The prototype `GattClient.kt` (180 lines) already works end to end on the bike. What it lacks is
  policy and robustness, not features.

**Behaviours we must copy** (each one is a test case in §5):

| # | Behaviour | From |
|---|---|---|
| C1 | One operation in flight per link. Every operation has a timeout, and **a timeout or disconnect fails the pending op**. Keep timeouts on the client, unlike Gadgetbridge. | KBLE `OperationMutex`; ABL `TimeoutableRequest` |
| C2 | A new `BluetoothGatt` for every attempt: `close()` the old one, then `connectGatt`. Never use `gatt.connect()` to re-arm. Since Android 14 it no longer forces autoConnect. | ABL :665-721 |
| C3 | Ignore callbacks from a `BluetoothGatt` that is not the current one. Use a generation counter. | GB `checkCorrectGattInstance()` |
| C4 | Classify 133/147 by elapsed time. At ≥ 25 s it is a direct-connect timeout (the device is not reachable). Earlier, it is a transient error: retry quickly. | ABL :96-101; KBLE `Peripheral.kt` :612-623 |
| C5 | Direct first, then autoConnect. A bonded device may go straight to autoConnect. | ABL `initialConnection`; KBLE `AutoConnect` |
| C6 | Do not discover services while `BOND_BONDING`. If bonding is in progress, discover after `BOND_BONDED`. | ABL :2257-2266, :400-418 |
| C7 | Handle `onServiceChanged` (API 31+) by discarding characteristic handles and re-running setup. | ABL :2516-2530 |
| C8 | Treat `BONDED → NONE` as a terminal event for auto-reconnect: close and ask the user. | ABL :370-393; KBLE AutoConnect docs |
| C9 | On adapter OFF, fail everything, reset the queue and close. On ON, start again. | ABL :270-300 |
| C10 | Never autoConnect to an unbonded device: it fails on 13+ if the device uses an RPA. | KBLE `AndroidEnvironment.kt` :216-222 |
| C11 | Remove the bond through CDM on Android 17. | KBLE `AndroidEnvironment.kt` :238-242; GB `removeBond(associationId)` |
| C12 | Wait for `onCharacteristicWrite` even for write-without-response (API 33 `writeCharacteristic(c, value, type)` returns a status). Retry `ERROR_GATT_WRITE_REQUEST_BUSY` after a short delay. | GB `WriteAction`; ABL :1239-1246 |
| C13 | Rate-limit crash → restart loops. | GB `reconnectAfterRestart()`, 10 min |

---

## 2. Connection architecture: an explicit state machine

### 2.1 Layers

```
BikeLinkService (FGS, type connectedDevice)          ← owns lifetime, notification, wakelock
 └─ LinkController  (single-threaded actor: Channel<Event>)  ← the state machine, PURE reducer
     ├─ GattPort     (interface)  → AndroidGattPort (BluetoothGatt, single-thread Executor)
     ├─ CtsServerPort(interface)  → AndroidCtsServer (BluetoothGattServer)
     ├─ SabSession   (protocol: assignments, phone mode, button decoding) — uses GattPort ops
     ├─ Clock/Timers (interface)  → coroutine delays in prod, virtual time in tests
     └─ SystemSignals: BT adapter state, bond state, KEY_MISSING, CDM presence, power/screen
CompanionPresenceService (CompanionDeviceService) → posts Presence events / starts the FGS
Boot/PackageReplaced receivers → re-assert CDM observation, optionally start the FGS
```

Rules:
- Every Android callback is turned into an `Event` and sent into a single channel. Only the reducer
  changes state. Effects (connect, close, write, start/stop timers, notify UI) are returned as data
  and run by an interpreter.
- This removes the races the prototype has, for example `connectWaiter` and `pending` being
  replaced from different threads.
- On API 37, give `connectGatt(settings, executor, cb)` a dedicated single-thread executor. Below
  37, use the `Handler` overload on a `HandlerThread`. Do not use the main thread: the CarLink UI is
  busy, and Gadgetbridge's "main thread fixes Samsung" note is about vendor stacks, not AOSP.

### 2.2 States

| State | Meaning | Resources held |
|---|---|---|
| `Unconfigured` | No association or bond yet. | — |
| `Dormant` | Configured, the bike is not present, and no FGS is running. CDM observes presence on our behalf. | None (only the system's CDM scan). |
| `Arming` | FGS started. Checking the prerequisites: BT on, `BLUETOOTH_CONNECT` granted, bond present, CTS server open. | FGS, CTS server |
| `DirectConnecting(attempt)` | `connectGatt(autoConnect=false)`. Used when we have a fresh sign that the bike is advertising (CDM appeared, scan hit, user tap, BT just turned on during a ride). | `BluetoothGatt` (gen *n*) |
| `Waiting` | `connectGatt(autoConnect=true)` pending: the controller connects as soon as the meter advertises. No timeout. | `BluetoothGatt` (gen *n*) |
| `Preparing` | Link up. Waiting for bonding to settle (C6), then MTU, then discovery. | link |
| `Initialising` | SAB setup: optional vehicle-info read (first time only), 3 × CCCD, assignments, END, REQUEST_SAB_MODE. | link, short wakelock |
| `Ready` | Forwarding presses. No keepalive (SAB §7). | link |
| `Backoff(reason, n)` | Setup failed. Everything is closed and a timer runs. | FGS, CTS |
| `BtOff` | Adapter off or turning off. All GATT objects and the server are closed. | FGS |
| `BondBroken` | Bond missing or keys rejected. Auto-reconnect stops and a user notification is shown. | FGS (may stop) |
| `Lingering` | Link lost and presence gone. Keep `Waiting` armed for `LINGER` (default 10 min: fuel stops, stalls), then stop the FGS and go to `Dormant`. | FGS, `BluetoothGatt` (auto) |
| `Stopped` | User stopped, or the association was removed. | — |

"Present" is a flag the controller derives from CDM events, scan hits and the link itself. It is
not a state. It decides `Lingering → Dormant` and whether a `DirectConnecting` is worth trying.

### 2.3 Events

- Lifecycle:
  - `Start(trigger)`: CDM, boot, user, package-replaced, scan-hit, power-connected
  - `Stop(user)`
  - `AssociationRemoved` (CDM `EVENT_ASSOCIATION_REMOVED`, API 36.1)
- Presence:
  - `PresenceAppeared` (BLE appeared / BT connected)
  - `PresenceGone` (BLE disappeared / BT disconnected)
  - `ScanHit`
- Adapter: `BtTurningOff`, `BtOff`, `BtOn`
- Bond:
  - `BondChanged(prev, new)`
  - `KeyMissing` (`ACTION_KEY_MISSING`, API 36; `BOND_LOSS_REASON_*` extras from 36.1)
  - `EncryptionChanged` (API 36)
- GATT:
  - `Conn(gen, status, newState, elapsed)`
  - `MtuDone(gen, status)`
  - `Discovered(gen, status)`
  - `OpDone(gen, op, status, value)`
  - `Notification(gen, uuid, value)`
  - `ServiceChanged(gen)`
- Server: `CtsRead`, `ServerOpenFailed`
- Timers:
  - `ConnectTimeout(gen)`
  - `OpTimeout(gen, op)`
  - `InitWatchdog(gen)`
  - `BackoffElapsed`
  - `LingerElapsed`
  - `ReadyStable` (60 s in `Ready`, which resets the counters)

### 2.4 Transitions (the important ones)

```
Unconfigured --(pairing done: association+bond)--> Arming
Dormant      --Start(any)--------------------------> Arming
Arming       --prereqs ok & presentHint------------> DirectConnecting(1)
Arming       --prereqs ok & !presentHint-----------> Waiting
Arming       --BT off------------------------------> BtOff
Arming       --bond missing------------------------> BondBroken

DirectConnecting(n) --Conn(CONNECTED)--------------> Preparing
DirectConnecting(n) --Conn(fail, early) & n<3------> DirectConnecting(n+1)   [after 300·n ms, new gatt]
DirectConnecting(n) --Conn(fail) & n=3 | timeout---> Waiting                 [close, new gatt autoConnect]
Waiting      --Conn(CONNECTED)---------------------> Preparing
Waiting      --PresenceAppeared/ScanHit------------> DirectConnecting(1)     [close auto gatt first]
Waiting      --Conn(DISCONNECTED, any status)------> Waiting (re-arm, new gatt) or Backoff if repeated <1 s apart

Preparing    --bond BONDING------------------------> Preparing (hold; discovery deferred, C6)
Preparing    --MTU done/failed → Discovered ok-----> Initialising
Preparing    --Discovered missing SAB services-----> Backoff(ServicesMissing) [refresh() once per presence cycle]
Initialising --all steps ok------------------------> Ready
Initialising --OpTimeout/OpFailed/InitWatchdog-----> Backoff(InitFailed)    [disconnect, close]
Initialising --auth status 5/15/137----------------> BondBroken (if bond gone) or Backoff (once)
Ready        --ServiceChanged----------------------> Preparing (rediscover, re-init)
Ready        --Conn(DISCONNECTED, 8/19/0/…)--------> Waiting (immediately re-armed; no backoff)
any linked   --Conn(DISCONNECTED) & !present-------> Lingering
Lingering    --Conn(CONNECTED)---------------------> Preparing
Lingering    --PresenceAppeared--------------------> DirectConnecting(1)
Lingering    --LingerElapsed-----------------------> Dormant (close gatt, stop server, stopForeground, stopSelf)
Backoff      --BackoffElapsed----------------------> presentHint ? DirectConnecting(1) : Waiting
any          --BtTurningOff/BtOff------------------> BtOff (close all gatts, close server)
BtOff        --BtOn--------------------------------> Arming (reopen server, then presentHint=true → direct)
any          --BondChanged(BONDED→NONE) | KeyMissing×2 --> BondBroken
any          --Stop(user) | AssociationRemoved-----> Stopped
```

### 2.5 Timeouts

Each one is a constant in one file. The values come from the protocol notes (SAB §7) and the
libraries.

| What | Value | Why |
|---|---|---|
| Direct connect, per attempt | **10 s** (app timer, then `close()`) | KBLE default. The system gives up at about 30 s with 133/147 anyway. When presence is fresh, the meter advertises fast, so a 10 s miss means it isn't there or the stack is stuck. |
| Direct retries | 3 attempts, 300 ms and then 600 ms apart | KBLE `retry = 2, 300 ms`; ABL `retry(n, delay)`. |
| close → next `connectGatt` | ≥ 300 ms settle | ABL sleeps 200 ms after `close()`: "close() is asynchronous". |
| autoConnect wait | none | It is a controller accept-list connection. |
| Bond settle in `Preparing` | wait while `BOND_BONDING`, at most 30 s | RoadSync bond step: 30 s (SAB §1.4). |
| MTU request (247) | 3 s; failure is tolerated | RoadSync tolerates failure (SAB §1.5). Since Android 14 the stack answers the first `requestMtu()` by negotiating 517 and ignores later requests [DOC-MTU14]. On API 37 the stack can do the exchange itself (`setAutomaticMtuEnabled`, default true). Our payloads are ≤ 20 bytes, so the MTU only matters as a step to tolerate. |
| `discoverServices` | **15 s** (RoadSync 20 s) | On a bonded link Android usually answers from the GATT cache. The prototype log shows connect → services listed in 2.7 s, MTU included. |
| Read / CCCD write / write-no-response callback | 3 s each | — |
| Assignment reply | 2 s × 5 tries per frame | SAB §5.2, prototype. |
| Command receipt (REQUEST_SAB_MODE) | 2 s × 3 | SAB §6.1. |
| **Init watchdog** (link up → `Ready`) | **20 s** | The happy path takes about 3.4 s (log: link 20:56:22.39 → "Connected" 20:56:25.78). |
| Disconnect request → `STATE_DISCONNECTED` | 2 s, then `close()` anyway | — |
| `Ready` stable (counter reset) | 60 s | — |
| `LINGER` after link loss with presence gone | 10 min (setting) | Covers stalls and fuel stops without a cold start. |

### 2.6 Disconnect-status classification

On a connection-state change the status is an HCI or GATT reason. Do not confuse it with an
operation status: 8 means `GATT_INSUFFICIENT_AUTHORIZATION` on an operation, but HCI "connection
timeout" on a connection change. Gadgetbridge's comment notes that some stacks mix them up.

| Status (when `newState = DISCONNECTED`) | Meaning | Action | Counts as failure? |
|---|---|---|---|
| 0 | Clean (we disconnected, or a graceful remote) | If not user-initiated, re-arm `Waiting`. | no |
| **8** (0x08) | Supervision timeout. **This is what ignition-off produces** (`bike-test.log` 20:58:11 `status=8`). | Re-arm `Waiting` at once. Mark presence as unknown. | no |
| 19 (0x13) | The remote terminated (meter shutting down or rejecting) | Re-arm `Waiting`. If it happens within 2 s of connect 3 times in a row, go to `Backoff`. | only if repeated |
| 22 (0x16) | The local host terminated (our stack: pairing failure, BT toggling, another local client) | Check the bond state. Short `Backoff`. | yes |
| 62 (0x3E) | Connection failed to be established | Retry direct (it is a radio collision). | yes, if it exhausts the retries |
| 61 (0x3D) | MIC failure: key mismatch | `BondBroken` candidate (with `KeyMissing`). | — |
| 5 / 15 / 137 | Insufficient authentication/encryption, auth failure | If not bonded: `BondBroken`. If bonded: 1 retry, then `BondBroken`. | — |
| **133** (0x85) | Generic GATT_ERROR: before API 35 also the direct-connect timeout | ≥ 25 s since `connectGatt`: timeout, so go to `Waiting`. Earlier: transient, so close, settle, retry (C4). | yes |
| 147 (0x93) | `GATT_CONNECTION_TIMEOUT` (API 35+) | Same as a 133 timeout. | no |
| 257 | GATT_FAILURE | Like 133 early. | yes |

**Escalation** (each counter is reset by `ReadyStable`, a new presence cycle, or `BtOn`):

- 3 consecutive `ServicesMissing` or discovery failures: call `BluetoothGatt.refresh()` once per
  presence cycle (hidden API, best effort; log whether it was found), then reconnect.
- 6 early 133s within 2 min:
  - the stack is probably wedged;
  - post a notification "Bluetooth needs a restart: toggle Bluetooth". Apps targeting 33+ cannot
    toggle the adapter (`BluetoothAdapter.disable()` is deprecated and does nothing for them);
  - keep `Waiting` armed with a 60 s backoff between re-arms.
- `Backoff` for setup failures: 1 s, 2 s, 4 s, 8 s, 16 s, capped at 30 s, with ±20 % jitter.
- Link losses (8/19 after `Ready`) **never back off**. Reconnecting after a stall must be instant.

### 2.7 What is reset, and when

| Scope | Reset on | Contents |
|---|---|---|
| Per GATT object (generation) | every `close()` | Pending operation (failed with `Disconnected`), operation queue, characteristic handles and service references, CCCD-enabled flags, MTU, notification collectors, assignment request-ID counter (restarts at 1), command-ID counter, "phone mode" flag, init watchdog. |
| Per presence cycle (ignition on → off) | `PresenceAppeared` after `Dormant`/`Lingering`, `BtOn` | Failure counters, backoff exponent, "refresh done" flag, 133 counter, `KeyMissing` count. |
| Per adapter session | `BtOff` | Everything above, **plus the CTS `BluetoothGattServer`** (it dies with the adapter; reopen it on `BtOn` *before* connecting, because the meter reads CTS about 1.1 s after the link comes up). |
| Persistent (DataStore, not plain prefs) | only on Forget/Re-pair | `associationId`, identity address plus address type, meter name, series code/model, display capability, firmware string, last-good timestamps, diagnostics ring buffer. |

### 2.8 Notes on the CTS server and the link

- Open the `BluetoothGattServer` in `Arming`, before any `connectGatt`. If `openGattServer` returns
  null (adapter not yet up), retry on `BtOn`. A server that is not open when the meter reads CTS
  means a wrong meter clock, not a broken link. Log `meter read the clock` per connection as a
  health signal.
- One LE link carries both GATT roles. Closing our client does not drop the link while another
  local client (or Android's own HID host, if the meter exposed HOGP) holds it. Watch for "we
  closed but no `DISCONNECTED`, and the meter keeps sending" in the logs.
- Request `CONNECTION_PRIORITY_BALANCED` (the default). RoadSync never sets a priority, and button
  latency on the default interval is fine.

---

## 3. Auto-start on ignition

### 3.1 Options compared

| Path | What wakes us | Latency (expected; measure in §6) | Survives kill / reboot | Cost | Verdict |
|---|---|---|---|---|---|
| **A. CDM presence** | The system's BLE scan for the associated MAC (`ScanFilter.setDeviceAddress`, `SCAN_MODE_LOW_POWER`, `CALLBACK_TYPE_FIRST_MATCH \| MATCH_LOST`) [AOSP-BLE]. It also fires on ACL connect (`BluetoothConnectionCallback`, `EVENT_BT_CONNECTED`) [AOSP-BTP]. | Low-power duty cycle. **With the screen off, the stack downgrades background scans to `SCAN_MODE_SCREEN_OFF`: a 512 ms window every 10.24 s** [DOC-SCANMGR]. Expect roughly 1–12 s; this is the main latency risk. **Measure.** | Yes. The association and the "notify on nearby" flag are persisted by `system_server`. The scan restarts whenever LE turns on (`checkBleState`). No app process is needed. | None to us | **Primary** |
| **B. autoConnect GATT armed in a running FGS** | The controller accept list ("background connection") | Usually fast; depends on the stack's background scan parameters. **Measure.** | No: it dies with our process, and on a BT toggle it must be re-issued. | Permanent FGS notification; the process must stay alive | **Secondary**: always used while the FGS runs; can be the only path in "always armed" mode |
| **C. PendingIntent scan** `startScan(filters, settings, PendingIntent)` (API 26) | A hardware-filtered scan delivered as a broadcast to our manifest receiver. The delivery itself is **not** an FGS-start exemption: it is a plain `PendingIntent.send()` with no temporary allow-list [DOC-SCANNER]. Our start relies on the CDM exemption. | Similar to A (same screen-off downgrade) | Survives process death; **not** reboot or BT toggle (re-register on boot/`BtOn`); cleared by force-stop | Small | **Fallback switch**, off by default |
| D. `BOOT_COMPLETED` → FGS + autoConnect | Boot | n/a | — | — | Needed for "always armed" mode, and to re-assert A |
| E. JobScheduler / alarms polling | — | Slow, Doze-gated | — | — | Rejected |

Why A is the primary path, beyond latency:

1. **Permission to start the FGS from the background.** `ActiveServices` grants
   `REASON_COMPANION_DEVICE_MANAGER` to any app that has a CDM association **and** holds
   `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` or
   `REQUEST_COMPANION_RUN_IN_BACKGROUND` [AOSP-AS :8808-8826].
   - This does not depend on the device being present at that moment. It also covers starts from
     path C, from boot and from `MY_PACKAGE_REPLACED`.
2. **Process importance.** While the device is present, the system binds our
   `CompanionDeviceService` with `BIND_ALMOST_PERCEPTIBLE` (non-self-managed association)
   [AOSP-CSC :76-94]. Our process is then much less likely to be killed by the low-memory killer.
3. **Doze.** If the app requests `REQUEST_COMPANION_RUN_IN_BACKGROUND`, CDM adds it to the
   power-save **allow-list while a device is present**, and removes it when no device is present
   [AOSP-EXEMPT :128-143].
4. **Google's own recommendation.** For "stay connected and listen to notifications", Google
   recommends CDM presence observing plus `CompanionDeviceService` [DOC-BLE-BG]. The docs say an
   FGS is not strictly required. We still start a `connectedDevice` FGS:
   - its process priority is higher than `BIND_ALMOST_PERCEPTIBLE` (adj 225);
   - the CDM binding "will be unbound when the app is removed from task manager" (a swipe from
     recents) [AOSP-CSC];
   - it gives the rider a visible status.
5. **Rotating addresses.** CDM has "no support for non-bonded rotating MAC addresses"; a device
   with an RPA must be bonded [DOC-BLE-BG]. We always bond (§4).

Other wake signal: `ACTION_ACL_CONNECTED` is on the implicit-broadcast exemption list, and the BT
stack sends it with a temporary FGS-allowed allow-list of about 20 s [DOC-AGENT, inference from
`Utils.getTempBroadcastOptions`]. It fires only once *something* connects, so it is useful as a
diagnostic, not as a wake-up.

### 3.2 Exact APIs by level (checked against the API 37 SDK `api-versions.xml`)

| API | Since | Deprecated |
|---|---|---|
| `CompanionDeviceManager.associate(AssociationRequest, Executor, Callback)` | 33 | — |
| `Callback.onAssociationPending(IntentSender)` / `onAssociationCreated(AssociationInfo)` | 33 | — |
| `Callback.onFailure(int errorCode, CharSequence error)` | 36 (`onFailure(CharSequence)` before) | — |
| `AssociationInfo.getId()`, `getDeviceMacAddress()` | 33 | — |
| `AssociationInfo.getAssociatedDevice()` → `AssociatedDevice.getBleDevice()` (a `ScanResult`) | 34 | — |
| `startObservingDevicePresence(String address)` / `stopObserving…(String)` | 31 | **36** |
| `startObservingDevicePresence(ObservingDevicePresenceRequest)` / `stopObserving…(…)` | **36** | — |
| `ObservingDevicePresenceRequest.Builder().setAssociationId(int)` (or `.setUuid(ParcelUuid)`, which needs `REQUEST_OBSERVE_DEVICE_UUID_PRESENCE`) | 36 | — |
| `CompanionDeviceService.onDeviceAppeared(AssociationInfo)` / `onDeviceDisappeared(AssociationInfo)` | 33 | **36** |
| `CompanionDeviceService.onDevicePresenceEvent(DevicePresenceEvent)` | **36** | — |
| `DevicePresenceEvent.EVENT_BLE_APPEARED`=0, `EVENT_BLE_DISAPPEARED`=1, `EVENT_BT_CONNECTED`=2, `EVENT_BT_DISCONNECTED`=3 | 36 | — |
| `DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED`=6 | 36.1 | — |
| `CompanionDeviceManager.removeBond(int associationId)` | **36** | — |
| `CompanionDeviceManager.disassociate(int)` / `getMyAssociations()` | 33 | — |
| `BluetoothDevice.createBond(int transport)`, `cancelBondProcess()`, `getBondStatus(int)` | **37** | — |
| `BluetoothDevice.connectGatt(BluetoothGattConnectionSettings, Executor, BluetoothGattCallback)` | **37** | old overloads deprecated in 37 |
| `BluetoothGattConnectionSettings.Builder().setAutoConnectEnabled / setTransport / setAutomaticMtuEnabled / setOpportunisticEnabled` | 37 | — |
| `BluetoothDevice.ACTION_KEY_MISSING`, `ACTION_ENCRYPTION_CHANGE`, `getIdentityAddressWithType()` | 36 | — |
| `BluetoothDevice.EXTRA_BOND_LOSS_REASON`, `BOND_LOSS_REASON_LE_ENCRYPT_FAILURE` | 36.1 | — |
| `BluetoothDevice.getKeyMissingCount()` | 36.1 | — |
| `BluetoothDevice.getAddressType()` | 35 | — |
| `BluetoothGatt.GATT_CONNECTION_TIMEOUT` = 147 | 35 | — |

**Important AOSP detail: both callbacks fire.** In `DevicePresenceProcessor.onDevicePresenceEvent`,
the system calls `notifyDevicePresenceEvent()` (→ `onDevicePresenceEvent`) **and also**
`legacyNotifyDevicePresenceEvent()` (→ `onDeviceAppeared/Disappeared`) [AOSP-DPP :690-718].
Implement both callbacks so that 33–35 is covered, and make the handling idempotent. Recommended:
on SDK ≥ 36, ignore the legacy ones.

**Presence going away mid-ride.** When the meter is connected it most likely stops advertising, so
`EVENT_BLE_DISAPPEARED` may arrive during the ride.
- The ACL connection produces `EVENT_BT_CONNECTED` (Classic **or LE** ACL, via
  `BluetoothConnectionCallback`) and keeps the association "present" [AOSP-BTP, DOC-AGENT].
- The LineageOS `lineage-24.0` `BleDeviceProcessor` goes further and leaves BT-connected devices
  out of CDM's BLE scan.
- After a disconnect, AOSP schedules `BLE_DISAPPEARED` about 10 s later unless the device is seen
  again [AOSP-DPP :1178-1201].
- Still verify on device (D16). Either way, the
FGS must **never** stop because of a disappear event while the GATT link is up. Only
`Lingering`/`LingerElapsed` stops it.

**Address matching.** CDM matches on the MAC stored in the association
(`ScanFilter.setDeviceAddress`).
- If the meter advertises with a public or static random address (likely: RoadSync, the prototype
  and autoConnect all work with a stored address), this is fine.
- If it used a rotating RPA, the association would store one RPA, and presence would stop working
  after rotation.
- Check once at pairing: `getAddressType()` (35) and `getIdentityAddressWithType()` (36), compared
  with `AssociationInfo.getDeviceMacAddress()`. Store the result in diagnostics. If it is an RPA,
  enable fallback C with a service-UUID filter (`4d87b1ea-…`) instead.

### 3.3 Manifest entries

```xml
<!-- Bluetooth -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />   <!-- only for fallback C / diagnostics -->
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />

<!-- Companion device -->
<uses-feature android:name="android.software.companion_device_setup" android:required="true" />
<uses-permission android:name="android.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE" />
<uses-permission android:name="android.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND" />
<uses-permission android:name="android.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND" />

<!-- Foreground service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<application …>
    <service android:name=".link.BikeLinkService"
        android:exported="false"
        android:foregroundServiceType="connectedDevice" />

    <service android:name=".link.CompanionPresenceService"
        android:exported="true"
        android:permission="android.permission.BIND_COMPANION_DEVICE_SERVICE">
        <intent-filter>
            <action android:name="android.companion.CompanionDeviceService" />
        </intent-filter>
    </service>

    <receiver android:name=".link.SystemEventsReceiver" android:exported="false">
        <intent-filter>
            <action android:name="android.intent.action.BOOT_COMPLETED" />
            <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
        </intent-filter>
    </receiver>

    <!-- Fallback C only: target of the PendingIntent scan (explicit, not exported) -->
    <receiver android:name=".link.ScanHitReceiver" android:exported="false" />
</application>
```

Notes:

- `connectedDevice` FGS on Android 14+:
  - the manifest type is required, plus `FOREGROUND_SERVICE_CONNECTED_DEVICE`;
  - at least one of the type's prerequisites must be held. `BLUETOOTH_CONNECT` is one of them;
    Gadgetbridge skips `startForeground` on 34+ when its BT permissions are missing.
  - Call `startForeground(id, n, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` within the usual
    deadline after `startForegroundService`.
- **Android 15 boot restriction.** With target ≥ 35, an FGS started from `BOOT_COMPLETED` is
  limited to an allow-list. The AOSP default list includes `CONNECTED_DEVICE` (also `HEALTH`,
  `REMOTE_MESSAGING`, `SYSTEM_EXEMPTED`, `SPECIAL_USE`, `LOCATION`) [AOSP-AMC :191-197,
  AOSP-AS :1170-1180]. Our type is allowed.
- `connectedDevice` has **no** FGS runtime limit: the Android 15 6-hour limits apply to `dataSync`
  and `mediaProcessing`.

### 3.4 Lifecycle, end to end

**Ignition on (normal case, app process dead, phone dozing):**
1. The system CDM scan matches the meter. CDM binds `CompanionPresenceService` and allow-lists the
   app for Doze (with `RUN_IN_BACKGROUND`).
2. It calls `onDevicePresenceEvent(EVENT_BLE_APPEARED)` (and the legacy `onDeviceAppeared`).
3. The handler calls `ContextCompat.startForegroundService(BikeLinkService, Start(CDM, present=true))`.
   This is allowed by the CDM exemption.
4. `BikeLinkService.onStartCommand` then:
   - calls `startForeground(connectedDevice)`;
   - opens the CTS server;
   - moves the controller to `Arming → DirectConnecting(1)`, then
     `Preparing → Initialising → Ready`.
   - Target: under 5 s from the presence event.
5. While the link is up, `EVENT_BT_CONNECTED` keeps the app "present". Ignore BLE
   appeared/disappeared churn.

**Ignition off:**
1. The link drops with status 8. The controller goes to `Waiting`, with autoConnect re-armed at
   once.
2. Presence goes (`EVENT_BT_DISCONNECTED`, later no BLE) → `Lingering(10 min)`.
3. If the ignition comes back on within that window, autoConnect or a presence-appeared event
   reconnects.
4. Otherwise: `LingerElapsed` → close the GATT, close the server, `stopSelf()` → `Dormant`.

**Reboot:**
1. CDM associations and their "notify on device nearby" flag persist in `system_server`. The CDM
   scan resumes as soon as LE is on, without our app [AOSP-BLE `checkBleState`, AOSP-DPP].
2. **Caveat:** if the user is not unlocked yet (a PIN/pattern after reboot), CDM queues the event
   (`onDeviceLocked`) and delivers it after unlock [AOSP-DPP :545-566]. On a bike phone with a PIN,
   the first ride after a reboot needs one unlock. Recommend no secure lock screen on this
   dedicated device, or accept that.
3. `BOOT_COMPLETED` → `SystemEventsReceiver`:
   - re-assert `startObservingDevicePresence` (idempotent; it also repairs a lost observation after
     a CDM data issue);
   - re-register the fallback-C PendingIntent scan if it is enabled;
   - in "always armed" mode only, start the FGS (`connectedDevice` is allowed from boot).
   - In "presence-driven" mode, do **not** start the FGS. If the bike is already on at boot (for
     example, the phone powers up with the ignition), CDM delivers `APPEARED` shortly after its
     scan starts.

**App update:** the process is killed and sticky services are not restarted automatically.
`MY_PACKAGE_REPLACED` → re-assert observation, and start the FGS if the meter is present (check:
`EVENT_BT_CONNECTED` state, or simply start in `Waiting` with a short linger).

**Process killed while riding:**
- The stack unregisters our GATT client, so the link drops and the meter sees a disconnect.
- `START_STICKY` restarts the service, normally within seconds while CDM keeps us bound. On
  restart, the controller starts in `Arming` with `presentHint=true`.
- Rate-limit crash loops (C13).

**Modes (a user setting; default "presence-driven"):**
- *Presence-driven:* the FGS runs only while the bike is present, plus `LINGER`. No permanent
  notification.
- *Always armed:* the FGS runs permanently with autoConnect armed. This is the prototype's
  behaviour. It is the most robust if CDM presence turns out to be slow or flaky on this ROM. The
  cost is a permanent notification, which is fine on a dedicated bike phone.
- Decide the default from the §6 latency measurements.

### 3.5 Fallback C: the PendingIntent scan, when enabled

- `BluetoothLeScanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(4d87b1ea-…))
  .build()), ScanSettings(SCAN_MODE_LOW_POWER, CALLBACK_TYPE_FIRST_MATCH),
  PendingIntent.getBroadcast(..., FLAG_MUTABLE))`.
  - Use the service UUID (optionally plus the name), not the address. An address filter whose type
    is not `ADDRESS_TYPE_PUBLIC`, or that carries an IRK, needs `BLUETOOTH_PRIVILEGED`
    [DOC-SCANNER]. Match the identity address in the receiver instead.
  - The intent **must** be mutable so the system can add `EXTRA_LIST_SCAN_RESULT`,
    `EXTRA_CALLBACK_TYPE` and `EXTRA_ERROR_CODE`.
  - Do not use `FLAG_CANCEL_CURRENT`, or `stopScan(pi)` may not match.
  - Always use a non-empty filter: unfiltered scans are stopped while the screen is off.
  - `FIRST_MATCH` scans are exempt from the 10-minute "long scan" downgrade [DOC-AGENT/AOSP
    `AppScanStats`].
  - The Bluetooth process holds the scan only in memory. It dies with a BT restart or reboot, and is
    cancelled on force-stop.
- Re-register after boot, `BtOn` and package replace. Stop it while the FGS is running, to avoid a
  duplicate scan.
- The receiver calls `startForegroundService` (allowed because of the CDM exemption) and then
  `stopScan(pendingIntent)` until the next `Dormant`.
- Mind the scan start throttle (about 5 `startScan` calls per 30 s per app). Our usage is far below
  it.

---

## 4. Bonding and first pairing (with CDM to pick the device)

### 4.1 Flow

1. **Preconditions screen.**
   - Grant `BLUETOOTH_CONNECT` and `POST_NOTIFICATIONS` (and `BLUETOOTH_SCAN` only if fallback C
     or diagnostics need it).
   - BT must be on.
   - Tell the rider: "Ignition on". If the meter was paired to another phone or the bond was lost:
     "Meter → Bluetooth reset" (SAB §1.1, steps 1–4).
2. **Association** (the system dialog lists matching meters):
   ```
   AssociationRequest.Builder()
     .addDeviceFilter(BluetoothLeDeviceFilter.Builder()
         .setNamePattern(Pattern.compile("HONDA BTU [A-Za-z0-9]*"))      // RoadSync's pattern
         .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(VEHICLE_INFO_4d87b1ea)).build())
         .build())
     .setSingleDevice(true)          // one bike → straight confirm dialog
     .build()
   cdm.associate(request, mainExecutor, callback)
   ```
   - `onAssociationPending(intentSender)` → launch it with the Activity Result API.
   - `onAssociationCreated(info)`: keep `info.id` and `info.deviceMacAddress`, and take the
     `BluetoothDevice` from `info.associatedDevice?.bleDevice?.device` (API 34+), or from
     `adapter.getRemoteDevice(mac)`.
   - `onFailure(code, msg)` (36): show it. `RESULT_USER_REJECTED`, `RESULT_DISCOVERY_TIMEOUT` and
     the others are constants since 34.
   - **No `setDeviceProfile`.** WATCH/GLASSES etc. need role permissions and grant things we don't
     need. RoadSync and Gadgetbridge both use no profile.
3. **Bond, before any GATT** (RoadSync's order, SAB §1.4; Gadgetbridge's order):
   - If `device.bondState == BOND_BONDED` (for example, previously paired in system Settings), skip
     ahead. If the user wants a fresh start, call `cdm.removeBond(id)` first.
   - Otherwise:
     - register for `ACTION_BOND_STATE_CHANGED` (filtered to this address);
     - call `device.createBond(BluetoothDevice.TRANSPORT_LE)` on 37+, else `createBond()`:
       - `createBond(int)` is public in the API 37 SDK (`api-versions.xml` since 37.0, with javadoc
         in the stubs); on earlier levels it was `@hide`.
       - The plain `createBond()` uses `TRANSPORT_AUTO`, which resolves to LE for an LE-only
         device.
       - Do it **within 10 minutes of the CDM approval**. Then the system may skip the pairing
         confirmation dialog, if the pairing variant allows it [DOC-CDM-PAIR]. The CDM dialog
         itself does not bond or connect.
     - accept the system pairing dialog. The method (Just Works or numeric comparison) is still
       unknown (SAB §10.3). `getBondStatus(TRANSPORT_LE).pairingAlgorithm` (37) logs it.
   - Outcomes:
     - `BONDED` → continue.
     - `NONE` after `BONDING` → show the failure with a retry button.
     - 30 s with no result → `cancelBondProcess()` (37) and fail.
   - **Do not** connect GATT while bonding (C6). The prototype's "BOND_NONE → try anyway" is
     dropped. The meter worked bonded; unbonded autoConnect would fail on an RPA (C10), so we never
     run unbonded.
4. **Address check:** compare `getAddressType()` and `getIdentityAddressWithType()` with the
   association MAC (§3.2). Store `identityAddress` and use it from then on.
5. **First connection:**
   - direct connect;
   - full session plus the onboarding reads (Vehicle Information series code `03F5` → CB350;
     display capability; DIS and firmware), which are then cached. Later sessions skip them.
   - Confirm that `meter read the clock` appeared.
6. **Observation:** `startObservingDevicePresence(ObservingDevicePresenceRequest.Builder()
   .setAssociationId(id).build())` on 36+, or `startObservingDevicePresence(mac)` on 33–35.
7. **Battery:** if the user previously restricted battery use, ask to set "Unrestricted"
   (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is optional, because the CDM allow-list covers
   presence time). Check `PowerManager.isIgnoringBatteryOptimizations` and the standby bucket in
   diagnostics.
8. **Persist:** association id, identity address, name, model. The FGS goes to `Waiting`
   (presence-driven mode then lingers out).

### 4.2 Forget, re-pair, change bike

- **Forget:**
  1. `stopObservingDevicePresence(request)`;
  2. stop the FGS;
  3. `cdm.removeBond(id)` (36+; the only non-privileged way on 17 [KBLE-ENV]);
  4. `cdm.disassociate(id)`;
  5. clear persistent state.
- **Re-pair after a lost bond:** the same flow, but reuse the association if it is still valid:
  `removeBond(id)` → meter "Bluetooth reset" → `createBond`.
- **Second bike:** a separate association. The controller is per association. Out of scope for v1
  (one bike).

### 4.3 Detecting a broken bond while riding

How bond loss behaves since Android 16 and 17 [DOC-A16, DOC-A17]:
- **Android 16:** when encryption fails on reconnect, the stack disconnects, **keeps the stored
  keys**, and shows a *system* re-pair dialog. Apps get `ACTION_KEY_MISSING`, and later
  `ACTION_ENCRYPTION_CHANGE`. If `ACTION_ENCRYPTION_CHANGE` arrives with the link encrypted, the
  bond was restored.
- **Android 17** adds autonomous re-pairing after user consent. `ACTION_PAIRING_REQUEST` carries
  `EXTRA_PAIRING_CONTEXT`. `ACTION_KEY_MISSING` is sent **only when re-pairing failed**.
- So on our phone, a meter that lost its keys (meter "Bluetooth reset") should lead to a system
  re-pair prompt, not a silent bond deletion.
- `BondBroken` therefore waits for `ACTION_ENCRYPTION_CHANGE(encrypted)` as a recovery event: back
  to `Arming`.

| Signal | Meaning | Action |
|---|---|---|
| `ACTION_BOND_STATE_CHANGED` with `BONDED → NONE` for our address | The phone forgot the bond (user in Settings, or a stack reset) | `BondBroken`: stop autoConnect (C8), notify "Re-pair the bike". |
| `ACTION_KEY_MISSING` (36; no longer privileged from 36) + `EXTRA_BOND_LOSS_REASON` (36.1, e.g. `LE_ENCRYPT_FAILURE`) + `getKeyMissingCount()` | The meter has lost its keys (meter BT reset, paired to another phone) or there is a spoof | After 2 events in one presence cycle: `BondBroken`, notify "The bike forgot this phone: re-pair (meter Bluetooth reset)". |
| Operation status 5/15/137 while bonded | Encryption not established | One reconnect, then `BondBroken`. |
| Disconnect 0x3D (MIC failure) or 22 right after connect, repeatedly | Key mismatch | Same as above. |

---

## 5. Edge cases (and what the design does)

| # | Case | Handling |
|---|---|---|
| 1 | **Ignition off mid-session** | Status 8 → `Waiting` (autoConnect re-armed, no backoff) → `Lingering` once presence is gone → `Dormant` after 10 min. |
| 2 | **Stall or kill-switch cycle (seconds)** | The autoConnect in `Waiting` reconnects as soon as the meter advertises. The full setup re-runs; assignment status 13 "already registered" counts as success (SAB §7). |
| 3 | **Meter brown-out or reset while riding** (cranking dips the voltage) | Same as 1/2. If it comes back with a changed GATT table: `onServiceChanged` → rediscover (C7). Missing services → one `refresh()` per cycle. |
| 4 | **BT turned off mid-ride** | `BtTurningOff` → close the GATT (gen++), close the server → `BtOff`. On `BtOn`: reopen the server, then `DirectConnecting` (presence hint true, since we were riding). CDM restarts its own scan when LE comes back. |
| 5 | **Airplane mode** | Usually turns BT off: same as 4. If the user lets BT stay on in airplane mode, nothing happens. |
| 6 | **App process killed by LMK or swiped away** | Link drops. `START_STICKY` restart; CDM binding keeps us more important while present; on restart, `Arming(presentHint)`. Crash-loop guard (C13). |
| 7 | **Force-stop from Settings** | The app is in the "stopped" state: no broadcasts and PendingIntent scans are cleared. CDM presence likely does not restart it either (verify in §6). The UI shows "Open the app once to re-enable". Documented, not fought. |
| 8 | **Reboot** | §3.4. CDM resumes by itself. `BOOT_COMPLETED` re-asserts observation and, in always-armed mode, starts the FGS. |
| 9 | **Reboot with a secure lock screen** | CDM holds events until unlock [AOSP-DPP]. App storage is credential-encrypted anyway. Documented; recommend no PIN on the bike phone. |
| 10 | **App update** | `MY_PACKAGE_REPLACED` → re-observe and restart if present. A per-generation reset guarantees no stale `BluetoothGatt`. |
| 11 | **Doze / App Standby while parked** | No FGS, no wakeups. CDM wakes us. With `RUN_IN_BACKGROUND`, CDM allow-lists the app while present [AOSP-EXEMPT]. |
| 12 | **Doze during a ride** (screen off at a long stop) | Doze does not list Bluetooth among its restrictions [DOC-DOZE]. The real risk is a cached or *frozen* process, which binder callbacks cannot reach [DOC-FREEZER]. The FGS plus the bound CDM service plus the allow-list keep us uncached, so GATT callbacks still arrive (a BT interrupt wakes the CPU). Timers matter only during `Initialising`/`Backoff`: hold a **partial wakelock only for the `Initialising` window**, with a 20 s cap matching the watchdog. `Waiting` relies on the controller (autoConnect), not on app timers. |
| 13 | **Battery Saver / low battery / "Restricted" battery setting** | Battery Saver does not stop an FGS. A "Restricted" bucket can block background starts and jobs, so diagnostics show the bucket (`UsageStatsManager.getAppStandbyBucket()`) and onboarding asks for "Unrestricted". |
| 14 | **GATT 133 loop** | C2 (a new object each time) + C4 (classify by time) + settle delay. Escalate after 6 in 2 min to a "toggle Bluetooth" notification while staying armed (§2.6). |
| 15 | **Stale service cache** (meter firmware update via RoadSync/dealer) | Missing characteristics → `refresh()` once, rediscover. Service Changed → C7. |
| 16 | **Bond lost on the phone** (user taps "Forget" in BT settings) | `BONDED → NONE` → `BondBroken` (C8). No autoConnect without a bond (C10). |
| 17 | **Bond lost on the meter** (meter BT reset, or paired to another phone) | Android 16/17 keep the keys and raise a system re-pair dialog; on 17 `KEY_MISSING` arrives only if that fails (§4.3). `KEY_MISSING` / auth failures → `BondBroken` after 2 events → notification with the re-pair steps. `ENCRYPTION_CHANGE(encrypted)` → recover. |
| 17b | **Swipe from recents** | CDM unbinds its binding [AOSP-CSC]. The FGS survives, because `BikeLinkService` is not `stopWithTask`, and we do not call `stopSelf` in `onTaskRemoved`, unlike nRF Toolbox. |
| 17c | **Pairing dialog overlaps GATT** (Android 13-era reports of 133 or failed reads) | GATT work is gated on `BOND_BONDED` (C6) [DOC-PT]. |
| 18 | **Two phones bonded to the meter** (for example an iPhone with RoadSync) | The meter accepts one central. If the other phone wins, ours sees presence (adverts) briefly, then the meter stops advertising. Diagnostics: "present, but connects fail or get refused". It is a documented limitation. Optional: if `Initialising` sees an assignment status other than 1/13, show "another app or phone controls the meter". |
| 19 | **RoadSync installed on this phone** | Both register assignments and consume presses (SAB §10.10). Onboarding checks `PackageManager` for `com.honda.ms.dm.sab` and warns. |
| 20 | **Callbacks after `close()`, or from the previous attempt** | Generation check (C3). Ignore them and log at debug. |
| 21 | **A GATT op callback never arrives** | The op timeout fails it (C1), then disconnect → `Backoff`. Never block forever (the Gadgetbridge pitfall). |
| 22 | **`writeCharacteristic` returns BUSY (201)** | Retry every 20–50 ms within the 3 s op timeout (C12). |
| 23 | **CTS server not open when the meter reads it** (race at `BtOn`) | Order: open the server before `connectGatt`. If `openGattServer` returns null, retry on the next `BtOn` or after 1 s. Log `meter read the clock` per session. |
| 24 | **Duplicate start triggers** (CDM new + legacy callback, boot, scan hit, user) | All map to `Start(trigger)`. The reducer ignores them when it is not in `Dormant/Stopped` (except to upgrade the presence hint). |
| 25 | **`EVENT_BLE_DISAPPEARED` while connected** (the meter stops advertising once connected) | Ignored while the link is up. Only link loss plus absence starts `Lingering`. |
| 26 | **Association removed** (Settings → Connected devices, or `EVENT_ASSOCIATION_REMOVED` on 36.1) | `Stopped`; onboarding UI. The FGS start exemption is gone, so do not try background starts. |
| 27 | **Bluetooth permission revoked** | `SecurityException` around every BT call → `Stopped`, with a notification. Unused-app auto-revoke is disabled by CDM for associated apps [AOSP-EXEMPT `updateAutoRevokeExemption`]. |
| 28 | **Notification permission denied** | The FGS still runs (the notification is hidden but appears in the Task Manager). Status is shown in the app UI. |
| 29 | **OS/ROM update or reflash** (Infinity X ↔ Evolution X) | A reflash wipes bonds and associations → onboarding. A ROM update keeps them, but re-run the §6 checklist; hidden `refresh()` behaviour may change. If the phone's BT address changes, the meter's bond is stale → case 17. |
| 30 | **Phone booted by ignition power** (if the phone is powered from the bike) | The meter is already advertising at boot. CDM's scan starts with LE → `APPEARED` → normal path. Always-armed mode connects from `BOOT_COMPLETED`. Measure boot→Ready. |
| 31 | **Init gets the meter's "phone mode off" (10) later in the ride** (the rider changes the meter mode) | This is a protocol concern, not a link concern: `SabSession` may re-send `REQUEST_SAB_MODE`. The link state stays `Ready`. |
| 32 | **System HID-over-GATT holds the link** (if the meter exposes HOGP for volume keys, SAB §10.8) | Our `disconnect()` won't drop the link, and `connectGatt` attaches to the existing ACL instantly. Detect with `dumpsys bluetooth_manager`; harmless for us. |

---

## 6. Testing

### 6.1 Make it testable: seams

- `LinkReducer`: `fun reduce(s: State, e: Event): Pair<State, List<Effect>>`.
  - Pure and synchronous: no Android imports, no coroutines.
  - Every row of §2.4–2.6 is a unit test.
- `LinkRuntime`: an actor that pulls events from a `Channel`, calls `reduce`, and runs effects
  through:
  - `GattPort` (open(direct|auto) → gen, close(gen), requestMtu, discover, read, writeNoResp,
    enableNotify; events as a `Flow<Event>`);
  - `CtsServerPort`;
  - `Timers` (start(id, ms), cancel(id));
  - `ServiceControl` (startForeground, stopSelf, wakelock);
  - `Notifier`;
  - `Store`.
- `SabSession` works on top of `GattPort` ops only. It can be tested against a fake meter.

### 6.2 Fakes

- **FakeGattPort**: a scriptable radio.
  - `advertising(on/off)`;
  - `connectOutcome(after = 1.2.s, status = 0 | 133 | 62 …)`;
  - `dropLink(status = 8)`;
  - `opBehaviour(op, delay | status | neverRespond)`;
  - `busyWrites(n)`;
  - `serviceChanged()`;
  - `bond(BONDED | NONE | BONDING)`;
  - `keyMissing()`;
  - `adapter(OFF | ON)`;
  - `staleCallbacks(fromGen)`.
  - It enforces the Android invariants: at most one op in flight per gatt; no ops after close;
    callbacks only for open generations unless `staleCallbacks` is used.
- **FakeMeter** (behind FakeGattPort): implements SAB from `SAB_PROTOCOL.md`.
  - Answers assignments with 1, or 13 after the first session.
  - Answers REQUEST_SAB_MODE with 10 then 9.
  - Emits button codes 1/2/3/4/7/8.
  - "Reads CTS" through `CtsServerPort` 1.1 s after the link comes up.
  - Faults: refuses assignment N, never answers, answers with a corrupt echo (SAB "Corruption").
- **Virtual time:** `kotlinx-coroutines-test` `runTest` + `StandardTestDispatcher`. All timeouts
  and backoffs run in milliseconds of real time. Tests assert exact timings ("re-armed within
  300 ms after status 8", "backoff 1,2,4,8,16,30,30 s").
- Ideas to port from the Kotlin-BLE-Library mock (`PeripheralSpec.simulate*`): reset, bond loss
  (peripheral side), bond removal (central side), service change, MAC change, proximity change,
  caching.

### 6.3 Deterministic tests to write (minimum set)

1. Happy path: `Start(CDM)` → direct → `Ready` in ≤ the scripted time. Effects in the exact order:
   server open **before** connect, MTU, discover, CCCD × 3, assignments, END, SAB mode.
2. Ignition off (8) → `Waiting` re-armed with no backoff → `Lingering` → `Dormant` after `LINGER`;
   the FGS is stopped.
3. A stall cycle within `LINGER` → reconnect without a cold start; assignments answered with 13 are
   accepted.
4. 133 at 0.5 s ×2 then success → retried with 300/600 ms delays, a new gen each time.
5. 133 at 26 s → treated as a timeout → `Waiting`, not retried.
6. 6 early 133s within 2 min → the escalation notification fires once, and the link stays armed.
7. An op that never answers → `OpTimeout` → disconnect → `Backoff(1 s)` → the exponent grows,
   capped at 30 s, and resets after `ReadyStable`.
8. BT off in each state → all gens closed, server closed. `BtOn` → server reopened before the
   connect.
9. Bond `BONDED → NONE` during `Ready` → `BondBroken`, no autoConnect issued afterwards.
10. `KEY_MISSING` ×2 → `BondBroken`.
11. `ServiceChanged` in `Ready` → rediscover and re-init, presses keep flowing afterwards.
12. A stale callback from gen n−1 does not change state.
13. Duplicate `Start` triggers (new + legacy CDM callbacks) → exactly one connect.
14. BLE disappeared while `Ready` → nothing happens.
15. Discovery missing services → `refresh` exactly once per presence cycle.
16. **Randomised/fuzz test:** random event sequences (10k runs, seeded). Invariants:
    - ≤ 1 open gen;
    - no GATT op outside `Preparing/Initialising/Ready`;
    - the FGS is running whenever a gen is open;
    - no connect while `BtOff` or `BondBroken`;
    - backoff ≤ 30 s;
    - the wakelock is held only in `Initialising`, with a cap.

### 6.4 Bench rig (no bike needed)

- A second Android phone running **nRF Connect for Mobile** (GATT server configuration plus
  advertiser), or an nRF52/ESP32 board. Advertise as `HONDA BTU SIM` (RoadSync's own simulator
  name, SAB §1.1) with service `4d87b1ea-…` and the SAB characteristics (write-without-response +
  notify). Toggle advertising or power to simulate the ignition.
- It lets us test CDM association, presence latency, bonding, `KEY_MISSING` (delete the bond on the
  simulator side) and 133 storms, at a desk.

### 6.5 On-device checklist (Poco F1, adb via the Windows host)

Useful commands:

- `adb shell cmd companiondevice list 0`
- `adb shell dumpsys companiondevice` (associations, present devices, bound apps)
- `adb shell cmd companiondevice simulate-device-appeared <associationId>`: binds our service and
  calls `onDeviceAppeared`. The device counts as present for 60 s unless `…-disappeared` is called
  [AOSP-SHELL].
- `adb shell cmd companiondevice simulate-device-event <associationId> <0|1|2|3>` (new-style
  events)
- `adb shell svc bluetooth disable|enable` and `adb shell cmd bluetooth_manager disable|enable`
- `adb shell dumpsys deviceidle force-idle`, `… unforce`, `adb shell dumpsys battery unplug`
- `adb shell am set-standby-bucket com.abid.handlebarlink rare|restricted`,
  `adb shell am get-standby-bucket …`
- `adb shell am kill com.abid.handlebarlink` (LMK-like) vs `am force-stop` (case 7)
- `adb shell dumpsys bluetooth_manager` (bonds, GATT clients, ACL state, HOGP)
- Developer options → "Enable Bluetooth HCI snoop log", then `adb bugreport` for btsnoop
- `adb logcat -b all | findstr /i "HandlebarLink CDM_ CompanionDevice BtGatt bt_btm"` (Windows host)

| # | Test | Pass criterion |
|---|---|---|
| D1 | Association + bond from scratch (after a meter "Bluetooth reset") | The dialog shows only the meter; `BOND_BONDED`; first session `Ready`; clock read logged |
| D2 | Address type check | Logged `getAddressType`/identity equals the association MAC (else enable fallback C) |
| D3 | `simulate-device-appeared` with the app force-killed (`am kill`) and the screen off | FGS starts (no `ForegroundServiceStartNotAllowedException`) and connects if the bike is on |
| D4 | **Real ignition on**, app process dead, phone idle ≥ 30 min (deep Doze) | `Ready` within target; record ignition→APPEARED and APPEARED→Ready (20 cycles; report median/p95) |
| D5 | The same with always-armed autoConnect only (CDM observation stopped) | Record the latency, to choose the default mode |
| D6 | Ignition off/on after 5 s, 60 s, 5 min, 15 min | Reconnects each time; after 15 min the FGS had stopped (`Dormant`) and was restarted by CDM |
| D7 | BT off/on mid-ride; airplane mode on/off | Back to `Ready` within 5 s of `BtOn`; clock read again |
| D8 | Reboot with the ignition on; reboot then ignition on | `Ready` without opening the app (note unlock behaviour) |
| D9 | `adb install -r` (update) with the ignition on | Restarts and reconnects |
| D10 | `am kill` while `Ready` | Restart and `Ready` within about 10 s |
| D11 | `force-stop` | Documented behaviour (expected: inactive until the app is opened) |
| D12 | Forget the bond in Android BT settings while connected | `BondBroken` notification; no reconnect storm |
| D13 | Meter "Bluetooth reset" (meter loses its keys) | `KEY_MISSING` seen; `BondBroken` with re-pair guidance; re-pair works via `cdm.removeBond` |
| D14 | 50 ignition cycles over a week of real riding | ≥ 49/50 `Ready` without touching the phone; zero 133 escalations, or each one understood from the logs |
| D15 | Standby bucket `restricted` | Behaviour recorded; onboarding warns |
| D16 | `dumpsys companiondevice` mid-ride | The association counts as present via BT connected (validates §3.2 "presence mid-ride") |

Record every session's timeline in the app's diagnostics ring buffer:
- trigger;
- the gen each state transition happened on;
- statuses;
- per-step durations;
- clock-read flag.

Export it from the UI. This turns the on-device checklist into data, not impressions.

---

## 7. Gaps in the prototype that this design closes

- `BikeService` waits with `connect(autoConnect=true, timeout=MAX)`, but nothing re-arms it after a
  BT toggle, and nothing closes the old GATT on `STATE_OFF`. Fixed by C9 and §2.7.
- `CtsServer` is opened once and never reopened after BT off/on. Fixed by §2.7 and §2.8.
- `GattClient` has no generation guard. `connectWaiter`/`pending` are mutated from binder threads
  and coroutines. Fixed by the single actor (C1, C3).
- `MainActivity` tries GATT even when bonding failed (`BOND_NONE → paired(d)`). Fixed: bond first,
  no unbonded runs (§4).
- The service is sticky and always on, with no presence awareness, no crash-loop guard and no
  `MY_PACKAGE_REPLACED`. Fixed by §3.4.
- There is no CDM association. That means no background-FGS exemption outside boot, and on
  Android 17 no way to remove a bond. Fixed by §3–§4.

---

## 8. Sources

Code (shallow clones, read 2026-11-16):
- [ABL] NordicSemiconductor/Android-BLE-Library @ da698f2 (2026-10-10):
  - `ble/src/main/java/no/nordicsemi/android/ble/BleManagerHandler.java`:
    - 96-101: the 20 s threshold for 133/147
    - 270-300: BT off
    - 336-440: bond receiver
    - 660-721: reconnect and `gatt.connect()` on Android 14
    - 735-800: direct-first autoConnect
    - 1222-1246: writes
    - 1628-1647: `refresh()`
    - 2240-2400: connection state handling and retries
    - 2516-2530: Service Changed
  - `BleManager.java` 640-665 (discovery delay rationale)
  - `ConnectRequest.java` 151-252
  - `error/GattError.java` 36-42
  - https://github.com/NordicSemiconductor/Android-BLE-Library
- [KBLE] NordicSemiconductor/Kotlin-BLE-Library @ b9498d8 (2026-11-13, "Docs for 2.0.0-beta11"):
  - `client-core-android/.../CentralManager.kt` 180-273 (ConnectionOptions)
  - `client-core-android/.../Peripheral.kt` 590-690 (connect, retry, 133/147 ≥ 25 s, direct
    before auto)
  - `client-core/.../internal/OperationMutex.kt`
  - `client-android/.../internal/BluetoothGatt.kt` (the API 37 `BluetoothGattConnectionSettings`
    path)
  - `client-core-mock/.../PeripheralSpec.kt` (`simulate*`)
  - README status table
  - https://github.com/NordicSemiconductor/Kotlin-BLE-Library/tree/version/2.0
- [KBLE-ENV] `core-android/.../AndroidEnvironment.kt` 210-242:
  - unbonded RPA autoConnect rejected on 13+ ("confirmed on Android 14 and 17");
  - bond removal only privileged or CDM from Android 17, citing
    https://cs.android.com/android/_/android/platform/packages/modules/Bluetooth/+/f4c525723297ede881a618bb325f4b78a9babb1c
- [GB] Gadgetbridge @ a29a47e (2026-11-16), https://codeberg.org/Freeyourgadget/Gadgetbridge:
  - `service/btle/BtLEQueue.java`:
    - 66-278: queue and threads
    - 353-388: connect, autoConnect comment
    - 399-513: disconnect handling and reconnect
    - 588-594: stale-gatt guard
    - 671: discovery on the main thread
  - `service/btle/AbstractBTLEDeviceSupport.java` 60-80
  - `service/receivers/AutoConnectIntervalReceiver.java` 86-131
  - `service/btle/BLEScanService.java` 401-433
  - `externalevents/BluetoothStateChangeReceiver.java`
  - `externalevents/AutoStartReceiver.kt`
  - `service/GBCompanionDeviceService.java` 91-131
  - `util/CompanionDeviceUtil.java` 79-182
  - `util/BondingUtil.java` 69-291, 455
  - `service/DeviceCommunicationService.java` 160, 464-501, 898-909
- [TBX] NordicSemiconductor/Android-nRF-Toolbox @ 0e73c9b (2026-11-06):
  - `lib_service/.../ProfileService.kt:146` (AutoConnect)
  - `profile/src/main/AndroidManifest.xml` (connectedDevice)
  - no CDM or boot receiver
- [SDK37] Android SDK Platform 37 (rev 2): `platforms/android-37.0/data/api-versions.xml` (every
  "since/deprecated" level in §3.2) and `android-stubs-src.jar` javadoc for
  `BluetoothDevice.connectGatt(BluetoothGattConnectionSettings, …)`, `createBond(int)`,
  `ACTION_KEY_MISSING`, `BluetoothGatt.GATT_CONNECTION_TIMEOUT`, and
  `BluetoothGattConnectionSettings`.

AOSP (main, fetched from android.googlesource.com on 2026-11-16):
- [AOSP-BLE]
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/companion/java/com/android/server/companion/devicepresence/BleDeviceProcessor.java:
  scan filters on the association MAC, `SCAN_MODE_LOW_POWER`, `FIRST_MATCH | MATCH_LOST`, restart
  on `ACTION_BLE_STATE_CHANGED`.
- [AOSP-BTP] `…/devicepresence/BluetoothDeviceProcessor.java`: `BluetoothConnectionCallback` →
  `EVENT_BT_CONNECTED/DISCONNECTED`.
- [AOSP-DPP] `…/devicepresence/DevicePresenceProcessor.java`:
  - 545-566: locked-user deferral
  - 652-733: dispatch; new **and** legacy callbacks; bind and exemption
  - 1178-1201: the 10 s BLE-disappeared scheduler after a BT disconnect
- [AOSP-CSC] `…/devicepresence/CompanionServiceConnector.java` 76-94: `BIND_ALMOST_PERCEPTIBLE`
  for non-self-managed associations.
- [AOSP-EXEMPT] `…/companion/CompanionExemptionProcessor.java` 115-170: power-save allow-list
  while present with `REQUEST_COMPANION_RUN_IN_BACKGROUND`; auto-revoke exemption.
- [AOSP-SHELL] `…/companion/CompanionDeviceShellCommand.java`: `simulate-device-appeared`,
  `simulate-device-disappeared`, `simulate-device-event`, `list`.
- [AOSP-AS]
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActiveServices.java:
  - 376-379: `FGS_BOOT_COMPLETED_RESTRICTIONS`, target 35
  - 1170-1180: boot type filter
  - 8808-8826: `REASON_COMPANION_DEVICE_MANAGER`
- [AOSP-AMC] `…/am/ActivityManagerConstants.java` 191-197: `DEFAULT_FGS_BOOT_COMPLETED_ALLOWLIST`
  includes `CONNECTED_DEVICE`.

Android documentation and trackers (fetched 2026-11-16):
- [DOC-BLE-BG] Communicate in the background (CDM + `CompanionDeviceService` recommended; RPA
  needs a bond; autoConnect semantics):
  https://developer.android.com/develop/connectivity/bluetooth/ble/background
- [DOC-CDM-PAIR] Companion device pairing (no connection or scan of its own; `createBond` within
  10 min skips the confirmation):
  https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing
  and https://developer.android.com/reference/android/companion/CompanionDeviceManager
- [DOC-CDS] https://developer.android.com/reference/android/companion/CompanionDeviceService ·
  https://developer.android.com/reference/android/companion/DevicePresenceEvent ·
  https://developer.android.com/reference/android/companion/ObservingDevicePresenceRequest.Builder
- [DOC-FGS-TYPES] https://developer.android.com/develop/background-work/services/fgs/service-types
  (connectedDevice prerequisites)
- [DOC-FGS-BG] https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
  (CDM exemption; BOOT_COMPLETED / MY_PACKAGE_REPLACED)
- [DOC-A15] https://developer.android.com/about/versions/15/behavior-changes-15 (BOOT_COMPLETED FGS
  type restrictions; dataSync/mediaProcessing time limits only)
- [DOC-A16] https://developer.android.com/about/versions/16/behavior-changes-16 (bond loss handling:
  `ACTION_KEY_MISSING`, `ACTION_ENCRYPTION_CHANGE`) and
  https://developer.android.com/about/versions/16/behavior-changes-all (CDM discovery timeout →
  `RESULT_USER_REJECTED`; job quota changes)
- [DOC-A17] https://developer.android.com/about/versions/17/behavior-changes-all and
  https://developer.android.com/about/versions/17/behavior-changes-17 (autonomous re-pairing,
  `EXTRA_PAIRING_CONTEXT`; no new BLE scan or FGS restriction)
- [DOC-DOZE] https://developer.android.com/training/monitoring-device-state/doze-standby
- [DOC-FREEZER] https://source.android.com/docs/core/architecture/ipc/binder-freezer
- [DOC-SCANNER] https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner
  (PendingIntent scan extras, privileged address filters)
- [DOC-SCANMGR] AOSP Bluetooth `le_scan/ScanManager.java` and `AppScanStats.java`:
  https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/le_scan/
  (screen-off scan mode 512 ms / 10,240 ms; 5 starts per 30 s; 10-minute long-scan downgrade)
- [DOC-GATT] https://developer.android.com/reference/android/bluetooth/BluetoothGatt
  (`GATT_CONNECTION_TIMEOUT` 147) ·
  https://developer.android.com/reference/android/bluetooth/BluetoothGattConnectionSettings
- [DOC-PT] Punch Through, "The Ultimate Guide to Android BLE Development":
  https://punchthrough.com/android-ble-guide/ (close-and-recreate on errors, serialise operations,
  bond before GATT, 133 retries)
- [DOC-MTU14] Nordic DevZone thread 115384 (Android 14 forces MTU 517 on the first `requestMtu`):
  https://devzone.nordicsemi.com/f/nordic-q-a/115384
- [DOC-BTTOGGLE] Nordic DevZone 45487 (re-issue autoConnect after BT on):
  https://devzone.nordicsemi.com/f/nordic-q-a/45487
- [DOC-IT] Android 13 regressions: direct-connect timeout not reported for bonded devices
  https://issuetracker.google.com/issues/242741838, and bonded reconnects failing with 133
  https://issuetracker.google.com/issues/242755161.
  - No specific Android 15–17 GATT regression was found (searched, unverified).
- [DOC-LOS] LineageOS `lineage-24.0` `BleDeviceProcessor` (skips BLE scanning for BT-connected
  associations):
  https://github.com/LineageOS/android_frameworks_base/blob/lineage-24.0/services/companion/java/com/android/server/companion/devicepresence/BleDeviceProcessor.java
- [DOC-AGENT] Items marked this way are inferences from the AOSP source by the research pass, not
  documented statements. Re-check them on the device (§6.5).

Project:
- `handlebar-link-prototype/SAB_PROTOCOL.md`: §1.1, §1.4, §1.5, §4.2, §7, §10, "Verified on the
  CB350"
- `handlebar-link-prototype/app/src/main/java/com/abid/handlebarlink/*.kt`
- `notes/bike-test.log`: 20:56:22–20:58:11, connect → Ready 3.4 s, ignition-off status 8
