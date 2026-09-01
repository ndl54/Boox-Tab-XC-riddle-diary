# CLAUDE.md — Development Notes

Engineering notes for the Riddle Diary app (BOOX / Onyx e-ink, developed and
tested on a **BOOX Note X2**, Android 11 / API 30, arm64). Read this before
touching the ink/refresh pipeline — most of it is hard-won device-specific
behavior that is not in the Onyx SDK docs.

## Module layout

```
app/src/main/java/com/billtt/riddle/
├── RiddleApp.kt         # Application: HiddenApiBypass + RxManager init — REQUIRED for the pen (see below)
├── MainActivity.kt      # Full-screen entry; gestures (long-press = settings); settings dialog; pen attach timing
├── DiaryController.kt    # State machine (write→absorb→await→reveal→linger→fade); TouchHelper wiring; animation driver
├── DiaryView.kt          # Page rendering: banded absorb cache, reply reveal; page PNG capture
├── Oracle.kt             # Backend interface + OracleFactory + shared persona prompts
├── AnthropicOracle.kt    # Anthropic backend (official Java SDK, Claude vision)
├── OpenAiOracle.kt       # OpenAI backend (Chat Completions; any OpenAI-compatible endpoint via base URL)
├── ReplyTypesetter.kt    # Reply layout: wrap, center, CJK-per-char / Western-per-word tokenization
├── Stroke.kt             # Stroke data model
├── EInk.kt               # EpdController wrapper (DU4 fast refresh / GC full refresh)
└── Prefs.kt              # Provider / key / model persistence
```

Plus `app/libs/`: the **local Onyx AAR set** (pen 1.5.2 + base 1.8.4 + device 1.3.3 +
`onyxsdk-pen-native-classes.jar`), taken verbatim from Boox-EinkDraw. See "The
pen-input story" — do not switch back to the maven coordinates.

## The pen-input story (most important)

**Final state (2026-09-01): zero-latency hardware ink, verified on-device.**
Earlier revisions of this file claimed app-render mode was the only working
path and hardware ink was unreachable — that was wrong on both counts. The
real story has two independent layers:

1. **Why the pen can die entirely: Android 11 hidden-API blocking.**
   `RawInputReader` maps the limit rect to digitizer coordinates by reflecting
   into the firmware framework (`android.onyx.ViewUpdateHelper.mapToRawTouchPoint`).
   Under Android 11's non-SDK restrictions that reflection silently fails, the
   mapped rect comes back empty (`RawInputReader: Empty region detected when
   mapping!!!!!`) and **no pen points are ever delivered** — in any render
   mode. This is what made default-mode experiments look "unsupported".
   Fix: `RiddleApp` (Application class) runs
   `HiddenApiBypass.addHiddenApiExemptions("")` + `RxManager.Builder.initAppContext`
   before anything touches the SDK. With the exemption, the mapping returns the
   full digitizer rect and callbacks work in every mode.

2. **Why maven `onyxsdk-pen:1.5.4` is slow: missing native fast-path classes.**
   The maven AAR's render layer falls back to slow software stroke drawing.
   The firmware's fast engine lives in `NeoPenNative`/`NeoPen*` classes that
   1.5.4 does not bundle. Boox-EinkDraw ships them as
   `onyxsdk-pen-native-classes.jar` (extracted from a device Notes APK) plus
   its local pen/base/device AARs — we bundle the same set in `app/libs`.
   With them, `FEATURE_ALL_TOUCH_RENDER` + render layer ON gives stock-Notes
   speed ink through the SDK's render layer.

Consequences baked into the current design:

- **`RiddleApp` must stay declared in the manifest** — remove it and the pen
  goes completely dead (Empty region), in every TouchHelper mode.
- **`app/libs` AAR set + `onyxsdk-pen-native-classes.jar`** — switching back to
  maven `onyxsdk-pen:1.5.4` compiles fine but ink reverts to slow software
  rendering (the native fast-path classes are gone).
