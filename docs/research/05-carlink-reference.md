# CarLink source notes for the Poco F1 motorcycle fork

Source: `/Volumes/My files/pocof1/carlink-src`, branch `bike` = upstream tag `v1.0.16`
(commit `0be1cdee`, 2026-11-12). Remotes: `origin` = Devsed-official/carlink-bike, `upstream` =
F-e-n-y-x/CarLink (push disabled). History was unshallowed (1273 commits).
All paths below are relative to the repo root. Line numbers are for this commit.

How claims are marked:
- No mark: read in the code or docs, with a file:line citation.
- **[I]**: my inference, not checked in code or on a device.

Abbreviations:
- `CPHA` = `vendor/diplay/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` (7398 lines)
- `CPC` = `vendor/diplay/shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`
- `CPHI` = `vendor/diplay/common/src/main/java/com/shilapi/xcertplay/CarPlayHostIntegration.kt`
- `core/` = `carlink-core/src/main/java/com/carlink/core/`
- `diplay-common/` = `vendor/diplay/common/src/main/java/com/shilapi/xcertplay/`
- `diplay-airplay/` = `vendor/diplay/shared/src/main/java/com/shilapi/xcertplay/airplay/`

---

## 0. Orientation (what you need before touching anything)

- One APK, two engines. CarPlay = DiPlay (`vendor/diplay`, package `com.shilapi.xcertplay`), runs
  in the **main process**. Android Auto = DiAuto (`vendor/diauto`) runs in the **`:aa` process**.
  For an iPhone-only bike build, everything we touch is in the main process.
- Modules (`settings.gradle.kts`): `:app` (UI, `com.carlink.app.*`), `:carlink-core` (backend
  library, `com.carlink.core.*`), `:carplay-shared` = `vendor/diplay/shared`, `:carplay-common` =
  `vendor/diplay/common`, `:androidauto` = `vendor/diauto/app`, `:androidauto-contract`.
- `:carlink-core` cannot see `:app` (no `ui/`, `switcher/` or the app's `R`). Backend code goes in
  `carlink-core/`, UI and strings in `app/` (`.claude/skills/carlink-dev/SKILL.md`, "Reusable backend").
- Repo rules (`CLAUDE.md`, `SKILL.md`):
  - No settings in `DiPlayActivity` or any engine screen. Settings live in `app/.../ui/settings`
    and reach the engine through `UnifiedSettings` → `CarPlaySettingsPolicy`.
  - English only. New strings go in `app/src/main/res/values/` with the `carlink_` prefix. No translations.
  - Engine code changes only through small `// CarLink` hooks. Each needs a row in
    `docs/VENDOR_CHANGES.md` ("every edit needs a row, every row an edit"). Prefer a host hook over
    editing engine logic. Audit: `git diff --stat 615b32ce0: HEAD:vendor/diplay` and `grep -rn CarLink vendor/`.
  - `vendor/diplay/CLAUDE.md` / `AGENTS.md` are upstream DiPlay's rules and **do not apply** in CarLink.
  - Never commit the CarPlay auth files (`*.pk8`, `*.p7b` are in `.gitignore:34-35`).
  - `git config core.fileMode false` (file modes are noise on this drive).
  - The upstream maintainer forbids Claude credit lines in *their* repo's commits. That is their
    rule for their repo; follow whatever the fork owner decides.
- `docs/dissection/DIPLAY.md` was written against DiPlay 0.2.15. Its line numbers are stale (the
  vendored engine is now 0.2.22+ / upstream 615b32ce0), but its findings still hold.

---

## 1. CarPlay UI size on a phone-sized screen

### 1.1 How CarPlay's size is decided (the chain)

iOS lays out CarPlay from the display description the accessory sends in AirPlay `/info`. The
lever DiPlay and CarLink use is the **physical width in mm**. `CarPlaySize.kt:3-6` says: "iOS keeps
controls at a fixed physical size, so the size is expressed as the physical screen width reported
to the iPhone: a wider screen gets smaller controls." So **fewer mm means bigger icons and text.**

The chain, from setting to iPhone:

1. **Driver's choice.** `UnifiedSettings.interfaceSize` (`core/settings/UnifiedSettings.kt:18`,
   enum at `:52` `SMALL, MEDIUM, LARGE, CUSTOM`, default MEDIUM, pref key `interface_size` in
   `carlink_unified`).
