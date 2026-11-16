# 04 — Helmet intercom audio: how sound and microphone flow, and the one setup to use

Date: 2026-11-16. Scope: research and observation only. No setting, code, pairing or app state was changed.

Setup under study: iPhone 15 "AbidiPhone" → wireless CarPlay (Wi-Fi Direct) → Poco F1 (Android 17 / SDK 37, custom ROM
`infinity_beryllium`, not rooted) running CarLink 1.0.16 (`com.carlink.app`, versionCode 10016, embeds DiPlay) →
Bluetooth helmet intercom "V6 Pro+".

Labels used in this note:
- **[VERIFIED]**: read in source with a file:line, or seen on the live Poco over adb (date and time given).
- **[INFERRED]**: follows from verified facts, but not observed end to end.
- **[UNKNOWN]**: not established. Needs the on-bike test in §7.

Source paths are relative to `/Volumes/My files/pocof1/carlink-src` (branch `bike` = tag v1.0.16, commit `0be1cdee`).
Short names:
- `AMS` = `vendor/diplay/shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`
- `MU` = `.../media/MicrophoneUplink.kt`
- `CPHA` = `vendor/diplay/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`
- `APP` = `vendor/diplay/common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt`
- `INFO` = `vendor/diplay/shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt`

AOSP references: `frameworks/base/.../server/audio/AudioDeviceBroker.java` and
`frameworks/av/services/audiopolicy/enginedefault/src/Engine.cpp`, both fetched from android.googlesource.com
`refs/heads/main` on 2026-11-16. The Poco runs a custom ROM, so the AOSP logic is [VERIFIED] as AOSP code. On this
device it is [INFERRED], except where the dumps below show it directly.

---

## 0. Summary

- **Why call audio "comes from somewhere".** A CarPlay call does not use Bluetooth hands-free on the Poco. DiPlay
  plays the call as an Android VoIP stream: `USAGE_VOICE_COMMUNICATION` plus `MODE_IN_COMMUNICATION` (AMS:588-610, 2296-2306).
  Nothing in CarLink or DiPlay ever chooses the intercom's SCO link. Android does not choose classic SCO on its own
  (AOSP `getDefaultCommunicationDevice` picks only a hearing aid or LE Audio). Android also drops A2DP during a call
  (Engine.cpp `STRATEGY_PHONE`). So the call goes to the **earpiece**. DiPlay then sees the earpiece and moves the call to
  the **Poco's loudspeaker** (AMS:617-627, `CallSpeaker`). Your voice is taken from the **Poco's own microphone**.
  While the call runs, music and navigation follow the call to the same loudspeaker (Engine.cpp `remapStrategyFromContext`).
  - On the live Poco, the phone strategy currently resolves to `AUDIO_DEVICE_OUT_EARPIECE`, and no communication device
    is set (dumpsys, 21:20) [VERIFIED].
  - The full chain through to the loudspeaker during a live call: [INFERRED]. It needs a call log to confirm.
- **Siri works the same way.** Siri's voice plays like media, so you hear it in the helmet when the intercom is on A2DP.
  Siri's microphone is the **Poco's built-in mic**: `source=VOICE_RECOGNITION … routedDeviceType=15`, seen live at
  21:07–21:08 [VERIFIED].
- **"Sound through car Bluetooth" does not mean what its name suggests.** Turning it on makes CarLink tell the iPhone that
  CarPlay has **no audio at all** (`disableAudioOutput`, CPHA:5185-5211, INFO:17-19, 28-29, 51-55). The iPhone then keeps
  every sound and the microphone on its own Bluetooth route. The Poco cannot receive audio from the iPhone over Bluetooth:
  it runs no A2DP-sink or hands-free-client service [VERIFIED]. So with this setting on, sound goes to whatever headset
  is paired **to the iPhone**, or to the iPhone's speaker.
- **Recommendation:** pair the intercom **to the iPhone only**, remove it from the Poco, and set CarLink › Sound ›
  **"Through the car's Bluetooth"**.
  - iOS then does everything natively over the intercom's hands-free (HFP) and stereo (A2DP) links: music, directions,
    Siri, calls and the helmet mic. The helmet's own buttons answer calls and trigger Siri on the iPhone.
  - It needs no code, and the Poco stays silent.
  - How iOS behaves when a CarPlay unit offers no audio is **[UNKNOWN]** on this phone. DiPlay calls the mode experimental.
    The bench test in §7.1 must pass before you rely on it.
  - If it fails, there is no fix inside the official CarLink. Option B (§5.2) needs code in the receiver process:
    setting the intercom's SCO link as the communication device. A separate helper app cannot do it (§5.3). We are not
    forking CarLink, so this becomes part of **our own receiver (§9)**.
- **Our own receiver (§9).** It supports both routing modes, chosen once per session before `/info`:
  - "phone-routed" = Option A;
  - "receiver-routed" = Option B done properly.
  In receiver-routed mode, calls are modelled as a self-managed Telecom call. Then Android itself brings up SCO to the
  intercom, and the helmet's answer and hang-up buttons reach our code.

---

## 1. What CarLink/DiPlay does with each CarPlay audio stream

### 1.1 What CarLink offers the iPhone

`INFO.audioFormats` (INFO:120-175) declares these streams [VERIFIED]:
- type 100 `default` (prompts), `alert`, `media`, `telephony` (with microphone input), and `speechRecognition` (Siri, with
  microphone input);
- type 101 `default` (the alternate channel: navigation prompts);
- type 102 `media` (AAC);
- the buffered music stream (type 103, AAC-LC) when "Buffered music" is on (INFO:170-173).

The microphone is offered only when RECORD_AUDIO is granted (`microphoneAvailable`, CPHA:278, 949). It is granted on the
Poco [VERIFIED, `dumpsys package`].

### 1.2 Each stream's Android routing

Mobile mapping (`AudioChannelMapping.kt:67-79`). CarLink never turns on the "automotive bus" mapping (`APP:231-233`,
default false). Usages come from AMS:2296-2306.

| CarPlay stream | DiPlay channel | AudioAttributes usage / content | Audio focus | Live evidence (logcat 2026-11-16) |
|---|---|---|---|---|
| `media` (Music, type 103 buffered AAC 48 kHz) | MEDIA | `USAGE_MEDIA` (1) / MUSIC | DiPlay focus off by default (`APP:251-253`, `focus=off` in log). CarLink's media-key session separately holds `USAGE_MEDIA` GAIN focus (focus stack) | `audio route type=103 audioType=media … usage=1 contentType=2 … focus=off`, `routeType=2` (speaker) 20:56:44 [VERIFIED] |
| `default` / `alert` / `compatibility` (directions, chimes, ringtone **[INFERRED]**) | NAVIGATION | `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` (12) / SPEECH; legacy stream only if Advanced "Navigation audio stream" ≠ 0 (`AMS:2190-2194`) | none; plays over music (AMS:2254-2262; opt-in prompt focus in `NavigationPromptFocus.kt`) | `type=101 audioType=default … channel=NAVIGATION usage=12`, `routeType=2` 21:18:39 [VERIFIED] |
| `speechRecognition` (Siri) | ASSISTANT | `USAGE_ASSISTANT` (16) / SPEECH | `GAIN_TRANSIENT_MAY_DUCK` when focus is on (AMS:132-134) | `type=100 audioType=speechrecognition … usage=16`, `routeType=2` 21:07:58 [VERIFIED] |
| `telephony` (CarPlay call) | PHONE (MEDIA if "Call audio plays as: Media") | `USAGE_VOICE_COMMUNICATION` / SPEECH (AMS:2298); media usage with `callsAsMedia` (AMS:2240, `HostAudio.kt`) | `GAIN_TRANSIENT` (AMS:133) | no call in the captured window [UNKNOWN] |

