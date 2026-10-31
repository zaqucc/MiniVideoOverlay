# Mini Video Overlay — diagnostic cloud build

This package keeps the app source and adds a GitHub Actions workflow that captures the **complete Gradle build log**, even when the build fails. The app output is Full HD (see the specification below).

## Build from a phone (no PC / Android Studio)

1. Open the GitHub repository in your mobile browser.
2. Upload the contents of this folder to the repository root, replacing the existing files when prompted. Make sure `.github/workflows/android-apk.yml` is included. If the browser hides `.github`, enable hidden files in your file manager before uploading.
3. Commit the changes to `main`.
4. Open **Actions → Build Android APK** and open the newest run.
5. If the build fails, download the artifact named **MiniVideoOverlay-build-diagnostic-log**. It contains `build-debug.log` and `build-exit-code.txt`. Send those files back for diagnosis.
6. If the build succeeds, download **MiniVideoOverlay-debug-apk**, extract it, and install `app-debug.apk`.

The APK artifact is only uploaded when the build succeeds. A successful cloud build does not replace testing the exported MP4 on the target phone.

## Output specification (Full HD update)

| Item | Value |
|---|---|
| Output | MP4, H.264, 1080 × 1920 (9:16). No fallback to a lower resolution: if the device encoder cannot do 1080×1920, the export fails with a clear message |
| Duration | Exactly 15 s: first 15 s if the source is longer, looped from the start if shorter (never a frozen frame) |
| Audio | None. The audio track is never read, so the MP4 has no audio stream |
| Background | Source video, center-cropped to 9:16, subtle Gaussian blur (9-tap, applied horizontally and vertically) |
| Overlay | Square photo, 960 × 960 at X=60, Y=480, center-cropped, sharp |
| Shadow | Black, 20% opacity, ~12 px blur, offset X 0 / Y 4 px, behind the photo |

All values live in `OverlayConfig.kt`. Files changed from the previous build: `OverlayConfig.kt`, `GlCompositor.kt`, `ExportEngine.kt`, `README.md`. `MainActivity.kt`, Gradle files, manifest, and the GitHub Actions workflow are unchanged.
