# MiniVideoOverlay 3.0 — Locked Product Specification

This document records the 3.0 Media3 migration while retaining the user-facing behavior established in 2.2.1.

## Project identity and structure
- Native Android Kotlin application.
- Application ID / namespace: `com.personal.minivideooverlay`.
- Existing folder and package structure are retained; do not rename or reorganize project folders without explicit approval.
- Existing UI and export foreground-service workflow remain in place.

## Export engine
- AndroidX Media3 Transformer and Composition are the only media export engine.
- No FFmpeg, BytePlus SDK, custom MediaCodec encoder/decoder pipeline, custom OpenGL compositor, or custom PCM encoder/muxer pipeline.
- Android framework APIs may still be used for SAF URI access, bitmap decoding, metadata inspection, and MediaStore file publication. These are I/O/metadata operations, not a second media processing engine.
- Output: MP4, H.264 video, AAC audio, 1920×1080 landscape.
- Requested video encoder settings: 8 Mbps for 30 FPS sources and 12 Mbps for 50/60 FPS sources, 2-second I-frame interval, with Media3 device-compatible encoder fallback.
- Transformer executes on a dedicated HandlerThread; progress, cancellation, and completion are bridged to the existing foreground service.

## Video and overlay
- Source video duration defines the output timeline.
- `Presentation` creates the fixed 1920×1080 frame using scale-to-fit behavior.
- User PNG is decoded to a transparent 1920×1080 canvas and composited above the video using Media3 `OverlayEffect` / `BitmapOverlay`.
- PNG without alpha is rejected.
- Music notification is a separate overlay above the user PNG.
- Notification card: top-left, compact dark translucent background, cyan accent, music icon, title and artist.
- Card appears for 3 seconds at each playlist track start, with 250 ms fade-in and 350 ms fade-out.
- Notification timing repeats with the ordered playlist. Timing depends on readable source duration metadata.

## Audio
- Original video audio and the added playlist are mixed in the Media3 Composition.
- Separate volume controls remain 0–100%.
- Audio sources are converted to 16-bit stereo PCM for compatible composition mixing, then encoded to AAC by Media3.
- Music files are concatenated in selection order.
- The music sequence loops until the non-looping source video sequence ends; the final music item may be partially exported.
- Target behavior is gapless sequential playlist playback. Actual gaplessness must be validated with real audio files and listening tests on device; Media3 Composition does not provide crossfade transitions.
- No auto-normalization, compressor, ducking, or crossfade is added.

## Service, output, and privacy
- Export continues in the existing foreground service.
- Progress and cancellation remain available.
- Transformer writes directly to a MediaStore pending output using `TransformerOutput`; the app publishes it only after successful export. This avoids a second full-size MP4 copy and reduces temporary storage requirements.
- Output folder: `Movies/MiniVideoOverlay`.
- Failure or cancellation deletes the pending output and temporary file.
- All processing is local; no media upload or network permission is added.

## Validation status and known risks
- Migration source has not been compiled with a local Android SDK/Gradle installation in this environment.
- A physical-device test is required for codec support, audio mix levels, playlist joins, popup alignment, aspect ratio, memory use, and export speed.
- Hardware acceleration is delegated to Media3's platform codec factories; actual encoder availability and throughput vary by Android device.

## Build toolchain for Media3 1.11.1
- compileSdk 36.
- Android Gradle Plugin 8.10.1.
- Kotlin Gradle Plugin 2.2.0.
- Gradle 8.11.1 and JDK 17.