- **4-arg `create(view, FEATURE_ALL_TOUCH_RENDER, callback, false)`** — the
  `false` keeps TouchHelper from installing its own OnTouchListener, so
  MainActivity's listener survives; it forwards events via
  `controller.forwardTouchToPen(event)`. This coexistence is also what keeps
  the long-press settings gesture working (with the 3-arg overload the helper's
  listener, installed later at attach, clobbers it and settings becomes
  unreachable).
- **Call order is critical** (Boox-EinkDraw recipe, see `attach()`):
  strokeWidth/enableFingerTouch/onlyEnableFingerTouch/strokeColor/setLimitRect
  → `openRawDrawing()` → style → re-apply width+color →
  `setRawDrawingRenderEnabled(false)` → `setRawDrawingEnabled(true)`.
  Note `setRawDrawingEnabled(b)` internally cascades
  `setRawDrawingRenderEnabled(b)` + `setRawInputReaderEnable(b)` (javap-verified
  on 1.5.2 and 1.5.4 alike) — so render OFF must come right before the final
  enable, and resume paths only need `setRawDrawingEnabled(true)`.
- **Live ink is drawn by the SDK's render layer** (hardware speed); the app
  collects points via callbacks and renders the finished stroke on pen-up:
  `onEndRawDrawing` sets `pendingPenUpRefresh`, then `onPenUpRefresh`
  (exact moment the preview clears) triggers one `EInk.animateFrame(view)`
  invalidate, with a 120 ms delayed fallback if it never fires.
- **Palm rejection:** `enableFingerTouch(false)` + `onlyEnableFingerTouch(false)`
  — stylus only. (There is NO `setRawPointFilterEnabled` on onyxsdk-pen;
  `enableFingerTouch` is the real API, verified via javap.)
- **Quirk (also present in Boox-EinkDraw): the very first stroke after launch
  may not preview**; from the second stroke the hardware ink is live.
- **Attach timing:** attach `TouchHelper` only after the window has focus
  (`onWindowFocusChanged`), so the view's on-screen position is final.

## Refresh / animation pipeline

E-ink refresh is slow (high-quality GU is ~300ms), so naïve per-frame gradients
stutter. The pipeline:

- **DU4 fast refresh** as the view's default update mode during animation
  (`EInk.beginAnimation` → `EpdController.setViewDefaultUpdateMode(view, DU4)`,
  ~150ms). Refresh a frame with a plain `view.invalidate()` (triggers `onDraw`);
  do **not** use `EpdController.postInvalidate` — it refreshes the ink layer
  without triggering `onDraw`, so nothing you drew appears.
- **Ink levels quantized to 5 steps** (`quantizeAlpha`) to match DU4 and avoid
  meaningless sub-step redraws.
- **Change-gated frames:** `runStagedFade` scans the timeline with a fine sample
  step (`SAMPLE_MS`) but only issues a real refresh when some element crosses a
  quantization step. Dead frames are skipped; every refresh is a visible jump.
- **GC full refresh** once at the end of a cycle to clear ghosting.

### Absorb animation (offscreen banded cache)

Redrawing hundreds of stroke segments per frame is what made absorption stutter.
Instead, `prepareAbsorb()` splits strokes **in write order** into `ABSORB_BANDS`
bands, renders each band into a small bitmap (bounding-box sized), and the fade
staggers the bands. Each frame then draws only a few bitmaps. This keeps the
"absorbed head-to-tail" ordering while making the redraw cost trivial. Reply
reveal/fade uses `drawText` directly (few elements, already cheap).

Tuning knobs live in `DiaryController.companion` (`FRAME_MS`, `SAMPLE_MS`,
`FADE_MS`, `ABSORB_BAND_FADE_MS`, `ABSORB_BAND_STAGGER_MS`, `REVEAL_WORD_MS`,
`lingerMillisFor`) and `DiaryView.ABSORB_BANDS`.