2. **mm mapping.** `CarPlaySettingsPolicy.widthMm()` in `core/connection/CarPlaySettings.kt:119-124`:
   SMALL 350, MEDIUM 300 (`MEDIUM_WIDTH_MM`, `:101`), LARGE **220**, CUSTOM null (null = leave
   DiPlay's saved value alone). `map()` puts it into `CarPlayEngineSettings.widthPhysicalMm` (`:144,157`).
3. **Write.** `CarPlaySettingsPolicy.apply()` → `AirPlayPersistence.saveWidthPhysicalMm` (`CarPlaySettings.kt:221`).
   It is called from `Connection.writeSession()` (`core/connection/Connection.kt:226-241`) on the
   `carlink-session` worker thread before every session start.
4. **DiPlay clamp.** `AirPlayPersistence.saveWidthPhysicalMm` / `loadWidthPhysicalMm`
   (`diplay-common/AirPlayPersistence.kt:833-848`) both pass through
   `AirPlayDisplaySettings.sanitizeWidthPhysicalMm`. That snaps to
   **MIN 100, MAX 400, STEP 5 mm** (`diplay-airplay/AirPlayDisplaySettings.kt:20-25,33-39`).
   Default if unset: `CarPlaySize.DEFAULT` = MEDIUM = 300 (`CarPlaySize.kt:8-14`). The class default
   `DEFAULT_WIDTH_PHYSICAL_MM = 200` is only the fallback in `/info` (`:26`, `AirPlayInfoPlist.kt:202-204`).
5. **Host load.** `CPHA.loadPersistedSettings()` reads `widthPhysicalMm` and `physicalSizeBasis`
   (WIDTH by default) and the max detected display (`CPHA:992-997`).
6. **Scale to the window.** `CPHA.resolvePhysicalSize()` (`CPHA:5289-5297`) →
   `AirPlayDisplaySettings.resolvePhysicalSizeMm` (`AirPlayDisplaySettings.kt:44-82`):
   reported width = `widthPhysicalMm × currentWidthPx / maxDetectedWidthPx`. Height comes from the
   aspect ratio. Then it is clamped to 1..2000 mm (`sanitizeReportedPhysicalMm`, `:41-42`), so the
   *reported* value is not bound by 100.
7. **Display config.** `CPHA.createAirPlayConfig(size)` (`CPHA:5011-5223`):
   - pixels = the video window size, made even (`:5014-5018`);
   - `widthPhysicalMm` / `heightPhysicalMm` from step 6 (`:5020-5021`);
   - `fps` (`:5022`);
   - `primaryInputDevice` = 3 (knob) on a TV or a no-touch device, otherwise 1 (touch) (`:5016,5025`);
   - resolution % (`CarPlayDisplayScale.applyPercent`, 30..160 %, `:5033-5034`);
   - UI-scale canvas (`CarPlayUiScale`): **dead**, forced to 100 at load (`CPHA:976-978`:
     "Size is now chosen only through CarPlaySize");
   - safe area from a saved custom rect, corner masks (`:5083-5094`);
   - square "turning" canvas and view areas (`:5124-5160`, see §1.3);
   - `deviceName = CarPlayHostIntegration.accessoryName`, `oemLabel`, icons (`:5191-5212`).
8. **Wire format.** `AirPlayInfoPlist.displayEntry()` (`diplay-airplay/AirPlayInfoPlist.kt:200-240`)
   sends per display: `widthPixels`, `heightPixels`, `widthPhysical`, `heightPhysical`, `maxFPS`
   (snapped to 30-60 step 5, `AirPlayDisplaySettings.kt:15-18`), `features` (default
   HIGH_FIDELITY_TOUCH|KNOBS = 0x08|0x02, `:15-16,221`), `primaryInputDevice`, appearance modes,
   `viewAreas[]` (each with `safeArea` and optional `viewAreaStatusBarEdge` dock edge) and
   `initialViewArea`, plus optional `cornerMasks` and `viewAreaTransitionControl`. `areaDict()` is at
   `:243-290`. Touch, knob, media and telephony HID devices are always declared (`AirPlayInfoPlist.kt:60-62`).
   Display types are in `AirPlayConfig.kt` (`AirPlayDisplayConfig`, `AirPlayViewArea`, `AirPlayInsets`).

### 1.2 Every place that bounds or sets the reported mm

| What | Where | Value / note |
|---|---|---|
| Interface-size presets (Settings › Display, setup, switcher) | `core/connection/CarPlaySettings.kt:119-124` | S 350 / M 300 / L 220 / Custom null. **Main knob to change.** |
| Live-size widths offered to DiPlay | `core/connection/CarPlayAdapter.kt:268` | `InterfaceSize.entries.mapNotNull(widthMm)` = [350, 300, 220]; follows `widthMm` automatically |
| **Advanced "Screen width reported to iPhone"** (`cp_width`) | `core/settings/AdvancedSettings.kt:111-114` | **Hard-coded options 250 / 300 / 350 / 400.** This is the "250-400" range in the UI. It is a fixed list, not a range check. Editing it sets Interface size = CUSTOM (`Overrides.INTERFACE_SIZE`, applied in `app/.../ui/settings/SettingsScreen.kt:180`). |
| DiPlay persistence clamp | `diplay-airplay/AirPlayDisplaySettings.kt:20-25` | MIN 100, MAX 400, STEP 5. **Vendor edit needed to go below 100 mm.** |
| DiPlay preset enum | `diplay-airplay/CarPlaySize.kt:8-11` | LARGE 250, MEDIUM 300, SMALL 350. Only used for labels/default, not by CarLink's mapping. |
| DiPlay in-session slider "Physical length" | `CPHA:3232-3249` | 100..400 step 5. Hidden in CarLink (`MenuItem.PHYSICAL_LENGTH` not in `app/.../engine/EngineChrome.kt:112-115`). |
| Reported value clamp | `AirPlayDisplaySettings.kt:27-28,41-42` | 1..2000 mm after window scaling |
| Square canvas mm | `CPHA:5135-5144` | On a turning screen: `liveBaseMm` = max(live widths, chosen) = **350**, reported as both width and height |
| Android Auto equivalent (for reference) | `core/connection/aa/AaSessionSettings.kt:188-192,217-231` | sizePercent S 80 / M 100 / L 135; handheld density from a 250 mm "Medium" |

UI labels for the sizes: `app/.../ui/settings/SettingsSheets.kt:149-159` (preview scales 0.8/1/1.25),
`SettingsModel.kt:239-242`, `app/.../ui/switcher/SwitcherPanel.kt:303` (only S/M/L listed),
`app/.../ui/setup/SetupScreen.kt:712-714`. Strings: `app/src/main/res/values/carlink_strings_settings.xml:107-116`.

### 1.3 Plain canvas vs turning square (this decides what the iPhone actually gets)

`CarPlayRotation.enabled` decides it. CarLink sets it from `HeadUnitDetector.handheld()` at install
and before **every** host open (`CarPlayAdapter.kt:272,387`).

`handheld()` (`core/detect/HeadUnitDetector.kt:40-51`) is true when all of these hold:
- the Screen type is not forced;
- there is an accelerometer;
- **auto-rotate is ON** (`Settings.System.ACCELEROMETER_ROTATION == 1`);
- there is telephony or a battery.

The driver's **Settings › General › Screen type** (`UiProfileMode` AUTO/CAR/HANDHELD) overrides this
through `ScreenType.forcedHandheld` (`app/.../ui/UiPreferences.kt:95-100`, applied at app start in
`CarLinkApplication.kt:39`).

**Turning square** (handheld, no split screen, no saved custom safe area; `CPHA:5126-5160`):
- The canvas is a square of side ≤ the long side. `CarPlayRotation.Picture.SMOOTHER` caps it at
  1920 (`diplay-common/CarPlayRotation.kt:18-19,33-80`).
- It declares landscape and portrait view areas, so a turn switches area without reconnecting.
- Reported mm = `liveBaseMm` = **350** (the widest live size) for both sides.
- The chosen size is then **a smaller full-screen view area** at the top-left. `CarPlayViewAreas.build`
  with `sizeWidthsMm`/`sessionWidthMm` adds one area per smaller width, each `1/factor` the size,
  where factor = sessionMm / widthMm (`diplay-common/CarPlayViewAreas.kt:174-232`, especially `:201-207`).
  The host starts on that area (`sizeVariant` + `use`, `CPHA:5160`).
- DiPlay's `contentRect` scales the area up to fill the window. CarPlay therefore lays out fewer
  points and looks bigger, **at reduced resolution**.
- Size changes from the switcher are live: `SwitcherController.setInterfaceSize` →
  `CarPlayHostIntegration.setInterfaceSizeLive(mm)` (`app/.../switcher/SwitcherController.kt:384-395`)
  → `CPHA.applyInterfaceSize` (`CPHA:6071-6089`) → `controller.showViewArea(index)` (`CPC:661-676`).
  It returns false (and CarLink reconnects) for a width above the session's, a plain canvas, split
  screen or side panel, the menu open, or no live host (`CPHI:225-231`).

**Plain canvas** (head unit, Screen type "Car stereo", auto-rotate off, or a saved custom safe area):
- The window's real pixels go out (~2246×1080 minus bars/cutout [I]).
- The chosen mm is reported directly (scaled per step 6).
- `CarPlayViewAreas.build` returns null, so there are no extra areas (`CarPlayViewAreas.kt:190-192`).
- A size change from the switcher **reconnects** (`setInterfaceSizeLive` false → `Connection.retry()`).
- A saved custom safe area (CarPlay menu › Safe area) also disables the square and the live sizes
  (`CPHA:5124-5132`; VENDOR_CHANGES row "the `square` canvas also needs no saved custom safe area").

Consequences for the Poco F1 (landscape, about 141×68 mm, 2246×1080):
- **[I]** With auto-rotate on, CarPlay connects as a 1920² square at 350 mm and shows Large as a
  ≈1920×923 / 1.59 ≈ 1206×580 px area, upscaled. With an even smaller target such as 140 mm, the
  factor is 2.5 and the area is about 768×369 px. That is below CarPlay's usual 800×480 minimum
  (minimum not checked in this code) and blurry.
- For a fixed bike mount it is cleaner to force **Screen type = Car stereo** or keep auto-rotate
  off, so the plain canvas sends full resolution and the real chosen mm. Side effects of "Car stereo":
  - `looksLikeCarDisplay` = true, so CarLink's own UI uses the CAR profile (76 dp targets, 24 sp text);
  - `WirelessPolicy` adds the car-hotspot path first, but only if hotspot details are known
    (`core/connection/WirelessPolicy.kt:50`, `DeviceEnvironment.kt:70`);
  - Android Auto's turning logic is off (`core/connection/aa/AaEngineHooks.kt:99-101`).
- 141 mm is above DiPlay's 100 mm floor. Reporting the real width, or e.g. 150-180 mm, needs **no
  vendor edit**. Only <100 mm needs `MIN_WIDTH_PHYSICAL_MM` changed.

### 1.4 Other inputs that affect CarPlay's layout

- **Resolution** (`displayScalePercent`): Picture AUTOMATIC/BEST/BALANCED = 100 %, LIGHT = 75 %
  (`CarPlaySettings.kt:137-143`); Advanced `cp_scale` 50..150 % (`AdvancedSettings.kt:103-106`);
  DiPlay range 30..160 % (`CarPlayDisplayScale.kt:12-13`); fallback to 100 % if the decoder cannot
  (`CPHA:5063-5072`). **[I]** With fixed mm this changes sharpness, not control size. Upstream's
  disabling of `CarPlayUiScale` (`CPHA:976-978`) suggests pixel-only scaling is not a reliable size lever.
- **FPS**: Picture presets 30/60, Advanced `cp_fps` (`AdvancedSettings.kt:107-110`); sanitized 30..60.
- **Safe area / insets**: `AirPlaySafeArea.toInsets` with a per-window-size saved rect
  (`AirPlayPersistence.loadSafeAreaRect`, `AirPlayPersistence.kt:1489-1495`), `drawUIOutsideSafeArea`,
  "disable safe area" with corner masks (`CPHA:5082-5094`). The editor is reachable in CarLink
  through the CarPlay session menu (`MenuItem.SAFE_AREA` is kept, `EngineChrome.kt:112-115`).
  Hide status/navigation bar: Advanced `cp_hide_top`/`cp_hide_bottom` (`AdvancedSettings.kt:120-125`).
  **[I]** The Poco F1 notch in landscape: the waiting view pads for `displayCutout` (`CPHA:2238-2243`),
  but I found no explicit `layoutInDisplayCutoutMode` for the video. Check on the device whether the
  CarPlay window excludes the notch strip.
- **Dock position**: Advanced `cp_dock` Automatic/Driver side/Bottom (`AdvancedSettings.kt:115-119`).
  A fixed dock makes DiPlay declare view areas, which also enables live sizes on a plain canvas
  (`CarPlayViewAreas.kt:190-195`; VENDOR_CHANGES row on `CarPlayViewAreas.kt`).
- **Driving side**: `rightHandDrive` (`CarPlaySettings.kt:174,227`).
- **primaryInputDevice**: touch (1) unless TV / no touchscreen (`diplay-common/AndroidTvInputMode.kt:27-31`).
- **Night/appearance**: `nightModeKey`, `appearanceKey` (`CarPlaySettings.kt:158-173`).
- **"Larger text"**: nothing in the accessory code sets text size. A text-size option, if any, is
  on the iPhone side. **[I]** The accessory controls size only through mm, pixels and view areas.

### 1.5 Exact edit points for a "bigger CarPlay" fork (no vendor edit needed for ≥100 mm)

1. `core/connection/CarPlaySettings.kt:119-124`: change LARGE (220) and/or add values. Adding an
   `InterfaceSize.EXTRA_LARGE` means also editing:
   - `UnifiedSettings.kt:52`;
   - `AaSessionSettings.sizePercent` (`aa/AaSessionSettings.kt:189-193`, exhaustive `when`);
   - `SettingsModel.kt:239-242`;
   - `SettingsSheets.kt:149-159`;
   - `SwitcherPanel.kt:303`;
   - `SetupScreen.kt:712-714`;
   - strings.
   `liveSizeWidthsMm` follows `widthMm` automatically (`CarPlayAdapter.kt:268`).
2. `core/settings/AdvancedSettings.kt:113`: extend the `cp_width` option list (e.g. 100, 120, 140,
   160, 180, 200, 250 ...). Values must be multiples of 5 within 100..400, or DiPlay snaps them.
3. Only for <100 mm: `vendor/diplay/shared/.../airplay/AirPlayDisplaySettings.kt:20` (`// CarLink` +
   VENDOR_CHANGES row). The slider at `CPHA:3232-3249` follows automatically.
4. Optionally default Screen type to Car stereo on this device (`UiPreferences.uiProfileMode`) for
   a sharp plain canvas.

---

## 2. BLE handlebar controller → live CarPlay session

### 2.1 What exists for sending input to the iPhone

All of these are on `CarPlayController` (public class, `CPC:188`). Each returns false when there is
no active AirPlay session (`activeSession` null or `closed`), checks `closed`/`activeSession` itself
and posts the HID report to its own `touchExecutor`. **They are safe to call from any thread
(including BLE binder callbacks) and do not block.**

| Call | Line | Effect |
|---|---|---|
| `sendMediaButton(index)` | `CPC:766-775` | Media HID press+release. Indices in `diplay-airplay/CarPlayMediaButton.kt:13-17`: PLAY 1, PAUSE 2, PLAY_PAUSE 3, NEXT 4, PREVIOUS 5 |
| `requestSiri()` | `CPC:738-747` | AirPlay `requestSiri` siriAction 2 then 3 (`AirPlaySession.kt:390-393`) |
| `sendKnob(AirPlayKnobState, momentary=true)` | `CPC:678-694` | Knob HID: `select`, `home`, `back` buttons and `x`, `y`, `wheel` deltas (`AirPlayHid.kt:12-19,57-68`). `momentary` also sends a release. |
| `answerCall()` / `endCall()` | `CPC:750,753` | Telephony HID Hook Switch / Drop |
| `showViewArea(i)`, `zoomDashboardMap(in)` | `CPC:661,724` | View-area switch; cluster map zoom (not relevant) |
| `nowPlayingSnapshot()` | `CPC:289` | Now playing, including `playing` |

How existing sources get the controller:

- `CarPlayMediaKeys` (`diplay-common/CarPlayMediaKeys.kt`, `internal object`):
  - `attach(context, controller)` (`:74-93`) is called by the host when it builds a controller
    (`CPHA:5818`); `detach(old)` at `CPHA:6394,6615`.
  - It owns the Android `MediaSession` (tag from `CarPlayHostIntegration.mediaSessionTag`, `:325`) and audio focus.
  - `send(index, source)` (`:421-432`) first lets `CarPlayVideo` take the key (car video player),
    then calls `controller.sendMediaButton` and logs to `WheelKeyLog`.
  - The external-source entry points are `onExternalMediaKey(index)` (`:440`) and `onExternalSiriKey()` (`:443-446`).
- `GeelyWheelKeys` (`diplay-common/GeelyWheelKeys.kt`, `internal object`) is the template for an
  external key source:
  - started and stopped by `CarPlayMediaKeys.attach/detach` (`:82,104`);
  - binds a vendor service, receives raw codes, maps them to `CarPlayMediaButton.*` and calls
    `CarPlayMediaKeys.onExternalMediaKey` (`:141-149`);
  - long press → `CarPlayMediaKeys.onExternalSiriKey()` (`:91-97`);
  - no-op off Geely (`GeelyHeadUnit.detected`, `:51`).
- `CarPlayBackgroundSession.snapshot()?.controller` (`CPHA:7318-7397`, `internal object`) is the
  process-wide handle used by `WheelKeyService` (`WheelKeyService.kt:49-80`), `CarPlayCallKeys`
  (`:94`) and CarLink's `CarPlayHostIntegration.pressPlay()` / `phonePlaying()` (`CPHI:232-237`).
- `CarPlayRemoteKeys` (`diplay-common/CarPlayRemoteKeys.kt`):
  - maps D-pad / system-navigation keys to knob wheel ±1 (with held-repeat every 3rd repeat), and
    CENTER/ENTER/BUTTON_A → select, BACK/BUTTON_B → back, with real down/up (`:63-143`);
  - used in `CPHA.dispatchKeyEvent` only when the device is knob-primary or
    `AirPlayPersistence.loadExternalController` is on and the event comes from a real input device
    (`CPHA:1759-1770`, `CarPlayRemoteKeys.kt:60-61`).
  - The **"external controller" pref** (`AirPlayPersistence.kt:592-599`, key `external_controller`)
    exists in DiPlay but is **not exposed in CarLink** (no reference in `app/` or `carlink-core/`).
- `CarPlayCallKeys` (`diplay-common/CarPlayCallKeys.kt`) answers/ends calls from wheel keys, gated by
  `BydOutputSettings.carPlayCallControls`. CarLink turns that on only when a call key is learned
  (`CarPlaySettings.kt:201,247-249`).
- `WheelKeyService` (`diplay-common/WheelKeyService.kt`) is an AccessibilityService (key filter)
  for BYD wheel zoom/joystick/Siri. It is still in the merged manifest, but the user must enable it,
  and it is BYD-oriented.
- CarLink's own **Steering-wheel buttons** (`core/settings/WheelKeys.kt`, UI
  `app/.../ui/settings/WheelKeysSheet.kt`, row `SettingsPages.kt:276-281`):
  - learned Android key codes become `CarPlayHostIntegration.keyRemap` (`CarPlaySettings.kt:199,241`);
  - `CPHA.dispatchKeyEvent` applies it as its first line (`CPHA:1723-1724`, `CPHI:29-42`);
  - actions VOICE / ANSWER / HANG_UP / PLAY_PAUSE / NEXT / PREVIOUS (`WheelKeys.kt:11-18`);
  - Back, Home, Power and volume cannot be learned (`:29-34`);
  - **works only while the CarPlay window has key focus** (USER_GUIDE "Steering-wheel buttons").