On the Poco, the policy engine puts `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` in STRATEGY_MEDIA and `USAGE_ASSISTANT` in
STRATEGY_ASSISTANT (`dumpsys media.audio_policy`, 21:20) [VERIFIED]. With an A2DP headset connected, music, directions
and Siri playback follow A2DP to the helmet [INFERRED]. When this snapshot was taken all three were on `SPEAKER`,
because the intercom was off (see §3).

### 1.3 Microphone capture (`MU`)

- **Source** (MU:97-112, 702-706) [VERIFIED]:
  - Siri: `VOICE_RECOGNITION` (`VOICE_COMMUNICATION` on Geely units);
  - call: `VOICE_COMMUNICATION`. It is `VOICE_RECOGNITION` when the BYD "car phone audio state" is held, and `MIC` for the
    BYD-suspended speakerphone case.
- **Rate and codec:** whatever the iPhone picks from the offer. Live: Siri used Opus at 48 kHz mono, 20 ms frames, with the
  MediaCodec encoder [VERIFIED, 21:07:58]. With "call at call rate", PCM at 8/16/24/32 kHz is offered (INFO:150-159).
- **Platform AEC/NS effects** are applied on calls unless speakerphone or car-phone-state (MU:169, 234-259).
- **No device selection on the recorder.** The `AudioRecord.Builder` sets no preferred device (MU:767-780) [VERIFIED].
  Anywhere in DiPlay or CarLink [VERIFIED by grep]:
  - no `startBluetoothSco`;
  - no `setPreferredDevice`;
  - the only `setCommunicationDevice` call targets **`TYPE_BUILTIN_SPEAKER`** (`CallSpeaker.kt:18-24`).
- **Live result for Siri:** `Microphone: start type=speechrecognition source=VOICE_RECOGNITION … routedDeviceType=15`
  (15 = `TYPE_BUILTIN_MIC`) [VERIFIED].

### 1.4 What happens on a CarPlay call (AMS:557-683)

1. **Microphone starts.** When the iPhone opens the telephony microphone, `onMicrophoneStarted` calls
   `enterCommunicationMode`. That sets `AudioManager.MODE_IN_COMMUNICATION` "before AudioRecord is created" (AMS:588-610).
   - It optionally routes to the loudspeaker if DiPlay's `callSpeakerphone` is on (APP:820-821, default false). No CarLink
     screen exposes it [VERIFIED by grep].
