# MiniVideoOverlay 2.0 — Implementation Report

## A. Summary

The alpha source has been upgraded from a silent visual-only compositor into an integrated Android-native compositor pipeline. The original application ID, namespace, minSdk, targetSdk, Kotlin DSL and no-third-party-dependency approach are retained.

## B. Feature status (source implementation)

| Feature | Source status | Test status |
|---|---|---|
| Video selection through SAF | Implemented | Not device-tested |
| PNG selection and alpha validation | Implemented | Not device-tested |
| Optional music selection | Implemented | Not device-tested |
| 1920×1080 landscape H.264 | Implemented with capability check | Not build/device-tested |
| Black canvas and full-width video geometry | Implemented in OpenGL shader | Not visually tested |
| Rotation metadata transform | Implemented in shader | Not visually tested |
| Full-canvas PNG alpha overlay | Implemented | Not visually tested |
| Non-16:9 PNG contain placement | Implemented | Not visually tested |
| Original video audio decode | Implemented for supported decoder PCM | Not audio-tested |
| Added music decode and loop | Implemented | Not audio-tested |
| Independent 0–100% gains | Implemented | Not audio-tested |
| PCM mix and AAC encode | Implemented | Not audio-tested |
| One mixed AAC track muxed with H.264 | Implemented | Not media-inspected |
| Duration based on source video | Implemented from video track duration | Not media-inspected |
| MediaStore pending/finalize/delete | Implemented | Not device-tested |
| Progress and cancellation | Implemented cooperatively through pipeline stages | Not stress-tested |
| Preview, play and share | Implemented using MediaPlayer/TextureView | Not device-tested |

## C. Build

Build command intended:

```bash
gradle --no-daemon --stacktrace --console=plain :app:assembleDebug
```

Build was **not run successfully in this environment**. The environment does not contain Gradle, an Android SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT` are unset), or an Android platform `android.jar`. Network DNS is unavailable, so Gradle/SDK could not be installed. A standalone Kotlin parser check was attempted; it cannot type-check Android APIs without the Android SDK. No APK is claimed or included.

The provided source archive did not contain a Gradle wrapper. The GitHub Actions workflow is retained and uses Gradle 8.7; AGP is updated to 8.6.1 for compileSdk 35 compatibility.

## D. Tests actually run

| Test | Environment | Expected | Actual | Status |
|---|---|---|---|---|
| Source archive extraction | Local container | All project files extract | 22 archive entries extracted | PASS |
| Kotlin syntax parser pass | Local kotlinc without Android SDK | No syntax/parser errors | No syntax-like parser errors; Android symbol/type checking unavailable | PARTIAL |
| Android Gradle build | Local container | Debug APK builds | Gradle/Android SDK unavailable | NOT RUN |
| Android lint | Local container | No lint errors | Android SDK/Gradle unavailable | NOT RUN |
| Physical device media export | Android device | Output meets spec | No device attached | NOT RUN |
| Audio listening/sync checks | Android device | Correct mix/loop/sync | No output media produced | NOT RUN |

## E. Artifacts

- Source ZIP: generated alongside this report.
- Debug APK: not generated because build tooling/SDK are unavailable.
- Release APK: not generated; no signing configuration was supplied.

## F. Known limitations requiring validation

1. Audio decoding expects signed 16-bit PCM output from Android decoders. Unsupported decoder output is rejected with a clear error.
2. Audio timestamp discontinuities and complex edit lists are not fully reconstructed; only the initial source audio timestamp offset is represented.
3. Preview codec compatibility depends on MediaPlayer on the device.
4. Codec capability and EGL behavior vary by device. 1080p encoder support and maximum GL texture size are checked, but hardware behavior requires physical-device tests.
5. AAC encoder delay/padding can create a small container-level duration tolerance. Verify actual output with MediaMetadataRetriever or ffprobe on device.
6. The app cancels an export when the Activity is finishing; it does not use a foreground service to continue after the screen is closed.

## G. Build and run

1. Open the extracted project root in Android Studio.
2. Install JDK 17 and Android SDK Platform 35 / Build Tools supported by Android Studio.
3. Sync Gradle. Use Gradle 8.7 and Android Gradle Plugin 8.6.1.
4. Run `:app:assembleDebug`.
5. Install `app/build/outputs/apk/debug/app-debug.apk` on an Android 10+ device.
6. Select a video, a transparent PNG, and optional music. Set the two volume sliders and preview.
7. Export. The output is published in `Movies/MiniVideoOverlay` after successful muxing.

Do not treat this implementation as device-validated until the tests in `instruksi.md` have been run against real media on a physical Android device.


## Update 2.1.0 — Background export UX

- Export now runs in `ExportService`, a foreground service independent of `MainActivity` lifecycle.
- Notification shows export status/progress and includes a cancel action.
- Activity no longer cancels rendering from `onDestroy`; leaving the screen does not itself cancel export.
- Last successful output URI and filename are persisted in app preferences and restored when the app is reopened.
- Android 13+ notification permission is requested so the progress notification can be visible in the notification drawer. Foreground-service visibility can still be limited by user/system notification settings.
- Android 15 uses the `mediaProcessing` foreground-service type; earlier supported Android versions use `dataSync`.
- This improves resilience but cannot guarantee survival if Android force-stops the app, the user force-stops it, the device reboots, or the system terminates the process under exceptional conditions.
- Build/device verification remains required; this environment does not have an Android SDK/Gradle installation.


## Update 2.2.1 — Music Track Notification

### Implemented in source
- Reads title, artist, and duration metadata for each selected playlist URI before video rendering; falls back to display filename and extractor duration when metadata retrieval is incomplete.
- Maps the ordered playlist timeline to video presentation timestamps and repeats the cue sequence with the looping playlist.
- Burns a compact top-left notification into the exported frames for 3 seconds after each track starts, with a 250 ms fade-in and 350 ms fade-out.
- Renders notification as an independent transparent OpenGL texture above the existing PNG layer. The supplied PNG remains unchanged.
- Version metadata updated to 2.2.1 / versionCode 6.

### Validation status
- Source-level review completed.
- Android Gradle build and physical-device playback/export test were not run in this environment because Android SDK/Gradle are unavailable.
- Verify on device that embedded title/artist metadata and duration metadata match the actual decoded track boundaries; container padding or unusual edit lists may cause small timing differences.
