package com.personal.minivideooverlay

object OverlayConfig {
    const val OUTPUT_WIDTH = 1920
    const val OUTPUT_HEIGHT = 1080
    const val OUTPUT_BACKGROUND_COLOR = 0xFF000000.toInt()
    const val MIME_AVC = "video/avc"
    const val MIME_AAC = "audio/mp4a-latm"
    const val VIDEO_BITRATE = 8_000_000
    const val AUDIO_BITRATE = 192_000
    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_CHANNELS = 2
    const val DEFAULT_FRAME_RATE = 30
    const val MAX_FRAME_RATE = 60
    const val OUTPUT_FOLDER = "Movies/MiniVideoOverlay"
    const val CODEC_TIMEOUT_US = 10_000L
    const val ENCODER_DRAIN_MAX_IDLE = 500
    const val FRAME_TIMEOUT_MS = 5_000L
}


enum class ExportState { Idle, Validating, Preparing, Exporting, Finalizing, Completed, Cancelled, Failed }
