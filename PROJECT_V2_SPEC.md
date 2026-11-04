# MiniVideoOverlay 2.0 — Implemented Product Specification

This document follows the user-provided `instruksi.md`. It supersedes earlier draft specifications that described a portrait canvas, adjustable layers, blur, or optional effects.

## Fixed output

- Android native Kotlin; package/application ID `com.personal.minivideooverlay`.
- minSdk 29, targetSdk 35, compileSdk 35, Java/Kotlin 17.
- MP4, H.264 video, 1920×1080 landscape, solid `#000000` background.
- Video is uniformly scaled to fill canvas width and centered vertically. No stretch or source crop operation. The fixed 1920×1080 output boundary naturally clips geometry extending outside the canvas.
- PNG is a full-canvas layer above video. Alpha is retained. Non-16:9 PNG is contained proportionally and centered on a transparent canvas.
- Output duration is based on source video duration; video is never looped and there is no fixed 15-second trim.

## Audio

- Original video audio and one optional added music file are decoded locally.
- Both sources are converted to 48 kHz stereo signed-16 PCM using bounded processing and temporary cache files.
- Separate gain values are applied from 0–100%; sources are mixed into one AAC-LC track.
- Music starts at output time zero, loops from its beginning when shorter, and is cut at the video timeline end.
- If the video has no audio, music-only export is supported. If neither source exists, output has no audio track.
- Clipping is softened at PCM conversion. No auto-normalization, compressor, ducking, or crossfade is applied.

## User interface

- Select video, PNG and optional music through Storage Access Framework.
- Display selected names and available durations.
- 16:9 preview with video and full-canvas PNG layer.
- Separate volume sliders, export progress and cancel action.
- Completed output can be played or shared.

## Output storage and privacy

- MediaStore output folder: `Movies/MiniVideoOverlay`.
- Output is created as `IS_PENDING=1`, then published only after muxing succeeds.
- Failed/cancelled pending output is deleted.
- Media processing is local. No network permission or cloud upload is used.

## Current implementation caveats

- Android decoder PCM output supports signed 16-bit PCM and float PCM. Other PCM encodings fail visibly rather than being silently misread.
- Resampling is linear interpolation to 48 kHz. Multichannel inputs are downmixed with a simple center/surround weighted stereo matrix.
- Initial video-audio timestamp offset is represented with leading silence. Complex internal timestamp discontinuities/edit lists are not fully reconstructed.
- Frame rate is preserved through source presentation timestamps; encoder configuration falls back to a lower supported frame-rate hint if needed.
- Preview uses Android MediaPlayer and may support fewer input codecs than the export decoder pipeline.
- A device-specific codec, EGL, thermal, storage, or memory failure may prevent export. Full device validation is still required.