### 2.2 Two ways to integrate the controller

**A. The controller is a standard BLE HID (keyboard/consumer/gamepad) paired in Android Bluetooth settings.**
- **No engine code.** Android sends its KeyEvents to the focused window = `CarPlayHostActivity`.
  - Media keys reach CarPlay via the window (`CarPlayMediaButton.forCarPlayWindow`, `CPHA:1857`)
    and the MediaSession (`CarPlayMediaKeys`; works while DiPlay holds focus, also in the background).
  - VOICE_ASSIST opens Siri.
  - Learned buttons are remapped via `keyRemap`.
  - D-pad/Enter/Back drive the knob only if `external_controller` is on. To use that, expose it as
    a new `AdvancedSetting` in `core/settings/AdvancedSettings.kt` with `carPlayToggle(
    AirPlayPersistence::loadExternalController, AirPlayPersistence::saveExternalController)`. That is
    a carlink-core change, not a vendor edit.
- Limits:
  - **[I]** A BLE HID keyboard can make Android hide the soft keyboard or change input config.
  - A Back key that is not a knob key will leave CarPlay.
  - Keys do nothing when CarPlay is not in front, except media keys through the MediaSession.

**B. The controller uses a custom GATT protocol (most handlebar remotes) → CarLink's own BLE client.**
This is the cleanest hook point:

