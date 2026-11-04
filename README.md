# MiniVideoOverlay 2.0

Android native Kotlin video compositor. All media processing is performed locally using Android MediaExtractor, MediaCodec, OpenGL ES and MediaMuxer. No media is uploaded.

## Implemented source features
- Fixed 1920x1080 landscape H.264 output with opaque #000000 background.
- Video uniformly scaled to fill canvas width, centered vertically; no source crop/stretch. Areas outside the video image remain black or are clipped only by the fixed output canvas.
- Rotation metadata is applied in the GL sampling shader.
- Full-canvas PNG overlay with alpha blending. Non-16:9 PNG is proportionally contained on a transparent 1920x1080 canvas. PNG without alpha is rejected.
- Source video duration drives the export timeline.
- Optional source-video audio and optional added music are decoded, converted to 48 kHz stereo PCM, gain-adjusted, mixed, and AAC-encoded. Added music loops to the video duration.
- H.264 video and one mixed AAC track are muxed into one MP4.
- Separate 0–100% volume controls, progress, cancellation, preview, playback and share actions.
- MediaStore output at `Movies/MiniVideoOverlay`, using pending publication and failure cleanup.
- Bounded PCM processing with temporary app-cache files; no whole video/PCM stream is held in RAM.

## Build
Requirements: JDK 17, Android SDK platform 35, Gradle 8.7, Android Gradle Plugin 8.6.1. Open the project root in Android Studio and run `:app:assembleDebug`.

## Important validation notes
This source has not been built or exercised on a physical Android device in the current environment. Codec support varies by device. The PCM conversion currently assumes Android decoder output is signed 16-bit PCM; unsupported decoder PCM formats fail with a visible error. Audio timestamp gaps/edit lists and variable-frame-rate behavior require device media test coverage before release.
