# MiniVideoOverlay 3.0.0 — Media3 Engine

Android native Kotlin application for composing a gameplay video, a transparent PNG layer, and an ordered music playlist. Export is handled by **AndroidX Media3 Transformer and Composition**. The previous custom MediaCodec/OpenGL/MediaMuxer encoder and custom PCM mixing pipeline have been removed.

## Preserved application features
- Existing native Android UI and application ID `com.personal.minivideooverlay`.
- Select one source video and one transparent PNG overlay through Android Storage Access Framework.
- Optional multi-file music playlist in selection order.
- Playlist sequence loops until the source video sequence ends; the final item is truncated at the video end.
- Separate 0–100% gain controls for original video audio and added playlist audio.
- Music title/artist popup at the top-left, shown for 3 seconds at each readable playlist track start, with fade-in/fade-out.
- 1920×1080 landscape MP4 output, H.264 video and AAC audio.
- Export progress, foreground service, cancellation, preview, playback, and share actions.
- Output is saved to `Movies/MiniVideoOverlay` using MediaStore pending publication and failure cleanup.
- Media is processed locally. No network upload is performed.

## Media3 implementation
- Media3 version: `1.11.1` (same version for common, effect, and transformer artifacts).
- `Transformer` exports a `Composition` containing a video sequence and an optional looping audio sequence.
- `Presentation` establishes a 1920×1080 output frame.
- `OverlayEffect` composites the user PNG and timestamp-driven music popup.
- `GainProcessor`, `ToInt16PcmAudioProcessor`, and `ChannelMixingAudioProcessor` normalize and mix audio sources.
- `DefaultEncoderFactory` requests H.264 at 8 Mbps for 30 FPS sources and 12 Mbps for 50/60 FPS sources and allows device-compatible fallback.
- Transformer runs on a dedicated HandlerThread and reports progress to the existing foreground export service.

## Build requirements
- JDK 17
- Android SDK platform 36
- Android Gradle Plugin 8.10.1
- Kotlin Gradle Plugin 2.2.0
- Gradle 8.11.1 or compatible Gradle 8.x
- Internet access for the first Gradle sync to resolve Google Maven dependencies

Open the project root in Android Studio / AndroidIDE and run `:app:assembleDebug`.

## Important validation notes
- This source migration has not been built with Android SDK/Gradle or exercised on a physical device in the current environment.
- Export speed is device-dependent. Media3 uses the device's available decoders/encoders; this does not guarantee CapCut-level speed.
- The looping audio sequence is supported by Media3 Composition. Seamless joins must still be listened to on device because codec/container padding can vary by source file.
- Composition audio sources are normalized to 16-bit stereo PCM before mixing. Very unusual multichannel or unsupported source formats may still fail on some devices.
- Popup timing uses source metadata durations. If a container reports inaccurate duration metadata, popup timing may drift slightly from the actual audio transition.
- Output is fixed landscape 1920×1080. Non-16:9 inputs are fitted to the output frame; verify framing for portrait or unusual aspect-ratio inputs.
- Transformer writes directly to a MediaStore pending item, avoiding a second full-size MP4 copy.