1. **One small vendor hook**, following the existing `pressPlay()` pattern: add `// CarLink` public
   wrappers to `CarPlayHostIntegration` (`CPHI:232-237`), because `CarPlayMediaKeys` and
   `CarPlayBackgroundSession` are `internal` to `:carplay-common` and cannot be called from
   `:carlink-core`. Example shape:
   - `fun remoteMediaButton(index: Int): Boolean`, routed via `CarPlayMediaKeys.onExternalMediaKey`
     so `CarPlayVideo` and `WheelKeyLog` still apply. Note that `onExternalMediaKey` currently returns
     Unit and logs source "geely-wheel", so a source parameter could be added.
   - `fun remoteSiri()`, `fun remoteKnob(state: AirPlayKnobState)`, `fun remoteAnswerCall()`,
     `fun remoteEndCall()`, each `CarPlayBackgroundSession.snapshot()?.controller?.xxx() == true`.
   - Add one row to `docs/VENDOR_CHANGES.md` (DiPlay table).
   - **[I]** Alternative with zero vendor edits: grab the controller from `CarPlayController`'s
     public static hooks. But there is no public "current controller" accessor today, so a wrapper is needed.
2. **The BLE client lives in `:carlink-core`** (backend; it must not depend on `app/`), main
   process, e.g. `core/remote/HandlebarRemote.kt`. Install it from `CarLinkCore.install` (main-process
   branch, `core/connection/CarLinkCore.kt:98-114`) or from `CarPlayAdapter.install` (`CarPlayAdapter.kt:234-276`).
