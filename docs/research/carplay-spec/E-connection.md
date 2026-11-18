# E. Connection design for C2: faster, sturdier wireless CarPlay

Status, 2026-11-19: research verified in the planning session; the **engine items E1-E9 are built** in
`:carplay:engine` (C2 part 1, worktree `carplay-c2`; see "Engine items" below for the tests and the planted bugs
each one catches). The Android ports (`:carplay:engine-android`) and the app wiring are separate C2 parts. Nothing
here has run against the iPhone yet: that is the device check (P1-P12).

Inputs: three model-written reports (research-1 CarLink/DiPlay, research-2 Android docs, research-3 other repos).
Every row below was re-checked against its primary source in this session. Rows marked **UNVERIFIED** could not be
confirmed; don't act on them before the check named in the row.

## Sources and how they were read

| Short name | What | How it was read |
|---|---|---|
| CTL, HOST, GM, … | DiPlay inside CarLink `upstream/main` at `1d37e9ac` (1.0.17) | `git -C carlink-src show upstream/main:vendor/diplay/...`. Paths: `D/` = `vendor/diplay/shared/src/main/java/com/shilapi/xcertplay/`. CTL = `D/orchestration/CarPlayController.kt`, GM = `D/network/WifiP2pGroupManager.kt`, HOST = `vendor/diplay/common/.../CarPlayHostActivity.kt`, `C/` = `carlink-core/src/main/java/com/carlink/core/connection/` |
| DiPlay #n | Issues and PRs on github.com/shihabal3amri/DiPlay | `gh api repos/shihabal3amri/DiPlay/{issues,pulls}/n`, read 2026-11-19 |
| AllPlay | github.com/UnmetDecade/AllPlay at `9086b0d5` | `gh api .../contents/...?ref=9086b0d5`: `core/projection/carplay/iap2/wireless_client.{cpp,h}`, `CHANGELOG.md` |
| Apple spec | "Accessory Interface Specification", CarPlay chapter 23, 2015-12-18 (`carplay.pdf` in github.com/45clouds/WirelessCarPlay at `51145ef5`) | Downloaded, `pdftotext`; cited by the printed page number |
| Android ref | developer.android.com reference pages, Android KB (`android docs fetch kb://…`) | Fetched 2026-11-19 |
| AOSP | `packages/modules/Wifi` main: `WifiP2pServiceImpl.java`, `WifiManager.java`; `frameworks/base` `core/res/AndroidManifest.xml` | android.googlesource.com `?format=TEXT` |

One fact frames everything: **CarLink 1.0.16 already runs wireless CarPlay on this Poco with the owner's iPhone over
Wi-Fi Direct** (spec README, "Checked against the CarLink APK"). So nothing below is needed to make CarPlay work at
all. Each item is about connecting faster, failing faster, or recovering on its own.

## What C1 already does (verified in our code, no work needed)

