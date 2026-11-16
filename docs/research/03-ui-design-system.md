# 03 — Handlebar Link UI: design system, components, motion

Status: research and recommendation (no app code). Date: 2026-11-16 (revised the same day: we are **not**
merging into CarLink; CarLink is loose inspiration only).

Scope: replace the plain-Views prototype (`handlebar-link-prototype/.../MainActivity.kt`) with a small
Compose design system that we own, in the spirit of shadcn/ui. The app starts as a handful of screens
(setup, status, mapping, diagnostics, settings). Later it will also host our own **full-screen CarPlay
video surface**, so the system must cover both "glanceable control screens" and "get out of the way of
video".

---

## 0. TL;DR (the decisions)

1. **Own everything visible; borrow only behaviour.** We use shadcn's model ("this is not a component
   library, it is how you build your component library"):
   - Our own token system: semantic colour pairs, a type scale, sizes and motion.
   - About 20 components, kept as source in a `:designsystem` module.
   - **Compose Foundation** as the headless layer (`clickable`, `toggleable`, `selectable`, semantics,
     `Dialog`, `AndroidExternalSurface`).
   - **Material 3 as a private implementation detail** for the one primitive that is expensive to rebuild
     well: `ModalBottomSheet`, which tracks the predictive-back gesture. M3 is *not* our design language;
     its `ColorScheme`/`Typography` roles are not our API.
2. **No third-party shadcn-style kit as a dependency** (§2.4). Lumo UI is dormant and generates its own
   palette at 44 dp sizes. Compose Unstyled is excellent and active, but would be a second headless layer
   for the five primitives we need, and its sheet lacks predictive-back progress. The shadcn ports are
   tiny, desktop-sized, or built on experimental APIs. Orbit is archived but is a good reference.
3. **Tokens tuned for a 6.2″ phone on handlebars.** Dark is the default, and there are **Day** and
   **Sunlight** (AAA high-contrast) schemes. Targets are 76 dp for gloves, with ≥23 dp gaps. Decision text
   is ≥32 sp. There is one accent, used only for interactive elements. The palette starts from CarLink's
   well-tested "graphite + cyan" look (inspiration, not a contract), with contrast fixes applied.
4. **Navigation 3 (stable 1.2.1) with `@Serializable` `NavKey` routes.** These are the same type-safe keys
   as Navigation Compose 2.8+. The back stack is a list we own, with per-entry
   `predictivePopTransitionSpec`. A Nav2 equivalent is given in §5.
5. **Motion explains state and nothing else.** Durations are 90/150/250/350 ms with M3's standard easings,
   and springs come from M3's **standard** scheme (no overshoot). At most one loop per screen; reduce-motion
   means everything snaps. The projection screen animates only a black fade over the video surface.
6. **Screenshot testing: Roborazzi.** It runs on Robolectric and interoperates with ViewModels and real
   semantics. It generates tests from previews and adds ATF accessibility checks and an accessibility-legend
   capture. nowinandroid uses it. Paparazzi is incompatible with Robolectric, and its 2.x line is still alpha.

---

## 1. Context and constraints (numbers we design against)

**Device.** Poco F1: 1080×2246 px, 6.18″ → **≈403 ppi**. At the stock 440 dpi density (2.75×), the
canvas is **≈393×817 dp**, and **1 dp ≈ 0.173 mm**. (I could not confirm the density live: the phone was
not attached to the Windows host. Check with `adb shell wm density`; custom ROMs often change it.)

| Size | Physical on Poco F1 | Note |
|---|---|---|
| 48 dp | 8.3 mm | Material minimum target; too small for gloves |
| 64 dp | 11.1 mm | Our floor for parked-only screens |
| 76 dp | 13.2 mm | Android-for-Cars minimum target; **our glove target** |
| 80 dp | 13.9 mm | Our primary button height |

