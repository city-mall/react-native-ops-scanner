# react-native-ops-scanner

Native Android QR / barcode scanner for CityMall ops apps (tl-app, partner-db).
Full-screen CameraX + ML Kit, one implementation for every app. Android only;
`isNativeCodeScannerAvailable()` is `false` elsewhere.

## Install

```sh
yarn add react-native-ops-scanner@city-mall/react-native-ops-scanner#v0.1.0
```

Autolinking registers `CodeScannerPackage` and the manifest merger brings in
`CodeScannerActivity` and the CAMERA permission. Requirements on the host:

- React Native ≥ 0.73 (old-bridge module; runs under the New Architecture interop), Kotlin 2.1, `minSdk` ≥ 24.
- Runtime CAMERA permission is the host's job — request it before `scanCode()`.
  The screen never prompts and rejects with `camera_permission_denied` without it.
- Set the CameraX version once in the host's `android/build.gradle` so this
  library and `react-native-face-capture` resolve to the same, tested version:

  ```groovy
  buildscript {
      ext {
          cameraxVersion = "1.4.2"
      }
  }
  ```

  Without it, each library falls back to its own default and Gradle silently
  picks the highest one. `mlkitBarcodeScanningVersion` (default `17.3.0`) can be
  overridden the same way.
- The ML Kit barcode model is **bundled** (works offline, on a fresh install
  and on stale Play Services).

## API

```ts
import {
  isNativeCodeScannerAvailable,
  normalizeScannerConfig,
  scanCode,
} from "react-native-ops-scanner";

if (isNativeCodeScannerAvailable()) {
  const result = await scanCode({ mode: "QR", title: "Scan rack" });
  if (!result.didCancel) handle(result.value, result.format);
}
```

```ts
scanCode({ mode?: "QR" | "BARCODE", title?: string, helper_text?: string,
           accept_values?: string[], reject_hints?: { [value]: string },
           invalid_text?: string })
  → { didCancel: false, value: string, format: "QR_CODE" | "CODE_128" | ... }
  | { didCancel: true }
  // rejects (E_SCAN_FAILED / E_NO_ACTIVITY / E_ALREADY_RUNNING) only when the
  // screen could not run — callers fall back to their old scan path
```

- **`mode` set** → locked to that mode, the QR | Barcode switcher is hidden.
- **`mode` missing / unknown** → switcher shown, opening on the last-used mode
  (SharedPreferences `code_scanner/last_mode`).
- QR → square frame, `FORMAT_QR_CODE` only. BARCODE → wide rectangle, 1D
  formats only (Code 128/39/93, Codabar, EAN-13/8, ITF, UPC-A/E).
- `title` / `helper_text` override the per-mode Hindi defaults.
- `accept_values` set → the screen stays open until it reads one of them
  (trimmed, upper-cased match). Any other code inside the frame shows a chip
  (`reject_hints[value]`, else `invalid_text`, else a Hindi default) and
  scanning continues. Unset → the first code inside the frame closes the screen.
- `normalizeScannerConfig(raw)` turns an untrusted (server-sent) config into a
  safe one: an unknown mode becomes "unset" instead of being forwarded.

## Invariants

1. **Always guard with `isNativeCodeScannerAvailable()`.** OTA updates
   (Revopush / CodePush) can put this JS on an APK without the native module.
   The JS module name stays `CodeScannerModule` so APKs built before the
   extraction keep working.
2. Any camera / ML Kit init failure finishes with `RESULT_SCAN_ERROR`; the
   module rejects and the caller falls back. Never strand the user.
3. Request code `0x9A04` must stay unique among modules that call
   `startActivityForResult` from the host activity (`0x9A01` partner-db proof
   capture, `0x9A02` / `0x9A03` react-native-face-capture).

## Speed decisions

Ops scan dozens of labels per shift, so:

- **First decode inside the frame wins** — no multi-frame confirmation. Every
  caller matches the value against ids it already holds, so a misread can only
  bounce as "not recognised", never be recorded as a real box.
- Formats narrowed per mode (fewer decoders per frame).
- Analysis at ~1280×720, `STRATEGY_KEEP_ONLY_LATEST`.
- One analyzer per launch (switcher launches decode QR + 1D and filter by the
  selected mode). Never swap it: CameraX 1.4 does not hand a newly set
  MlKitAnalyzer the PreviewView transform, so it drops every frame
  ("Sensor-to-target transformation is null").
- No open/close transitions; beep + haptic on accept.
- Only codes whose centre is inside the frame (+8% tolerance) count, so the
  neighbouring rack's label isn't picked up. A code seen outside the frame shows
  a "move it inside" chip.

## Layout

| File | Role |
|---|---|
| `src/index.ts` | JS API. |
| `android/.../CodeScannerModule.kt` | JS entry, `startActivityForResult`. |
| `android/.../CodeScannerActivity.kt` | Camera, ML Kit analyzer, mode switcher, torch, result. |
| `android/.../ScanFrameOverlayView.kt` | Scrim, cut-out frame, brackets, laser line. |
| `android/.../ScannerConfig.kt` | Config parsing (ReadableMap → intent extras), mode → ML Kit formats + frame shape. |
| `android/src/main/res/` | Layout, drawables, `ops_scanner_*` strings / colors, `Theme.OpsScanner`. |

## Debug launch

Debug builds of the host export the activity (`android/src/debug/AndroidManifest.xml`):

```bash
# switcher shown (camera permission must already be granted to the app)
adb shell am start -n <host.applicationId>/live.citymall.opsscanner.CodeScannerActivity

# locked to barcode
adb shell am start -n <host.applicationId>/live.citymall.opsscanner.CodeScannerActivity \
  --es scanner_mode BARCODE

adb logcat -s CodeScanner:V AndroidRuntime:E
```

## Releasing

```sh
npm run check          # typecheck + tests + build into lib/ (committed)
git tag vX.Y.Z && git push origin vX.Y.Z
```

Hosts pin the tag. A native change needs a new APK in each host; a JS-only
change can ship over the air.
