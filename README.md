# BrawlAI

BrawlAI is a native Android port built around the open-source PylaAI computer-vision pipeline for Brawl Stars. It runs directly on the phone: screen capture, ONNX inference and control decisions are local; no PC, emulator or VPS is required.

## What is included

- Android app in Kotlin, minSdk 26 (Android 8.0+), targetSdk 36.
- MediaProjection screen capture in a foreground service.
- ONNX Runtime Android 1.30.0 with optional NNAPI acceleration and CPU fallback.
- Pinned PylaAI `mainInGameModel.onnx` entity model (`enemy`, `teammate`, `player`).
- Pinned PylaAI tile detector for walls/bushes.
- 16:9 center crop for wide phones.
- Local combat loop: player/enemy detection, nearest-target selection, approach/kite behaviour, auto attack, teammate follow, center movement and basic obstacle avoidance.
- Android Accessibility gesture backend for joystick + attack input.
- One-tap Brawl Stars launcher.
- GitHub Actions build that produces an installable `BrawlAI.apk` and SHA-256 file.

The three upstream ONNX files are intentionally not committed to this repository. Gradle downloads them from the pinned PylaAI revision during `preBuild`, verifies the expected file sizes, and packages them inside the APK.

Pinned PylaAI revision:

`203aab8556fb9e957eeac6df74f761cc1dc918d5`

## Get the APK

Open **Actions → Build BrawlAI APK**, open the newest successful run, then download the `BrawlAI-APK` artifact. Inside it are:

- `BrawlAI.apk`
- `BrawlAI.apk.sha256`

## Run on Android

1. Install `BrawlAI.apk`.
2. Open BrawlAI and tap **Открыть настройки Accessibility**.
3. Enable **BrawlAI input**. Some Android skins require enabling “restricted settings” for a sideloaded app first.
4. Return to BrawlAI.
5. Leave NNAPI enabled initially. If the device's NNAPI driver rejects the model, disable it to use CPU inference.
6. Tap **Запустить BrawlAI** and allow screen capture.
7. Tap **Открыть Brawl Stars**.
8. Return to BrawlAI and tap **Остановить** to stop the capture/control service.

BrawlAI requests screen-capture and Accessibility permissions because Android does not allow an ordinary app to inspect another app's screen or inject gestures without explicit user-granted capabilities.

## Build locally

Requirements: JDK 17, Android SDK 36, Gradle 9.6.0.

```bash
gradle :app:assembleDebug
```

The APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Notes

PylaAI's desktop version controls an Android emulator through ADB/scrcpy. BrawlAI replaces that with Android's MediaProjection and Accessibility APIs. The original detection preprocessing is preserved: aspect-ratio resize, top-left placement, gray padding, BGR NCHW float input, YOLO output decoding and class-wise NMS.

The result is a real standalone Android implementation, but performance depends heavily on the phone's SoC and NNAPI driver. On slower devices CPU inference can reduce the control rate.

## License and attribution

PylaAI source/models are distributed under CC BY-NC 4.0. BrawlAI preserves that attribution and is non-commercial. See `LICENSE`, `NOTICE.md`, and `MODEL_SOURCES.txt`.

BrawlAI is an independent project and is not affiliated with or endorsed by PylaAI or Supercell. Automated gameplay may conflict with game rules and can lead to account action.