3. **Session start/stop signals** (pick one):
   - `HostStates.add { state -> ... }` (`CarPlayAdapter.kt:307-315`, public object). It fans out
     DiPlay's `HostState` on the main thread: `FirstFrame` = picture up (`CPHI:145-146`),
     `Closed` = nothing left (`:148-152`), `Retrying`, `RetriesStopped`, … (`CPHI:107-153`).
     CarLink itself uses it for resume-music (`CarPlayAdapter.kt:259-263`).
   - `Connection.get(ctx).state: StateFlow<SessionState>` (`core/connection/SessionManager.kt:221-222`):
     `Active(request, path, attempt, phase)` with `phase == Phase.Connected`, and
     `request.engine == Engine.CARPLAY` (`:60-111`). Use it to show controller status on Home.
   - `CarPlayStatusBus.addListener` (`vendor/diplay/shared/.../orchestration/CarPlayStatusBus.kt`):
     fine-grained statuses, but `null` also appears at each internal restart. **Never read
     `current == null` as "no session"** (DIPLAY F13; `CarPlayAdapter.kt:40-41`).
   - "Is a session running at all": `CarPlayHostIntegration.hasSession()` (`CPHI:203`), main thread.
   - Calls during a DiPlay internal reconnect simply return false (no active session). No extra
     guard is needed.
4. **Threading rules:**
   - `CarPlayController.send*/requestSiri/answer/end` work from any thread (they post to
     `touchExecutor`).
   - `CarPlayMediaKeys.send` synchronizes and posts focus work to main.
   - `CarPlayHostIntegration.stopSession`, `retryNow`, `setInterfaceSizeLive` and `HostSessions`
     are **main thread only** (`CPHI:205-231,250`).
   - `SessionManager` methods are main thread (`SKILL.md` "Main thread"). Its StateFlows can be
     read anywhere.
   - `CarPlaySettingsPolicy.apply` runs off main (`CarPlaySettings.kt:211-214`).
5. **Process layout:**
   - The BLE client must be in the **main process**, where the CarPlay controller lives.
   - `:aa` hosts only DiAuto. `CarLinkApplication` runs in both processes; branch on
     `CarLinkCore.isAndroidAutoProcess` (`app/.../CarLinkApplication.kt:12,30-41`; `CarLinkCore.kt:118`).
   - During a session the main process is kept alive by `DiPlaySessionService`
     (FGS type `connectedDevice|microphone|location`, `vendor/diplay/common/src/main/AndroidManifest.xml:71-72`).
   - **[I]** Outside a session nothing keeps the BLE link alive. Connect on `FirstFrame` (or on
     `SessionState.Active`) and keep it until `Closed`. Alternatively add your own
     connectedDevice FGS (Android 14+ requires a type and permission).
   - Permissions `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` are already merged (DiAuto manifest
     `vendor/diauto/app/src/main/AndroidManifest.xml:69-70`, without `neverForLocation`) and requested
     by setup (`app/.../ui/setup/SetupModel.kt:74`, `DevicePreflight.kt:111`).
   - **[I]** BLE scanning without `neverForLocation` may need location on for scan results. A
     direct `connectGatt` to a known address avoids scanning.
6. **Android Auto** is not needed for an iPhone. If ever wanted, keys must cross to `:aa`
   (`AaCommandReceiver` broadcasts), or be learned keys through DiAuto's `key-codes`.

### 2.3 How to add the controller's setting (repo conventions)