**Viewing distance.** About 60–70 cm (phone on the bars, glanced at while stopped). For a ≈20 arcmin
character height at 65 cm, cap height ≈ 3.8 mm. Inter's cap height is ≈0.73 em, so the font size is
≈ 3.8 / 0.73 / 0.173 ≈ **30 sp**. Hence decision text (status headline, last press) is ≥32 sp, which
matches the AAOS guidance of "32dp primary decision text, 24dp secondary"
([driving UX principles](https://docs.partner.android.com/drivingux/foundations/design-principles)).
Text read only when parked can be 15–20 sp.

**Gloves.** I found no published motorcycle or glove touch-target standard; it only shows up in forum and
product posts (e.g. [Beeline](https://beeline.co/blogs/motorcycling/why-we-designed-the-beeline-moto-ii-for-glove-friendly-navigation),
[MyRoute forum](https://mracommunity.nodebb.com/topic/2589/bigger-buttons-specially-in-carplay)). We
borrow the car numbers: **76×76 dp minimum** and **≥23 dp between targets**
([Android for Cars sizing](https://developers.google.com/cars/design/android-auto/design-system/sizing),
[driving UX principles](https://docs.partner.android.com/drivingux/foundations/design-principles)).
Treat these as a car-derived baseline to validate on the bike with the user's gloves.

**Light.** Apple's CarPlay HIG: "Test your app's color scheme under a variety of lighting conditions in
an actual car… low-contrast colors can wash out in direct sunlight"; support both light and dark
([CarPlay HIG](https://developer.apple.com/design/human-interface-guidelines/carplay)). AAOS: ≥4.5:1 for
all text and icons; day mode light, night mode dark.

**Glanceability.** "A driver should be able to read content and understand the system state within 2
seconds", and "the system should respond within 0.25 seconds"; avoid auto-scrolling text. Apple: "place
the most important content and controls in the upper half of the screen"; "avoid using the same color
for interactive and noninteractive elements"; "if your app requires setup on iPhone, make sure people
perform it before the vehicle is in motion."

**Future CarPlay surface.** The app will later render CarPlay video full-screen, with touch going to the
iPhone. This affects the design system in three ways:
- The projection route is **always dark** (letterbox black), whatever the theme.
- Our chrome over video must be minimal and transient.
- Animations must never transform the video surface itself (§6.4).

**CarLink as inspiration** (read-only, *not* a constraint). Things worth taking:
- Its palette and contrast discipline (`ui/theme/CarLinkColors.kt`).
- Its "every error has one recovery action" rule (`design/DESIGN_SPEC.md` §3).
- A stateless `XContent(state)` split per screen that makes a screenshot gallery cheap (981 images in
  `docs/screenshots/`).
- Motion limits (≤350 ms, no overshoot, reduce-motion snap).

Things we deliberately don't take: its two-profile density override, glass gradients and grain
everywhere, the ~1,100-line screen files, and an enum-plus-hand-rolled back stack.

---

## 2. What the references teach

### 2.1 shadcn/ui → Compose translation

Source: `shadcn-ui/ui@e62c943` (2026-11-13), `apps/v4/content/docs/(root)/index.mdx`, `theming.mdx`,
`apps/v4/registry/new-york-v4/ui/button.tsx`.

| shadcn/ui idea | What it says | Compose translation for us |
|---|---|---|
| Open code over a headless core | "The top layer of your component code is open for modification… you simply edit the button code directly." The core (`@base-ui/react`, Radix) stays a dependency and "can receive fixes by updating your dependencies". | Components are `.kt` files in `:designsystem`. The "headless core" dependency is **Compose Foundation** (gestures, semantics, focus, `Dialog`) plus M3's `ModalBottomSheet` only. |
| Composition | "Every component… shares a common, composable interface." | One parameter order everywhere: required data, `onClick`, `modifier: Modifier = Modifier`, optional flags, slots last ([Compose component API guidelines](https://github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-component-api-guidelines.md) §Parameters order). |
| Tokens via CSS variables, as semantic **pairs** | `background`/`foreground`, `card`/`card-foreground`, `primary`/`primary-foreground`, `muted`, `accent`, `destructive`, `border`, `ring`. Dark mode redefines the same names under `.dark`. | `@Immutable data class HbColors` with the same pair idea (`canvas`/`onCanvas`, `accent`/`onAccent`, `fault`/`onFault`, `border`, `focusRing`…) provided through a `staticCompositionLocalOf`. A theme is just another instance. Components read tokens only, never literals. |
| One radius drives the scale | `--radius` with `radius-sm = 0.6×` … `4xl = 2.6×`. | `HbRadii(base = 24.dp)` with derived `sm`/`md`/`lg`/`xl`/`pill`. |
| Variants with `cva` | `variant: default · destructive · outline · secondary · ghost · link`, `size: xs…lg · icon`. | One internal `ButtonCore(variant, size)` behind **separate public composables** (`PrimaryButton`, `SecondaryButton`, `QuietButton`, `DangerButton`). The Compose guideline is "prefer multiple components over style classes"; the internal enum keeps a single implementation. |
| `data-slot` and `asChild` | Parts are addressable, and the element can be swapped. | Slot lambdas (`leading`, `trailing`, `content`) and `Modifier` passthrough. |
| CLI and registry | Copies files into your project. | Unnecessary for one app. The `:designsystem` module *is* the registry. |

### 2.2 nowinandroid `core/designsystem` (`android/nowinandroid@2982e63`, 2026-10-20)

- Layout: `theme/` (Color, Theme, Type, Background, Gradient, Tint), `component/` (one file per
  component), `icon/NiaIcons.kt`.
- Components are thin wrappers over M3 (`NiaButton` → `Button` with theme colours), with a
  `NiaButtonDefaults` object. Extra theme values travel in `CompositionLocal`s next to `MaterialTheme`
  (`LocalGradientColors`, `LocalBackgroundTheme`, `LocalTintTheme`).
- Screenshot tests live with the module (`core/designsystem/src/test/.../ButtonScreenshotTests.kt`, …)
  and use **Roborazzi 1.56**, `@GraphicsMode(NATIVE)`, a shared `captureMultiTheme()`
  (`core/screenshot-testing/.../ScreenshotHelper.kt`) and `CompareOptions(changeThreshold = 0f)`.
  97 goldens are committed under `src/test/screenshots/`.
- Navigation uses **navigation3 1.2.0**.
- Takeaway: copy the module shape, the per-component screenshot tests and the Defaults-object
  convention. Don't copy the M3-wrapper depth: NiA wants to look like Material, and we don't.

### 2.3 Material 3 and M3 Expressive motion (read in source, `androidx/androidx@56126aab`)

- `MotionScheme` is public and stable, with `standard()` and `expressive()`, each providing
  `fast/default/slow` × `Spatial/Effects` specs. The KDoc says spatial specs are for "animations that may
  change the shape or bounds… For color or alpha animations use the `effects` equivalent." Standard is
  "for utilitarian UI elements and recurring interactions"; expressive is "for prominent UI elements and
  hero interactions."
- Spring constants (`tokens/StandardMotionTokens.kt`, `ExpressiveMotionTokens.kt`):

  | Spec | Standard (damping / stiffness) | Expressive |
  |---|---|---|
  | fastSpatial | 0.9 / 1400 | **0.6** / 800 (visible bounce) |
  | defaultSpatial | 0.9 / 700 | 0.8 / 380 |
  | slowSpatial | 0.9 / 300 | 0.8 / 200 |
  | fast/default/slow Effects | 1.0 / 3800 · 1600 · 800 | same |

- Durations and easings (`tokens/MotionTokens.kt`): Short1–4 = 50–200 ms, Medium1–4 = 250–400,
  Long1–4 = 450–600. Easings: Standard `(0.2,0,0,1)`, EmphasizedDecelerate `(0.05,0.7,0.1,1)`,
  EmphasizedAccelerate `(0.3,0,0.8,0.15)`.
- **Takeaway:** copy the *standard* values into our own `HbMotion` (we don't need `MaterialTheme` for
  them). Expressive's 0.6 damping is exactly the bounce a glanceable UI shouldn't have. Damping 0.9
  overshoots by under 1 %, so it is effectively critically damped.

### 2.4 Library options vs owning our own components

All candidates were cloned to the scratchpad and read. Stars, licence and archive status come from the
GitHub API on 2026-11-16.

| Candidate | What it is | Activity / licence | Toolchain | Quality notes from the source | Verdict |
|---|---|---|---|---|---|
| **Lumo UI** — `nomanr/lumo-ui` (the name is right) | A Gradle plugin and CLI that **copies component templates into your module**, the closest thing to shadcn's CLI. Templates are in `lumo-ui/plugin/src/main/resources/templates/android/{components,foundation}/*.kt.template`, configured by `lumo.properties` (`ThemeName`, `ComponentsDir`, `PackageName`). About 25 components: Button, IconButton, Chip, Switch, Checkbox, RadioButton, Slider, TextField, OTP field, Card, ModalBottomSheet, AlertDialog, Snackbar, TopBar, NavigationBar, Accordion, Tooltip, Badge, progress… | 596★, Apache-2.0. Last commit on `main`: **2025-04-15 ("release 1.2.5")**; feature branches only since. Effectively dormant. | Kotlin 2.1.0, Compose BOM 2024.12.01, CMP 1.7.3 | Foundation-only; it imports just `material.ripple`. It generates its **own theme**: raw palettes (`Gray900`, `Red600`, `Blue900`…) plus its own Typography. That is not a semantic-pair system; you would rewrite it. `ButtonVariant` has 13 values (Primary/Secondary/Destructive × Filled/Outlined/Elevated/Ghost, + Ghost), so the matrix is wider than needed. `ButtonDefaults.MinHeight = 44.dp`. Its `ModalBottomSheet` template has no predictive-back handling. Each file contains its own previews. Code is readable and idiomatic. | The ownership model is right, but after generation we would rewrite the theme, every size and the sheet, which leaves little value. Read the templates for the interaction and elevation plumbing and the Defaults-object style. |
| **Compose Unstyled** — `composablehorizons/compose-unstyled` (Alex Styl / composables.com) | Headless, Radix-style primitives as separate artifacts (Button, Dialog, Modal, ModalBottomSheet, BottomSheet, Drawer, DropdownMenu, Slider, ToggleSwitch, Checkbox, RadioGroup, TabGroup, Tooltip, Portal, FocusRing, Scrollbars, TextField…) plus a theme builder `buildThemeV2 {}` with colour-scheme support. | 1,263★, MIT, very active (commit **2026-11-16**, v2.10.2) | Kotlin 2.4.0, CMP 1.12.0, AGP 9.1.1 | Careful a11y and keyboard work: `UnstyledButton` uses `Role.Button` and Enter/Space activate it; modals trap Tab focus and restore focus on close (2.10.2 changelog). Its `ModalBottomSheet` dismisses on back through an `EscapeHandler`, but **I found no predictive-back progress handling**; M3's sheet animates with the gesture. It is deprecating its minimum-interactive-size policy: "implement that policy in your own components." | The best headless option, and the one to pick if we needed menus, tooltips, sliders or desktop or iOS targets. For an Android-only app whose primitives are button, toggle row, radio row, sheet and dialog, Foundation already covers four of the five, and M3 does the sheet better. **Named fallback, not a v1 dependency.** |
| **shadcn ports** (four exist; `Kashif-E/Shadcn-UI-Compose` does not) | | | | | |
| — `ronjunevaldoz/shadcn-compose` | "70+ components", no Material, KMP | 8★, Apache-2.0, last push 2026-10-01 | Kotlin 2.4.0, CMP 1.12.1; needs `@OptIn(ExperimentalFoundationStyleApi::class)` | A faithful port (`ButtonStyles.kt` mirrors each `button.tsx` variant, including hover alpha), built on Compose's **experimental Foundation `Style` API**. Web-desktop sizes: `ButtonSize.Md` 36 dp, Lg 40 dp. I found no minimum-touch-size handling in `shadcn/core`. | No: an experimental API, pointer-sized controls, and a one-person project. |
| — `rainxchzed/RikkaUi` | "The shadcn/ui of Compose Multiplatform… Zero Material3", with an optional copy-source CLI | 186★, Apache-2.0, last push 2026-09-18, README says "UNDER ACTIVE DEVELOPMENT" | Kotlin 2.3.0, CMP 1.10.0 | Clean enums (`ButtonVariant` Default/Outline/Secondary/Ghost/Destructive/Link; `ButtonAnimation` None/Scale/**Bounce**). Sizes 28–44 dp. Sets semantics and `Role` in 19 files. | Read for API ergonomics. Its defaults are wrong for gloves, and the project is unstable. |
| — `derangga/komoui` (renamed from `shadcn-ui-kmp`; the old repo redirects) | shadcn look on an M3 foundation. Tokens come through `MaterialTheme.styles.foreground` / `mutedForeground` and `MaterialTheme.radius`. | 41★, MIT, last push 2026-09-24 | Kotlin 2.2.20, CMP 1.10.2, M3 1.9.0 | Uses `minimumInteractiveComponentSize()`. Buttons 28–44 dp. Its set is web-flavoured (Combobox, Sidebar, Table, Charts). | Shows that "shadcn tokens over an M3 core" works. Not a dependency. |
| — `jershell/shadcn-multiplatform` | shadcn on Compose Unstyled primitives, with tokens generated from Figma JSON | 8★, MIT, last push 2026-10-24 | Kotlin 2.4.0, CMP 1.11.1 | Interesting token pipeline (`imports/design-tokens.json`). Tiny community. | No. |
| **Kiwi.com Orbit Compose** — `kiwicom/orbit-compose` | A real production design system | 182★, MIT, **archived 2025-07-05** | Compose BOM 2024.04.01, M3 1.2 | The best *reference* here for how a company layers its own system: `OrbitTheme` owns `Colors`/`Typography`/`Shapes`, provides them through `LocalColors` etc., and *also* feeds a converted `MaterialTheme(colors.toMaterial3Colors(), …)` so any M3 internals look right. It uses **separate composables per variant** (`ButtonPrimary`, `ButtonPrimarySubtle`, `ButtonCritical`, `AlertInfo/Success/Warning/Critical`), a `Timeline` with `TimelineScope.TimelineItem`, Paparazzi tests and a `component-status.yaml`. | Reference only. Its theme bridge is exactly the pattern we use for the one M3 sheet. |

**The four architectures, judged on their merits:**

| Approach | For | Against |
|---|---|---|
| A. Material 3 as the base, fully re-themed (NiA, CarLink) | The most components for free; well tested | M3's role vocabulary (≈40 colour roles, 15 type roles) becomes our API; M3 opinions (state layers, tonal elevation, 48 dp policy, typography assumptions) leak and must be fought per component; the result tends to look like Material whatever we do |
| B. Compose Unstyled primitives + our styled layer | shadcn's exact split (Radix ↔ Unstyled); strong a11y; active; MIT | A second headless layer over Foundation for what are mostly one-modifier jobs; sheet lacks predictive-back progress; CMP artifacts in an Android-only app |
| C. Lumo-generated source | The "own the code" CLI | Dormant; generates a raw palette and 44 dp sizes we would rewrite; no predictive back |
| **D. Foundation + our tokens + our components, M3 only for `ModalBottomSheet`** | shadcn's philosophy with the thinnest possible core; our token vocabulary only; every size and colour is ours; predictive-back sheet for free | We write about 20 components ourselves (≈1–1.5 k lines). Mitigated by the small scope and by copying patterns from Lumo, Orbit and Unstyled |

**Recommendation: D.** Why it wins for *this* app:
1. **The surface is small and specialised.** Status, setup, mapping, diagnostics, settings and a
   projection overlay need perhaps 20 components, most of them domain-specific (connection ring, press
   tester, step guide). No kit ships those, and every kit's generic controls are 28–44 dp, so we would
   resize all of them anyway.
2. **The headless jobs are already in Foundation.**
   - Button and row: `Modifier.clickable(role = Role.Button)`.
   - Switch row: `Modifier.toggleable(role = Role.Switch)`.
   - Radio row: `selectable(role = Role.RadioButton)` with `selectableGroup()`.
   - Dialog: `androidx.compose.ui.window.Dialog`.
   - Focus: `onFocusChanged`.
   - Ripple: `androidx.compose.material:material-ripple` (`ripple()`), which is not M3.
   - Minimum touch size: our own `Modifier.minTouch()`. Compose Unstyled itself now says this policy
     belongs in your own components.
3. **The one hard primitive is M3's sheet.** Gesture-tracked predictive back, drag-to-dismiss, scrim and
   a11y are worth a dependency. Android 17 has predictive back on by default, and the guide confirms M3's
   `ModalBottomSheet` animates with it (material3 ≥1.3.0,
   [predictive back setup](https://developer.android.com/develop/ui/compose/system/predictive-back-setup)).
   Wrap it in `HbSheet` and pass every colour, shape and scrim explicitly. Following Orbit, also provide a
   `MaterialTheme` derived from our tokens, so any defaults it reads are ours.
4. **The CarPlay surface needs no UI kit at all.** It needs `AndroidExternalSurface` (Foundation), insets
   and immersive-mode control, and a couple of overlay components we design ourselves.
5. **Exit ramps stay open.** If we later need menus, tooltips, sliders or text fields with more polish,
   add the specific Compose Unstyled artifact (MIT) for that primitive and style it with our tokens. That
   is exactly shadcn's "if a component does not exist, we bring it in… and adjust its style".

### 2.5 Compose animation, shared elements, predictive back

- Use the official decision tree ([choose an animation API](https://developer.android.com/develop/ui/compose/animation/choose-api)):
  - content swap → `AnimatedContent`/`Crossfade`
  - appear/disappear → `AnimatedVisibility`
  - size → `animateContentSize`
  - several properties that start together → `updateTransition`
  - a gesture-driven or interruptible single value → `Animatable`
  - infinite → `rememberInfiniteTransition`
  - list items → `animateItem()`
- **Shared elements** ([docs](https://developer.android.com/develop/ui/compose/animation/shared-elements)):
  `SharedTransitionLayout` with `sharedElement()` for the same content and `sharedBounds()` for a
  container transform. They don't work across View interop, "includ[ing] any composable that wraps
  `AndroidView`, such as a `Dialog` or `ModalBottomSheet`", and there is no automatic shape morph. Nav3
  supports them via `NavDisplay(sharedTransitionScope = …)`.
- **Predictive back.** System animations are on by default from Android 15. Nav3's `NavDisplay` takes
  `transitionSpec`, `popTransitionSpec` and `predictivePopTransitionSpec`, globally or per entry through
  `NavDisplay.TransitionKey`/`PopTransitionKey`/`PredictivePopTransitionKey` metadata
  ([Nav3 animations](https://developer.android.com/guide/navigation/navigation-3/animate-destinations)).
  Intercepting back at the root activity disables back-to-home, so we never put a `BackHandler` on Status.

### 2.6 Accessibility

- Material's minimum is 48 dp; ours is 76 dp. A small visual can reserve layout space with a
  `minimumInteractiveComponentSize`-style modifier
  ([Compose a11y defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)).
  We implement `Modifier.minTouch(HbTokens.size.minTouch)` ourselves.
- Lift click handling to rows (`toggleable`/`selectable` with a `Role`; the inner control is
  non-interactive). Give icon-only controls a `contentDescription`, and decorative icons `null`. Add
  `onClickLabel` where the verb isn't obvious.
- **Font scaling**: Android 14+ scales up to 200 % on a nonlinear curve, so large text grows less. Use sp
  for text **and line height**, never sp for padding, and test at 200 %
  ([Android 14 features](https://developer.android.com/about/versions/14/features#non-linear-font-scaling)).
- Contrast is WCAG 2.x: 4.5:1 body (AA), 7:1 (AAA) for decision text, 3:1 for UI boundaries (every pair
  computed in §3.1). Never signal by colour alone: every status gets an icon and a word.

### 2.7 Automotive guidance we adopt

| Adopt | Source |
|---|---|
| 76 dp targets, 23 dp gaps, icons 44/36/24 dp, content centred in its target | AAOS sizing; driving UX principles |
| Understand state in ≤2 s, respond in ≤250 ms, no auto-scrolling text | Driving UX principles |
| Decision text 32 dp, secondary 24 dp; avoid bold at reading sizes (SemiBold only at display sizes) | AAOS typography |
| One accent, used only on interactive elements; status colour always with an icon and a word | CarPlay HIG; AAOS colour |
| Most important content in the upper half; setup done before riding | CarPlay HIG |
| Every error offers one primary recovery action | CarLink DESIGN_SPEC §3 (a good idea, independent of CarLink) |
| Light (day) and dark (night) modes, tested outdoors | CarPlay HIG, AAOS |
| CarPlay projection: never require input on the iPhone; report problems on our screen | CarPlay HIG ("Never lock people out… because the connected iPhone requires input") |

---

## 3. Design tokens

All tokens live in `:designsystem/tokens`. Components read them through `HbTokens.*`. No component or
screen may contain a colour, size or font literal (§7 has the lint guard).

### 3.1 Colour roles (shadcn-style semantic pairs)

Three schemes: **Dark** (default, also night), **Day** (light) and **Sunlight** (maximum-contrast light;
opt-in). The **projection route always uses Dark**. Ratios were computed with the WCAG 2.x
relative-luminance formula (script: scratchpad `contrast.py`). "canvas" = screen, "surface" = card,
"raised" = the highest container.

Role list (our API; the shadcn equivalent is in brackets):
- `canvas`/`onCanvas` [background/foreground]
- `surface`, `surfaceLow`, `surfaceRaised`, `surfacePressed` [card/popover]
- `content`, `contentMuted`, `contentSubtle` [foreground / muted-foreground]
- `border`, `borderStrong`, `hairline` [border/input]
- `accent`/`onAccent`, `accentSoft`/`onAccentSoft` [primary / accent]
- status pairs `live`, `caution`, `fault` (each with `on*`, `*Soft`, `on*Soft`) [destructive, extended]
- `inverse`/`onInverse`, `focusRing` [ring], `scrim`, `glassFill`, `glassFillPressed`, `glassStrokeTop`,
  `glassStrokeBottom`, `atmosphereLight`, `atmosphereFill`, `videoBlack`

#### Dark (default)

| Role | Hex | Use | Contrast |
|---|---|---|---|
| `canvas` | `#0C1016` | Screen | — |
| `videoBlack` | `#000000` | Projection letterbox, splash | — |
| `surfaceLow` | `#12161C` | List background | — |
| `surface` | `#1B1E24` | Solid card (glass = white 6 % over canvas ≈ this) | — |
| `surfaceRaised` | `#24282D` | Sheets, dialogs | — |
| `surfacePressed` | `#2C3036` | Pressed rows | — |
| `content` | `#EEF2F6` | Titles and values; not pure white (night glare) | 16.95 canvas · 14.84 surface · 11.79 pressed — **AAA** |
| `contentMuted` | `#A9B3C0` | Descriptions | 8.99 · 7.87 · 6.25 — AAA on canvas/surface |
| `contentSubtle` | `#8A95A3` | Metadata and timestamps only | 6.27 · 5.49 · 4.36 (**not on pressed**) |
| `borderStrong` | `#7A8490` | Boundaries of toggles and inputs | 5.02 · 4.40 · 3.49 |
| `border` / `hairline` | `#2A2F37` / white @ 10 % | Dividers (decorative) | — |
| `accent` | `#5BD6F0` | Primary button fill, toggle on, links, focus ring. **Interactive only.** | 11.19 canvas · 9.80 surface |
| `onAccent` | `#03222B` | Label on accent | 9.72 — **AAA** |
| `accentSoft` / `onAccentSoft` | `#12343E` / `#BFF2FB` | Selected row, flashing mapping row | 10.91 |
| `live` | `#7EE2A8` | Connected, pass | 12.10 · 10.60 |
| `liveSoft` / `onLiveSoft` | `#0F3320` / `#C8F5DA` | Connected banner | 11.57 |
| `caution` | `#FFB547` | Waiting, degraded | 10.85 · 9.50 |
| `cautionSoft` / `onCautionSoft` | `#3A2A0E` / `#FFE3B8` | Caution banner | 11.16 |
| `fault` | `#FF7A70` | Error title and icon | 7.51 · 6.57 (AAA at ≥24 sp) |
| `onFault` | `#3A0905` | Text on a fault fill (Danger button) | 6.77 |
| `faultSoft` / `onFaultSoft` | `#3D1715` / `#FFD9D5` | Error banner | 12.12 |
| `inverse` / `onInverse` | `#EEF2F6` / `#0D1117` | **Press-tester "lit" key**: a neutral flash, so the accent stays for interaction | 16.82 |
| `glassFill` / `glassFillPressed` | white @ 6 % / 10 % | Cards, secondary buttons | — |
| `glassStrokeTop` / `glassStrokeBottom` | white @ 16 % / 4 % | 1 dp top-lit card edge | — |
| `atmosphereLight` / `atmosphereFill` | `#16324A` / `#111A26` | Soft background light (Status and Setup only) | — |
| `scrim` | black @ 55 % (video overlays: @ 40 %) | Behind sheets | — |

#### Day (light)

| Role | Hex | Contrast (canvas `#E8EBEF` · surface `#FFFFFF` · pressed `#DADFE5`) |
|---|---|---|
| `canvas` | `#E8EBEF` | — |
| `surfaceLow` / `surface` / `surfaceRaised` / `surfacePressed` | `#F7F8FA` / `#FFFFFF` / `#F1F3F5` / `#DADFE5` | — |
| `content` | `#0D1117` | 15.83 · 18.92 · 14.12 — AAA |
| `contentMuted` | `#46505C` | 6.85 · 8.20 · 6.11 |
| `contentSubtle` | `#4F5965` | 5.95 · 7.12 · 5.31 |
| `borderStrong` | `#6B7580` | 3.92 · 4.69 · 3.50 |
| `accent` / `onAccent` | `#006273` / `#FFFFFF` | 5.86 · 7.01 · 5.23; label 7.01 — AAA |
| `accentSoft` / `onAccentSoft` | `#CBEFF6` / `#00303A` | 11.60 |
| `live` | `#146235` | 6.21 · 7.43 · 5.54 |
| `caution` | `#8A5000` | 5.44 · 6.51 · 4.85 |
| `fault` / `onFault` | `#B3261E` / `#FFFFFF` | 5.47 · 6.54 · 4.88; label 6.54 |
| soft pairs (live / caution / fault) | `#CDEFD9`/`#002111`, `#FFE3B8`/`#2B1700`, `#FCDAD6`/`#410002` | 13.8 / 13.8 / 13.2 |
| glass | fill white @ 62 %, stroke top white @ 90 %, bottom `#0D1117` @ 10 % | — |

(CarLink's light values for subtle text, border, accent, live, caution and fault fall below 4.5:1 or
3:1 on its highest container. The values above are darkened until every pair passes.)

#### Sunlight (high contrast, light) — direct sun; doubles as an accessibility high-contrast mode

| Role | Hex | Contrast on `#FFFFFF` (and on `#EEF0F3`) |
|---|---|---|
| `canvas`/`surface` | `#FFFFFF`; `surfaceRaised`/`surfacePressed` `#EEF0F3` | — |
| `content` | `#000000` | 21.0 (18.4) |
| `contentMuted` | `#262C33` | 14.1 (12.3) |
| `contentSubtle` | `#3A424C` | 10.2 (8.9) |
| `borderStrong` | `#4A535E`, drawn **2 dp** | 7.8 (6.8) |
| `accent` / `onAccent` | `#00505F` / `#FFFFFF` | 9.08 |
| `live` / `caution` / `fault` | `#0B5A30` / `#6E4200` / `#9E1F17` | 8.3 / 8.6 / 7.9 — all **AAA** |
| glass | off: solid white cards with a 2 dp `borderStrong` outline; no atmosphere, no gradients | — |

Sunlight also raises text weight by one step (Regular→Medium, Medium→SemiBold). Rationale: screen
reflection adds roughly constant luminance to every pixel, which crushes a dark theme's contrast far more
than a light theme's. White ground with black ink keeps the largest absolute luminance difference. (This
reasoning is mine, not cited; validate outdoors per the CarPlay HIG.)

**Colour rules:** one accent, used only for interactive elements; status colour always with an icon and a
word; no dynamic colour (Material You), so contrast is guaranteed; no pure-white text on dark; no blur.

**Theme selection:** `ThemeMode { Dark (default), Auto, Day, Sunlight }`. Auto follows the system
day/night setting. *Optional later experiment:* Auto+ reads `Sensor.TYPE_LIGHT` and switches to
Sunlight above about 10 klx and back below about 5 klx, with ≥5 s hysteresis so shade and tunnels don't
flicker it. Theme switches cross-fade over 300 ms.

### 3.2 Typography

Inter v4 (SIL OFL), bundled and subset to the scripts we ship. Bundling gives identical metrics on every
ROM and in screenshot tests. Inter has tabular figures (`fontFeatureSettings = "tnum"`) for every number,
and Inter Display for the large sizes. Weights are 400 / 500 / 600 only. Line heights are in sp, with
`LineHeightStyle(Center, Trim.None)`. Two tiers: **Glance** (must read in ≤2 s at about 65 cm) and
**Parked** (read only when stopped). The roles are ours and are not M3 names.

| `HbType` role | sp / line | Weight | Tier | Use |
|---|---|---|---|---|
| `hero` | 56 / 60, −1.1 sp tracking, Inter Display | 600 | Glance | Press readout ("Next track") |
| `headline` | 40 / 46, −0.6 | 600 | Glance | Status headline ("Bike connected") |
| `stepTitle` | 32 / 38, −0.3 | 600 | Glance | Setup step title |
| `screenTitle` | 28 / 34, −0.2 | 600 | Glance | Screen title |
| `sheetTitle` | 24 / 30 | 600 | Glance | Sheet title, card hero value |
| `cardTitle` | 22 / 28 | 500 | Glance | Card title, status sub-line |
| `rowTitle` | 20 / 26 | 500 | Glance | Row title (mapping, settings) |
| `body` | 20 / 28 | 400 | Glance | Status explanation, banner body |
| `rowValue` | 17 / 22 | 500 | Parked | Row value, secondary title |
| `bodyParked` | 17 / 24 | 400 | Parked | Descriptions |
| `caption` | 15 / 20 | 400 | Parked | Metadata |
| `button` | 20 / 24 | 500 | Glance | Primary and secondary button labels |
| `buttonSmall` | 17 / 20 | 500 | Glance | Compact buttons |
| `eyebrow` | 13 / 16, +1 sp tracking, CAPS | 600 | — | `StatusPill` and section eyebrows only, ≤2 words |
| `mono` | 14 / 20, `FontFamily.Monospace` | 400 | Parked | Log lines |

Rules: never ellipsize a button label or error title; wrap to two lines and let the component grow. No
marquee. Device names may ellipsize after one line in rows. Don't clamp `fontScale`. Test at 1.0, 1.3
and 2.0.

### 3.3 Spacing and sizing

4 dp base. `HbTokens.space`: `xxs 4 · xs 8 · sm 12 · md 16 · lg 24 · xl 32 · xxl 48`.

| `HbTokens.size` | dp | Note |
|---|---|---|
| `minTouch` | **76** | Anything on Status, Setup and the projection overlay |
| `minTouchParked` | 64 | Rows on Diagnostics and Settings |
| `primaryButton` | 80 | One per state |
| `secondaryButton` | 76 | |
| `targetGap` | 24 | ≥23 dp (AAOS) |
| `screenMargin` | 20 portrait / 32 landscape | 393 dp − 2×20 = 353 dp of content |
| `cardPadding` | 20 | |
| `rowOneLine` / `rowTwoLine` | 76 / 92 | Whole row is the target |
| `iconLarge` / `iconMedium` / `iconSmall` | 44 / 36 / 24 | AAOS icon sizes |
| `iconTile` | 52 | |
| `statusIndicator` | 168 portrait / 120 landscape | `ConnectionIndicator` |
| `pressKey` | 88 | One key of the press-tester D-pad |
| `focusRing` | 3 | |
| `contentMaxWidth` | 600 | Landscape |

### 3.4 Shape (radii)

`HbRadii(base = 24.dp)`: `sm = 0.6× (≈14)`, `md = base (24)`, `lg = 1.33× (32)`, `xl = 1.66× (40)`,
`pill = 50 %`.
- Buttons, pills and toggles: `pill`. Large radii read as automotive, and pills are easy to hit with gloves.
- Cards: `lg`. Sheets: 28 dp top corners. Press-tester keys: `md`. Icon tiles: 30 % of their size.

### 3.5 Elevation and materials

Elevation is tonal only, with **no drop shadows**: shadows are invisible on near-black, and tonal steps are
cheaper. There are three materials:
- *Atmosphere*: one soft radial light baked into the background, on Status and Setup only.
- *Glass*: a translucent fill with a 1 dp top-lit stroke, for cards and secondary buttons.
- *Solid accent*: the primary button.

| Level | Token | Use |
|---|---|---|
| 0 | `canvas` (+ atmosphere) | Screen |
| 1 | `glassFill` / `surface` | Cards, rows |
| 2 | `surfaceRaised` + `scrim` | Sheets, dialogs |
| 3 | `glassFillPressed` / `surfacePressed` | Pressed |

Sunlight replaces glass with solid white and a 2 dp border. The projection overlay uses `scrim` @ 40 % pills
on video, never glass (glass over moving video is unreadable).

### 3.6 Motion tokens

```kotlin
object HbMotion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)               // M3 standard
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val PRESS = 90        // press colour change
    const val SHORT = 150       // toggles, pills, badge appear
    const val MEDIUM = 250      // state cross-fade, banner in/out
    const val LONG = 350        // screen transitions (max for any transition)
    const val THEME = 300       // day/night cross-fade, linear
    const val PRESS_FLASH_HOLD = 120
    const val PRESS_FLASH_DECAY = 600
    const val LOOP = 1600       // the one allowed loop (searching/connecting ring)
    const val VIDEO_FADE = 200  // black cover over the projection surface
    fun <T> spatial() = spring<T>(dampingRatio = 0.9f, stiffness = 700f)       // M3 standard default spatial
    fun <T> spatialFast() = spring<T>(dampingRatio = 0.9f, stiffness = 1400f)
    fun <T> effects() = spring<T>(dampingRatio = 1f, stiffness = 1600f)
    fun <T> effectsFast() = spring<T>(dampingRatio = 1f, stiffness = 3800f)
}
```

`LocalReduceMotion` is true when `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (the "Remove
animations" accessibility switch sets this). When it is true, every duration is 0 and loops become
static frames.

### 3.7 Token plumbing (sketch)

```kotlin
@Immutable data class HbColors(/* §3.1 roles */ val canvas: Color, val onCanvas: Color, /* … */
                               val glassEnabled: Boolean, val borderWidth: Dp)
@Immutable data class HbTypeScale(/* §3.2 roles */ val hero: TextStyle, val headline: TextStyle, /* … */)
@Immutable data class HbSizes(/* §3.3 */ val minTouch: Dp, /* … */)
@Immutable data class HbRadii(val base: Dp) { val sm get() = base * 0.6f; /* … */ }
enum class ThemeMode { Dark, Auto, Day, Sunlight }

internal val LocalHbColors = staticCompositionLocalOf<HbColors> { error("No HbTheme") }
// … LocalHbType, LocalHbSizes, LocalHbRadii, LocalReduceMotion

@Composable fun HbTheme(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
    // resolve scheme → provide the locals → also provide LocalContentColor/LocalTextStyle defaults
    // → MaterialTheme(colorScheme = colors.toMaterialColorScheme(), …) ONLY so the wrapped
    //   ModalBottomSheet's internal defaults come from our tokens (Orbit's bridge pattern)
}
object HbTokens {
    val colors: HbColors @Composable @ReadOnlyComposable get() = LocalHbColors.current
    val type: HbTypeScale @Composable @ReadOnlyComposable get() = LocalHbType.current
    val size: HbSizes @Composable @ReadOnlyComposable get() = LocalHbSizes.current
    val radii: HbRadii @Composable @ReadOnlyComposable get() = LocalHbRadii.current
    val space = HbSpace; val motion = HbMotion
    val reduceMotion: Boolean @Composable get() = LocalReduceMotion.current
}
@Composable fun HbText(text: String, style: TextStyle, modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current, maxLines: Int = Int.MAX_VALUE)   // BasicText; no M3 Text
```

---

## 4. Components we own

Rules for every component:
- Parameter order: data, `onClick`, `modifier`, flags, slots.
- Every size and colour comes from `HbTokens`.
- Interactive elements meet `minTouch` and merge their semantics with a `Role`.
- Icon-only elements require a `contentDescription`.
- Each file ends with `@HbPreviews` previews covering all variants and states (§8).
- Built on Foundation unless stated otherwise.

### 4.1 Primitives

```kotlin
// Surfaces
@Composable fun HbCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(HbTokens.size.cardPadding),
    content: @Composable BoxScope.() -> Unit)                          // glass, or solid + border in Sunlight
@Composable fun HbGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)  // rows + hairlines
@Composable fun Atmosphere(modifier: Modifier = Modifier, glow: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit)

// Buttons: one internal core, separate public names (Compose guideline: multiple components over style classes)
internal enum class ButtonVariant { Primary, Secondary, Quiet, Danger }
enum class ButtonSize { Glove /*80 or 76 dp*/, Compact /*64 dp, parked screens*/ }
@Composable fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null, enabled: Boolean = true, busy: Boolean = false)
@Composable fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null, enabled: Boolean = true, size: ButtonSize = ButtonSize.Glove)
@Composable fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null)                                    // text-only, accent label
@Composable fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null)                                    // fault fill; only inside ConfirmDialog
@Composable fun RoundIconButton(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, onVideo: Boolean = false)           // Back, Settings, Close; onVideo = scrim pill
// busy = true: a spinner replaces the icon, the width is kept, clicks are ignored, stateDescription = "Working"

// Status text
enum class Tone { Neutral, Live, Caution, Fault }                       // → content/live/caution/fault + icon
@Composable fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier)  // lit dot + CAPS eyebrow
@Composable fun Eyebrow(text: String, modifier: Modifier = Modifier)
@Composable fun IconTile(@DrawableRes icon: Int, modifier: Modifier = Modifier, tone: Tone? = null)

// Rows (shadcn "Item")
@Composable fun ListRow(
    title: String, modifier: Modifier = Modifier, supporting: String? = null,
    leading: (@Composable () -> Unit)? = null, trailing: RowTrailing = RowTrailing.None,
    onClick: (() -> Unit)? = null, enabled: Boolean = true, highlight: Float = 0f /* 0..1 flash */)
sealed interface RowTrailing {
    data object None : RowTrailing
    data object Chevron : RowTrailing
    data class Value(val text: String) : RowTrailing
    data class Toggle(val checked: Boolean, val onCheckedChange: (Boolean) -> Unit) : RowTrailing // row = toggleable(Role.Switch)
    data class Radio(val selected: Boolean) : RowTrailing                                          // row = selectable(Role.RadioButton)
}
@Composable fun HbToggle(checked: Boolean, modifier: Modifier = Modifier)   // visual only; the row owns the semantics

// Inline alert (shadcn Alert / Orbit AlertInline)
@Composable fun Banner(tone: Tone, title: String, modifier: Modifier = Modifier, body: String? = null,
    action: Action? = null, onDismiss: (() -> Unit)? = null)
@Immutable data class Action(val label: String, @DrawableRes val icon: Int? = null, val onClick: () -> Unit)

// Overlays
@Composable fun HbSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit)          // wraps material3.ModalBottomSheet; all colours passed in
@Composable fun SheetHeader(title: String, body: String? = null)
@Composable fun <T> ChoiceSheet(title: String, options: List<T>, selected: T, label: @Composable (T) -> String,
    onSelect: (T) -> Unit, onDismiss: () -> Unit, body: String? = null)
@Composable fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit,
    onDismiss: () -> Unit, destructive: Boolean = false)  // ui.window.Dialog + our card

// Scaffolding
@Composable fun ScreenFrame(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null, atmosphere: Boolean = false,
    content: @Composable BoxWithConstraintsScope.(margin: Dp) -> Unit)  // insets, header, margins
```

### 4.2 Domain components

```kotlin
@Immutable sealed interface LinkUi {
    data object NoBike : LinkUi
    data class NeedsPermission(val missing: List<Perm>) : LinkUi
    data object BluetoothOff : LinkUi
    data object Searching : LinkUi                       // bike off or out of range: "Waiting for the bike"
    data class Connecting(val step: Int, val of: Int) : LinkUi
    data class Connected(val bikeName: String, val since: Long, val mediaReady: Boolean) : LinkUi
    data class Fault(val title: String, val body: String, val recovery: Action, val secondary: Action? = null) : LinkUi
}

/** Hero ring for the bike link. */
@Composable fun ConnectionIndicator(state: LinkUi, modifier: Modifier = Modifier,
    size: Dp = HbTokens.size.statusIndicator)
// Searching/Connecting: one slow sweep (the single allowed loop; value read in the draw phase).
// Connected: solid live ring, check and a tnum "since" timer. Fault: static fault ring.
// Reduce motion: a static arc at 50 %.

/** StatusPill + headline + one line + one primary action + at most one secondary. */
@Composable fun StatusHero(state: LinkUi, onAction: (Action) -> Unit, modifier: Modifier = Modifier)
// AnimatedContent keyed by state::class (contentKey), so a ticking timer never re-animates the block.
// semantics: heading on the headline; liveRegion = Polite on the container.

/** "Last press" card on Status. */
@Composable fun LastPressCard(press: PressUi?, modifier: Modifier = Modifier, onClick: () -> Unit)
@Immutable data class PressUi(val switch: HandlebarSwitch, val action: MappedAction?, val at: Long, val seq: Long)

/** Live press tester: a drawing of the CB350 left-hand switch cluster that lights as presses arrive. */
@Composable fun PressTester(
    lastPress: PressUi?, modifier: Modifier = Modifier,
    layout: SwitchLayout = SwitchLayout.Cb350,        // D-pad Up/Down/Left/Right + Vol±; long Down/Left as "hold" badges
    seen: Set<HandlebarSwitch> = emptySet(),          // setup: ticks each key once seen
    onKeyTap: ((HandlebarSwitch) -> Unit)? = null,   // tap = simulate a press (tests the CarPlay side)
    showReadout: Boolean = true)
enum class HandlebarSwitch { Up, Down, Left, Right, VolumeUp, VolumeDown, LongDown, LongLeft }   // = prototype Sab.Function
// Keys ≥ pressKey (88 dp). Flash = inverse fill: snap on, hold 120 ms, decay 600 ms; restarts per seq.
// Readout: "Down → Next track" in `hero`.

/** Setup. */
@Composable fun StepScaffold(
    step: Int, stepCount: Int, eyebrow: String, title: String, modifier: Modifier = Modifier,
    body: String? = null, illustration: (@Composable () -> Unit)? = null,
    primary: Action? = null, secondary: Action? = null, onBack: (() -> Unit)?, onClose: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit = {})
@Composable fun StepProgress(step: Int, stepCount: Int, modifier: Modifier = Modifier)
@Composable fun Checklist(items: List<CheckItem>, modifier: Modifier = Modifier)
@Immutable data class CheckItem(val label: String, val state: CheckState, val fix: Action? = null)
enum class CheckState { Pending, Running, Pass, Fail }      // icon + word, never colour alone

/** Mapping and diagnostics. */
@Composable fun MappingRow(switch: HandlebarSwitch, action: MappedAction?, onClick: () -> Unit,
    modifier: Modifier = Modifier, flash: Float = 0f)
@Composable fun LogList(lines: List<LogLineUi>, modifier: Modifier = Modifier, followNewest: Boolean)

/** Future CarPlay projection (designed now so tokens and motion cover it). */
@Composable fun ProjectionFrame(
    surface: @Composable (Modifier) -> Unit,          // AndroidExternalSurface (SurfaceView) supplied by the app
    state: ProjectionUi, modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {})    // our own transient chrome
@Immutable sealed interface ProjectionUi { data object Starting; data object Live; data class Lost(val reason: String) }
@Composable fun VideoToast(text: String, tone: Tone, modifier: Modifier = Modifier)  // scrim pill, auto-hide 3 s
@Composable fun PressHud(press: PressUi?, modifier: Modifier = Modifier)            // optional 1 s corner badge on a handlebar press
```

Deliberately **not** built in v1: tabs, chips, text fields (no text entry; the bike is found by scan),
sliders, menus, tooltips, navigation bar or rail, snackbar (use `Banner`/`VideoToast`). If any becomes
necessary, take the matching Compose Unstyled artifact and style it (§2.4).

---

## 5. Screens and navigation

### 5.1 Routes (Navigation 3, type-safe)

```kotlin
@Serializable sealed interface Route : NavKey
@Serializable data class Setup(val step: SetupStep = SetupStep.Welcome) : Route
@Serializable enum class SetupStep { Welcome, Permissions, MeterPairingMode, FindBike, Pair, TestPress, Done }
@Serializable data object Status : Route          // hub; start destination once a bike is paired
@Serializable data object Mapping : Route
@Serializable data object Diagnostics : Route
@Serializable data object Log : Route
@Serializable data object Settings : Route
@Serializable data object Projection : Route      // later: full-screen CarPlay

val backStack = rememberNavBackStack(if (hasBike) Status else Setup())
NavDisplay(
    backStack = backStack, onBack = { backStack.removeLastOrNull() },
    transitionSpec = { sharedAxisX(forward = true) },          // 350 ms, 48 dp travel
    popTransitionSpec = { sharedAxisX(forward = false) },
    predictivePopTransitionSpec = { sharedAxisX(forward = false) },   // scrubbed by the gesture
    entryProvider = entryProvider {
        entry<Status> { StatusScreen(…) }
        entry<Setup> { key -> SetupScreen(key.step, next = { backStack.add(Setup(it)) },
                                          finish = { backStack.clear(); backStack.add(Status) }) }
        entry<Projection>(metadata = metadata {               // fade through black, never slide video
            put(NavDisplay.TransitionKey) { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }
            put(NavDisplay.PopTransitionKey) { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }
            put(NavDisplay.PredictivePopTransitionKey) { fadeIn() togetherWith fadeOut() }
        }) { ProjectionScreen(…) }
        // Mapping, Diagnostics, Log, Settings …
    })
```

- **Why Nav3 over Navigation Compose 2.x:** the back stack is a `SnapshotStateList` we own (trivial to
  replace after setup, or to push Projection from a service event); per-entry transition metadata (needed
  for the video route); stable since 2025; nowinandroid uses it. The keys are the same `@Serializable`
  types as Nav2's type-safe routes. **Nav2 fallback:** `NavHost(navController, startDestination = Status)
  { composable<Status> { … }; composable<Setup> { e -> e.toRoute<Setup>() } }` with
  `enter/exit/popEnter/popExitTransition` (navigation-compose ≥2.8.0 supports predictive back).
- Each setup step is its own entry, so the back gesture (with predictive preview) steps back through setup
  without a custom handler.
- Depth is never more than 2 below Status. There are no tabs and no drawer. The foreground-service
  notification opens Status.

### 5.2 Screen layouts (portrait 393×817 dp first)

**Status (hub)** — "is the bike link working, and if not, what do I tap?" in 2 s.
```
┌ Handlebar Link                            (⚙) ┐  ← 76 dp RoundIconButton
│        ╭─────────╮                              │
│        │  ring   │  ConnectionIndicator 168 dp  │  upper half = the answer
│        ╰─────────╯                              │
│        ● CONNECTED            (StatusPill)      │
│      Bike connected          (headline 40 sp)   │
│   Honda CB350 · media control ready (body)      │
│  [ ▶ One recovery action – PrimaryButton 80dp ] │  only when not OK
│  [   Secondary – SecondaryButton 76 dp      ]   │  optional
│ ┌ LAST PRESS ──────────────────────────────┐    │
│ │  ⬇  Down  →  Next track      2 s ago     │    │  LastPressCard → tester sheet
│ └──────────────────────────────────────────┘    │
│ ┌ HbGroup ─────────────────────────────────┐    │
│ │ [ic] Bike      Honda CB350             › │    │  → Settings
│ │ [ic] Buttons   6 of 8 mapped           › │    │  → Mapping
│ │ [ic] Checks    All good                › │    │  → Diagnostics
│ └──────────────────────────────────────────┘    │
└─────────────────────────────────────────────────┘
```

| State | Primary | Secondary |
|---|---|---|
| NoBike | "Set up your bike" | — |
| NeedsPermission | "Allow" | — |
| Connected but media access missing | "Allow media control" | — |
| BluetoothOff | "Turn on Bluetooth" | — |
| Searching | none (Caution pill "WAITING"; body "Turn the ignition on; the meter connects by itself") | "Pair again" |
| Fault | from the error catalogue, e.g. "Try again" | "Forget and re-pair" |
| Connected | — (later: "Open CarPlay" when an iPhone is projecting) | — |

The screen stays on while Status is visible and the link is up (`Modifier.keepScreenOn()` or
`FLAG_KEEP_SCREEN_ON`). Tapping the LastPress card opens a full-screen press-tester sheet.

**Setup** (`StepScaffold` on Atmosphere, `StepProgress` in the header, Back/Skip at the bottom):
1. *Welcome*: one line on what the app does, plus "Start".
2. *Permissions*: a `Checklist` (Nearby devices, Notifications, Media control access), each with its own
   "Allow". Continue is enabled once all pass.
3. *Put the meter in pairing mode*: an illustration slot (vector of the CB350 meter and switch cluster)
   plus numbered steps in `body`. Primary "It's in pairing mode", secondary "Where's that menu?" (a help
   sheet).
4. *Find the bike*: a scanning `ConnectionIndicator`, then found bikes as `ListRow`s with already-bonded
   ones first. A 20 s timeout turns it into a Fault banner with "Search again".
5. *Pair*: "Accept the pairing prompt" with live bond status. On failure: "Try again" / "Continue without
   pairing" (the prototype's fallback).
6. *Test a button*: a full `PressTester` with `seen` ticks; "Press any switch on the left handlebar".
   "Looks good" is enabled after the first press; Skip is allowed.
7. *Done*: replaces the stack with Status.

**Mapping**: a compact `PressTester` (no readout) with the hint "Press a switch to find it". Below it,
eight `MappingRow`s (Up · Down · Left · Right · Vol + · Vol − · Hold Down · Hold Left). Pressing a real
switch scrolls to its row and flashes it, so a gloved rider never reads the list. A row tap opens a
`ChoiceSheet` (Previous track · Next track · Play/Pause · Volume up · Volume down · Nothing). "Reset to
defaults" is a `QuietButton`. Changes apply instantly, with no save button.

**Diagnostics** (parked): a `Checklist` of Bluetooth on · Permissions · Bike bonded · Link up · Button
notifications subscribed · Media control access · Media target available, each with a fix. Then rows
"Event log ›" and "Share report". **Log**: a `mono` `LogList`, newest first, with a level icon per line. It
follows the newest line only until the user scrolls (then a "Jump to newest" `QuietButton` appears).

**Settings** (parked): `HbGroup` sections.
- *Bike*: name, "Forget bike" (opens a `ConfirmDialog`, destructive).
- *Behaviour*: start on boot, keep screen on.
- *Look*: theme `ChoiceSheet` with Dark, Auto, Day and Sunlight.
- *About*.

**Projection (later)**: full-bleed `AndroidExternalSurface` (SurfaceView) for the video.
- **Immersive mode**: hide system bars with `WindowInsetsControllerCompat`, transient bars on swipe.
- **Always dark**: `videoBlack` letterbox.
- **Overlay**: none while live, except an optional 1 s `PressHud` badge when a handlebar switch fires
  (useful while tuning) and a `VideoToast` for problems ("Phone disconnected — reconnecting").
- **Lost link**: on `Lost`, the video fades to black (200 ms) and a centred card shows the one recovery
  action.
- **Leaving**: a `RoundIconButton(onVideo = true)` appears for 3 s after a tap in the top safe corner, or
  the user uses the system back gesture.
- **Gesture conflicts**: if CarPlay touch input conflicts with edge gestures, use
  `Modifier.systemGestureExclusion` on the bottom area. Android caps exclusion at 200 dp per edge.

**Landscape** (817×393 dp): Status splits 50/50 (indicator and hero on the left; last press and group on
the right). Setup puts the illustration on the left and text and actions on the right. Others centre in
600 dp. Projection is landscape-native.

---

## 6. Motion guidelines

### 6.1 What animates, and how

| Moment | API | Spec |
|---|---|---|
| Screen push/pop | `NavDisplay` specs | Shared axis X: slide 48 dp + fade. Enter 350 ms EmphasizedDecelerate, exit 350 ms EmphasizedAccelerate, fade-out 175 ms |
| Predictive back | `predictivePopTransitionSpec`; M3 sheet built in | Same shared axis, scrubbed by the gesture |
| Status state change | `AnimatedContent(targetState, contentKey = { it::class })` | Cross-fade + 16 dp vertical slide, 250 ms; ring colour `animateColorAsState(effects())` |
| Recovery button appear/disappear | `AnimatedVisibility` | fade + expandVertically, 250 ms |
| Banner in/out | `AnimatedVisibility` | fade + 16 dp slide, 250 ms |
| Card height change | `animateContentSize(spatial())` | 0.9 / 700 |
| Button/row press | `MutableInteractionSource` → `animateColorAsState` | 90 ms to the pressed fill; no scale, no bounce |
| Toggle thumb | `animateDpAsState(spatialFast())` | 0.9 / 1400 |
| **Press-tester flash** | `Animatable(0f)`; per new `seq`: `snapTo(1f)` → `delay(120)` → `animateTo(0f, tween(600, easing = LinearOutSlowIn))` | Instant attack, well inside the 250 ms response budget |
| Mapping row flash on hardware press | same `Animatable` → `accentSoft` at that alpha | + `animateScrollToItem` |
| Searching/connecting ring | `rememberInfiniteTransition`, read **in draw** only | 1.6 s linear sweep; the one loop; stops on Connected or Fault |
| Theme switch | `animateColorAsState` per colour role | 300 ms linear |
| Step progress | `animateFloatAsState` | 250 ms Standard |
| Projection enter/leave/lost | A black `Box` above the surface, `animateFloatAsState` alpha | 200 ms; the **surface itself never moves or scales** |
| Video toast / HUD | `AnimatedVisibility` fade | 150 ms in, 250 ms out |

### 6.2 What never animates

- Status text while the state class is unchanged. A ticking timer or a new last press changes in place.
- Errors: no shake, flash or pulsing red. The fault ring is static.
- Decoration: no parallax, no animated gradient or atmosphere, no confetti on "Done", no looping icon, no
  marquee or auto-scrolling text, no auto-scrolling log once the user has scrolled.
- Overshoot: none (no expressive scheme, no `dampingRatio < 0.9`).
- More than one loop per screen; and any loop on Settings, Mapping, Diagnostics or Projection.
- The video surface: no slide, scale or shared element. SurfaceView content can't be transformed
  smoothly. `AndroidEmbeddedExternalSurface` (TextureView) could, but costs performance and adds
  latency, which is wrong for CarPlay.
- Shared-element transitions: none in v1 (they can't cross `ModalBottomSheet` or SurfaceView anyway).
- Anything longer than 350 ms, except the 600 ms press-flash *decay*, which is feedback rather than a
  transition.
- With reduce motion, everything snaps. The ring shows a static arc, and the press flash becomes a solid
  300 ms highlight with no fade (feedback is kept; a test asserts it, §8).

### 6.3 Performance

- Read looping and flash values inside `drawBehind`/`Canvas` or `graphicsLayer {}` lambdas, so a frame
  redraws one layer and recomposes nothing.
- Pass `label =` to every animation (Animation Preview). Avoid blur and large translucent overdraw;
  glass is a flat alpha fill plus a 1 dp gradient stroke.
- On the projection route, no Compose animation runs while video is live.

---

## 7. Folder layout

```
handlebar-link/
├─ app/                                     # Activity, NavDisplay, ViewModels, BikeService (from prototype)
│  └─ src/main/kotlin/com/abid/handlebarlink/
│     ├─ MainActivity.kt                    # setContent { HbTheme(mode) { HandlebarApp() } }
│     ├─ nav/Routes.kt                      # §5.1
│     ├─ status/   StatusScreen.kt  StatusContent.kt (stateless)  StatusViewModel.kt
│     ├─ setup/    SetupScreen.kt   steps/*.kt                    SetupViewModel.kt
│     ├─ mapping/  MappingScreen.kt MappingContent.kt
│     ├─ diagnostics/ DiagnosticsScreen.kt LogScreen.kt
│     ├─ settings/ SettingsScreen.kt
│     └─ projection/ (later) ProjectionScreen.kt  — owns AndroidExternalSurface + decoder wiring
├─ designsystem/                            # :designsystem — Android library; no BLE, no app logic
│  ├─ build.gradle.kts                      # deps: compose-bom, foundation, ui, material-ripple,
│  │                                        #       material3 (implementation, ONLY for HbSheet), ui-tooling-preview
│  └─ src/
│     ├─ main/kotlin/com/abid/handlebarlink/designsystem/
│     │  ├─ tokens/   HbColors.kt (Dark, Day, Sunlight)  HbType.kt  HbSizes.kt  HbSpace.kt  HbRadii.kt  HbMotion.kt
│     │  ├─ theme/    HbTheme.kt  HbTokens.kt  ThemeMode.kt  ReduceMotion.kt  MaterialBridge.kt (internal)
│     │  ├─ foundation/ HbText.kt  MinTouch.kt  FocusRing.kt  Glass.kt (modifiers)
│     │  ├─ components/ HbCard.kt HbGroup.kt Atmosphere.kt Buttons.kt StatusPill.kt IconTile.kt
│     │  │              ListRow.kt HbToggle.kt Banner.kt HbSheet.kt ChoiceSheet.kt ConfirmDialog.kt ScreenFrame.kt
│     │  ├─ bike/     ConnectionIndicator.kt StatusHero.kt LastPressCard.kt PressTester.kt MappingRow.kt
│     │  │            StepScaffold.kt StepProgress.kt Checklist.kt LogList.kt  model/ (LinkUi, PressUi, …)
│     │  ├─ projection/ ProjectionFrame.kt VideoToast.kt PressHud.kt
│     │  ├─ icons/    HbIcons.kt            # only the Material Symbols Rounded glyphs we use, as vector drawables
│     │  └─ preview/  HbPreviews.kt (multipreview)  PreviewFixtures.kt
│     ├─ main/res/    drawable/hb_ic_*.xml  font/inter_*.ttf (subset)
│     └─ test/kotlin/…/designsystem/        # Roborazzi component tests (§8)
│        test/screenshots/                  # committed goldens
└─ gradle/libs.versions.toml
```

Guard rails:
- Outside `designsystem/tokens/`, the build fails on `Color(0x`, `.dp` literals in `RoundedCornerShape(`,
  `fontSize =`, and any `import androidx.compose.material3` outside `HbSheet.kt`/`MaterialBridge.kt`.
  Use a small Gradle check task (grep) or detekt `ForbiddenImport`/regex rules.
- `:designsystem` doesn't depend on `:app` or any Bluetooth code. Components take UI models (`LinkUi`,
  `PressUi`), never `BluetoothDevice`.
- Screens split into `XScreen` (ViewModel wiring) and stateless `XContent(state, callbacks)`, so previews
  and screenshot tests drive `XContent` with fake state.

---

## 8. Previews and screenshot testing: **Roborazzi**

**Why Roborazzi** (`takahirom/roborazzi@f845f1f`, v1.79.0, 2026-11-12):
1. **Robolectric-based.** Tests can use real ViewModels, `Lifecycle` and semantics interactions
   (`performClick`, then capture). Paparazzi "is incompatible with Robolectric" (Roborazzi README).
2. **Proven for a `core/designsystem` module.** nowinandroid uses it (Roborazzi 1.56, `captureMultiTheme`,
   `changeThreshold = 0f`).
3. **Preview → test generation** with ComposablePreviewScanner:
   `roborazzi { generateComposePreviewRobolectricTests { enable = true; packages = listOf("com.abid.handlebarlink") } }`.
   Every `@HbPreviews` preview becomes a golden.
4. **Accessibility in the same run.**
   - `roborazzi-accessibility-check` runs Google's Accessibility Test Framework (touch-target size,
     contrast, labels).
   - The `AccessibilityLegend` capture renders the screenshot next to the TalkBack traversal order with
     roles, states and "⚠ NO LABEL", so a changed label or order shows up as a golden diff.
5. **Motion review.** `recordScreenRoboVideo`/GIF capture in lockstep with the Compose clock lets us check
   the press flash and state cross-fades in PRs.
6. **Paparazzi's state.** Last stable release **1.3.5 (2024-11-06)**; 2.0 has been alpha since 2025-04
   (2.0.0-alpha05.1, 2026-09-28), and that release changed accessibility goldens. Its layoutlib engine is
   fast, and Kiwi's Orbit used it well, but nothing here needs it.

**Setup:**
- `@HbPreviews` multipreview: device `spec:width=393dp,height=817dp,dpi=440` ("Poco F1"), × themes
  {Dark, Day, Sunlight}, × fontScale {1.0, 2.0}, plus one landscape variant for screens.
- Every component file previews all variants and states. Screens preview `XContent` for each `LinkUi`
  state and each setup step.
- Hand-written screen-state tests named `<area>_<state>_<config>_<theme>.png`.
  `./gradlew recordRoborazziDebug` records; CI runs `verifyRoborazziDebug`; `compareRoborazziDebug`
  writes diffs.
- Motion tests:
  - With `LocalReduceMotion = true`, assert that no infinite transition is running: set
    `mainClock.autoAdvance = false`, advance 2 s, and compare two captures.
  - Capture the press tester at t = 0, 120 and 720 ms after a fake press.
- Projection: Robolectric can't render decoded video. Test `ProjectionFrame` with a solid placeholder
  surface lambda and screenshot only our overlay states (Starting, Live + HUD, Lost).
- Goldens live in `designsystem/src/test/screenshots/` (NiA convention). Record and verify on the same OS
  (Linux CI), because font rasterisation differs.

---

## 9. Open questions and things to verify on the bike

1. Glove trial: are 76 dp keys and 24 dp gaps enough, or does the press tester need 88–96 dp? Test with
   the rider's gloves at the mount angle.
2. Sunlight theme: validate outdoors at noon at full brightness. If Day is already legible, keep Sunlight
   as an accessibility option rather than a recommended mode.
3. Portrait or landscape mount? This decides which Status layout gets polish first. Projection is
   landscape either way.
4. Confirm the Poco F1 density (`adb shell wm density`). The dp math assumes 440 dpi.
5. Projection: decide SurfaceView (`AndroidExternalSurface`) vs TextureView after measuring decode-to-
   display latency on the Poco F1. This doc assumes SurfaceView.
6. Revisit Compose Unstyled if v2 needs menus, tooltips or sliders.

---

## 10. Sources

**Repos read** (shallow clones in
`/private/tmp/claude-501/-Volumes-My-files-pocof1/338e22e3-6e55-4ad9-85b5-2c4986af3681/scratchpad/research/`):

| Repo | Commit (date) | Files |
|---|---|---|
| shadcn-ui/ui | e62c943 (2026-11-13) | `apps/v4/content/docs/(root)/index.mdx`, `theming.mdx`, `apps/v4/registry/new-york-v4/ui/button.tsx` |
| android/nowinandroid | 2982e63 (2026-10-20) | `core/designsystem/**`, `core/screenshot-testing/.../ScreenshotHelper.kt`, `gradle/libs.versions.toml`, `README.md` |
| androidx/androidx (sparse) | 56126aab (2026-11-16) | `compose/material3/.../MotionScheme.kt`, `tokens/{Motion,StandardMotion,ExpressiveMotion}Tokens.kt`, `compose/docs/compose-component-api-guidelines.md` |
| takahirom/roborazzi | f845f1f (2026-11-12) | `README.md`, `roborazzi-accessibility-check/README.md` |
| cashapp/paparazzi | 038d542 (2026-11-13) | `README.md`, `CHANGELOG.md` |
| nomanr/lumo-ui | 2dcc703 (2025-04-15) | `README.md`, `lumo.properties`, `gradle/libs.versions.toml`, `lumo-ui/plugin/build.gradle.kts`, `lumo-ui/plugin/src/main/resources/templates/android/**` |
| composablehorizons/compose-unstyled | c026757 (2026-11-16) | `README.md`, `CHANGELOG.md`, `gradle/libs.versions.toml`, `docs/pages/*.md`, `composeunstyled-modal-bottom-sheet/src/**` |
| ronjunevaldoz/shadcn-compose | 3561d2a (2026-10-02) | `README.md`, `shadcn/core/.../styles/ButtonStyles.kt`, `gradle/libs.versions.toml` |
| rainxchzed/RikkaUi | 43ac1bc (2026-09-18) | `README.md`, `components/.../button/Button.kt`, `gradle/libs.versions.toml` |
| derangga/komoui | 8800423 (2026-09-24) | `README.md`, `LICENSE.md`, `komoui/src/commonMain/.../Button.kt`, `gradle/libs.versions.toml` |
| jershell/shadcn-multiplatform | 72624ad (2026-10-24) | `README.md`, `gradle/libs.versions.toml` |
| kiwicom/orbit-compose (archived) | 722ecd7 (2025-05-07) | `readme.md`, `ui/src/androidMain/.../OrbitTheme.kt`, `controls/{Button,Alert,Timeline}.kt`, `ui/src/androidUnitTest/.../ScreenshotTest.kt` |

GitHub API metadata (stars, licence, archived, last push) was fetched 2026-11-16.

**Local inspiration (read-only):**
- `/Volumes/My files/pocof1/carlink-src/app/src/main/java/com/carlink/app/ui/theme/*.kt`, `ui/premium/*.kt`, `ui/CarLinkApp.kt`
- `carlink-src/design/DESIGN_SPEC.md` (§0, §1.1, §3, §5) and `design/RESEARCH_PREMIUM_UX.md` (§6)
- `carlink-src/docs/screenshots/README.md` and phone-portrait screenshots
- Prototype: `/Volumes/My files/pocof1/handlebar-link-prototype/app/src/main/java/com/abid/handlebarlink/{MainActivity,Sab,BikeService,CarPlayControl}.kt`

**Web docs:**
- Compose animation decision tree — https://developer.android.com/develop/ui/compose/animation/choose-api
- Shared elements — https://developer.android.com/develop/ui/compose/animation/shared-elements
- Predictive back — https://developer.android.com/develop/ui/compose/system/predictive-back and …/predictive-back-setup
- Navigation 3 — https://developer.android.com/guide/navigation/navigation-3 ; animations: …/navigation-3/animate-destinations ; releases (1.2.1 stable, 1.3.0-alpha04, 2026-11-04): https://developer.android.com/jetpack/androidx/releases/navigation3
- Compose accessibility defaults — https://developer.android.com/develop/ui/compose/accessibility/api-defaults
- Android 14 nonlinear font scaling — https://developer.android.com/about/versions/14/features#non-linear-font-scaling
- Android for Cars sizing — https://developers.google.com/cars/design/android-auto/design-system/sizing
- Driving UX design principles — https://docs.partner.android.com/drivingux/foundations/design-principles
- Apple CarPlay HIG — https://developer.apple.com/design/human-interface-guidelines/carplay (read via the page's JSON data endpoint)
- Glove anecdotes (no standard exists) — https://beeline.co/blogs/motorcycling/why-we-designed-the-beeline-moto-ii-for-glove-friendly-navigation ; https://mracommunity.nodebb.com/topic/2589/bigger-buttons-specially-in-carplay ; https://forum.kurviger.de/t/enhanced-menu-style-big-buttons/1867
- shadcn-for-Kotlin discovery — https://klibs.io/project/derangga/komoui ; https://klibs.io/project/ronjunevaldoz/shadcn-compose ; https://central.sonatype.com/artifact/com.github.jershell/shadcn-multiplatform ; https://klibs.io/project/rainxchzed/RikkaUi ; https://relatedrepos.com/gh/dead8309/shadcn-kotlin (Kotlin/JS via Kobweb, not Compose)

**Not verified:**
- The m3.material.io motion pages render client-side and returned no text, so the spring values come from
  the androidx source.
- ISO 15008 was not consulted (paywalled).
- The visual-angle estimate (§1) and the sunlight-reflection reasoning (§3.1) are my own.
- "No predictive-back progress in Compose Unstyled's sheet" is from reading its source (only an
  `EscapeHandler` found), not from running it.
