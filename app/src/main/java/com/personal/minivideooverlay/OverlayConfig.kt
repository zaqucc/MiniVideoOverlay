package com.personal.minivideooverlay

/**
 * All fixed constants live here. Nothing else in the app should hard-code these numbers.
 * None of these values can be changed from the UI.
 */
object OverlayConfig {
    // Output canvas: Full HD vertical 9:16
    const val OUTPUT_WIDTH = 1080
    const val OUTPUT_HEIGHT = 1920

    // Square photo overlay, centered. Previous 720x1280 values scaled by 1.5.
    const val OVERLAY_SIZE = 960
    const val OVERLAY_X = (OUTPUT_WIDTH - OVERLAY_SIZE) / 2   // 60
    const val OVERLAY_Y = (OUTPUT_HEIGHT - OVERLAY_SIZE) / 2  // 480

    // Every export is exactly 15 seconds: trimmed when longer, looped when shorter.
    const val OUTPUT_DURATION_US = 15_000_000L

    // Subtle Gaussian blur on the background. 9-tap kernel, taps spaced this many output pixels apart.
    const val BACKGROUND_BLUR_TAP_SPACING_PX = 2f

    // Shadow behind the overlay photo.
    const val SHADOW_ALPHA = 0.20f
    const val SHADOW_BLUR_PX = 12f
    const val SHADOW_OFFSET_Y_PX = 4f

    const val MIME_AVC = "video/avc"
    // Bitrate for 1080x1920 H.264 at 30 fps.
    const val VIDEO_BITRATE = 8_000_000
    const val DEFAULT_FRAME_RATE = 30
    const val MAX_FRAME_RATE = 60

    const val OUTPUT_FOLDER = "Movies/MiniVideoOverlay"

    const val CODEC_TIMEOUT_US = 10_000L
    const val ENCODER_DRAIN_MAX_IDLE = 300        // idle polls of CODEC_TIMEOUT_US before giving up
    const val FRAME_TIMEOUT_MS = 5_000L
}