- **Unified (driver-facing) setting** (an essential, like wheel keys):
  - add a field to `UnifiedSettings` (`core/settings/UnifiedSettings.kt:12-44`);
  - add load/save keys in `UnifiedSettingsStore` (`:62-117`, prefs `carlink_unified`);
  - add a backup field in `app/.../ui/settings/SettingsBackup.kt:41-70` (SettingsBackupTest round-trips them);
  - add a row in `SettingsPages.kt` (e.g. in `CarEssentials`, `:262-283`, next to wheel keys);
  - add an `EssentialSheet` entry (`SettingsModel.kt:164-168`) and a sheet in `SettingsSheets.kt:~291`;
  - write through `CarLinkViewModel.updateSettings` (`app/.../ui/CarLinkViewModel.kt:483-492`),
    which saves and calls `session.onSettingsChanged()`;
  - to reach DiPlay at session start, extend `CarPlayEngineSettings` + `map()` + `apply()` in `CarPlaySettings.kt`.
- **CarLink-only preference** (not an engine value, e.g. "paired controller address"):
  `CarLinkPreferences` (`core/settings/CarLinkPreferences.kt`, prefs `carlink`), plus a `CARLINK`
  backup field.
- **Advanced / Lab engine setting**: an entry in `AdvancedSettings.all` (`core/settings/AdvancedSettings.kt:99-...`)
  with category, tier, `Applies.NOW|NEXT_CONNECTION` and control. Its title and description are
  literal English strings in that file (not resources).
- Strings: `app/src/main/res/values/carlink_strings_*.xml`, prefix `carlink_`, English only.
  `dev.sh test` runs `scripts/resource_collisions.py --check`.
- A learn-a-button UI already exists to copy: `WheelKeysSheet.kt` (15 s learn, Cancel/Back).

---

## 3. Building a debug APK

### 3.1 Toolchain (read from the build files)

| Item | Value | Where |
|---|---|---|
| Gradle | 9.8.2 | `gradle/wrapper/gradle-wrapper.properties` |
| Gradle **daemon** JVM | **toolchain 25**, auto-provisioned via foojay | `gradle/gradle-daemon-jvm.properties:12`, `settings.gradle.kts:14-16` |
| AGP | 9.5.0 | `gradle/libs.versions.toml:2` |
| Kotlin | 2.4.21 (compose plugin) | `libs.versions.toml:6` |
| compileSdk / targetSdk / minSdk | 37 / 37 / 25 (DiAuto compileSdk 36, contract 34) | `app/build.gradle.kts:56-65`, `vendor/diauto/app/build.gradle.kts:16` |
| Java source/target | 11 | `app/build.gradle.kts:144-147` |
| NDK | DiPlay **28.2.13676358**, DiAuto **29.0.14206865** | `vendor/diplay/shared/build.gradle:25`, `vendor/diauto/app/build.gradle.kts:17` |
| CMake | 3.22.1 | `vendor/diauto/app/build.gradle.kts:28`, `docs/BUILDING.md` |
| Build-tools | 35 and 36 (docs) | `docs/BUILDING.md` "Requirements" |