## Backends

`OracleFactory.create(prefs)` returns an `Oracle` for the selected provider, or
`null` if that provider's key is unset. Both backends send the page PNG plus the
shared persona/instruction from `OraclePrompts`. The OpenAI backend uses raw
OkHttp + `org.json` (no OpenAI SDK) so it works against any OpenAI-compatible
endpoint via a configurable base URL. The request intentionally sets **no**
token-limit parameter (avoids the `max_tokens` vs `max_completion_tokens`
incompatibility across models/gateways); reply length is bounded by the prompt.

## Build

Needs Android Studio, or JDK 17 + Android SDK 34 (`compileSdk 34`, `minSdk 28`).

- **Onyx SDK comes from the local AAR set in `app/libs`** (pen 1.5.2, base 1.8.4,
  device 1.3.3 + `onyxsdk-pen-native-classes.jar`, plus `onyxsdk-baselite:1.1.1`
  from the Boox maven repo for the `base.data.TouchPoint` supertype).
  The maven `onyxsdk-pen:1.5.4` builds but lacks the firmware's native fast-path
  classes → slow software ink; see "The pen-input story". (RxJava 2/1 and
  `hiddenapibypass` are pulled in as the Onyx SDK's runtime requirements.)
- **Jetifier is required** (`android.enableJetifier=true`): `onyxsdk-device:1.3.5`
  pulls in the legacy Android Support Library, which collides with AndroidX
  without it.
- **jniLibs conflict:** `onyxsdk-pen` and its `mmkv` dependency both ship
  `libc++_shared.so`; `packagingOptions.jniLibs.pickFirsts` resolves it.

```bash
./gradlew assembleDebug   # or Run from Android Studio
```

`local.properties` (`sdk.dir=…`) is generated by Android Studio; for
command-line builds point it at your own SDK.

### Building on Windows (verified 2026-08-31)

- JDK 17 at `C:\Users\pangc\jdk17` (Temurin zip), Android SDK at
  `C:\Users\pangc\android-sdk` (cmdline-tools + `platforms;android-34` +
  `build-tools;34.0.0`). Build with `JAVA_HOME` set, `./gradlew.bat assembleDebug`.
- **`local.properties` must use forward slashes** (`sdk.dir=C:/Users/pangc/android-sdk`).
  A properties-escaped backslash path (`C\:\Users\...`) gets its backslashes
  eaten by the properties parser and AGP dies with a misleading
  "Could not determine the dependencies of null / IOException".
- The repo path contains an apostrophe (`Tom Riddle's Diary`); if AGP chokes on
  it, build through the junction `C:\riddle` (created 2026-08-31, points at this
  directory).
- Debug keystore is per-machine: a Windows-built APK will not `install -r` over
  the Mac-built one — `adb uninstall com.billtt.riddle` first, then re-inject
  `shared_prefs/riddle.xml` (see below) and `pm enable` again (BOOX auto-freeze).

## Install & debug on the device

- **BOOX auto-freeze:** newly installed apps are frozen by the launcher's
  optimization (EAC). After `adb install`, run `adb shell pm enable
  com.billtt.riddle` before `am start`, or the activity launch fails with
  "Activity … does not exist".
- **Logs:** the controller logs under tag `RiddleDiary` (attach state, pen
  begin/end). `adb logcat -s RiddleDiary`.
- **USB debugging** must be enabled on the device (Settings → About → tap build
  number; then enable USB debugging and authorize the host).

## Notes / possible future work

- Erase is whole-stroke deletion, not pixel-level.
- The reply is font-rendered reveal, not stroke-level handwriting animation.
- UI strings (`res/values/strings.xml`) are Chinese (the device user's language);
  everything else — code comments and docs — is English.
- Live ink latency: **solved** (hardware render layer, see "The pen-input story").
  Remaining quirk: the first stroke after launch may not preview.