2. **Earpiece fallback.** A routing listener on the call track watches for `TYPE_BUILTIN_EARPIECE` (AMS:2140-2147, 2601-2603).
   If the call lands there, `moveCallToLoudspeaker` calls `CallSpeaker.on`, which does
   `setCommunicationDevice(TYPE_BUILTIN_SPEAKER)`, once per call (AMS:617-627). The code comment says this was made for
   phones and tablets used as head units (#701, #782).
3. **Restore.** When the microphone stops, the mode goes back to `MODE_NORMAL` and the communication device is cleared
   (AMS:628-650).

Why the call lands on the earpiece (AOSP):
- The communication device is chosen only from an explicit `setCommunicationDevice`, or by default from a hearing aid or
  LE Audio headset (`AudioDeviceBroker.getDefaultCommunicationDevice`). **Classic SCO is never a default.**
- In a call, `STRATEGY_PHONE` removes A2DP devices (Engine.cpp, "Do not use A2DP devices when in call"), then falls back
  to dock, then `EARPIECE`, then `SPEAKER`.
- In a call, `STRATEGY_MEDIA` and sonification are remapped onto `STRATEGY_PHONE` (`remapStrategyFromContext`).
- `VOICE_COMMUNICATION` / `VOICE_RECOGNITION` capture uses the SCO headset mic **only if** the phone strategy's device is
  SCO. Otherwise it uses the built-in mic (Engine.cpp `getDeviceForInputSource`).

**Result with the intercom connected to the Poco** [INFERRED]:
- the call plays on the Poco's earpiece, then its loudspeaker;
- music and directions during the call follow it to the same speaker;
- your voice is taken from the handlebar phone's mic.

This matches "call audio comes from somewhere".

**"Call audio plays as: Media"** (`callAudioAsMedia`, strings `carlink_strings_settings.xml:158-163`) is worse on a phone
[INFERRED]:
- it changes the track to `USAGE_MEDIA` (AMS:2240), but the microphone still enters `MODE_IN_COMMUNICATION` (AMS:560-561
  only skips that for BYD);
- the media track is then remapped to the phone strategy, which goes to the earpiece;
- the earpiece fallback never fires, because it only watches PHONE-channel tracks (AMS:2140, 2601).

So the call can stay on the earpiece. Do not use it.

### 1.5 Phone-call settings in CarLink and DiPlay

- **CarLink Settings › Sound** (`app/.../ui/settings/SettingsPages.kt:196-235`):
  - "Sound plays: Through CarLink / Through the car's Bluetooth";
  - audio delay;
  - levels for media, navigation and calls;
  - "Call audio plays as: Phone call / Media";
  - mic gain.
- **Advanced** (`carlink-core/.../settings/AdvancedSettings.kt:150-176`):
  - media and navigation stream;
  - "Take audio focus";
  - "Mute music for car announcements";
  - buffered music;
  - call echo cancellation;
  - "Clearer call voices".
- **None of these selects a Bluetooth headset or hands-free route** [VERIFIED].
- DiPlay's "car phone audio state" (`telecom/PhoneAudioRoute.kt:132-216`) holds a self-managed Telecom call while a CarPlay
  call is active (call state from iAP2 `CallStateUpdate` 0x4155, `hud/BydCarPlayCall.kt:22-60, 150-158`).
  - It is BYD-only and off by default (`hud/BydOutputSettings.kt:136`).
  - No DiPlay phone account is registered on the Poco (`dumpsys telecom`) [VERIFIED].
  - [INFERRED] Turning it on would make Android Telecom route a self-managed call to the connected HFP headset. That
    would be a second way to reach SCO. But it is reachable only through DiPlay's own settings screen, and CarLink hides
    that screen.

### 1.6 What `soundThroughCarBluetooth` ("Sound plays: Through the car's Bluetooth") really does

Chain [VERIFIED]:
1. `UnifiedSettings.soundThroughCarBluetooth` (`core/settings/UnifiedSettings.kt:24`, pref `sound_car_bluetooth`, default false).
2. → `CarPlayEngineSettings.carBluetoothAudio` (`core/connection/CarPlaySettings.kt:175`).
3. → `AirPlayPersistence.saveCarBluetoothAudio` (`CarPlaySettings.kt:228`). The DiPlay doc comment reads: *"Offer no
   CarPlay audio, so the iPhone keeps audio on its Bluetooth link with the car; applies at reconnect"* (APP:742-748).
4. → `AirPlayConfig.disableAudioOutput` (CPHA:5185-5211).
5. → `INFO.features()` clears the CarPlay audio feature bits (`CARPLAY_FEATURES and CARPLAY_AUDIO_FEATURES.inv()`,
   INFO:17-19, 28-29). `/info` then omits `audioLatencies` and `audioFormats`, **including the microphone formats**
   (INFO:51-55).
6. At session start CPHA logs `Car Bluetooth audio: on; CarPlay audio not offered` and probes which A2DP/HFP devices the
   **Poco** has connected (CPHA:5215-5240).

The UI string says "Music goes through the car's own Bluetooth audio instead" (`carlink_strings_settings.xml:137-139`).
In fact **all** CarPlay sound and the microphone leave CarLink. DiPlay's 0.2.15 release notes call it experimental:
"calls, Siri and navigation behavior need vehicle feedback" (`vendor/diplay/docs/RELEASE-NOTES-0.2.15.md:18`).

On a head unit, "the car's Bluetooth" is the unit's hands-free/audio sink. The Poco has none:
- enabled profile services: GATT, A2DP (source), AVRCP (target), BATTERY, HEADSET (audio gateway), HEARING_AID,
  HID_DEVICE, HID_HOST, MAP, OPP, PAN, PBAP, SAP;
- no A2DP_SINK and no HEADSET_CLIENT (`dumpsys bluetooth_manager`, 21:20) [VERIFIED].

So with this setting the iPhone's sound can only go to a headset paired **to the iPhone**, or to the iPhone itself
[INFERRED].

---

## 2. Bluetooth roles of each device

### 2.1 iPhone ↔ Poco

- The Poco's bond record for AbidiPhone lists these iPhone UUIDs: iAP2 `00000000-deca-fade-…`, A2DP source `110a`,
  AVRCP `110e`, NAP `1116`, HFP audio gateway `111f`, PBAP `112f`, MAP `1132`, plus Apple-private UUIDs [VERIFIED].
- CarLink and DiPlay open **only an RFCOMM client to iAP2** (`orchestration/CarPlayController.kt:1465`, UUID at `:3726`).
  It is used for identification, MFi auth, Wi-Fi credentials and StartSession; picture and sound then go over Wi-Fi
  Direct (see `CARLINK_NOTES.md` §4.2) [VERIFIED].
- The Poco cannot act as the iPhone's headset or car kit (no HF or A2DP-sink role, §1.6). So **the iPhone never sends
  call or music audio to the Poco over Bluetooth**. With normal CarLink sound, everything travels as AirPlay streams over
  Wi-Fi [VERIFIED].
- iPhone ACL to the Poco at 21:20: not connected (`ACL BR/EDR:N`) [VERIFIED].

### 2.2 iOS: CarPlay audio vs a Bluetooth headset

- While wired or wireless CarPlay **with audio** is active, iOS routes media, Siri and calls to CarPlay. A Bluetooth headset
  paired to the iPhone is then a second route that the user or iOS may pick (Control Center output picker, or the
  in-call audio button) [INFERRED from general iOS behaviour; Apple publishes no routing table].
  - If the intercom is paired to **both** phones, which one carries a call becomes a race between iOS's route choice and
    the intercom's own logic [INFERRED]. This is one candidate cause of "comes from somewhere".
  - The V6 Pro **auto-answers incoming calls after 5 s** (EJEAS V6 Pro manual: "When comes a call, without operation 5s,
    phones will automatic receive") [VERIFIED in the manual].
- Motorcycle CarPlay screen vendors usually give this advice:
  - "Pair the intercom directly to your phone; that is what carries calls, music, and navigation voice reliably" (Aoocci
    C9 Pro Max routing guide);
  - "Pair your headset directly with your phone … audio routes automatically when CarPlay or Android Auto connects"
    (Laser Co. 6" bike CarPlay FAQ).
  - Those screens play no audio themselves, which is what `disableAudioOutput` reproduces [VERIFIED: what the pages say;
    INFERRED: that the mechanism is the same].
- **[UNKNOWN]** How iOS 26/27 on this iPhone routes Siri and calls when the CarPlay accessory declares no audio and the
  accessory's Bluetooth address (the Poco's, sent in `bluetoothIDs`, INFO:44) has no HFP. The expected and most likely
  result is "use the connected HFP headset", but it is not verified. See test §7.1.

### 2.3 Android (Poco) A2DP vs HFP/SCO

- Media, navigation and assistant usages go to the active A2DP device when not in a call [INFERRED from policy; on the
  snapshot they were on SPEAKER because no A2DP device was connected].
- Since Android 12 (API 31), voice uses SCO only after an explicit `AudioManager.setCommunicationDevice(<TYPE_BLUETOOTH_SCO
  device>)` (or the deprecated `startBluetoothSco`). Valid types include `TYPE_BLUETOOTH_SCO`
  (`AudioDeviceBroker.VALID_COMMUNICATION_DEVICE_TYPES`).
- When SCO becomes the phone strategy's device:
  - `VOICE_RECOGNITION` and `VOICE_COMMUNICATION` capture use `AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET` (Engine.cpp);
  - A2DP is suspended while SCO is up.
- **Mode-owner rule** [VERIFIED, AOSP `AudioDeviceBroker.topCommunicationRouteClient`, lines 514-525]:
  - The communication route used is the request from the **same UID as the audio-mode owner**.
  - Only when nobody owns the mode is the first *active* client used.
  - DiPlay sets `MODE_IN_COMMUNICATION` from the CarLink process. So **a separate app's `setCommunicationDevice` is
    ignored during a CarPlay call**. The SCO choice must be made inside CarLink's process.
- **LE Audio:** the Poco has no LE Audio service (`mLeAudio: null`, no LE_AUDIO profile) [VERIFIED]. The V6 Pro+ is
  classic BR/EDR only (its UUIDs) [VERIFIED]. Not relevant here.

### 2.4 The intercom: "V6 Pro+" = EJEAS V6 PRO+

- **Identity.** Sold as the "EJEAS V6 PRO+ Motorcycle Helmet Bluetooth Intercom" (several retail listings; EJEAS product
  page ejeas.com/products/v6-pro). The Bluetooth MAC prefix `00:12:6F` belongs to Rayson Technology (Taiwan), a Bluetooth
  module maker (maclookup.app) [VERIFIED name/OUI; INFERRED brand match].
- **Profiles seen by the Poco** [VERIFIED, bond record]:
  - SPP `1101`, HSP `1108`, A2DP source `110a` and sink `110b`, AVRCP `110e`, HFP hands-free `111e` and audio gateway
    `111f`;
  - the A2DP codec was AAC 44.1 kHz (A2DP state machine);
  - HFP bring-up included `AT+BIND=1,2` (HF indicators) and the Apple extension `+XAPL=0000-0000-0100,10` (features 10 =
    battery reporting and Siri-status bits [INFERRED meaning of the bitmask]);
  - `isInbandRingingEnabled: false`.
- **Manual facts** (EJEAS V6 Pro manual, manuals.plus) [VERIFIED as text in the manual]:
  - pair with the phone by holding the Phone button 5 s (red/blue flashing);
  - Phone button: short press = answer / hang up / play-pause, long press = reject / **voice assistant**, double press =
    redial;
  - auto-answer after 5 s;
  - clear pairings with Phone + B for about 2 s;
  - intercom with up to 6 units, 2 talking at a time, 800 m;
  - SafeRiding companion app.
- **[UNKNOWN]**:
  - how many phones it holds at once (resellers claim two phones; the manual does not say);
  - whether music or a phone call can mix with an intercom conversation;
  - whether its HFP link supports wideband speech (mSBC; "HD-Voice" is marketing).
- **Consequence of pairing it to the Poco** [INFERRED]: its Phone-button commands go to the **Poco's** HFP audio gateway,
  not the iPhone's.
  - Answer/hang-up act on Poco telephony; there is no Poco call.
  - Voice assistant (BVRA) opens the Poco's default assistant, not Siri. The Google app is registered against the V6 Pro+
    bond (`[Packages]` in the bond record) [VERIFIED].
  - So the helmet's own call and Siri buttons cannot control CarPlay in that setup.

---

## 3. Live observations (adb, read-only, 2026-11-16 20:48–21:20 IST)

Commands run: `dumpsys bluetooth_manager`, `dumpsys audio`, `dumpsys media.audio_policy`, `dumpsys telecom`,
`dumpsys package com.carlink.app`, `logcat -d`. No state was changed.

**Intercom state**
- The intercom is **not connected now**. The last HFP/A2DP session to the Poco was 19:14:02–19:28:54. Earlier sessions:
  13:20–13:35, 13:58–15:11, 16:13–16:41, 17:20–18:00. Active A2DP/HFP: NULL [VERIFIED].
- The brief said the intercom was "the active A2DP device". That was true earlier in the day, not at capture time.

**Where CarPlay sound went during the capture**
- With the intercom off, CarPlay music, directions and Siri all played on the **Poco loudspeaker**: `routeType=2` on every
  `audio stats` line (17× media, 5× navigation, 9× Siri) [VERIFIED].
- Nothing warns the rider about this. Failure mode F1 (§6).

**Siri**
- Three Siri sessions: 21:07:58, 21:08:11, 21:08:55.
- Microphone was `VOICE_RECOGNITION`, `routedDeviceType=15` (built-in mic), Opus 48 kHz.
- Playback was `USAGE_ASSISTANT`, `routeType=2` [VERIFIED].

**Calls**
- No CarPlay call happened in the logcat window. No `mode entered communication` or `call routed to the earpiece` lines
  [VERIFIED absence].

**Audio routing (`dumpsys audio`)**
- Mode `MODE_NORMAL`, mode owner none.
- `Computed/Applied Preferred communication device: null`.
- `mScoAudioState: SCO_STATE_INACTIVE`; `Bluetooth SCO on, requested: false`.
- `Active communication device: bt_a2dp V6 Pro+` (stale, cached from the last connection) [VERIFIED].
- Each time the intercom connected, the broker logged `SCO sink/source device … now available` followed by
  `updateCommunicationRoute, preferredCommunicationDevice: null`. So SCO was available but never selected [VERIFIED].

**Audio policy (`dumpsys media.audio_policy`)**
- `STRATEGY_PHONE` selected device `AUDIO_DEVICE_OUT_EARPIECE`; `STRATEGY_MEDIA` and `STRATEGY_ASSISTANT` → SPEAKER.
- `Force use for communications: 0`, `Force use for record: 0` [VERIFIED].

**Audio focus stack**
- `com.carlink.app` (CarPlayMediaKeys) `USAGE_MEDIA` GAIN.
- DiPlay's own focus is off (`focus=off`) [VERIFIED].

**Earlier assistant activity**
- `setMode(MODE_ASSISTANT_CONVERSATION)` from `com.google.android.googlequicksearchbox` at 16:52:31 and 17:18:08. The
  intercom was not connected at those times, so these were not from the helmet button [VERIFIED times; cause unknown].

**Telecom and permissions**
- Telecom: no DiPlay self-managed phone account is registered.
- CarLink holds RECORD_AUDIO, MODIFY_AUDIO_SETTINGS, BLUETOOTH_CONNECT, MANAGE_OWN_CALLS and READ_PHONE_STATE [VERIFIED].

**HandlebarLink**
- `com.abid.handlebarlink` (our handlebar app) is bonded to the bike's BLE remote. It sends media keys through
  `DiPlay-MediaKeys` (`notes/bike-test.log`) [VERIFIED].

---

## 4. Where each sound goes today (intercom paired to the Poco, CarLink sound "Through CarLink")

| Sound | Expected path | Status |
|---|---|---|
| Music | iPhone → Wi-Fi → CarLink `USAGE_MEDIA` → Poco A2DP → helmet | [INFERRED]; went to the Poco speaker whenever the intercom was off [VERIFIED] |
| Directions | iPhone → Wi-Fi → `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` → A2DP → helmet | [INFERRED] |
| Siri voice | `USAGE_ASSISTANT` → A2DP → helmet | [INFERRED] |
| Siri mic | **Poco built-in mic** on the handlebar (wind noise) | [VERIFIED] |
| Call audio | `MODE_IN_COMMUNICATION` → earpiece → DiPlay moves it to the **Poco loudspeaker** | [INFERRED] |
| Call mic | **Poco built-in mic** | [INFERRED] |
| Music/directions during a call | same loudspeaker as the call | [INFERRED] |
| Helmet Phone button | goes to the Poco's HFP gateway, not the iPhone | [INFERRED] |
| Helmet answer/hang-up | does nothing for a CarPlay call | [INFERRED] |
| Helmet voice-assistant button | does not start Siri | [INFERRED] |

So nothing about calls reaches the helmet in this setup, and Siri hears the handlebar instead of the rider.

---

## 5. Recommendation

### 5.1 Target setup (Option A): intercom ↔ iPhone, CarLink offers no audio

1. **On the Poco:** Settings › Bluetooth › V6 Pro+ › **Forget**. The intercom must never connect to the Poco: it would
   steal the intercom's phone slot and let the Poco's system sounds and assistant interfere.
2. **On the intercom:** optionally clear pairings (Phone + B, about 2 s). Then pair it **from the iPhone**: hold Phone 5 s
   until red/blue flashing, then in iPhone Settings › Bluetooth tap V6 Pro+.
   - Allow "Share System Notifications" if asked.
   - Check that it shows as a headset, so both call and media audio are enabled.
3. **On the iPhone:**
   - leave CarLink paired as is (iAP2 only);
   - Settings › Siri › "Press Side Button / Hey Siri" as preferred;
   - optionally Settings › Accessibility › Touch › Call Audio Routing = **Automatic** (leave on Automatic, not Speaker).
4. **In CarLink:** Settings › Sound › **Sound plays = "Through the car's Bluetooth"**. It applies from the next connection
   (APP:742).
   - Leave "Call audio plays as" on Phone call; it is unused in this mode.
   - Leave the levels and mic gain alone; they no longer apply.
5. **The Poco itself:** keep its media volume low or muted and turn off its notification sounds. It plays nothing from
   CarPlay in this mode.

Expected result [INFERRED, verify with §7.1]:
- every iPhone sound (music, directions, Siri, calls, ringtone) goes iPhone → intercom over A2DP/HFP;
- every microphone use (Siri, calls) uses the helmet mic over HFP;
- the helmet buttons control the iPhone (answer, hang up, Siri, play/pause);
- CarPlay still shows the picture, takes touch, receives HandlebarLink's media keys (these go over the AirPlay control and
  HID channel, not audio) and shows the call screen.

Why A rather than B:
- iOS's own HFP stack handles call and Siri routing and the helmet buttons. A fork cannot get those helmet buttons back
  (§2.4).
- It needs no code, and it removes the Poco and Wi-Fi from the audio path entirely.

Costs:
- The Wi-Fi Direct audio buffer and the CarLink levels, echo canceller and mic gain no longer apply.
- If the iPhone drops the intercom, sound falls back to the **iPhone's** speaker in your pocket (F2).

### 5.2 Fallback (Option B): intercom ↔ Poco, receiver audio on, receiver enforces SCO for calls and Siri

> **Update 2026-11-16:** we will not fork CarLink. Use the official CarLink only as it is today (Option A, §5.1). The
> code changes below describe **behaviour that has to exist somewhere**. They become requirements for our own receiver
> (§9), not a CarLink patch. With the unmodified CarLink, Option B cannot route calls or Siri to the helmet.

Use this only if §7.1 shows iOS will not do Option A cleanly.

Fork changes, in the CarLink/DiPlay process (`// CarLink` hooks plus a `docs/VENDOR_CHANGES.md` row each):

1. **Call route hook.** Add `HostAudio.callRoute: ((AudioManager) -> AudioDeviceInfo?)?`, set by carlink-core:
   - In `AMS.enterCommunicationMode`, after `mode = MODE_IN_COMMUNICATION` and before the AudioRecord is built (AMS:588-610):
     choose `availableCommunicationDevices.firstOrNull { it.type == TYPE_BLUETOOTH_SCO }`, preferring the intercom's address.
   - Call `setCommunicationDevice(sco)` and keep it as the undo, like `CallSpeaker` (`CallSpeaker.kt:18-24`).
   - Fall back to `CallSpeaker` only when no SCO device exists.
   - Make `moveCallToLoudspeaker` (AMS:617-627) prefer SCO too, so the earpiece fallback never picks the loudspeaker while
     the intercom is connected.
   - Listen with `addOnCommunicationDeviceChangedListener` and log the chosen device.
2. **Siri.** On `onMicrophoneStarted` with `audioType == "speechrecognition"` (AMS:557), when SCO exists:
   - enter communication mode and set the SCO communication device the same way;
   - play the `speechRecognition` track with `USAGE_VOICE_COMMUNICATION` instead of `USAGE_ASSISTANT` (AMS:2299-2303).
     Reason: A2DP is suspended while SCO is up, and `USAGE_ASSISTANT` is not remapped onto the phone strategy, so Siri
     could otherwise fall to the loudspeaker [INFERRED from Engine.cpp];
   - `VOICE_RECOGNITION` capture then uses the SCO mic automatically (Engine.cpp).
   - Restore on microphone stop (AMS:628-650).
   - Expect a 0.3–1 s SCO setup delay. Hold Siri's first audio or accept a clipped start [INFERRED].
3. **Settings to avoid.** Never use "Call audio plays as: Media" (§1.4). Never enable DiPlay's `callSpeakerphone`.
4. **Helmet buttons.** The intercom's ATA/CHUP/BVRA go to the Poco's HFP gateway (§2.4). To make them act on CarPlay:
   - map them to `CarPlayController.answerCall()` / `endCall()` / `requestSiri()` (CPC:738-753; `CARLINK_NOTES.md` §2);
   - the voice-assistant press can be caught by an activity in CarLink or HandlebarLink for `android.intent.action.VOICE_COMMAND`,
     chosen as the default [INFERRED; Android delivers HFP BVRA as a voice-command start];
   - answer/hang-up have no clean public hook without a Telecom call. DiPlay's self-managed call (§1.5) is the only route,
     so this is [UNKNOWN].
5. **Alert when the intercom is missing.** When a session starts and no A2DP/HFP device is connected, show an on-screen
   notice instead of playing on the loudspeaker (see F1).

### 5.3 What HandlebarLink (our separate app) can and cannot do

- **Cannot** choose the call or Siri route. AOSP uses the communication route of the **mode-owner UID**
  (`AudioDeviceBroker.topCommunicationRouteClient`, lines 514-525), and that owner is CarLink [VERIFIED in AOSP].
- **Can:**
  - watch Bluetooth and audio state read-only (`BluetoothProfile.HEADSET`/`A2DP` proxies, `AudioManager.getCommunicationDevice`,
    `registerAudioRecordingCallback`);
  - show a rider warning ("Intercom not connected: sound will play from the phone");
  - keep sending CarPlay media and Siri keys as it does now (`DiPlay-MediaKeys`).
- In Option A it should check that the Poco has **no** A2DP/HFP device connected. In Option B it should check that the
  V6 Pro+ **is** connected.

---

## 6. Failure modes and handling

| ID | Situation | What happens | Handling |
|---|---|---|---|
| F1 | Option B, intercom off or out of range | CarPlay sound plays from the Poco loudspeaker (seen live, §3); Siri and calls use the Poco mic | Fork/HandlebarLink warning at session start and on HFP/A2DP disconnect; optionally pause music until it is back |
| F2 | Option A, intercom off or out of range | iOS falls back to the iPhone speaker or earpiece in your pocket; inaudible on the move, and the caller hears the pocket | Turn the intercom on before connecting CarPlay; check the iPhone's audio route on the CarPlay Now Playing screen |
| F3 | Intercom bonded to **both** phones | Two audio gateways race. Calls may land on the iPhone's HFP or CarPlay's; the intercom may auto-connect to the Poco first and occupy its phone slot; the helmet buttons act on the wrong phone | Bond it to exactly one phone (Option A: iPhone only; Option B: Poco only, and forget it on the iPhone) |
| F4 | Option B, intercom still paired to the iPhone | The iPhone may route a call to the helmet over HFP directly while CarLink also opens a call stream (DiPlay #987 pattern, `CallMicrophoneWatch.kt:11-16`) | Forget it on the iPhone |
| F5 | Auto-answer (V6 Pro answers after 5 s) | In Option A, calls auto-answer. That is wanted on the move, but note it | Reject with the Phone button within 5 s if needed |
| F6 | Intercom-to-intercom conversation active (pillion or group) | Phone audio may be mixed or blocked depending on the firmware [UNKNOWN] | Test §7.2 step 8 |
| F7 | Option A, "Through the car's Bluetooth" not applied (it applies at reconnect) | CarPlay audio still goes to the Poco | Disconnect and reconnect CarPlay after changing it; check the log line `Car Bluetooth audio: on; CarPlay audio not offered` |
| F8 | Option B, SCO setup fails (HFP up but `setCommunicationDevice` returns false, or SCO is refused) | Call goes to the loudspeaker | Retry once after 500 ms, then show a warning; log `communicationDevice` |
| F9 | HFP up but A2DP not (or the reverse) after reconnecting | One kind of sound is missing | Detect partial profiles; toggling the intercom off and on reconnects both |

---

## 7. Test plan

Log commands (read-only), run from the Mac with the Poco on USB:

```
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB logcat -c   # optional: clears the log buffer only; settings untouched
$ADB logcat -v time -s xcertplay-usb:I DiPlay-AudioStats:I DiPlay-AudioFocus:I DiPlay-PhoneAudio:I CarLink:I | \
  grep -E "Car Bluetooth audio|audio route|routeType|Microphone: start|mode entered|mode restored|earpiece|CarPlay call"
$ADB shell dumpsys audio | grep -E "Active communication device|mScoAudioState|Requested mode|Actual mode"
$ADB shell dumpsys bluetooth_manager | grep -A4 -E "^  A2DP:|^  HFP:"
```

Route numbers in the logs:
- output: 1 = earpiece, 2 = loudspeaker, 7 = Bluetooth SCO, 8 = Bluetooth A2DP;
- `routedDeviceType` for the mic: 15 = built-in mic, 7 = Bluetooth SCO.

### 7.1 Bench test for Option A (parked, about 15 min; do this first)

1. Forget V6 Pro+ on the Poco. Pair it from the iPhone. Set CarLink Sound = "Through the car's Bluetooth".
2. Start CarPlay.
   - Expect the log line `Car Bluetooth audio: on; CarPlay audio not offered`.
   - Expect `Car Bluetooth audio: … A2DP connected=[]`, `HFP connected=[]` (the Poco has no audio device).
3. Music: press play in CarPlay.
   - It should play in the helmet.
   - There should be **no** `audio route type=103` line on the Poco.
   - HandlebarLink next/previous should still work.
4. Directions: start a route in Maps or Waze. The prompt plays in the helmet and music ducks.
5. Siri, three ways: the CarPlay Siri button, HandlebarLink's Siri key, and the helmet Phone long-press.
   - Siri should hear the helmet mic: speak with the phone covered or held away.
   - There should be no `Microphone: start` line on the Poco.
6. Incoming call (call the iPhone from another phone):
   - it rings in the helmet;
   - it auto-answers after 5 s or on a Phone-button press;
   - both directions are clear;
   - hang up from the helmet;
   - the CarPlay call screen updates.
7. Outgoing call from CarPlay (Phone app). Same checks.
8. Turn the intercom off mid-music. Note where sound goes (expect the iPhone speaker). Turn it on again and check that it
   resumes in the helmet.
- **Pass:** steps 3–7 all in the helmet with the helmet mic. Then adopt Option A.
- **Fail:** CarPlay refuses to start, there is no sound, or calls/Siri pick the iPhone's speaker or mic. Record which
  step, and go to 7.3 (Option B, needs the fork).

### 7.2 On-bike test (Option A, helmet on, engine running, then a short ride)

1. Order: intercom on → iPhone in pocket → CarLink in front → CarPlay connects.
2. Music at riding speed (40 / 80 km/h): volume and dropouts.
3. Turn-by-turn prompt over music.
4. "Hey Siri" (or the helmet button) at speed: "Call <test contact>". Check that the other person understands you (wind noise).
5. Incoming call while navigating: prompts keep working, the call is clear, music resumes after it.
6. Ride out of intercom range of the phone? Not applicable; the phone stays in your pocket. Instead stop, walk 15 m away
   from the bike, and check that CarPlay holds and the helmet keeps the iPhone.
7. Restart the iPhone's Bluetooth link: switch the intercom off and on. Confirm A2DP and HFP both come back.
8. If you ride with a pillion or group: start an intercom chat, then play music and receive a call, and note how they mix
   (F6).

### 7.3 Option B check (only if 7.1 fails, before writing fork code)

1. Re-pair the intercom to the Poco only. Set CarLink Sound = "Through CarLink".
2. Place a call. Capture the `mode entered communication`, `audio stats … channel=CALL routeType=`,
   `call routed to the earpiece; loudspeaker=` and `Microphone: start type=telephony … routedDeviceType=` lines.
   - Expected today: routeType 1 then 2, mic 15. That confirms §1.4.
3. After the fork (§5.2), expect:
   - `routeType=7` and mic `routedDeviceType=7`;
   - `Active communication device … bt_sco … V6 Pro+` during the call;
   - `mScoAudioState` active.
   Repeat for Siri.

---

## 8. Open questions

- **[UNKNOWN]** iOS behaviour with a no-audio CarPlay accessory plus an HFP headset paired to the iPhone (§2.2). Settled
  by test §7.1.
- **[UNKNOWN]** V6 Pro+ firmware specifics:
  - two-phone support;
  - whether phone audio mixes with intercom conversations;
  - wideband speech (mSBC).
- **[UNKNOWN]** Whether the CarPlay ringtone uses the `alert` or the `default` stream in normal CarLink mode. Irrelevant
  in Option A.
- **[INFERRED, unconfirmed by a call log]** The earpiece-then-loudspeaker chain for CarPlay calls on the Poco. Capture one
  call with the commands in §7 to close it.

## 9. Design for our own receiver

Context: we will build our own CarPlay receiver and use CarLink/DiPlay **only as reference**. This section says how that
app should handle audio so that every sound and the microphone reach the helmet intercom deterministically. Line
references point at DiPlay code to copy or avoid. Nothing here has been built or tested.

### 9.1 Two routing modes, chosen once per session before `/info`

The CarPlay audio capability is fixed by the `/info` features and `audioFormats` the receiver declares (INFO:28-55). So the
mode cannot change mid-session without a reconnect. Decide it in a pre-flight step before advertising or answering
`/info`:

| Mode | When | What `/info` declares | Who routes |
|---|---|---|---|
| **P: phone-routed** (= Option A) | The intercom is bonded/connected to the **iPhone**, and not to the receiver | No audio feature bits, no `audioFormats`/`audioLatencies`, no microphone (copy DiPlay's `disableAudioOutput`, INFO:17-19, 28-29, 51-55) | iOS, natively over HFP/A2DP to the helmet; the receiver plays nothing |
| **R: receiver-routed** (= Option B) | The intercom's HFP **and** A2DP are connected to the receiver (Poco) | Full audio and microphone formats (§9.2) | Our app: A2DP for media, prompts and Siri playback when idle; SCO for calls and Siri capture |

Pre-flight rules:
- **Mode R is allowed only if** `BluetoothProfile.HEADSET` and `A2DP` proxies both list the configured intercom address.
- **If neither mode's condition holds**, start CarPlay video-only in mode P and show "Intercom not connected". Never fall
  back silently to the receiver's loudspeaker.
- **Show the chosen mode in the UI and the log.** The "it came from somewhere" problem is mostly a visibility problem.
- **Default:** mode P if §7.1 passes; it needs the least code and the helmet buttons work natively. Mode R gives us level
  control, ducking and the option to mix receiver alerts (bike warnings) into the helmet. Build P first. R can come later
  behind the same pre-flight.

### 9.2 Audio formats to declare (mode R)

Copy DiPlay's declaration (INFO:120-175), with these points:
- **Main audio, type 100:** `media`, `default`, `alert`, `telephony` (with input), `speechRecognition` (with input).
- **Alt audio, type 101:** `default` (directions).
- **Main high audio, type 102:** `media` AAC.
- **Buffered music stream:** only if we implement it (iOS 27 used AAC-LC on it, INFO:170-173).
- **Microphone input:**
  - offer PCM mono at 16 kHz (the HFP wideband rate; 8 kHz for CVSD) for `telephony` and `speechRecognition`, plus Opus;
  - copy the "call at call rate" idea: telephony in and out at the vocoder rates, because the iPhone uses one format for
    both directions (INFO:150-159);
  - resample from whatever `AudioRecord` delivers on SCO.

### 9.3 Playback: one `AudioTrack` per CarPlay stream (copy DiPlay's structure, AMS:2079-2160)

| CarPlay stream | Mode R, no SCO up | Mode R, SCO up (call or Siri) | Focus |
|---|---|---|---|
| `media` (type 100/102/103) | `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC` → A2DP | Paused: the iPhone stops music during calls and Siri. If anything arrives, let Android remap it onto the phone route (Engine.cpp `remapStrategyFromContext`) | `AUDIOFOCUS_GAIN` while playing, so receiver apps stay quiet |
| `default` / `alert` (directions, chimes; incoming-call ring **[UNKNOWN]** which stream) | `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` / SPEECH → A2DP | Remapped to SCO automatically (media strategy → phone in a call) [INFERRED] | None of its own. Duck media locally (§9.4) |
| `speechRecognition` (Siri voice) | — (Siri always brings up SCO in mode R, §9.6) | **`USAGE_VOICE_COMMUNICATION`** / SPEECH. Not `USAGE_ASSISTANT`: A2DP is suspended while SCO is up, and the assistant strategy is not remapped onto the phone route, so it could land on the loudspeaker [INFERRED, Engine.cpp] | `GAIN_TRANSIENT_EXCLUSIVE` |
| `telephony` | — | `USAGE_VOICE_COMMUNICATION` / SPEECH on the Telecom-routed call (§9.5) | Telecom handles it for the self-managed call |

Copy from DiPlay:
- **Per-stream tracks.** Independent usages, so streams route independently.
- **Route listeners on tracks and a periodic `routedDevice` log.** DiPlay logs `routeType=` on each stats line
  (AMS:2685-2700); that was how §3 could be verified. Log the same for every track and recorder.
- **Restore `MODE_NORMAL`, not the saved previous mode** (AMS:628-650, comment #987).
- **Close tracks when the sink closes.** No reacquiring focus afterwards (AMS:658-683).

Avoid from DiPlay:
- **Hard-coded earpiece → loudspeaker fallback** (AMS:617-627). In our app, any route other than the intercom during a
  mode-R call is an **error to report**, not something to fix silently.
- **"Calls as media"** (AMS:2240). On a phone it can still end up on the earpiece (§1.4).
- **Usage-only routing with no explicit device.** Every voice path must name its device (§9.5, §9.6).

### 9.4 Ducking and focus (mode R)

- **Directions over music.** The iPhone sends `duckAudio` / `unduckAudio` around each prompt (`AirPlaySession.kt:65-66,
  790-791`). By default DiPlay plays the prompt over full-volume music; its focus-based ducking is opt-in
  (`NavigationPromptFocus.kt:18-25`).
  - In a helmet at speed, duck it ourselves: ramp the media track's `setVolume` to about 0.2 (−14 dB, DiPlay's
    `DUCKED_VOLUME`, AMS:162) over 100–200 ms on `duckAudio`, and back on `unduckAudio`.
  - Copy DiPlay's safety: undo the duck if the session ends mid-prompt (`CarPlayMediaEngine.kt:552-553`). Add a 10 s
    watchdog.
- **Receiver-side focus.**
  - Hold `AUDIOFOCUS_GAIN` for media.
  - On `AUDIOFOCUS_LOSS_TRANSIENT`, mute media (DiPlay does this, AMS:76-83).
  - Never let another app's focus loss stop a call.
  - The Poco has no music of its own and no internet, so focus losses come only from system sounds. Consider muting the
    Poco's notification sounds during a session.
- **Volume.** A2DP absolute volume is unsupported by this intercom (`mAvrcpAbsVolSupported: false`, §3). The helmet's +/−
  changes the Poco's stream volume. Keep CarPlay content at full scale and let the rider set the volume on the helmet.

### 9.5 Calls (mode R): model each CarPlay call as a self-managed Telecom call

Why: Android routes a self-managed call's audio to the connected HFP device itself. The AOSP Bluetooth app's
`BluetoothInCallService` declares `android.telecom.INCLUDE_SELF_MANAGED_CALLS=true` (packages/modules/Bluetooth
`android/app/AndroidManifest.xml:142-153`) [VERIFIED in AOSP]. So the helmet sees the call over HFP, and its
answer/hang-up commands come back to **our** `Connection` [INFERRED; verify on the Poco ROM]. DiPlay already proves that
placing such a call works (`telecom/TelecomPhoneAudioBackend.kt:33-138`), though for a different purpose: BYD
echo-cancel state.

Flow:
1. **Call state.**
   - Subscribe to iAP2 `StartCallStateUpdates` (0x4154) and parse `CallStateUpdate` 0x4155, copying DiPlay's
     `CarPlayCallState` (`hud/BydCarPlayCall.kt:22-60`). Status values: 2 ringing, 1/3 dialing, 4 active, 5 held,
     0/6 ended.
   - Also watch AirPlay `modesChanged` app states (phone call app state), as a second signal.
2. **Ringing** → `TelecomManager.addNewIncomingCall` on our self-managed `PhoneAccount`, then `Connection.setRinging()`.
   - Telecom then brings up HFP call indicators.
   - The intercom's 5 s auto-answer (§2.4) sends ATA → `Connection.onAnswer()` → our code sends the CarPlay telephony
     HID **Hook Switch** (DiPlay: `CarPlayController.answerCall()`, CPC:750).
   - Dialing (outgoing from CarPlay) → `placeCall` with `EXTRA_PHONE_ACCOUNT_HANDLE` → `setDialing()`, then `setActive()`
     when status 4 arrives.
3. **Audio.** When the iPhone opens the `telephony` stream and microphone:
   - play on `USAGE_VOICE_COMMUNICATION`;
   - record on `VOICE_COMMUNICATION`;
   - request the Bluetooth endpoint: `Connection.requestCallEndpointChange` (API 34+) with the intercom's
     `CallEndpoint.TYPE_BLUETOOTH`, or the older `setAudioRoute(ROUTE_BLUETOOTH)`;
   - Telecom owns the audio mode for the call, so **do not** call `setMode` or `setCommunicationDevice` yourself (the
     mode-owner rule, §2.3);
   - after 1 s, check: track `routedDevice.type == TYPE_BLUETOOTH_SCO (7)` and recorder `routedDevice.type == 7`.
     Otherwise log it, alert the rider, and retry the endpoint once.
4. **Helmet hang-up** (CHUP) → `onDisconnect()` → CarPlay HID **Drop** (`endCall()`, CPC:753).
   **iPhone-side end** (status 0/6) → `setDisconnected` + `destroy()`.
   - Keep the call open about 2 s after the last call ends, to bridge call waiting. Copy DiPlay's
     `RELEASE_DELAY_MILLIS = 2000` (`PhoneAudioRoute.kt:117`).
5. **If Telecom refuses** (another call on the Poco SIM, or a ROM restriction): fall back to the direct route.
   - `setMode(MODE_IN_COMMUNICATION)` from our process, then `setCommunicationDevice(<TYPE_BLUETOOTH_SCO device of the
     intercom>)` before building the `AudioRecord`;
   - clear it and set `MODE_NORMAL` on the end;
   - in this path the helmet buttons will not answer;
   - copy DiPlay's 5-minute back-off after a refusal (`PhoneAudioRoute.kt:42-44, 118`).
6. **Echo and noise.**
   - The intercom sent `AT+NREC=0` at connect (`EVENT_TYPE_NOISE_REDUCTION valInt=0`, §3). That is the hands-free
     unit saying it does its own echo cancelling and noise reduction [VERIFIED event; INFERRED meaning].
   - Helmet speakers and a boom mic also give little acoustic echo.
   - So on SCO: **no software AEC** (DiPlay's lesson: two cancellers fight, AMS:736-740) and keep the platform's
     `VOICE_COMMUNICATION` processing.
   - Keep DiPlay's Speex AEC with a far-end reference (`EchoReference.kt`, `SpeexEchoCanceller.kt`) **only** for the
     loudspeaker emergency fallback.

### 9.6 Siri (mode R)

1. **Triggers.** All of these call AirPlay `requestSiri`, copying DiPlay's siriAction 2 then 3 (`AirPlaySession.kt:390-393`):
   - a CarPlay button;
   - a HandlebarLink key;
   - the **helmet's voice button**. That arrives as HFP BVRA on the Poco and Android turns it into `ACTION_VOICE_COMMAND`.
     Register an activity for `android.intent.action.VOICE_COMMAND` and have the rider pick it as the default [INFERRED].
2. **Bring up SCO.** When the iPhone opens the `speechRecognition` stream and microphone (or as soon as `requestSiri` is
   sent, to hide the SCO setup delay):
   - `setMode(MODE_IN_COMMUNICATION)`, then `setCommunicationDevice(intercom SCO)`. We are the mode owner here, so our
     choice applies (§2.3);
   - record from `VOICE_RECOGNITION`. With an SCO communication device it captures from the SCO headset mic (Engine.cpp
     `AUDIO_SOURCE_VOICE_RECOGNITION` branch); without one it is the Poco's built-in mic, as seen in §3;
   - play Siri's voice on `USAGE_VOICE_COMMUNICATION` (§9.3).
3. **Hide the gap.** SCO takes about 0.3–1 s to come up [INFERRED]. Buffer the first mic frames until
   `getCommunicationDevice()` reports SCO and the recorder's `routedDevice` is 7. Siri listens for a few seconds, so a
   short pre-buffer is better than sending handlebar wind noise.
4. **Release.** When the microphone stops and no call is active: `clearCommunicationDevice()`, then `MODE_NORMAL`. A2DP
   music then resumes by itself.

### 9.7 Failure handling (mode R)

| Situation | Behaviour |
|---|---|
| Intercom drops mid-session | Media: pause via CarPlay HID and post an alert. Do not play on the loudspeaker. Call: keep it on the iPhone side; Telecom's route falls to the earpiece or speaker, so alert "Intercom lost — call on handlebar phone" and offer "move call to iPhone" (hang up our call; the rider continues on the iPhone) |
| Intercom reconnects | If HFP and A2DP are both back, continue. Re-request the Bluetooth endpoint if a call is active |
| Only one of HFP/A2DP connects | Treat as not connected for mode-R pre-flight. Mid-session, alert |
| Intercom also bonded to the iPhone | Pre-flight warning: two gateways. The rider must keep it on exactly one phone |
| SCO refused | One retry after 500 ms, then alert. Log `mScoAudioState` and `getCommunicationDevice()` |

### 9.8 Diagnostics to build in from day one

- One log line per stream start: stream type, audioType, usage, `routedDevice.type`, sample rate, codec.
- One log line per recorder start: source, `routedDevice.type`. Copy DiPlay's `Microphone: start … routedDeviceType=` lines.
- Mode and communication-device changes (`addOnCommunicationDeviceChangedListener`, `addOnModeChangedListener`), and
  Telecom call state.
- A recording-config watch during calls, flagging a second recorder. Copy CarLink's `CallMicrophoneWatch.kt:18-69`.
- An on-screen "audio route" chip: Helmet (A2DP) / Helmet (call) / **Phone speaker** in red.

## Sources (web)

- AOSP Bluetooth app manifest (`BluetoothInCallService` includes self-managed calls): https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/AndroidManifest.xml

- EJEAS V6 Pro user manual (manuals.plus PDF): https://manuals.plus/ejeas/v6-pro-bluetooth-intercom-headset-manual.pdf
- EJEAS V6 Pro product page: https://www.ejeas.com/products/v6-pro
- V6 PRO+ listing (EJEAS V6 PRO+ naming): https://aio.lv/en/product--ejeas-v6pro--10908413.md
- OUI 00:12:6F → Rayson Technology: https://maclookup.app/macaddress/00126f
- Aoocci, "How CarPlay audio routing works on a motorcycle display": https://aoocci.com/blogs/motorcycle/bluetooth-carplay-routing-c9-pro-max
- Laser Co., Motorbike CarPlay Screen 6" FAQ – Audio setup: https://help.laserco.com.au/support/solutions/articles/51000502274-motorbike-carplay-screen-6-faq-audio-setup
- AOSP AudioDeviceBroker.java (main): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/audio/AudioDeviceBroker.java
- AOSP Engine.cpp (main): https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/audiopolicy/enginedefault/src/Engine.cpp