| Behaviour | Evidence | Our code |
|---|---|---|
| Re-acknowledge a repeated iAP2 SYN+ACK until the first data (a lost ACK otherwise costs a 30 s identification timeout) | DiPlay PR #1280 (open; wired Renault log: 30 identical SYN+ACKs, then timeout) | `LinkMachine.kt:24-25, 83-112` (`peerSynchronize`) |
| RFCOMM dial starts first and runs while the group, listener and Bonjour come up; the 15 s budget counts from the dial's start | `D/orchestration/BackgroundDial.kt`, CTL:1430-1477, CTL:3742 | `WirelessAttempt.kt:58-70` |
| Keep reading and ACKing RFCOMM after StartSession until the link really ends. AllPlay measured that stopping reads makes iOS send TEARDOWN about 30 s later | AllPlay `wireless_client.cpp:465-472`, `CHANGELOG.md:294-298`. Apple spec p.410 says the Bluetooth iAP2 client "must be disconnected once the CarPlay session is established": close it or keep reading, never leave it open and unread | `WirelessAttempt.bootstrap`: `BootstrapControl.serve` and `Iap2Channel.reads` run until EOF; closed only at the end of the attempt. **No test proves the ACKs continue after Connected** (C2 test gap, see engine item E7) |
| RFCOMM EOF after AirPlay is verified does not end the session (the iPhone may close Bluetooth tens of ms after SETUP) | DiPlay PR #1178 (open): 0x4301 → TCP +233 ms → SETUP +537 ms → EOF +571 ms. SETUP comes after pair-verify (470 gate), so our "verified" point is earlier than the EOF | `WirelessAttempt.kt:117-120`; test "losing Bluetooth after the AirPlay connection is verified keeps CarPlay connected" |
| The old RFCOMM socket and group are closed before the next attempt dials (the iPhone's iAP2 SPP accepts only one connection) | AllPlay `CHANGELOG.md:341-343` | `Cleanup` + `previous?.join()` in `CarPlayRuntime.launchAttempt`; test "when the iPhone disconnects everything is closed…" |
| Unverified AirPlay connections: 8 at most, closed after 30 s | DiPlay #1130 via CarLink `13b0f867` | `WirelessAttempt.acceptMore`, `serve` |
| Wi-Fi configuration without DiPlay's optional BSSID param 0 | carplayd's definition lists params 1-4 (research-3 §2.2; carplayd not re-read: **UNVERIFIED** as a requirement). We never send param 0 either way | `WirelessAttempt` calls `wifiConfiguration(network)` with `bssid = null` |

## Ranked changes for C2

Ranked by expected effect on the bike (connect time, then self-recovery), then by strength of evidence. "Where":
**engine** = `:carplay:engine` (pure Kotlin, testable with `FakePorts`/`SimulatedIphone`), **port** = `:carplay:engine-android`
(C2, not built), **ROM** = our EvoX build, **Poco** = only a device test can settle it.

| # | Change | Why (evidence) | Status | Where |
|---|---|---|---|---|
| 1 | **Stable SSID, passphrase and channel**, created once per install and reused on every attempt (`WifiP2pConfig.Builder.setNetworkName("DIRECT-xy…")`, `setPassphrase`, `setGroupOperatingFrequency`, `enablePersistentMode(true)`) | Apple spec p.397: credentials "are stored on the Apple device and will be used to join … automatically during reconnection", and the iPhone starts Wi-Fi scanning when any Bluetooth profile reconnects. p.405: it joins with "the Wi-Fi credentials mapped to the BD_ADDR of the accessory". p.399: "Channel switching should be limited as it increases connection time … avoided across accessory power cycles". CarLink made this the default after random names made the iPhone set up a new network every time (`D/network/P2pStableIdentity.kt:41-80`, GM:182-185). DiPlay #464: "iOS found several Wi-Fi names". The CarPlay time saved is **not measured** anywhere (DiAuto's 1.1 s vs 8.9 s is Android Auto) | Verified (requirement and CarLink code); gain UNVERIFIED | port (`WifiDirectPort.createGroup`); stored by the port, not by `CredentialsPort` (one identity per receiver, since the iPhone keys it to our BD_ADDR, which is fixed) |
| 2 | **Channel plan: 5180 (36), 5745 (149), then 2437/2412/2462, then the system default.** Never DFS. Remember the frequency that last produced a verified session and try it first | Apple spec p.398-399: allowed 5 GHz channels are 36/40/44/48/149/153/157/161 (no DFS), 5 GHz "highly recommended" to avoid Bluetooth coexistence on 2.4 GHz. DiPlay plan `D/network/P2pStartupRecovery.kt:52-96` (no station on the bike → 5 GHz first), non-DFS list `WifiP2pChannels.kt:14-21`, memory `P2pConfigurationMemory` + GM:410-423 (CarLink proof = first frame; ours = Connected until C4). An unsupported band or frequency makes `createGroup` fail, it is not ignored (Android ref `WifiP2pConfig.Builder`) | Verified | port |
| 3 | **Re-send 0x5703 then 0x4301 when no AirPlay TCP arrives 3 s after StartSession, at most twice** (each send re-arms the 3 s) | AllPlay `wireless_client.cpp:446-462`, constants `:66-70` (`kAirPlayRetryMs = 3000`, `kAirPlayRetryMax = 2`), reason `wireless_client.h:76-80`: measured 0x4301 → 14.5 s before the phone connected, and black-screen cases where it never connected; "iOS does not retry after declaring this route dead". The Wi-Fi config goes first because "the phone likely did not get to the 0x5703 join action". Not counted against the reply caps (AllPlay keeps separate counters) | Verified (AllPlay; DiPlay has no equivalent, its wired analogue is PR #962, not re-read) | **engine**: `BootstrapControl` needs to know when TCP arrived (a `CompletableDeferred` set at the first `accept`). Test: an iPhone that answers 0x4301 with silence gets 0x5703+0x4301 at +3 s and +6 s and nothing at +9 s |
| 4 | **Bonjour off the critical path.** Start `bonjour.publish` in parallel; iAP2 and StartSession never wait for it. Keep publishing as a fallback | Today `WirelessAttempt.kt:65` awaits `publish` before awaiting the dial. DiPlay PR #972 (merged): "Wireless startup waits for Bonjour initialization before connecting RFCOMM, so a slow resolver delays the rest" (JmDNS reverse DNS, 20.3 s → 45 ms on a BYD). AllPlay `CHANGELOG.md:331-338` (v0.5): with NsdManager and no TXT record "an iPhone can connect using just Bluetooth 0x4301 + RTSP /info". DiPlay's WIRELESS_HOTSPOT_JOIN.md:23-24 cites WWDC23 for the iOS 14+ endpoint exchange | Verified (code and AllPlay measurement). Gain on the Poco: UNVERIFIED (NsdManager has no reverse-DNS step) | **engine** (launch publish; a failure is logged, never fatal). Port: if JmDNS is ever used, create it on a pre-named `InetAddress` (`D/network/CarPlayBonjour.kt:283-290`) |
| 5 | **Retry at once when Bluetooth turns on** (only when the last failure was `BluetoothUnavailable`) | DiPlay PR #914 (merged): BT on → next attempt 29.2 s → 0.66 s, → WirelessActive 35.0 s → 6.8 s. Code HOST:641-653 (`reconnectWaitsForBluetooth`, set at HOST:6330 from `RADIO_OFF_MARK`) | Verified | **engine**: new signal on `BluetoothPort` (e.g. `val radioOn: Flow<Unit>`), collected in `run()`, `trySend` to the inbox; the pause becomes `withTimeoutOrNull(pause) { skip.await() }`. **port**: `BluetoothAdapter.ACTION_STATE_CHANGED`, `EXTRA_STATE == STATE_ON`, `RECEIVER_NOT_EXPORTED` |
| 6 | **Quick retries for a dial that got no answer: 1.5 s, up to 8 in a row**, then the normal 2-4-8-16-30 s | CarLink HOST:6288-6296 ("~5 s per try and often succeeds on the next one; the doubling backoff turned four misses into ~55 s"), constants HOST:7247-7251 | Verified | **engine** (`CarPlayRuntime.backoffMillis` by error type: `DialFailed` → quick). Test: 3 dial failures retry at 1.5 s ±jitter each |
| 7 | **IPv4 first in StartSession, then every other address the listener is bound to** | DiPlay `D/network/HotspotAddressPolicy.kt:19-30` returns the first usable IPv4; `WirelessAddressFamilyPlan.kt:29-36`: CarLink turned the IPv6 guess off after measuring a 9.5 s vs 1.9 s handshake. All bound addresses, published first: CTL:1753-1755 (PR #1071). IPv6 failures: #242 (closed; works only with IPv6 off on wlan0), #1344 (open; iPhone SYNs to `fe80::` only, reply had no route). Counterpoint: Apple spec p.426 says link-local IPv6 is often faster and more reliable (for the accessory dialling the iPhone's `_carplay-ctrl`, a different direction) | Verified | **engine**: `WifiGroup` carries `addresses: List<String>`; the engine orders IPv4 first; `NetworkPort.listen` binds all of them; StartSession lists them; Bonjour publishes the first. **port**: read addresses from `NetworkInterface.getByName(group.interface)`; never hard-code 192.168.49.1 (AOSP `WifiP2pServiceImpl.java:5348-5351`: tethering picks a random range) |
| 8 | **Keep the group up across retries within one ride**; remove it on `stop()` only. Re-create it after repeated "iPhone never joined" failures | Today every attempt removes the group (`WirelessAttempt.kt:61`). CarLink reads a live group with the stable pair as it stands because "removing and re-creating it re-addresses the network the iPhone saved" (GM:225-249). AOSP shuts an idle P2P interface down after 150 s (`P2P_INTERFACE_IDLE_SHUTDOWN_TIMEOUT_MS`, `WifiP2pServiceImpl.java:227`). DiPlay #1114 (open): the iPhone could not rejoin until the AP was restarted, hence the re-create rule | Mechanism verified; benefit **UNVERIFIED** (depends on Poco checks P4/P5) | engine (group owned by the runtime, not the attempt) + port (reuse a live group, reclaim a leftover owned group: GM:250-267) |
| 9 | **Liveness: TCP keep-alive 10/3/3 and `TCP_USER_TIMEOUT` 20 s on the control socket; `TCP_NODELAY` and `SO_LINGER 0` on control and event sockets** | `D/network/TcpLiveness.kt:12-43` (raw Linux 4/5/6 for KEEPIDLE/INTVL/CNT because `OsConstants` lacks them; fd via `ParcelFileDescriptor.fromSocket`, a dup on API 29+). `TCP_USER_TIMEOUT` is in `OsConstants` (API 26) | Verified (DiPlay code). Apple's 3 s × 3 in-session figure: not re-read, UNVERIFIED | port (`NetworkPort` contract already says NODELAY + keep-alive) |
| 10 | **Fail fast with clear verdicts instead of 30 s timeouts**: no StartSession request 4 s after the iPhone joined; not joined 9 s after the Wi-Fi details; no TCP 8 s after a DHCP lease; iPhone left the group for 2 s after a running session | `C/CarPlayLinkWatch.kt:5-26` (tablet + iPhone 15: good starts joined ≤ 5.1 s, TCP ≤ 2.8 s after the lease; failures hung 19-20 s; a departed iPhone noticed 20.6 s late) | Verified (CarLink code; its own measurements) | engine (verdicts as `CarPlayError`s, timers on injected time) + port: a P2P client-joined/left signal (`WifiP2pManager.registerWifiP2pListener`, API 35: `onPeerClientJoined`, `onPeerClientDisconnected`, `onGroupRemoved`) |
| 11 | **Pause the Poco's own Wi-Fi scans during a session** (`WifiManager.allowAutojoinGlobal(false)`, restore after) | DiPlay PR #225 (merged; BYD: full-band scan every 10 s, 3-6 s each, radio leaves the CarPlay channel), #570 (merged; audio loss locked to a 10.0 s period), #1346 (open; 46 → 2 lost music packets). `allowAutojoinGlobal` is public API 33 but needs `NETWORK_SETTINGS` or `MANAGE_WIFI_NETWORK_SELECTION` (`signature|privileged|knownSigner`, AOSP `core/res/AndroidManifest.xml:2216`); it "will also disable connectivity initiated scanning" and resets on reboot or Wi-Fi toggle (AOSP `WifiManager.java:7790-7810`). CarLink only runs it on BYD units (`D/network/WifiScanPause.kt:61-63`) | Mechanism verified. **Whether the Poco scans at all with no saved network: UNVERIFIED** (check P6) | ROM (privileged permission for our app via a privapp-permissions allowlist) + port. Only if P6 shows scans |
| 12 | **Page the iPhone early without holding the dial**: start an SDP query (`fetchUuidsWithSdp`) or, on API 37, `BluetoothDevice.connect()` (needs a CDM association) when CarPlay is about to start; the dial waits for the page, at most 6 s; never run SDP and an RFCOMM connect at once | `C/BluetoothWarmup.kt:19-60` (cold dial 4.5-6.7 s vs 0.2 s with a link up; an SDP query racing an RFCOMM connect can fail the connect). Android ref: `BluetoothDevice.connect()` API 37 "will page the device", needs `BLUETOOTH_CONNECT` plus `BLUETOOTH_PRIVILEGED` or a CDM association. CarLink's "already connected" check uses reflection (`isConnected`), which AGENTS.md forbids: track `ACTION_ACL_CONNECTED/DISCONNECTED` instead | Verified (code, docs). Gain on the bike: UNVERIFIED (only helps if there is other start-up work to overlap) | port. `connect()` also connects HFP/A2DP/PBAP, which conflicts with 13 |
| 13 | **Keep Bluetooth quiet and Wi-Fi on 5 GHz**: no PBAP/MAP/A2DP to the iPhone during CarPlay | Apple spec p.399 (5 GHz avoids Bluetooth coexistence), p.403/405 (on 5 GHz, Bluetooth to the iPhone goes idle after the session starts). DiPlay #1323 (open field report: MediaTek combo chip, 5 GHz ch 149 and no PBAP → no gaps). #1029: "2.4 GHz starves Bluetooth" is the maintainer's hypothesis, never confirmed by the reporter | Partly verified (Apple; #1323 is a single report on another chip) | port / app settings |
| 14 | **Apple Device IE `dd0800a0400000020021` in beacon, probe and association response** | Apple spec p.402: "must include the Apple Device IE in Beacon, Probe response, and Association response frames". DiPlay `docs/WIRELESS_HOTSPOT_JOIN.md:31-56`: one A/B on a SoftAP with an iPhone 8 Plus on iOS 14.0.1 (no auto-join without it). Not available to apps on a P2P GO: `setVendorElements` (API 33) covers P2P discovery only and needs `OVERRIDE_WIFI_CONFIG`. **CarLink joins this Poco's group with the owner's iPhone without it** | Requirement verified; need on our setup **contradicted by CarLink working**. Effect on a P2P GO: UNVERIFIED | ROM (`wpa_cli VENDOR_ELEM_ADD` or a supplicant patch), only if P3 shows join failures |
| 15 | **Android 17 musts** (not speed, but nothing connects without them): `ACCESS_LOCAL_NETWORK`; RFCOMM `read()` returns -1 on close; one long-lived `WifiP2pManager.Channel` | KB `local-network-permission` (blocked by default for target 37; same NEARBY_DEVICES group, no second prompt if Bluetooth is granted). DiPlay #425: IPv4 TCP never arrived on a Xiaomi Android 17 until the permission was granted. KB `behavior-changes-17`: "read() … now returns -1 when the socket is closed". AOSP `WifiP2pServiceImpl.close()` (`:1071-1109`) sends REMOVE_GROUP and DISABLE_P2P when the last client binder goes | Verified | port (our `ByteStream.read` contract already says -1 = closed) |

### Engine items (built 2026-11-19, `:carplay:engine`)

Each has tests through the public `CarPlayLink` (`CarPlayRuntimeTest`, `FakePorts`, the simulated iPhone) and a
planted bug that a named test catches (lessons 12, 56: failing test names read from the result XMLs, the source
restored and compared afterwards). SPI changes are in `spi/Ports.kt`.

| Item | What was built | Tests | Planted bug → caught by |
|---|---|---|---|
| **E1 (#3)** nudge | `BootstrapControl.nudge`: 3 s after the latest StartSession with no accepted TCP, 0x5703 then 0x4301, at most 2; every StartSession (ours or one the iPhone asks for) re-arms the wait; not counted against the reply caps | "with no AirPlay connection the Wi-Fi details and StartSession go again at 3 s and 6 s, and no more"; "a StartSession the iPhone asks for again re-arms the nudge"; "an AirPlay connection within 3 s means no nudge" | 0x4301 before 0x5703 → the first two; re-arm missing → the first two and the NO_TCP test |
| **E2 (#4)** Bonjour in parallel | `WirelessAttempt.publish` runs in its own child; a `CarPlayException` (or the port's own timeout) is logged as `carplay.bonjour.failed`; `unpublish` is registered before publishing starts | "a Bonjour publish that never returns does not hold up the connection, and is undone at stop"; "a Bonjour publish that fails is logged and the connection goes on" | awaiting publish again → the first (hangs until the test's time limit) |
| **E3 (#5)** Bluetooth on | `BluetoothPort.radioOn: Flow<Unit>`, collected by the runtime (only `trySend`); retries at once only when started, the last failure was `BluetoothUnavailable` and its pause is still running | "Bluetooth turning on retries at once when the attempt failed because Bluetooth was off"; "… does not cut short the pause after another failure"; "… after stop starts nothing" | retry after any error → the second; ignore `started` → the third |
| **E4 (#6)** quick dial retries | `CarPlayRuntime.pauseAfter`: `DialFailed` waits 1.5 s ±20 % while it is failure 1-8 in a row, then the usual 2 s doubling to 30 s; the count resets on Connected | "an unanswered dial is retried after a second and a half eight times, then backs off, closing each time"; "a connection resets the quick dial retries" (the old doubling test now uses a failing group) | count not reset on Connected → the second |
| **E5 (#7)** IPv4 first | `WifiGroup.addresses` replaces `ownerAddress`; `WirelessAttempt.ipv4First`; the listener binds all (`NetworkPort.listen(addresses, port)`); StartSession lists all without the IPv6 scope (DiPlay CTL:1753-1755, 3404-3410); Bonjour gets the first IPv4 | the end-to-end test "the iPhone is bootstrapped over Bluetooth, joins, pair-verifies and authenticates" (fake group lists `fe80::1%p2p-wlan0-0` first) | keep the platform's order → that test |
| **E6 (#8)** group across retries | **Deferred** (decision below) | — | — |
| **E7 (C1 gap)** ACKs after Connected | already built in C1 (lesson 55): "the Bluetooth link keeps acknowledging the iPhone's packets after CarPlay is connected" with DiPlay's wireless SYN parameters | that test | cancel the bootstrap at Connected → that test |
| **E8 (port contract)** | `ByteStream.read` and `BluetoothPort.dialIap2` KDoc: cancellation closes the socket; `FakePorts` counts open dial sockets and cancelled dials | "stopping during a long dial cancels it and leaves no socket open" | the dial wrapped in `withContext(NonCancellable)` → that test (and three teardown tests) |
| **E9 (#10)** fail fast | `LinkWatch` (CarLink CarPlayLinkWatch.kt:15-110 ported, same thresholds); `WifiDirectPort.clients(): Flow<GroupClients>` feeds it; verdicts become `CarPlayError.Stalled(NOT_JOINED / NO_START_REQUEST / NO_TCP)` or, after Connected, `PhoneDisconnected("iPhone left the Wi-Fi")`; a lost group (the flow throws) fails the attempt; `WifiDirectPort.groupWorked()` at Connected (#2's "remember the last good channel") | "an iPhone that does not join the Wi-Fi fails the start 9 s after the Wi-Fi details"; "an iPhone that joins but never asks to start fails 4 s after joining"; "an iPhone with a lease that opens no connection fails 8 s after the last nudge"; "an iPhone that leaves the group ends the session after 2 s, and a quick rejoin does not"; "without client reports from the platform no verdict cuts a slow start short"; "a lost Wi-Fi Direct group fails the attempt" | NO_TCP from the first StartSession → the third; a rejoin not clearing "gone" → the fourth; deciding before any report → the fifth |

Two deliberate differences from CarLink's watch, both written in `LinkWatch`'s KDoc: NO_TCP counts from the
latest StartSession (so E1's nudges get their time: 6 s + 8 s), and nothing is decided until the platform has
reported the group's clients once. Without that guard a port that cannot report clients would turn every slow
but healthy start into NOT_JOINED after 9 s (docs/LESSONS.md 63).

**E6 decision: deferred until P4 and P5.** Keeping the group across retries saves the group's creation time only
if the iPhone can rejoin a group that stayed up, and DiPlay #1114 (open) shows an iPhone that could not rejoin until
the access point was restarted; AOSP also shuts an idle P2P interface down after 150 s
(`WifiP2pServiceImpl.java:227`). What the iPhone actually remembers is the network (#1: same SSID, passphrase and
channel, which the port keeps stable), and the port can reuse a live group of ours at `createGroup` (port item
"Reuse or reclaim"). So each attempt still removes its group, and the engine change waits for the Poco's numbers.

### Android port items (`:carplay:engine-android`, C2; document only)

| Item | API, permission, file |
|---|---|
| Group with stable identity and channel plan (#1, #2) | `WifiP2pManager.createGroup(Channel, WifiP2pConfig, ActionListener)` (API 29) with `WifiP2pConfig.Builder.setNetworkName/ setPassphrase/ setGroupOperatingFrequency/ enablePersistentMode(true)`; plan 5180 → 5745 → 2437/2412/2462 → `createGroup(channel, null, …)`; BUSY: once more after 500 ms, then next (`P2pStartupRecovery.kt:115-176`). Verify with `WifiP2pGroup.getFrequency()`. Security from `WifiP2pGroup.getSecurityType()` (API 36; UNKNOWN → WPA2, as GM:905-925). Store SSID/passphrase/last good frequency in the app's private storage. Permissions: `NEARBY_WIFI_DEVICES` (`neverForLocation`), `ACCESS_FINE_LOCATION` `maxSdkVersion=32`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` |
| Reuse or reclaim a live group (#8) | `requestGroupInfo` first; ours with our SSID/passphrase/frequency → use it; ours but different → `removeGroup`, poll until gone (≤ 2 s, GM:250-267); someone else's → error |
| One Channel for the service's life (#15) | `WifiP2pManager.initialize(..., ChannelListener)`; re-initialize on `onChannelDisconnected` |
| Client join/leave, group removed (#10) | `WifiDirectPort.clients()`. `WIFI_P2P_CONNECTION_CHANGED_ACTION` with `EXTRA_WIFI_P2P_GROUP` works on every supported release and is what CarLink uses (`CarPlayAdapter.kt:350-368`, `RECEIVER_NOT_EXPORTED`; leases = `clientList.count { it.ipAddress != null }` on API 35+); `registerWifiP2pListener` (`onPeerClientJoined/Disconnected`) exists only from API 35 (api-versions.xml). Broadcasts are not sticky since Android 10: emit a `requestGroupInfo` snapshot first |
| Addresses (#7) | `NetworkInterface.getByName(group.getInterface())`, all of them (scoped `fe80::` with `%iface`); the engine orders them. DiPlay waits up to 2 s for the IPv4 to appear (`WifiP2pGroupManager.awaitInterfaceAddress`) |
| Receiver id (`interfaceMac`) | Apps targeting API 30+ get `null` from `NetworkInterface.getHardwareAddress()`; DiPlay falls back to `group.owner.deviceAddress`, then a saved id (CTL:1539-1541). Log which source was used (P4) |
| Listener and sockets (#9) | `ServerSocket` bound to each group address, port 7000 then 7001-7010 then ephemeral (`AirPlayPortSelector.kt`); `TCP_NODELAY`, `SO_KEEPALIVE`, `Os.setsockoptInt(fd, IPPROTO_TCP, 4/5/6, 10/3/3)`, `TCP_USER_TIMEOUT` 20000 via `ParcelFileDescriptor.fromSocket` |
| mDNS (#4) | `NsdManager.registerService` with no `setNetwork`. **Verified 2026-11-19 in AOSP** `packages/modules/Connectivity` main, `service-t/.../mdns/MdnsSocketProvider.java`: `requestSocket(null)` (lines ~683-709) also creates sockets for the local-only and tethered interfaces and for `mWifiP2pTetherInterface`, which it learns from `WIFI_P2P_CONNECTION_CHANGED_ACTION` (lines ~107-160, 212-221); a P2P group owner is reported through `onLocalOnlyInterfacesChanged`. `NsdServiceInfo.setAttribute` (framework-t) only warns on keys over 9 characters and keeps the TXT in an `ArrayMap` (order not kept, which DNS-SD does not need). No library needed. Optional: `AdvertisingRequest.FLAG_SKIP_PROBING` (API 36: "must only be used when the service name is known to be unique"; our name contains the device id). Register after the group interface exists |
| RFCOMM (#12, #15, E8) | `createRfcommSocketToServiceRecord(00000000-deca-fade-deca-deafdecacafe)`; `BluetoothAdapter.cancelDiscovery()` first (Android ref: "should always" be called; needs `BLUETOOTH_SCAN`); read loop treats -1 as closed; `close()` from another thread on cancel |
| Bluetooth on (#5) | `BluetoothAdapter.ACTION_STATE_CHANGED`, `EXTRA_STATE == STATE_ON`, registered **`RECEIVER_EXPORTED`**: Bluetooth broadcasts are sent by the Bluetooth app, not the system, so a not-exported receiver misses them (this draft first said `RECEIVER_NOT_EXPORTED`; `:bike:link-android` `AndroidSystemSignals` and docs/PLAN.md M4.1 already use exported; the action is protected, so no other app can send it; docs/LESSONS.md 62) |
| Early page (#12) | `fetchUuidsWithSdp()` or API 37 `BluetoothDevice.connect()` with a CDM association; ACL state from `ACTION_ACL_CONNECTED/DISCONNECTED` (no reflection) |
| Low-latency lock | `WIFI_MODE_FULL_LOW_LATENCY` is "only active when the device is connected to an access point", screen on, app in foreground (Android ref `WifiManager`): it does nothing for a GO-only Poco. Optional, don't rely on it |
| Permissions | `ACCESS_LOCAL_NETWORK` (Android 17), `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `NEARBY_WIFI_DEVICES`: one Nearby-devices prompt |

### ROM items (EvoX; document only)

| Item | What | Only if |
|---|---|---|
| Scan pause (#11) | Grant our app `MANAGE_WIFI_NETWORK_SELECTION` (privileged: priv-app + privapp-permissions allowlist), call `allowAutojoinGlobal(false)` during a session, restore after | P6 shows periodic scans during a session |
| Apple Device IE (#14) | `wpa_cli -i p2p-wlan0-0 VENDOR_ELEM_ADD 0/1/… dd0800a0400000020021` for beacon, probe response, association response (frame ids to look up in wpa_supplicant's `vendor_elem` enum before use), or a supplicant patch | P3 shows the iPhone not auto-joining |
| DHCP lease persistence | Apple spec p.403: "the accessory must store the DHCP IP address lease information across multiple reboots" (lease ≥ 3 days). Android's tethering DHCP server is not known to persist leases: **UNVERIFIED** | P7 shows DHCP delaying the first TCP |
| SCC / channel pinning | Qualcomm `WCNSS_qcom_cfg.ini` concurrency settings (research-3, LineageOS sdm845-common:189-198, **not re-read**) | The station is ever on during CarPlay |

## Needs the Poco

Each check names the log or command that settles it. All after the flash, on a release build (lesson 40).

- **P1. Baseline timeline.** One log line per phase (dial start/end, group up, StartSession, client joined, DHCP
  lease, first TCP, pair-verify, Connected) for 10 cold and 10 warm starts. Apple's target: reconnect ≤ 8 s from the
  first Bluetooth packet to the session start (spec p.406). Compare with CarLink 1.0.16 on the same Poco.
  The engine's own lines (tag `carplay`, logfmt; key material is never logged, `WifiNetwork` prints no passphrase),
  in order: `carplay.step step=LOADING_IDENTITY`, `step=PREPARING`, `carplay.wifi.group network=… addresses=[IPv4, …]`,
  `carplay.airplay.listening port=7000`, `carplay.wifi.clients joined= leased=` (first snapshot, then each change),
  `carplay.bonjour.published` (or `carplay.bonjour.failed`, never fatal), `step=DIALING_PHONE`,
  `carplay.bluetooth.dialed after_ms=`, `step=IDENTIFYING`, `carplay.iap2.connected`, `carplay.iap2.identified`,
  `step=AUTHENTICATING`, `carplay.iap2.authenticated`, `carplay.iap2.wifi_offered reason=request`,
  `carplay.iap2.start_session reason=request 1`, `step=WAITING_FOR_PHONE`, optionally `carplay.iap2.wifi_offered
  reason=nudge 1` + `start_session reason=nudge 1` (P8), `carplay.wifi.clients joined=1`, `carplay.airplay.accepted
  peer=`, `step=PAIRING`, `carplay.airplay.verified`, `carplay.connected phone=`. Failures: `carplay.watch.verdict`,
  then `carplay.recovering error= attempt= pause_ms=`; Bluetooth on: `carplay.bluetooth.on_retry` (P12).
- **P2. 5 GHz on the Poco GO.** Does `createGroup` at 5180 and at 5745 succeed (regulatory domain), and what does
  `getFrequency()` report? Which channel does CarLink get today?
- **P3. Auto-join.** With a stable group, does the iPhone join on its own after a Bluetooth reconnect? If not, test #14.
- **P4. Interface MAC.** Does the `p2p-wlan0-*` MAC change on each `createGroup`? It is our AirPlay `deviceID` and the
  `WifiGroup.interfaceMac`; a change per group would make the iPhone see a new receiver.
- **P5. Group reuse.** Time from stop to reconnect with the group kept vs. re-created; does the group survive 150 s
  with no client (AOSP idle shutdown is for an inactive interface)?
- **P6. Station scans.** `dumpsys wifiscanner` and `dumpsys wifi` during a session with Wi-Fi on and no saved network:
  periodic scans or not? If yes, #11.
- **P7. DHCP.** Time from "client joined" to the lease and to the first TCP; any lost DHCP offers (CarLink saw
  ~12 s of iOS DHCP back-off on a tablet, `WirelessAddressFamilyPlan.kt:11-13`).
- **P8. Nudge.** How often the first TCP takes > 3 s after StartSession, and whether the #3 nudge shortens it.
- **P9. NSD on the GO.** `dumpsys mdns`: is `_airplay._tcp` announced on the P2P interface? Does the iPhone connect
  with Bonjour off entirely (AllPlay says yes)?
- **P10. RFCOMM after Connected.** Keep reading for 2 minutes: no TEARDOWN; then test closing RFCOMM after the
  session starts (Apple p.410) and see whether the session survives.
- **P11. Dial timing.** Cold page vs. ACL already up; `BluetoothSocketException` codes on failures (API 34+).
- **P12. Bluetooth on.** Turn Bluetooth off, start, turn it on: time to the next attempt (target < 1 s, DiPlay 0.66 s).

## Log lines for the device checks (`:carplay:engine-android`, C2)

Every line goes through our `Logger` to logcat with tag `HandlebarLink`, one logfmt line per entry:
`<time> <level letter> <logger tag> <event> key=value …` (values with spaces or `=` are quoted). The logger tag is
the one the app passes to `createAndroidCarPlayPorts` (`carplay` if it binds `logger.child("carplay")`). Read with
`adb logcat -s HandlebarLink:V | grep -E 'carplay\.'` (captured to a file from the start, lesson 36). Lines marked
*engine* come from `:carplay:engine` (names as in snapshot 10afbd3; check them against branch `carplay-c2`).

| Check | Lines to read | What settles it |
|---|---|---|
| P1 timeline | `carplay.android.iphone.selected name= address= chosen= bonded_iphones= linked=` → `carplay.android.rfcomm.dialing address= acl_up=` → `carplay.android.wifi.plan iphone= ssid= plan=` → `carplay.android.wifi.create request= outcome=` (one per request) → `carplay.android.wifi.group_ready ssid= stable= requested= frequency= channel= security= interface= addresses=` → `carplay.android.listener.bound port= preferred= addresses=` → `carplay.android.mdns.registered type= name= port=` → `carplay.android.rfcomm.connected after_ms= acl_up=` → *engine* `carplay.bluetooth.dialed`, `carplay.step`, `carplay.wifi.clients joined= leased=`, `carplay.airplay.accepted peer=`, `carplay.airplay.verified`, `carplay.connected` | Logcat timestamps between the first and the last line, 10 cold and 10 warm starts |
| P2 5 GHz | `carplay.android.wifi.create request=Frequency(megahertz=5180) outcome=created\|BUSY\|ERROR\|…` and `carplay.android.wifi.group_ready … frequency=5180 channel=36` | Which request succeeded and the frequency the framework reports (`WifiP2pGroup.getFrequency()`) |
| P3 auto-join | `carplay.android.wifi.group_ready … stable=true` (same `ssid=` every attempt), then *engine* `carplay.wifi.clients joined=1` | Time from group_ready to joined=1 after a Bluetooth reconnect, without touching the iPhone |
| P4 receiver id | `carplay.android.wifi.receiver_id id= source=HARDWARE\|GROUP_OWNER\|IDENTITY interface_mac= owner_address=` and `carplay.android.bluetooth.local_address source=IDENTITY address=` | Expected on a normal build: `source=IDENTITY`, `interface_mac=null`, `owner_address=02:00:00:00:00:00`, and the same `id` on every group (it comes from the stored AirPlay identity, not the interface) |
| P5 group reuse | `carplay.android.wifi.group_reused ssid= mhz=` or `carplay.android.wifi.group_reclaimed ssid= mhz=`; on stop `carplay.android.wifi.group_removed ssid= gone=true\|false`, `carplay.android.wifi.remove_skipped ssid=`, `carplay.android.wifi.remove_timeout after_ms=` | Whether a live group is read as it stands, and how long removal takes |
| P6 station scans | none of ours | `dumpsys wifiscanner`, `dumpsys wifi` during a session |
| P7 DHCP | *engine* `carplay.wifi.clients joined=1 leased=0`, then `leased=1` (Android 15+), then `carplay.airplay.accepted peer=` | Time joined → leased → first TCP |
| P8 nudge | *engine* lines of the 0x5703/0x4301 re-send, then `carplay.airplay.accepted` | How often the first TCP needs the nudge |
| P9 NSD | `carplay.android.mdns.registered type=_airplay._tcp name= port=` or `carplay.android.mdns.failed type= code=` | Plus `dumpsys mdns` (is it announced on `p2p-wlan0-*`?) |
| P10 RFCOMM after Connected | *engine* `carplay.connected`, later `carplay.bootstrap.ended reason=` | That no TEARDOWN follows in 2 minutes while the RFCOMM reads continue |
| P11 dial timing | `carplay.android.rfcomm.dialing address= acl_up=true\|false`, then `carplay.android.rfcomm.connected after_ms= acl_up=` or `carplay.android.rfcomm.failed address= after_ms= code= error=` (`code` is `BluetoothSocketException.getErrorCode()`, API 34+) | Cold page vs ACL already up; failure codes |
| P12 Bluetooth on | `carplay.android.bluetooth.on`, then *engine* `carplay.bluetooth.on_retry` and the next `carplay.android.rfcomm.dialing` | Time from Bluetooth on to the next dial (target < 1 s) |

Also useful: `carplay.android.mfi.loaded certificate_bytes=607` (the owner's identity is in the build) or
`carplay.android.mfi.missing key= certificate=` (it is not: `carplay.mfi.dir` unset), `carplay.android.identity.created
pairing_id=` (first run only), `carplay.android.wifi.channel_remembered iphone= mhz=` (after Connected),
`carplay.android.wifi.channel_lost` (the framework dropped our Wi-Fi Direct channel), `carplay.android.tcp.tuning_failed`.

## Not acted on (and why)

- **IPv6-first guess** (CarLink `ipv6Guess`): faster first TCP but a slower handshake on CarLink's tablet; off.
- **`_carplay-ctrl` invite probe** (`CarPlayBonjour.kt:97-129`): CarLink notes it is "Not yet verified on the tablet";
  unmeasured.
- **Unreal-link detection** (`D/transport/BluetoothLinkCheck.kt`, PR #590): for head units whose Bluetooth answers in
  < 50 ms with no phone behind it; the Poco has a real radio.
- **Hidden APIs** (`setWifiP2pChannels`, `isConnected` by reflection): AGENTS.md forbids them.
- **Research claims not re-read this session** (treat as UNVERIFIED): WWDC17/WWDC23 talk contents; Apple reference
  `AirPlayReceiverSession.c` keep-alive 3 s × 3; showcase's TOS 0x80 on the screen socket; WAAD #253/#201 paging
  numbers; LIVI `server.rs`; carplayd `wifi_handoff.rs`; AOSP `MdnsSocketProvider`; the Qualcomm `WCNSS_qcom_cfg.ini`
  lines; elezabi/wireless-aa-bridge.