On this Mac (checked):
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21` (21.0.13) only launches the wrapper. The daemon needs
  JDK 25. **One is already provisioned** at `~/.gradle/jdks/eclipse_adoptium-25-aarch64-os_x.2`.
  Homebrew also has `openjdk` / `openjdk@27` (27.0.1).
- SDK `~/Library/Android/sdk`: platforms `android-35`, `android-37.0`; build-tools 35/36/37;
  NDK **29.0.14206865 only**; **no CMake**; licenses dir present.
  **Missing: NDK 28.2.13676358, CMake 3.22.1, platform android-36.**
  **[I]** AGP normally auto-installs missing SDK packages when the license is accepted. Otherwise:
  `sdkmanager "ndk;28.2.13676358" "cmake;3.22.1" "platforms;android-36"`.
- `local.properties` (git-ignored) already has `sdk.dir=/Users/abidmajid/Library/Android/sdk`.
  Do not also export a conflicting `ANDROID_SDK_ROOT` (`docs/BUILDING.md`).
- `~/.android/debug.keystore` exists.

### 3.2 Command (equivalent of `dev.sh build`)

```sh
cd "/Volumes/My files/pocof1/carlink-src"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME="$HOME/Library/Android/sdk"; unset ANDROID_SDK_ROOT
DIPLAY_AUTH_ASSETS_DIR="$HOME/.carlink-private" ./gradlew --no-daemon :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk   (package com.carlink.app.hudtest, versionName 1.0.16-hud-test)
```

- `DIPLAY_AUTH_ASSETS_DIR` must be the folder **containing** `offline-mfi/`, i.e. `~/.carlink-private`,
  not `.../offline-mfi`. `app/build.gradle.kts:8-11,73-79` copies **only** `offline-mfi/` into
  `app/build/generated/carlinkAuthAssets` and adds it as an asset dir. This is deliberate: 1.0.0-1.0.7
  shipped the whole private folder, release keys included.
  Present on this Mac: `~/.carlink-private/offline-mfi/{identity.pk8,certificate.p7b}`.
- At runtime `DiPlayBootstrap.ensure()` installs them from `assets/offline-mfi/`. Without them
  `CarPlayPreflight` reports `Problem.CARPLAY_AUTH_MISSING` (`Connection.kt:277`) and Diagnostics shows it.
- `org.gradle.configuration-cache=true` (`gradle.properties`). **[I]** A changed env var should
  invalidate the cache because it is read through `providers.environmentVariable`.
- Tests (as `dev.sh test`): `python3 -I scripts/resource_collisions.py --check` then
  `./gradlew --no-daemon --continue :app:testDebugUnitTest :carlink-core:testDebugUnitTest
  :androidauto:testGithubDebugUnitTest :carplay-shared:testDebugUnitTest :carplay-common:testDebugUnitTest`.
  `SmoothVideoHostLifecycleTest` is known flaky.

### 3.3 Package identity and signing

- `carlink.applicationId=com.carlink.app`, `carlink.debugApplicationIdSuffix=.hudtest`
  (`gradle.properties`). They are shared with DiAuto's BuildConfig. Debug = **`com.carlink.app.hudtest`**,
  `versionNameSuffix "-hud-test"`, ABIs arm64-v8a, armeabi-v7a, x86, x86_64 (`app/build.gradle.kts:96-101`).
- Debug signing: AGP's default debug key (`~/.android/debug.keystore`). There is no custom debug signingConfig.
- Release (`assembleRelease`): R8 on, `-dontobfuscate`. Signing from env `ANDROID_KEYSTORE_PATH`,
  `_PASSWORD`, `ANDROID_KEY_ALIAS`, `_PASSWORD` (`app/build.gradle.kts:81-91`). `dev.sh build-release`
  falls back to the debug keystore. R8 needs keep rules for any reflection/JNI
  (`app/src/main/keepRules/carlink.keep`).
- The in-app updater only acts in the release package signed with the pinned key, so it never
  touches `.hudtest`.
- Debug and release can be installed side by side. Both react to Bluetooth/USB, so keep only one
  on the phone.

### 3.4 What `dev.sh` does (`.claude/skills/carlink-dev/scripts/dev.sh`)

- Commands: `build`, `test`, `install`, `shot`, `size`, `logs`, `build-release`, `install-release`.
- `build` adds `DIPLAY_AUTH_ASSETS_DIR=$HOME/.carlink-private` when `identity.pk8` exists, then runs
  `./gradlew --no-daemon -q :app:assembleDebug` (`:49-55`).
- **It is written for the maintainer's Linux box:**
  - it hard-codes `ANDROID_HOME`, `ANDROID_SDK_ROOT=$HOME/android-sdk` and `ADB=$HOME/android-sdk/platform-tools/adb` (`:20-28`);
  - it only targets `emulator-5560` or the tablet at `192.168.10.103:5555` and refuses any other serial (`:30`).
- On this Mac, run Gradle directly (§3.2), or run `dev.sh` with its paths edited.
- Installing on the Poco: the phone is on the Windows PC (`abid@192.168.68.200`, adb
  `C:\pocof1\platform-tools\adb.exe`). Copy the APK over and run `adb install -r app-debug.apk`,
  then `adb shell am start -n com.carlink.app.hudtest/com.carlink.app.ui.HomeActivity`.

### 3.5 Logs on the device (debuggable build only, `run-as com.carlink.app.hudtest`)

- `files/logs/carlink.log`: SessionManager decisions (logcat tag `CarLink-Session`).
- `files/logs/diplay.log`: CarPlay session log, `WIRELESS_TIMING` / `WIRED_TIMING` / `AIRPLAY_TIMING`.
  `createAirPlayConfig` logs "Display request …", "Display effective … physical=WxHmm …" and
  "Turning screen: square canvas …" (`CPHA:5161-5178`). This is how to verify what mm/pixels the iPhone got.
- Logcat tags: `CarLink-CarPlay`, `CarLink-CarPlayHost`, `DiPlay-MediaKeys`, `DiPlay-Rotation`.

---

## 4. Session start, reconnect, background, phone pitfalls

### 4.1 Architecture of a session

- `Connection.get(ctx)`: the single `SessionManager` (`core/connection/Connection.kt:31-36,162-178`).
  - Main thread. One engine at a time. A new start waits for the old stop to finish (DIPLAY F14).
  - Settings are written per session on worker `carlink-session` (`Connection.kt:181-246`).
- `CarPlayAdapter.start(plan)` (`core/connection/CarPlayAdapter.kt:86-125`):
  1. selects the iPhone (`CarPlayHostIntegration.selectPhone`);
  2. sets DiPlay's wireless flag;
  3. holds a low-latency Wi-Fi lock;
  4. on Wi-Fi Direct, watches the P2P group clients (`CarPlayLinkWatch`);
  5. opens `CarPlayHostActivity` (`DiPlayEngineHooks.openHost`, `:385-417`). If CarLink is not in
     front, the open is deferred until one of its activities resumes, because of Android 10+
     background-launch rules.
- **A CarPlay session lives in an Activity** (`CarPlayHostActivity`). There is no headless start;
  the host needs a laid-out visible window to know its size (DIPLAY §0.1, §1.3).
  `DiPlaySessionService` only provides the FGS and notification.
- Stop: `CarPlayHostIntegration.stopSession(completion)`, which completes after controller close and
  service stop (`CPHI:205-211`, `HostSessions.step`, `:315-335`). CarLink adds a 20 s timeout
  (`CarPlayAdapter.kt:127-151,226`).

### 4.2 Wireless CarPlay sequence (Wi-Fi Direct default)

From DIPLAY §0 item 3, §3.1:
1. Bring up the network (Wi-Fi Direct group; a stable persistent identity per install,
   `CarPlayHostIntegration.wifiP2pStableIdentity = true`, `CarPlayAdapter.kt:238-239`).
2. Bind the AirPlay listener.
3. Advertise `_airplay._tcp` via JmDNS.
4. **RFCOMM client** to the bonded iPhone's iAP2 UUID.
5. iAP2 identification and MFi auth.
6. Send Wi-Fi credentials (0x5703), then StartSession (0x4301).
7. Wait 30 s for the iPhone's first AirPlay TCP.

Other notes:
- CarLink pages the iPhone over Bluetooth early (`BluetoothWarmup`, `WirelessStartupTimeline.beforeBluetoothDial`,
  `CarPlayAdapter.kt:265`). Measured on the tablet: ≈16-20 s to picture, dominated by Bluetooth paging (README).
- First time: pair **from the iPhone** in Bluetooth settings (the display is not a car kit), accept
  "Use CarPlay with CarLink?" with the iPhone unlocked (README "First-time setup", DIPLAY §3.5).
  The name shown is `CarPlayHostIntegration.accessoryName` = app name "CarLink" (`CarPlayAdapter.kt:236`).
- Retries inside DiPlay: startup budget 2/4/8/16/30 s, max 5. Other losses back off 2 s doubling to 30 s.
  CarLink adds 1.5 s quick retries ×8 for unanswered RFCOMM dials (VENDOR_CHANGES row on
  `isBluetoothDialFailure`). `HOTSPOT_CONFIGURATION` is never retried. After the budget the
  waiting view shows Retry (`HostState.RetriesStopped`).
- CarLink's `StallPlaybook`: per-phone deadlines (2.5× median), then nudge, retry, fresh Wi-Fi
  Direct, next path, fail (`SKILL.md`). Only `FailureReason.pathRelated` moves to the next path.
  The working path is remembered per phone (`PathMemory`).
- The iPhone leaving: Wi-Fi Direct `CarPlayLinkWatch` verdicts (`CarPlayAdapter.kt:180-197`).

### 4.3 Automatic start triggers

| Trigger | Where | Conditions |
|---|---|---|
| **Home comes to front** (app opened, screen wakes to CarLink) | `HomeActivity.onResume` → `Connection.onHomeShown` (`app/.../ui/HomeActivity.kt:82-91`, `Connection.kt:107-137`) → `SessionManager.onHomeShown` (`SessionManager.kt:487-534`) | auto-BT on (default true), not paused, Idle, wireless possible, no USB phone, ≥60 s since last auto start **and** since last session end (`HOME_AUTO_START_COOLDOWN_MILLIS`, `:1047`). Only paired/known phones. Both kinds present → asks. |
| **iPhone Bluetooth ACL connects** | `SystemEventReceiver` (`core/entry/SystemEventReceiver.kt:33-108`) → `HomeLauncher.openHome` (`app/.../entry/HomeLauncher.kt`) → Home → onHomeShown | needs `BLUETOOTH_CONNECT`. Opening Home from the background needs "display over other apps"; otherwise a full-screen notification. **[I]** A phone is not a car kit, so the iPhone rarely initiates an ACL to it (README FAQ, USER_GUIDE "Known limits"). In practice the Home-shown trigger is what starts it. |
| **Boot / ACC-on broadcasts** | `SystemEventReceiver` `BOOT_ACTIONS` (`:35-40,173-180`) | only with `CarLinkPreferences.startOnBoot` (default **false**, `CarLinkPreferences.kt:23-24`) |
| **USB iPhone** | `UsbRouterActivity` / `UsbWatcher` | starts at once (not relevant for the wireless bike setup) |
| Manual Connect / Retry | Home, switcher | clears the pause |

- Pause: Disconnect, Cancel or unplug set `autoConnectPaused` for **10 min**
  (`CarLinkPreferences.kt:68-74,87`). While paused no automatic start happens.
- A Bluetooth link from the running session's own phone is ignored. Another phone gets a switch
  offer (`SessionManager.onBluetoothPhone`, `:426-459`).

### 4.4 Foreground / background behaviour

- `CarPlayHostActivity.onStop` keeps the session ("The controller, USB/iAP2 link, and VPN attachment
  intentionally outlive the UI", `CPHA:1911-1925`). A new host adopts it via `CarPlayBackgroundSession`.
- The host window sets `FLAG_KEEP_SCREEN_ON` (`CPHA:912`).
- Android 17 / targetSdk 37 background audio (`docs/ARCHITECTURE.md` "Android 17 background audio"):
  - an FGS gets while-in-use rights only if started while the app is visible;
  - `DiPlaySessionService` is started only by the visible `CarPlayHostActivity.startCarPlay`, and
    DiPlay's internal reconnects keep it running;
  - otherwise `requestAudioFocus` fails silently;
  - **[I]** On the Poco (Android 17), starting a session while the screen is off or another app is
    in front will not work; CarLink must be in front.
- Android 17 needs the **Local network** permission (`ACCESS_LOCAL_NETWORK`, `carlink-core` manifest
  line 29). Setup asks for it since 1.0.15 (#38). Without it IPv4 to the iPhone breaks silently.
- The floating switcher (bubble over CarPlay) needs the overlay permission (`SwitcherController.install`).
- Screen orientation of the CarPlay host is overridden to `fullUser` (`carlink-core/src/main/AndroidManifest.xml:113-114`).

### 4.5 What "Screen type: Phone or tablet" (HANDHELD) changes

`UiProfileMode` (`app/.../ui/theme/UiProfile.kt:14-33`); applied via `ScreenType.forcedHandheld`
(`UiPreferences.kt:95-100`). Strings: `carlink_strings_settings.xml:197-204`.

1. CarLink's own UI tokens: HANDHELD = Material 3 sizes on the device density; CAR = 76 dp targets,
   24 sp minimum on a pixel-scaled canvas.
2. CarPlay canvas: `HeadUnitDetector.handheld()` → `CarPlayRotation.setEnabled`. HANDHELD (or Auto
   with an accelerometer, auto-rotate on and a battery/modem) gives the **turning square + view
   areas + live sizes at 350 mm base** (§1.3). CAR gives the plain canvas and real mm.
3. Wireless path order: `looksLikeCarDisplay` (CAR) lets CarPlay try a known car hotspot first
   (`WirelessPolicy.kt:50`). The Poco has telephony and a battery, so in Auto mode it counts as a
   non-car display.
4. Android Auto turning screen / density (`AaEngineHooks.kt:99-101`, `AaSessionSettings.kt:217-231`).

### 4.6 Known pitfalls on phones (vs head units)

- Turning reconnects: fixed in 1.0.15 by the square canvas (CHANGELOG 1.0.15; VENDOR_CHANGES rows
  on `applyViewArea` / `pendingViewArea` retries). A turn just before the session is ready is resent
  every 0.5 s for 15 s.
- Head units with a 4G modem or battery were mistaken for phones and got black bars / no safe area
  (#33, #65). Now an accelerometer and auto-rotate are required (`HeadUnitDetector.kt:44-50`).
- The square canvas costs sharpness and decoder load (1920 cap with SMOOTHER). The live-size areas
  cost more (§1.3). **[I]** For a fixed landscape mount, the plain canvas is better.
- Decoders: the square side is probed against the default decoder (`CarPlayRotation.squareSide`).
  On a Snapdragon 845 (Poco F1) **[I]** 1920² H.264 should be accepted. Check the
  `DiPlay-Rotation` log line "square … for a … px screen".
- No hands-free/car-audio role: the iPhone never connects by itself. CarLink must be brought to the
  front, or the driver taps Connect.
- Wireless Android Auto puts the phone on mobile data. CarPlay over Wi-Fi Direct: **[I]** the iPhone
  keeps cellular for data, as with any CarPlay unit.
- Same Wi-Fi CarPlay needs mDNS between clients and is opt-in. Wi-Fi Direct is the default and was
  proven on the tablet.
- Picture AUTOMATIC on low-memory units caps at 1280×720 (CHANGELOG 1.0.15). The Poco F1 (6 GB)
  should not be affected **[I]**.
- `WheelKeys`-style learned keys and `dispatchKeyEvent` routes only work while the CarPlay window
  has focus.
