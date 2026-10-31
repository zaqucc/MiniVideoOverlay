package com.personal.minivideooverlay

/**
 * All fixed constants live here. Nothing else in the app should hard-code these numbers.
 * The user cannot change any of these values from the UI.
 */
object OverlayConfig {
    const val OUTPUT_WIDTH = 720
    const val OUTPUT_HEIGHT = 1280

    const val OVERLAY_SIZE = 640
    // Centered on the 720 x 1280 canvas: x = (720 - 640) / 2, y = (1280 - 640) / 2
    const val OVERLAY_X = (OUTPUT_WIDTH - OVERLAY_SIZE) / 2   // 40
    const val OVERLAY_Y = (OUTPUT_HEIGHT - OVERLAY_SIZE) / 2  // 320

    const val MIME_AVC = "video/avc"
    const val VIDEO_BITRATE = 5_000_000
    const val DEFAULT_FRAME_RATE = 30
    const val MAX_FRAME_RATE = 60

    const val OUTPUT_FOLDER = "Movies/MiniVideoOverlay"

    const val CODEC_TIMEOUT_US = 10_000L
    const val ENCODER_DRAIN_MAX_IDLE = 300        // idle polls of CODEC_TIMEOUT_US before giving up
    const val FRAME_TIMEOUT_MS = 5_000L
    const val AUDIO_BUFFER_BYTES = 512 * 1024
}
